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
bun scripts/probe.ts                      # ¿el prefiltro de moneda filtra de más?
bun scripts/suggest.ts [texto]            # la sugerencia de categoría en vivo de la app
bun scripts/chat.ts [texto]               # la categoría del bot de WhatsApp
```

`suggest.ts` mide lo que hace `TransactionQuickScreen` mientras se tipea la
descripción, con **la pregunta del worker** (`categoryQuestion`, importada de
`app/worker/src/suggest.ts` para que el banco de pruebas no se desincronice de
lo que sale publicado). Última corrida: 19/20 sobre una tabla es-AR, p50 313 ms,
p90 435 ms. El único error es «entradas boca» → `Comida:Restaurantes` con
confianza 0,50: el nombre del club es también un sustantivo, y la confianza lo
muestra (las que acierta están casi todas arriba de 0,9).

`chat.ts` mide el otro lado del mismo truco: el bot de WhatsApp. Ahí el mensaje
lo lee Gemini — monto, dirección, payee y cuentas son lenguaje, y un regex que
decide si `12.500` son doce mil o doce con cincuenta es una heurística
escondida en la cañería — y lo único que se le pregunta a Jev es la categoría,
que no es lenguaje sino una elección dentro del árbol del usuario. Va con
`Promise.all` **al lado** de la llamada a Gemini, así que no cuesta tiempo: se
preguntan la de gasto y la de ingreso en paralelo y se usa la que corresponda a
la dirección que devolvió el otro modelo. La pregunta es **la misma** que usa la
pantalla de carga (`categoryQuestion`, importada de `suggest.ts`), así mejorar
una mejora las dos.

Última corrida: **16/17**, p50 305 ms. El único que no acierta es «plazo fijo
15300», y no se equivoca: elige «ninguna de estas categorías» con confianza
0,40, y entonces el worker se queda con lo que dijo Gemini. Los dos lectores se
cubren entre sí — si TypeSafe se cae, se pierde la categoría mejor elegida y
nada más.

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
La búsqueda es scan exacto: con 75 vectores tarda **2–30 ms**, contra ~400 ms
de embeber la consulta. El cuello de botella es la API, no el scan.

## Números medidos

- 23.671 notificaciones + 132 mails.
- Con símbolo de moneda: **148**. Con dígitos pero sin moneda: 10.276.
- Jev es bimodal: 73 mensajes por debajo de 0,2 y 73 por encima de 0,8. El
  umbral entre 0,3 y 0,6 da el mismo resultado, así que 0,6 no es una elección
  delicada.
- Con umbral 0,6: **75 movimientos**, que incluyen 51 de los 52 del allowlist
  de `Ingest.kt` **más 24 que el allowlist no ve**: avisos Santander por mail,
  DolarApp, Lemon Cash, InvertirOnline, recargas Tuenti, pagos de entradas por
  Telegram. El único que Jev baja (0,19) es *"Recibiste $5.000 🍟 — de regalo
  para pedir tu comida favorita"*: un cupón que el allowlist toma por
  movimiento.

## ¿El prefiltro de moneda filtra de más?

`scripts/probe.ts` contesta eso con números: agarra lo descartado y lo pasa por
redes cada vez más anchas. Encontró una fuga real, ya arreglada.

No era exigir moneda, era exigirla **como prefijo**. `MONEY` venía de
`Ingest.kt`, donde la plata se escribe `$ 19.200`; las billeteras que hablan
inglés la ponen al final: `30.000 ARS`, `500,000 ARS`, `200 USD`. Anclada a los
dos lados, la regex suma 8 mensajes → **6 movimientos** confirmados por Jev
(depósito DolarApp, transferencia Lemon Cash, cuatro recibos ARQ) y 2
cotizaciones de AstroPay descartadas. `Ingest.kt` tiene la misma limitación.

Lo que sigue afuera, con su conteo: número gordo + verbo de movimiento son 32
mensajes **sin un solo movimiento** (códigos 2FA, `#2000011979889947` de
facturas de ML), y dígitos sueltos son 23.616 (horas, resultados de fútbol,
"4 new messages").

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
