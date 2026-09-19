# Plan — vectorizar notificaciones y emails (sandbox en `prueba-jev/`)

Objetivo: poder buscar por **similitud semántica** entre los mensajes capturados
(notificaciones push + emails), pero embebiendo **solo los que parecen
movimientos de dinero**, no los 21k mensajes enteros. El filtro tiene dos pasos:
(1) barato y local — ¿hay un monto?, (2) caro y remoto — el test de Jev
(`client.systemOne` con `noul`), que ya funciona en `prueba-jev/index.ts`.

Todo se hace con scripts dentro de `prueba-jev/`, sobre una copia local
(`lab.db`). **No se toca** `app/worker` ni código Kotlin durante el experimento.
La única parte que sí toca la app es la promoción final (§5), opt-in y aparte.

---

## Estado: implementado

§1–§5 están escritos y corridos (ver `prueba-jev/README.md`). Resultados:

- `pull.ts`: 23.671 notificaciones + 132 mails a `data/lab.db` en ~8 s.
- `classify.ts`: **148** mensajes con moneda → 148 llamadas a Jev en ~7 s.
  Distribución **bimodal** (73 bajo 0,2 / 73 sobre 0,8), así que el umbral no es
  una elección delicada: 0,3 y 0,6 dan el mismo conjunto. Con 0,6 salen **75
  movimientos**, que cubren **51/52** del allowlist de `Ingest.kt` y agregan
  **24 que el allowlist no ve** (avisos Santander por mail, DolarApp, Lemon
  Cash, InvertirOnline, recargas Tuenti, pagos de entradas por Telegram).
  El único del allowlist que Jev baja a 0,19 no es un error suyo: *"Recibiste
  $5.000 🍟 — $5.000 de regalo para pedir tu comida favorita"* es un cupón, no
  un movimiento.

### ¿El prefiltro de moneda filtra de más? Sí — filtraba, y se arregló

`scripts/probe.ts` toma lo que el paso 1 descarta y lo vuelve a filtrar con
redes progresivamente más anchas, para responder eso con números. Sobre los
23.664 descartados:

| red | n | qué había |
| --- | --- | --- |
| A · número con formato de plata + palabra de movimiento | 1 | **un movimiento real**: "Recibiste 30.000 ARS" (Lemon Cash) |
| B · número + moneda en palabras | 10 | cotizaciones de AstroPay, promos de Dia |
| C · número con formato de plata, sin verbo | 5 | "Hay 1.000 becas", IPs, un `#17 1.216 error:` de un build |
| D · número gordo + palabra de movimiento | 32 | códigos 2FA, `#2000011979889947` de facturas de ML |
| E · dígitos sueltos | 23.616 | horas, resultados de fútbol, "4 new messages" |

La causa de la fuga no era pedir moneda: era pedirla **solo como prefijo**.
`MONEY` venía de `Ingest.kt`, donde la plata se escribe `$ 19.200`, pero las
billeteras que hablan inglés la ponen después: `30.000 ARS`, `500,000 ARS`,
`200 USD`. Anclar la regex a **cualquiera de los dos lados** suma 8 mensajes,
de los cuales Jev confirma **6 movimientos** (un depósito DolarApp, la
transferencia Lemon Cash y cuatro recibos ARQ/DolarApp) y descarta las 2
cotizaciones de AstroPay. 140 → 148 candidatos, 69 → **75 movimientos**.

Lo demás se queda afuera a propósito: ensanchar a "número gordo + verbo" (red
D) traería 32 mensajes sin un solo movimiento, y a dígitos sueltos, 23.616.
`Ingest.kt` tiene exactamente la misma limitación de prefijo.
- `embed.ts` + `search.ts`: **75 vectores reales** con `gemini-embedding-001` a
  512 dimensiones (la key de OpenAI responde `429 insufficient_quota`, así que
  el proveedor en uso es Gemini — la misma key que ya usa el worker).
  `lib/embed.ts` renormaliza, porque Google devuelve el vector truncado sin
  normalizar y la distancia coseno lo nota. Embeber los 69: **1,8 s**.
- Búsqueda semántica verificada: "recibí plata de alguien" devuelve los cuatro
  "Recibiste $…" de Mercado Pago; "cuota de la tarjeta de crédito" devuelve los
  avisos de consumo de Santander; "pago de la tarjeta en dólares" mezcla
  DolarApp y los `U$S` de Santander. Scan exacto: **2–30 ms** sobre 69 vectores,
  contra ~400 ms de embeber la consulta — el cuello de botella es la API.
