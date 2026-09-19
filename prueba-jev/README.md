# prueba-jev — clasificar y vectorizar mensajes

Laboratorio para responder dos preguntas sobre las notificaciones y mails
capturados: **cuáles son movimientos de plata** (Jev) y **cuáles se parecen
entre sí** (embeddings + `vector_distance_cos` de libSQL).

Todo corre sobre una copia local, `data/lab.db`. La DB real del usuario
(`finance-00mtqgh7qtjguu7n6902`) se lee en `pull.ts` y solo se escribe desde
`push.ts --confirm`.

## Setup

`.env` (ya ignorado por git):

```
TYPESAFE_API_KEY=...          # Jev
GEMINI_API_KEY=...            # embeddings (el proveedor en uso)
OPENAI_API_KEY=...            # alternativa, con --provider openai
TURSO_TOKEN=...               # turso db tokens create finance-00mtqgh7qtjguu7n6902
```

## Flujo

```bash
bun scripts/pull.ts                       # DB real → data/lab.db  (~8 s, 23.8k filas)
bun scripts/classify.ts --dry-run         # cuántas llamadas a Jev haría
bun scripts/classify.ts                   # paso 1 (regex) + paso 2 (Jev) → data/report.md
bun scripts/embed.ts --provider gemini    # embeddings de los movimientos
bun scripts/search.ts "pagaste en dolares"
bun scripts/ids.ts [texto]                # lista ids embebidos (id corto, fecha, jev_score)
bun scripts/search.ts --like 54f47dca     # "más como este"; el id acepta prefijos
bun scripts/push.ts                       # dry run; --confirm escribe en la DB real
```

## Cómo está guardado

Las cinco columnas van **en `notifications` y `emails`**, no en una tabla
satélite: el embedding es una propiedad del mensaje y muere con él.

| columna | qué es |
| --- | --- |
| `jev_score` | la probabilidad cruda que devolvió Jev |
| `is_movement` | `jev_score >= umbral`, recalculable sin volver a llamar |
| `classified_at` | punto de reanudación de la corrida |
| `embedding` | `F32_BLOB(512)` escrito con `vector32(...)` |
| `embedding_model` | `gemini-embedding-001/512` — mezclar modelos envenena toda distancia |

**Sin índice vectorial.** El motor de sync del teléfono tiene
`vector_distance_cos` pero no `vector_top_k` ni `libsql_vector_idx`
(verificado sobre el `.so` vendorizado), y el esquema se replica al teléfono.
La búsqueda es scan exacto: con 69 vectores tarda **2–30 ms**, contra ~400 ms
de embeber la consulta. El cuello de botella es la API, no el scan.

## Números medidos

- 23.671 notificaciones + 132 mails.
- Con símbolo de moneda: **140**. Con dígitos pero sin moneda: 10.284 (horas,
  códigos, "15% OFF": el muestreo del reporte no encontró un solo movimiento
  ahí).
- Jev es bimodal: 90 mensajes por debajo de 0,2 y 68 por encima de 0,8. El
  umbral entre 0,3 y 0,8 da el mismo resultado, así que 0,6 no es una elección
  delicada.
- Con umbral 0,6: **69 movimientos**, que incluyen los 51 del allowlist de
  `Ingest.kt` (51/51, sin falsos negativos) **más 18 que el allowlist no ve**:
  avisos Santander por mail, DolarApp, InvertirOnline, recargas Tuenti, pagos
  de entradas por Telegram.

## Cuerpos de mail

`lib/text.ts` va más lejos que `Ingest.kt` en dos puntos, porque un embedding
sobre ruido MIME mide el ruido:

- `unwrapMime` extrae la parte `text/html` (o `text/plain`) de un cuerpo que
  vino como entidad multipart entera, incluso cuando la fila quedó cortada a
  10 kB **dentro** del multipart y la declaración del boundary ya no está (se
  infiere de la primera línea `--token` seguida de un header MIME).
- Un `<style>` sin cierre — lo normal cuando el corte cae dentro de la hoja de
  estilos — se descarta hasta el final del texto. Sin eso, el "cuerpo" de esos
  mails era CSS.

Efecto: 45 de 132 mails tenían MIME crudo en su texto, ahora 0. De paso, el
paso 1 bajó de 159 a 140 candidatos (19 `$` que estaban en CSS y base64, no en
un monto) sin perder un solo movimiento.

## Proveedores de embeddings

`--provider openai` (default), `gemini` (el que está en uso; la key de OpenAI no
tiene crédito) o `hash`. El último es un bag-of-words hasheado, determinista y
**no semántico**: existe para ejercitar el camino SQL sin gastar ni esperar una
key, y `push.ts` se niega a promover vectores suyos.

Con `gemini-embedding-001` a 512 dimensiones hay que **renormalizar** el vector:
Google devuelve el embedding truncado sin normalizar, y la distancia coseno lo
nota. `lib/embed.ts` lo hace.
