# Plan — "parecidos a este" en la app (tanda 1 de la vectorización)

Llevar a la app lo que `prueba-jev/` ya probó, pero **solo la mitad barata**:
buscar por similitud. Esta tanda **no clasifica nada** — sin Jev, sin extracción
con Gemini, sin `Reconcile`. Eso es la tanda siguiente, y este plan existe en
parte para conseguirle los datos.

Propiedad que ordena todo el diseño: **el vector de la fila que estás mirando ya
está guardado**, así que las tres pantallas de "similares" funcionan **offline y
sin una sola llamada de red**. Lo único que necesita conexión es el barrido que
llena los vectores, y eso va detrás de un botón explícito.

Antecedentes medidos (ver `plans/embeddings-prueba-jev.md`): el motor de sync
del teléfono tiene `vector32`/`vector_distance_cos`/`vector_extract` pero **no**
`libsql_vector_idx` ni `vector_top_k`, así que la búsqueda es scan exacto y **no
se crea ningún índice vectorial** (el esquema se replica al device y ahí el
índice no parsea).

---

## Decisiones tomadas

**f32 × 512 dims en las tres tablas.** Se evaluó cuantizar notificaciones a
`float8` para aguantar 23.671 filas (47 MB → 12 MB), pero como solo se embeben
**las candidatas**, el corpus real es:

| tabla | filas a embeber | f32×512 |
| --- | --- | --- |
| `notifications` (pasan el regex `MONEY`) | ~148 | 300 kB |
| `emails` (idem) | ~130 | 260 kB |
| `ledger_transactions` (todas) | 193 | 390 kB |
| **total replicado a cada device** | ~470 | **~1 MB** |

Con 1 MB no se paga precisión por 500 kB. Y el tipo único es lo que hace posible
el cruce entre tablas (§"Cruce"): `vector_distance_cos` exige **mismo tipo y
misma dimensionalidad**, no le importa de qué tabla salga el vector. Con
`float8` en notis y `f32` en transacciones, el cruce fallaba o había que
envolver cada fila en `vector32(...)` dentro del scan.

**Solo candidatas**, con escotilla: si abrís una notificación que el regex
descartó, no tiene vector. En vez de ocultar la sección, un botón **"buscar
parecidas"** la embebe en el momento (una llamada, ~400 ms, queda guardada). Es
barato y además delata dónde se equivoca el regex.

**El botón de revectorizar va en `ProfileScreen`**, no en el Journal: toca las
tres tablas y el Perfil ya es el lugar del mantenimiento (cadena de
dispositivos, sesión).

**Chip "solo esta app"** en la lista de similares de notificaciones: filtrar por
`package_name` es más relevante y mucho más barato, pero por defecto se busca en
todo para no perder el cruce entre orígenes (el push de Santander parecido al
mail de Santander).

---

## 1. Esquema (`Schema.kt`)

Dos columnas en tres tablas:

```sql
alter table ledger_transactions add column embedding F32_BLOB(512);
alter table ledger_transactions add column embedding_model text;  -- 'gemini-embedding-001/512'
-- idem notifications, emails
```

- Van en `SCHEMA_SQL` + `migrateSchema()` vía `addColumn()`, y
  **`SCHEMA_VERSION` 6 → 7** (`Schema.kt:90`). Sin el bump no pasa nada en el
  device que corrió la migración y explota en los otros con "no such column",
  que es exactamente lo que pasó con `accounts.in_net_worth` en iOS.
- `embedding_model` guarda `modelo/dims`: dos modelos nunca se comparan entre
  sí, y cambiar de modelo es cambiar la constante y revectorizar.
- **Ningún índice.**
- `jev_score`/`is_movement` **no** entran acá. Son de la tanda de clasificación.

---

## 2. Worker: `POST /embed`

La key no puede viajar en el APK, así que el embedding pasa por el worker. Es el
endpoint más chico que tiene:

- Se cuelga del bloque que ya existe en `app/worker/src/index.ts` (mismo patrón
  que `/import/analyze`): `authenticate(request, env)` con la cookie
  `auth_finance`, o sea que revocación y expiración salen gratis.
- Body: `{ texts: string[] }` (lote acotado, ~100). Respuesta:
  `{ model: string, dims: number, vectors: number[][] }`.