- Hallazgo lateral: 45 de los 132 mails tenían **MIME crudo** como "cuerpo"
  (multipart entero, o cortado a 10 kB dentro del multipart con el boundary ya
  fuera del texto) y varios terminaban con un `<style>` sin cerrar, es decir
  CSS. `lib/text.ts` agrega `unwrapMime` + el descarte del bloque abierto; los
  candidatos bajaron de 159 a 140 (19 `$` que vivían en CSS y base64) **sin
  perder un solo movimiento**. `Ingest.kt` tiene las dos mismas limitaciones y
  es candidato a recibir el mismo arreglo.

---

## 0. Verificaciones — YA HECHAS

**1. ¿El motor nuevo de sync soporta vectores en el device?** Sí, las funciones;
no, el índice. Símbolos presentes en el `libturso_sync_sdk_kit.so` vendorizado
(las 4 ABIs, idéntico resultado):

| Presente | Ausente |
| --- | --- |
| `vector32`, `vector64`, `vector8`, `vector1bit` | `vector_top_k` |
| `vector_distance_cos`, `vector_distance_l2`, `vector_distance_jaccard` | `libsql_vector_idx` |
| `vector_extract` | `diskann`, `F32_BLOB`, `vector16`/`vectorb16` |

Consecuencias, que son las que ordenan el resto del plan:

- La búsqueda por similitud **corre offline en el device** con
  `order by vector_distance_cos(embedding, vector32(:q)) limit k`. Con cientos o
  pocos miles de candidatos, un scan lineal es instantáneo; DiskANN no hace
  falta.
- **No crear el índice vectorial** (`libsql_vector_idx`) en la DB del usuario,
  ni siquiera desde el servidor. El servidor sí lo soporta, pero el esquema se
  replica al device y el motor local no conoce esa función: es la forma más
  probable de romper la apertura de la DB en el teléfono. Ganancia nula, riesgo
  concreto → queda fuera.
- El tipo declarado `F32_BLOB(512)` no lo reconoce el motor local; como en
  SQLite el tipo declarado es solo un hint y el valor es un BLOB, se puede
  declarar igual (el servidor lo aprovecha) o simplemente `blob`. Elegimos
  `F32_BLOB(512)` en la creación pero sin depender de ninguna validación.

**2. ¿Molestan columnas/tablas nuevas al sync?** El motor replica estado de
filas de la base entera, no tabla por tabla, y ninguna query del código Kotlin
usa `select *` (verificado en `NotificationsRepository`/`EmailsRepository`: todas
nombran columnas), así que una columna nueva no se cuela en ningún read path
existente ni infla lo que la UI trae.

**3. Key de OpenAI**: está en `~/Desktop/openai.weil.txt`; se copia a
`prueba-jev/.env` como `OPENAI_API_KEY` (el `.gitignore` ya excluye `.env`).

**4. Cuál es la DB y cuánto hay adentro.** Confirmado: sí, es
`finance-00mtqgh7qtjguu7n6902` (usuario `00mtqgh7qtjguu7n6902`, grupo `finance`,
`libsql://finance-00mtqgh7qtjguu7n6902-faustofusse.aws-us-east-1.turso.io`). De
las 7 DB `finance-*` es la única con datos reales; las otras tienen 0
notificaciones. Consultada por HTTP `/v2/pipeline` con un token de
`turso db tokens create` (el `turso db shell` falla contra estas DB: el
WebSocket devuelve 400).

| | filas |
| --- | --- |
| `notifications` | **23.671** |
| `emails` | **132** |
| notificaciones con `$` | **108** |
| notificaciones con algún dígito | 10.321 |
| mails con `$` (subject/text/html) | **58** |

Y el servidor sí tiene las funciones vectoriales:
`select vector_distance_cos(vector32('[1,0]'), vector32('[0,1]'))` → `1.0`.

Esto cambia el tamaño del problema: **el universo a clasificar con Jev son ~166
mensajes, no miles**. Todo el andamiaje de concurrencia/costo del §2 pasa a ser
trivial (una corrida de un par de minutos, centavos), y el gasto de embeddings
es despreciable.

---