- Proveedor: `gemini-embedding-001` con `outputDimensionality: 512`, por
  `batchEmbedContents`. Se reusa el patrón de `geminiJson` de `import.ts` para ir
  por el AI Gateway con fallback a Google directo.
- **Renormalizar**: Google trunca a 512 dims sin renormalizar, y el coseno lo
  nota. Ya está resuelto en `prueba-jev/lib/embed.ts` — se porta.
- El worker **no escribe** en la Turso del usuario: devuelve vectores y escribe
  la app. Así el device sigue siendo el único escritor de esas tablas.

Nuevo archivo `app/worker/src/embed.ts`; `index.ts` solo rutea.

---

## 3. sharedLogic: `EmbeddingsRepository`

Nuevo `app/sharedLogic/src/commonMain/kotlin/ar/fausto/weil/EmbeddingsRepository.kt`:

```kotlin
suspend fun embedPending(limit: Int = 200, onProgress: (done: Int, total: Int) -> Unit)
suspend fun embedOne(kind: EmbedKind, id: String)          // la escotilla del botón
suspend fun similar(kind: EmbedKind, id: String, k: Int = 10,
                    into: EmbedKind = kind, samePackageOnly: Boolean = false): List<Similar>
suspend fun invalidateAll()                                 // el "revectorizar" duro
```

- `embedPending` busca filas con `embedding is null` **o** `embedding_model <>`
  el actual, en lotes, guardando después de cada lote: se puede cortar y
  reanudar (el patrón ya probado en `classify.ts`).
- Escritura con `vector32(:v)` y el array como JSON string.
- `similar` es el scan exacto:

```sql
select id, …, vector_distance_cos(embedding, vector32(:q)) as d
from notifications
where embedding is not null and id <> :self
order by d limit :k
```

  El `:q` sale de `vector_extract(embedding)` de la fila origen — **sin red**.

### Texto canónico (el detalle que decide si esto funciona)

Port de `canonicalText` de `prueba-jev/lib/text.ts`, más una decisión nueva: una
notificación es una oración ("Pagaste $46.210 a Rappi") y una transacción es
estructura (`Rappi | Mercado Pago → Comida:Delivery`). Son **registros de texto
distintos**, y el coseno entre registros distintos es más flojo que dentro del
mismo. Para que el cruce sirva, el texto de la transacción se redacta acercándose
a la oración y con el **payee bien al frente**, que es la señal que de verdad
comparten. Si esto se hace mal, el cruce anda a medias y parece que la idea no
sirve.

- Transacción: `"{payee} · {cuenta origen} → {cuenta destino} · {nota}"`.
  **Sin monto ni fecha**: de eso sabe `Reconcile`, y en el vector solo agregan
  ruido (dos compras en Rappi de montos distintos deben parecerse, no
  distinguirse).
- Notificación: `"{nombre de la app} · {título} · {texto}"`.
- Email: `"{remitente} · {asunto} · {cuerpo recortado}"`.

### Arrastre obligatorio: los fixes de texto del lab

Tres cosas que `prueba-jev` encontró y que `Ingest.kt` todavía no tiene. Van en
esta tanda porque **la calidad del vector depende de ellas**:

1. `unwrapMime` — **45 de 132 mails** tienen MIME crudo guardado (multipart
   cortado a 10 kB con el boundary declarado después del corte). Embeber eso es
   medir encabezados, no el recibo.
2. `<style>` sin cerrar → CSS entero como texto.
3. `MONEY` anclado a ambos lados (`30.000 ARS`, no solo `$ 30.000`), que es lo
   que define qué fila es candidata.

Los tres son ports línea a línea desde `prueba-jev/lib/text.ts` a `Ingest.kt`,
con tests. Mejoran `IngestRepository.inbox()` de paso.

### Staleness

Editar payee o cuentas deja el vector viejo. Invalidación barata: poner
`embedding = null` en el `update` de `TransactionsRepository`, y que el próximo
barrido lo recalcule.

---

## 4. UI

Un componente compartido `SimilarList` (fila = título + **monto y fecha bien
visibles** + distancia en dim), usado por las tres pantallas.

| pantalla | qué se agrega |
| --- | --- |
| `TransactionDetailScreen` | "Transacciones similares" + "Notificaciones parecidas" (§Cruce) |
| `EmailDetailScreen` | "Emails similares" + "Transacciones parecidas" |
| **`NotificationDetailScreen` (nueva)** | texto completo, app, fecha, "Notificaciones similares" (+ chip *solo esta app*) y "Transacciones parecidas" |
| `ProfileScreen` | ítem "Revectorizar" con progreso, y variante que primero invalida todo |