## 1. Traer los mensajes a un sandbox local

Script: `prueba-jev/scripts/pull.ts`

- La DB es conocida (§0.4), así que no hace falta el `findUser` vía wrangler D1
  de `scripts/import-common.ts`: alcanza con
  `TURSO_DB=finance-00mtqgh7qtjguu7n6902` en `.env` y un token de
  `turso db tokens create $TURSO_DB` (o `platformClient` de `import-common.ts`
  con `TURSO_API_TOKEN`/`TURSO_ORG`, que hace lo mismo programáticamente). El
  script acepta `--db` por si alguna vez es otro usuario.
- Copia a un archivo local `prueba-jev/data/lab.db`
  (`createClient({ url: 'file:...' })`, que usa libSQL con vectores incluidos):
  - `notifications(id, package_name, title, text, category, post_time)`
  - `emails(id, from_email, subject, body_text, body_html, received_at)`
  - `applications(id, name)` para etiquetas legibles.
- Idempotente (`insert or replace`), con `--since` opcional.

Trabajar sobre `lab.db` primero evita gastar llamadas y evita escribir en la DB
real hasta que los números convenzan.

---

## 2. Marcar candidatos: monto + Jev

Script: `prueba-jev/scripts/classify.ts`

El flag y el vector van **como columnas de `notifications` y `emails`** (ver
§3 para el porqué). En el sandbox se agregan sobre las copias de esas mismas
tablas en `lab.db`, con el mismo DDL que después irá a `SCHEMA_SQL`:

```sql
alter table notifications add column jev_score real;          -- noul, null = sin consultar
alter table notifications add column is_movement integer;     -- 1/0 tras el umbral, null = sin consultar
alter table notifications add column classified_at integer;
-- idem para emails
```

`has_amount` (paso 1) **no se persiste**: es una regex determinista y barata,
se recalcula en cada corrida. Lo que vale la pena guardar es lo que cuesta
plata: el score de Jev.

**Paso 1 — monto (local, gratis).** Regex portada de `Ingest.kt`:
`MONEY = ((?:U\$S|US\$|USD|\$)?\s*[0-9][0-9.,]*)`. Para emails se aplica sobre
`subject + emailPlainText(body_html ?? body_text)` — hay que portar (o
simplificar) `decodeMimeHeader`/`emailPlainText` de `Ingest.kt`, porque el
`body_text` guardado suele ser MIME crudo cortado a 10 kB.