**Lo que no existe y hay que crear**: las notificaciones no tienen pantalla de
detalle. `NotificationCard` (`NotificationsScreen.kt:192`) ni siquiera es
clickeable. Hace falta `NotificationDetailRoute(id)` en `Routes.kt`, la pantalla,
y hacer la card navegable. **Es el grueso del trabajo de esta tanda.**

Todas las cadenas nuevas van a
`sharedUI/src/commonMain/composeResources/values/strings.xml` — nada hardcodeado.

---

## Cruce entre tablas: para qué es todo esto

Como las tres tablas comparten tipo y dimensiones, "notificaciones parecidas a
esta transacción" es un `select` sobre otra tabla. Y al lado de cada candidata va
un botón **"vincular como origen"**, que escribe
`transaction_sources(kind='notification', ref=<id>)`.

Eso paga tres cosas de un saque:

1. Encontrás el push que corresponde a un gasto cargado a mano o venido de un
   resumen.
2. **Llena el corpus que hoy está en cero.** Medido en la base real: 184 sources
   de documentos, 6 de WhatsApp, **0 de notificaciones**. Cada vínculo manual es
   un ejemplo etiquetado para el pipeline automático de la tanda siguiente —
   etiquetado mientras usás la app, sin pantalla de etiquetado.
3. La lista al revés (transacciones parecidas a una notificación) es la misma
   función con los parámetros dados vuelta.

**Cuidado explícito**: el coseno **no** decide si es el mismo evento. Dos compras
en Rappi son vectores casi idénticos y transacciones distintas; el mismo consumo
visto por el push y por el resumen puede no parecerse textualmente. Por eso la
lista muestra monto y fecha, y **la vinculación la confirma el humano**. Más
adelante se puede ordenar esa lista con `Reconcile.matchAll`, que sí sabe de
montos, fechas y cuentas.

---

## Orden de trabajo

1. Fixes de texto en `Ingest.kt` (`unwrapMime`, `<style>`, `MONEY`) + tests.
   No tocan esquema ni red; se pueden mergear y dejar correr solos.
2. `SCHEMA_SQL` + `migrateSchema()` + bump a 7.
3. `app/worker/src/embed.ts` + ruta.
4. `EmbeddingsRepository` + texto canónico + invalidación en el update.
5. `ProfileScreen`: revectorizar (sin esto no hay datos para ver nada).
6. `SimilarList` + secciones en `TransactionDetailScreen` y `EmailDetailScreen`.
7. `NotificationDetailScreen` + ruta + card clickeable.
8. Cruce entre tablas + "vincular como origen".

Verificación: `./gradlew :app:androidApp:assembleDebug`,
`:app:sharedUI:compileKotlinIosSimulatorArm64`, y las pantallas nuevas con
`./gradlew :app:desktopApp:shot` antes de tocar un emulador.

---

## Riesgos

1. **El bump de `SCHEMA_VERSION`.** Es el paso que se olvida y falla en el device
   que no migró, nunca en el que escribió la migración.
2. **Registro de texto en el cruce** (§3): si transacción y notificación se
   redactan distinto, el cruce decepciona y la conclusión equivocada sería "los
   vectores no sirven".
3. **Modelo distinto al del lab.** `prueba-jev` midió con
   `gemini-embedding-001/512` (la key de OpenAI está sin crédito). Si el worker
   terminara usando otro, los números medidos no se trasladan — de ahí que
   `embedding_model` esté en cada fila.
4. **Primer barrido**: ~470 llamadas en lotes. Son segundos, pero tiene que
   poder cortarse y reanudarse igual, porque el usuario puede irse de la
   pantalla.
5. **"Solo candidatas" depende del regex**, que puede equivocarse. Mitigado por
   el botón "buscar parecidas" por fila.

## Lo que esta tanda deja preparado y no hace

Clasificación (Jev o Gemini), extracción estructurada de mensajes sin plantilla,
`CandidateEvent` → `Reconcile.matchAll` automático, y sugerencia de categoría por
vecinos en `TransactionQuickScreen`. Todo eso se apoya en los vectores y en los
vínculos manuales que esta tanda empieza a juntar.