El símbolo de moneda es obligatorio (`$`, `U$S`, `US$`, `USD`): pedir solo
dígitos deja **10.321** notificaciones de 23.671 (cualquier código, hora o "15%
OFF" tiene dígitos), mientras que exigir moneda deja **108**. El precio de esa
elección es perder un movimiento redactado como "mil pesos"; el script imprime
un muestreo de lo descartado-con-dígitos para ver si vale la pena ampliar.

**Paso 2 — Jev (remoto, caro).** Solo para `has_amount = 1`:
- Mismo `systemOne` de `index.ts`, con el estado enriquecido:
  notificación → `{ application (label), title, text, category }`;
  email → `{ from, subject, body }` con el cuerpo recortado (~1500 chars; el
  template se reconoce mucho antes).
- La API es de a **uno** (no hay batch): pool de concurrencia ~8, reintentos con
  backoff, y **checkpoint de `jev_score` después de cada respuesta** para poder
  cortar y reanudar (se saltean los que ya tienen `classified_at`). Con ~166
  mensajes esto es una corrida de minutos, no una campaña.
- `is_movement = jev_score >= UMBRAL` (arrancar en 0.6 y calibrar). El score
  crudo queda guardado, así mover el umbral es un `update` y no 2.000 llamadas
  de nuevo.

**Calibración del umbral (importa).** Ya hay ground truth: el allowlist de
`Ingest.kt` detecta 22 movimientos y 27 en los mails, sin falsos positivos
(medido sobre 21.383/125 filas; hoy son 23.671/132). El script imprime una matriz
`jev_score` × `allowlist` para ver a qué umbral Jev recupera esos 22/27 y
cuántos extra aparecen (los extra son justamente lo interesante: movimientos
que el allowlist no cubre). Salida: `prueba-jev/data/report.md` con el histograma
de scores y una muestra de 20 candidatos nuevos para revisar a ojo.

Salvaguarda de costo: flags `--limit` y `--dry-run` (cuenta cuántas llamadas
haría y no las hace). Queda igual, pero con 166 candidatos es una red de
seguridad, no una restricción operativa.

---

## 3. Embeddings

Script: `prueba-jev/scripts/embed.ts`

- Modelo: **`text-embedding-3-small` con `dimensions: 512`** (el parámetro
  `dimensions` de OpenAI recorta el vector sin reentrenar). 512 × 4 B = 2 KB por
  fila — importa porque estas filas se sincronizan a dos teléfonos. Si la
  calidad de búsqueda no alcanza, subir a 1536 es cambiar una constante y
  re-embeber.
- Texto a embeber: un canónico por mensaje, guardado junto al vector para poder
  auditar qué se embebió:
  `"{app/remitente} | {título/asunto} | {cuerpo recortado}"`.
- Batching real: la API de embeddings acepta hasta 2048 inputs por request →
  lotes de 100, con reintento.
- Columnas, otra vez en las tablas existentes:

```sql
alter table notifications add column embedding F32_BLOB(512);
alter table notifications add column embedding_model text;  -- 'text-embedding-3-small/512'
-- idem para emails
```

- Escritura: `vector32(:v)` con el array serializado como JSON string.
- Idempotente: salta los que ya tienen vector del mismo `embedding_model`;
  cambiar de modelo o de dimensiones es cambiar esa constante y re-embeber solo
  los que no coinciden.
- **Sin `create index ... libsql_vector_idx`**: el motor del device no lo
  entiende (§0). La búsqueda es scan lineal.

### Por qué columnas y no una tabla `message_embeddings` aparte

La versión anterior de este plan usaba tablas separadas por reflejo ("no tocar
el esquema de la app"). Revisado, in-place gana:

- **Ciclo de vida.** El embedding no tiene existencia propia: es una propiedad
  derivada de esa notificación. En la misma fila, borrar la notificación se
  lleva el vector; en tabla aparte hace falta `on delete cascade` (que ninguna
  otra tabla del esquema usa) o queda basura huérfana replicándose para
  siempre.
- **Clave compuesta artificial.** Una tabla aparte necesita `(kind, id)` porque
  une dos tablas distintas, y después todas las queries cargan un join y un
  `kind = 'notification'` que no aporta nada.
- **Sync.** Un vector en tabla aparte es una fila más a replicar, con su propio
  índice primario; en la fila existente es un campo de una fila que igual se
  replica.
- **Lectura.** El miedo razonable era "ahora cada `select` arrastra 2 KB de
  blob": verificado que **ninguna** query Kotlin usa `select *` (todas nombran
  columnas en `NotificationsRepository`/`EmailsRepository`), así que ningún read
  path existente paga el blob. Y en `emails` un blob de 2 KB es ruido al lado
  del `body_html`.
- **Consultar junto.** El caso de uso real es "traeme los movimientos parecidos
  a esto, del último mes, de Mercado Pago": filtro por `post_time`/
  `package_name` **y** distancia en la misma fila, sin join.

El costo es el que se acepta explícitamente: cuando esto se promueva a la DB
real, es un cambio de `SCHEMA_SQL` + **bump de `SCHEMA_VERSION`** y columnas
nuevas por `migrateSchema()`/`addColumn()`. Es exactamente el mecanismo que ya
existe para esto; evitarlo con tablas satelitales era pagar deuda de diseño para
no tocar una constante.

Costo estimado: `text-embedding-3-small` a ~$0.02/1M tokens; aun embebiendo
2.000 mensajes de ~100 tokens son ~200k tokens ≈ **$0.004**. El costo real del
experimento es Jev, no OpenAI.

---

## 4. Consultar por similitud

Script: `prueba-jev/scripts/search.ts "supermercado en pesos"`

- Embebe la consulta con el mismo modelo/dims y corre el scan exacto (la única
  forma que funciona igual en servidor y en device, §0):

```sql
select id, package_name, title, text, post_time,
       vector_distance_cos(embedding, vector32(:q)) as d
from notifications
where embedding is not null
order by d limit 20
```

  El script mide el tiempo: es el número que decide si alguna vez hace falta
  algo más que un scan (con ~2.000 vectores de 512 dims, no debería).
- Además un modo `--like <messageId>` que usa el vector de un mensaje existente
  como consulta: es el caso de uso concreto ("mostrame las notificaciones que se
  parecen a esta", el paso previo a aprender un template nuevo sin escribirlo a
  mano en el allowlist).

---

## 5. Promoción a la DB real (aún dentro de `prueba-jev/`, opt-in)

Script: `prueba-jev/scripts/push.ts --confirm`

- `alter table ... add column` (tolerando "duplicate column name", igual que
  `addColumn()` en Kotlin) sobre `notifications` y `emails` en la DB del
  usuario, y `update` de `jev_score`/`is_movement`/`classified_at`/`embedding`/
  `embedding_model` fila por fila desde `lab.db`. Nada de tablas nuevas.
- Este paso sí exige el cambio en la app, en el mismo commit: las 5 columnas
  (× 2 tablas) en `SCHEMA_SQL`/`migrateSchema()` y **bump de `SCHEMA_VERSION`**.
  Sin el bump, el device que corrió el script no nota nada y los demás se rompen
  con "no such column" — el mismo error que ya costó `accounts.in_net_worth` en
  iOS.
- Alternativa si se quiere probar en la DB real **antes** de tocar la app: usar
  un usuario/DB de prueba. Escribir columnas en la DB de producción sin
  declararlas en `SCHEMA_SQL` deja un esquema que ninguna instalación fresca
  reproduce.
- Requiere `--confirm`; sin el flag, imprime lo que haría.

---

## 6. Qué queda para después (fuera de este plan)

- Mover los pasos 2 y 3 al worker/app para que corra continuo sobre mensajes
  nuevos (hoy es un barrido manual).
- Usar la similitud dentro de `IngestRepository`: "esta notificación se parece a
  una que ya clasificaste como movimiento" como tier extra, complementando —no
  reemplazando— el allowlist de `Ingest.kt`.
- Decidir si el `event_key` de `Reconcile.kt` puede apoyarse en similitud para
  emparejar el mail y la push del mismo consumo.

---

## Archivos

| Archivo | Qué hace |
| --- | --- |
| `prueba-jev/scripts/pull.ts` | copia notificaciones/emails de la DB del usuario a `data/lab.db` |
| `prueba-jev/scripts/classify.ts` | paso 1 (regex de monto) + paso 2 (Jev) → `jev_score`/`is_movement` + `data/report.md` |
| `prueba-jev/scripts/embed.ts` | embeddings OpenAI → columna `embedding` |
| `prueba-jev/scripts/search.ts` | búsqueda por texto o por mensaje similar |
| `prueba-jev/scripts/push.ts` | promoción opt-in de las columnas a la DB real |
| `prueba-jev/lib/text.ts` | port de `MONEY`, `decodeMimeHeader`, `emailPlainText` |
| `prueba-jev/.env` | `TYPESAFE_API_KEY` (ya está) + `OPENAI_API_KEY`, `TURSO_DB=finance-00mtqgh7qtjguu7n6902`, `TURSO_TOKEN` |

Dependencias nuevas: `@libsql/client` y `openai` (o `fetch` directo a
`/v1/embeddings`, que evita una dependencia entera por un solo endpoint).

## Riesgos

1. ~~Vectores en el motor local de sync~~ — resuelto en §0: funciones sí,
   índice DiskANN no. Riesgo residual: **no crear** `libsql_vector_idx` en la DB
   del usuario, porque el esquema se replica a un motor que no conoce esa
   función.
2. ~~Costo/latencia de Jev~~ — medido en §0.4: el prefiltro de moneda deja ~166
   mensajes. El riesgo real se dio vuelta: no es gastar de más, es que el
   prefiltro sea **demasiado** estrecho y Jev nunca vea un movimiento escrito
   sin símbolo de moneda.
3. **Peso del sync**: 2 KB por vector × N candidatos. Con ~166 candidatos son
   ~300 kB — irrelevante. Si más adelante se amplía el prefiltro a los 10.321
   con dígitos, son ~20 MB y ahí sí importan las 512 dims.
4. **El bump de `SCHEMA_VERSION`** en §5: es el paso que se olvida y falla en el
   device que no migró, no en el que escribió la migración.
4. **Calidad del texto de los emails**: si se embebe MIME crudo, los vectores
   miden encabezados, no contenido. Por eso `emailPlainText` sobre `body_html`
   es parte del paso 1, no un detalle.
