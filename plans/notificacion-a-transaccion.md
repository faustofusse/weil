# Plan — de una notificación a una transacción, con Jev

Botón en `NotificationDetailScreen`: **«Crear transacción»**. No escribe nada:
llama a la API, que usa Jev para decidir *qué cuentas* mueve ese aviso, y
abre la pantalla de revisión con el borrador cargado. Guardar sigue siendo un
acto del usuario.

Esto es la tanda 2 de la vectorización: la tanda 1 (`plans/similares-en-la-app.md`)
existía en parte para conseguirle los datos a esta. Lo que hace fuerte a la
sugerencia no es el modelo, son los **precedentes**: las notificaciones
parecidas que ya están vinculadas a una transacción dicen, literalmente, cómo
se registró la última vez el mismo aviso del mismo banco.

---

## Lo que ya existe (revisado sobre el código, no sobre el plan viejo)

Desde que se escribió la primera versión de este plan, Jev ya entró al worker y
a la app. Nada de acá se construye de cero:

| pieza | dónde | qué aporta |
| --- | --- | --- |
| `systemOne(env, state, questions)` | `app/worker/src/suggest.ts` | el cliente HTTP a `api.typesafe.ai/v1/systemone`, sin SDK, con los tipos `ChoiceAnswer`/`NoulAnswer` |
| `categoryQuestion(body, options)` | `suggest.ts` | **la** definición de «qué categoría es esta», parametrizada por `kind` y por un `context` que describe de dónde salió el texto |
| `NO_MATCH` | `suggest.ts` | el centinela de ninguna-de-estas, escrito como frase justamente para que no choque con una cuenta llamada «Otros» |
| `POST /suggest/category` | `index.ts` | endpoint cookie-authed que no lee Turso: el device manda el árbol |
| `chat.ts` | worker | el precedente de dos modelos juntos: Gemini lee el mensaje y Jev elige la categoría **en paralelo** (`Promise.all`), reordenando `GEMINI_MODELS` para probar primero el lite |
| `CategorySuggester` / `CategorySuggestRepository` | sharedLogic | el patrón de cliente: interfaz para poder inyectar un fake, `CategorySuggestion(accountId, path, confidence, ranked)`, y `accountId = null` cuando el modelo contestó NO_MATCH (una respuesta, no una falla) |
| `NotificationDetailScreen` con `AppTopBar` + `SimilarSection` | sharedUI | la pantalla donde va el botón, ya con la piel nueva |

Dos consecuencias directas para este plan:

1. Las preguntas de categoría **no se escriben de nuevo**: se importa
   `categoryQuestion` con otro `context` («una notificación push de un banco» en
   vez de «un campo a medio tipear»), igual que hizo `chat.ts`. Una sola
   definición, y mejorarla mejora las tres superficies.
2. El reparto Gemini/Jev ya está decidido y probado en el camino de WhatsApp:
   **el importe, la dirección y el payee los lee Gemini**, porque son lenguaje;
   **las cuentas las elige Jev**, porque son una lista cerrada. Este plan es el
   mismo reparto sobre otra entrada.

---

## Flujo

Tres etapas, y el orden importa: **primero se extrae, después se busca, al
final se decide**. Buscar parecidos con el texto crudo del aviso mezcla la
plantilla del banco con lo único que distingue una compra de otra; con el
comercio y el importe ya normalizados, la búsqueda deja de ser «avisos que se
escriben parecido» y pasa a ser «compras parecidas».

```
NotificationDetailScreen
  └ botón «Crear transacción»
      │
      ├ etapa 1 — POST /suggest/message    → Gemini Flash Lite, responseSchema
      │           { amount, commodity, direction, merchant, accountHint,
      │             normalized, isMovement }
      │
      ├ etapa 2 — device, offline, sin red
      │           · Reconcile.matchEvent(CandidateEvent(importe, fecha, ...))
      │             → si Confident, se corta acá: la fila abre en modo «asociar»
      │             y Jev no se llama
      │           · similar(embedding de `normalized`) → precedentes
      │           · payees iguales o parecidos en el libro (like sobre payee)
      │           · reconcileFacts(t ± 20 h)
      │
      └ etapa 3 — POST /suggest/accounts   → Jev elige las cuentas
      └ navega a ImportReviewRoute(ReviewSource.Message(Notification, id))
          └ una sola fila, abierta, editable, con «crear» / «asociar» / «omitir»
```

Son dos round trips desde el teléfono, y es a propósito: la recuperación tiene
que correr **en el device**, porque el matcher y los vecinos deben ver filas que
todavía no sincronizaron — el mismo argumento por el que `Reconcile.kt` vive en
Kotlin y no en el worker. Presupuesto: Gemini Flash Lite sobre dos renglones
mide ~1–2 s, Jev ~100–300 ms, el resto es SQL local. Lejos de los 15–25 s que
tarda una importación de resumen.

Qué aporta cada modelo, para no pagar dos por gusto:

| | qué resuelve | por qué no lo resuelve el otro |
| --- | --- | --- |
| Gemini (extracción) | importe, comercio, dirección, texto normalizado | Jev elige entre opciones: no genera texto ni normaliza «COTO CICSA 4821» a «Coto» |
| Jev (decisión) | qué cuenta propia y qué categoría, con probabilidad calibrada | Gemini devuelve una categoría sin decir cuánto se la cree, y eso es lo que prende o apaga el prellenado |

**Nada de regex en este camino.** Leer «$ 12.400», «12.400 ARS» o «U$S100,00»
es un problema de lenguaje, y un regex que decide si 12.500 son doce mil o doce
con cincuenta es una heurística escondida en la cañería — es exactamente el
argumento con el que el camino de WhatsApp le dejó el monto a Gemini. El
allowlist de `Ingest.kt` sigue existiendo para el inbox, pero este botón no lo
usa ni lo consulta: una sola forma de leer un aviso, no dos que puedan
discrepar.

La pantalla de revisión ya hace todo lo demás: corre `Reconcile` contra la
ventana de hechos, escribe con `addAll` en una transacción SQL, adjunta el
`transaction_sources(kind='notification', ref=<id>)` y deja el Snackbar de
deshacer. No hay pantalla nueva de preview: **`ImportReviewScreen` con un solo
candidato ya es esa pantalla**, y reusarla evita duplicar el camino de escritura
(que es el único lugar del código que puede arruinar el libro mayor).

---

## Paso 0 — la pantalla de prueba (primero esto, y sólo esto)

Antes del botón «Crear transacción» va un botón **«Probar»** en
`NotificationDetailScreen`, que corre exactamente el mismo camino y **no lleva
a ningún lado**: abre `SuggestDebugRoute(id)`, una pantalla que muestra todo lo
que entró y salió de cada etapa. Nada se escribe, no aparece la pantalla de
revisión, no se crea ninguna transacción.

Es el orden que ya funcionó dos veces en este repo: `prueba-jev/scripts/` y
`scripts/chat.ts` existieron antes que las features. La diferencia es que acá
el arnés tiene que correr **en el teléfono**, porque la mitad del contexto (los
vectores, el libro sin sincronizar, el árbol real) sólo existe ahí.

Qué muestra, en bloques colapsables y tipografía monoespaciada:

| bloque | contenido |
| --- | --- |
| 1 · entrada | app, título, texto y fecha del aviso, tal cual salen de `notifications` |
| 2 · Gemini: pedido | el prompt y el `responseSchema` que se mandaron |
| 3 · Gemini: respuesta | el JSON crudo + el modelo que respondió (cuál de `GEMINI_MODELS` ató) + ms |
| 4 · recuperación | precedentes con su distancia coseno y a qué transacción están vinculados, vecinos ±20 h, resultado de `Reconcile.matchEvent` con su `MatchReason` |
| 5 · Jev: pedido | el `state` y el `questions` completos, el JSON que se postea |
| 6 · Jev: respuesta | por pregunta: `choice`, `confidence` y la distribución entera, más `usage` y ms |
| 7 · candidato | el `ImportCandidate` que saldría, y qué campo quedó prellenado vs. vacío según los umbrales |

Arriba de todo, una línea con los tiempos de pared de las tres etapas, que es
el número que decide si el flujo es usable. Y un botón de copiar todo el trazo
como JSON, para poder pegarlo en un issue o compararlo entre avisos.

Cómo se consigue el trazo sin duplicar código: los dos endpoints aceptan
`?debug=1` y devuelven, además de la respuesta normal, un `debug` con el pedido
tal cual se armó (prompt, `state`, `questions`) y la respuesta cruda del
proveedor. El device lo guarda en un `SuggestTrace` junto con lo que armó él
mismo (etapa 2). Así la pantalla de prueba mira **el camino de producción**, no
una copia paralela que se desincroniza — que es el único modo en que un arnés
de este tipo sirve para algo.

Con eso andando se puede recorrer el historial de notificaciones a mano, ver
dónde falla y recién ahí fijar los umbrales y enchufar la pantalla de revisión.
La pantalla de prueba **queda** después, detrás del overflow: cuando el botón
real se equivoque, el único modo de saber por qué es volver a ver las siete
cajas.

---

## Dónde corre cada cosa

| pieza | dónde | por qué |
| --- | --- | --- |
| etapa 1, extracción | worker → Gemini (`/suggest/message`) | la API key es un secreto de servidor; reusa el fallback gateway→Google de `import.ts` |
| etapa 2, recuperación y matcher | device (`SuggestRepository`, sharedLogic) | los vectores, el árbol y el libro están locales, incluidas las filas sin sincronizar; el worker sólo ve estado *del servidor* |
| etapa 3, preguntas + Jev | worker (`/suggest/accounts`) | `TYPESAFE_API_KEY` es un secreto de servidor, nunca viaja al teléfono |
| escribir | device | sigue siendo el único escritor, igual que `/embed` y `/import` |

Ninguno de los dos endpoints toca Turso: reciben lo que el device les manda y
devuelven una respuesta. Se autentican con la misma cookie `auth_finance` que
`/import/analyze` y `/embed` (`authenticate(request, env)`).

---

## El contexto (el `state` de Jev)

Todo se arma en `SuggestRepository` con consultas que ya existen. JSON con
campos nombrados, porque son varias partes con relaciones distintas:

```jsonc
{
  "notification": { "app": "Mercado Pago", "title": "...", "text": "...",
                    "category": "msg", "when": "2025-03-11 14:32 (martes)" },

  // lo más informativo: avisos parecidos que YA se registraron
  "precedents": [
    { "text": "Pagaste $12.400 en Coto",
      "recorded_as": { "payee": "Coto", "from": "Activos:Mercado Pago",
                       "to": "Gastos:Supermercado", "when": "hace 8 días" } }
  ],

  // ventana ±20 h: para saber si ya está registrado
  "nearby_transactions": [
    { "id": "t_1", "payee": "Coto", "amount": "$12.400",
      "from": "Activos:Mercado Pago", "to": "Gastos:Supermercado",
      "when": "hace 3 h" }
  ],

  // el árbol, ya separado por rol
  "my_accounts":  [ { "path": "Activos:Mercado Pago", "commodity": "ARS", "used": 412 }, ... ],
  "categories":   [ { "path": "Gastos:Supermercado", "used": 63 }, ... ],
  "income":       [ { "path": "Ingresos:Sueldo", "used": 14 }, ... ],

  // lo que devolvió la etapa 1, no lo que Jev tiene que adivinar
  "extracted": { "amount": "ARS 12.400", "merchant": "Coto",
                 "direction": "expense", "account_hint": "Mercado Pago" }
}
```

De dónde sale cada cosa:

- **extracted**: la salida de la etapa 1. Es también lo que arma el
  `CandidateEvent` del matcher.
- **precedents**: `EmbeddingsRepository.similar(Notification, id, into = Notification)`,
  y para cada vecino `ledger.transactionsForSource(EventSource.Notification, vecino.id)`.
  Se quedan **los 3** más cercanos que tienen transacción: con tres alcanza para
  que el patrón se vea, y de ahí para arriba empiezan a contradecirse entre sí
  cuando la misma app manda avisos de cosas distintas. Si no hay ninguno (hoy el device
  tiene **cero** fuentes de tipo notificación), la lista va vacía y Jev decide
  solo con el árbol; cada vinculación manual de la tanda 1 mejora esta lista.
  Conviene sumar también `similar(..., into = Transaction)`, que no necesita
  vínculos previos: es el texto del aviso contra el texto de las transacciones.
- **nearby_transactions**: `ledger.reconcileFacts(t − 20 h, t + 20 h)`, la misma
  ventana que usa `Reconcile.kt`. Se mandan con id corto para poder mapear la
  respuesta.
- **my_accounts / categories / income**: `accounts.tree()` aplanado, con el
  `path` completo como identidad visible y el id sólo del lado del device.
  `used` = cantidad de postings, para que el modelo prefiera lo que el usuario
  usa de verdad.

Con la extracción por delante, la recuperación mejora en dos frentes: el
embedding de consulta se calcula sobre la **frase normalizada** («Coto, gasto»)
en vez del texto del aviso, y el re-ranking por importe que ya tiene `similar()`
recibe un importe confiable en vez del `biggestMoney` del texto. Los vectores
guardados no se tocan: lo que cambia es el vector de la *consulta*.

Todo el texto del estado va en español porque los datos son en español; las
`instructions` van en inglés, que es el idioma primario de Jev.

---

## Las preguntas

Una sola request, todas las preguntas en paralelo, incluidas las especulativas
(las ramas que no aplican se ignoran en código). Las claves de `criteria` son
**los paths de las cuentas**, no los ids: los paths son únicos, el modelo los
entiende y el device los mapea de vuelta a id sin ambigüedad. El límite de
Choice son 255 opciones (`MAX_OPTIONS = 200` en `suggest.ts`); el árbol real
tiene decenas, así que va entero — no se manda un shortlist.

Dos de estas preguntas **no se escriben acá**: `expense_category` e
`income_category` son `categoryQuestion(…, kind, context)` de `suggest.ts` con
el `context` de una notificación push, exactamente como `chat.ts` la reusa para
WhatsApp. El centinela de ninguna-de-estas es el `NO_MATCH` que ya existe; el
resto de los Choice usa uno análogo, escrito como frase por la misma razón
(una cuenta puede llamarse «Otros»).

```jsonc
{
  "is_movement": {
    "type": "noul",
    "instructions": "Does `notification` report a movement of the recipient's own money that already happened (a payment, charge, transfer, deposit or withdrawal)?",
    "criteria": {
      "true": "Money actually left or entered an account of the recipient",
      "false": "A promotion, offer, reminder, balance update, login alert or an amount the recipient did not move"
    }
  },

  "direction": {
    "type": "choice",
    "instructions": "In `notification`, which way did the money move, from the recipient's point of view?",
    "criteria": {
      "expense":  "The recipient spent money: a purchase, a fee, a bill, a card charge",
      "income":   "The recipient received money: salary, a refund, a transfer someone sent them, interest",
      "transfer": "Money moved between two accounts the recipient owns: topping up a wallet, paying their own credit card, buying foreign currency. Nothing was spent or earned"
    }
  },

  // importe, comercio y moneda NO se preguntan: los leyó Gemini en la etapa 1
  // y están en `extracted`. `direction` se pregunta igual porque el signo
  // decide qué rama especulativa se lee, y cuesta casi nada confirmarlo.

  "my_account": {
    "type": "choice",
    "instructions": "Which of the recipient's own accounts did the money move through? `precedents` shows how similar notifications were recorded before.",
    "criteria": {
      "Activos:Mercado Pago": "Mercado Pago wallet, ARS",
      "Pasivos:Visa Santander": "Santander Visa credit card, ARS",
      "Ninguna de estas cuentas": "The notification does not say which account, and the precedents do not settle it"
    }
  },

  // especulativa: se lee sólo si direction = expense. Es categoryQuestion()
  // de suggest.ts, con kind = 'expense' y el context de una notificación.
  "expense_category": { "type": "choice", "...": "categoryQuestion(...)" },

  // especulativa: sólo si direction = income. Misma función, kind = 'income'.
  "income_category": { "type": "choice", "...": "categoryQuestion(...)" },

  // especulativa: sólo si direction = transfer. Pregunta aparte sobre la misma
  // lista que `my_account`: dos preguntas claras («de dónde salió», «a dónde
  // entró») confunden menos al modelo que una sola partida por el signo.
  "transfer_destination": {
    "type": "choice",
    "instructions": "Assuming this is a transfer between the recipient's own accounts, which account did the money arrive in? It is not the same account as the one the money left.",
    "criteria": { "...": null, "Ninguna de estas cuentas": "..." }
  },

  "already_recorded": {
    "type": "noul",
    "instructions": "Is the movement in `notification` already one of the rows in `nearby_transactions`?",
    "criteria": {
      "true": "The same movement, same amount and same counterparty, is already in the ledger",
      "false": "No row in `nearby_transactions` is this movement"
    }
  },

  "duplicate_of": {
    "type": "choice",
    "instructions": "If the movement is already in `nearby_transactions`, which row is it?",
    "criteria": { "t_1": "Coto $12.400 hace 3 h", "t_2": "...", "none": "It is not any of them" }
  }
}
```

Criterios de diseño detrás de esa forma, que es lo que el skill insiste en
cuidar:

1. **Una pregunta = un juicio.** «¿Qué transacción es esto?» esconde cinco
   decisiones; separadas se pueden mirar, ajustar y combinar en código.
2. **Elegir, no generar — y sólo lo que es elegible.** A Jev se le dan listas
   cerradas (cuentas, categorías, transacciones vecinas). Lo que hay que leer y
   normalizar (el importe, el comercio) lo hace Gemini, que para eso sirve. Ni
   uno ni otro campo pasa por un regex.
3. **Opciones cerradas = valores válidos por construcción.** Cualquier cosa que
   Jev devuelva en `my_account` es un path real del árbol. No hay «la categoría
   que sugirió no existe».
4. **Siempre una salida de escape.** El `NO_MATCH` de `suggest.ts` en cada Choice. Sin
   ella, el modelo reparte probabilidad entre opciones malas y el resultado
   parece una decisión.
5. **Especulativas en la misma llamada.** `expense_category`, `income_category`
   y `transfer_destination` se preguntan las tres siempre; el código lee la que
   corresponde a `direction`. Corren en paralelo, así que no cuestan latencia,
   sólo tokens — y evitan una segunda request (que sí costaría un round trip
   completo desde el teléfono).
6. **La duplicación es parte de la misma llamada**, no un paso aparte:
   `already_recorded` + `duplicate_of` convierten el botón en «crear **o**
   asociar», que es exactamente lo que la pantalla de revisión ya sabe ofrecer.
   `Reconcile.kt` sigue corriendo igual en el device: si el matcher determinista
   dice `Confident`, gana el matcher, no el modelo.
7. **Qué NO se le pregunta a Jev**: la fecha (es `postTime`, dato duro), el
   importe y la moneda (los leyó Gemini), el comercio (ídem), y si la
   notificación es de una app bancaria conocida (eso es `AppLookup`).

### Qué hace el código con las probabilidades

Los umbrales se calibran contra las ~148 notificaciones con moneda del device;
punto de partida:

| resultado | acción |
| --- | --- |
| `is_movement < 0.35` | igual se abre la revisión, pero con un aviso «esto no parece un movimiento» |
| `already_recorded > 0.6` y `duplicate_of.confidence > 0.6` | la fila se abre en modo **asociar** a esa transacción |
| `confidence ≥ 0.75` en una cuenta/categoría | se prellena |
| `confidence` entre 0.5 y 0.75 | se prellena **y** se marca el campo para revisar |
| `confidence < 0.5` o NO_MATCH | campo vacío, cae el default de `SettingsRepository` (`resolveDefault`) |
| Gemini no devolvió importe | el importe queda vacío y el teclado abre en ese campo |

Las probabilidades crudas se devuelven al device y se muestran en la fila (una
línea tipo «Mercado Pago · 0,92»), no se esconden: es la misma decisión de
diseño que tomó `ImportReviewScreen` con los `MatchReason`.

---

## Pasos

0. **La pantalla de prueba** — `SuggestDebugScreen.kt` +
   `SuggestDebugRoute(id)` + botón «Probar» en `NotificationDetailScreen`, el
   `?debug=1` en los dos endpoints y `SuggestTrace` en sharedLogic. Todo lo que
   sigue del 3 al 6 se construye a partir de lo que se vea acá; los pasos 1 y 2
   son su requisito.
1. **Worker, extracción** — `POST /suggest/message` en `app/worker/src/message.ts`,
   al lado de `chat.ts` y con la misma forma: Gemini con `responseSchema`,
   reusando el cliente y el fallback gateway→Google de `import.ts` y el reorden
   de `GEMINI_MODELS` que pone el lite primero (medido en el camino de
   WhatsApp: un mensaje de un renglón no necesita el modelo grande). Devuelve
   importe, moneda, dirección, comercio normalizado, la frase normalizada para
   el embedding y una pista de cuenta; nada más.
2. **Worker, decisión** — `POST /suggest/accounts` en `suggest.ts`, con el
   `systemOne()` y el `categoryQuestion()` que ya están ahí. El secreto
   `TYPESAFE_API_KEY` ya está puesto. Arma las preguntas desde las listas del
   payload, mapea la respuesta a la forma `WireCandidate` que
   `ImportRepository` ya sabe leer, más un bloque `confidence` por campo.
   **Ninguno de los dos escribe en Turso.**
3. **sharedLogic** — `SuggestRepository.kt`, con el molde de
   `CategorySuggestRepository` (cliente ktor propio, interfaz para el fake):
   orquesta las tres etapas (extracción, matcher + recuperación, decisión) y
   devuelve
   `Suggestion(candidate: ImportCandidate, confidences, duplicateOf: String?, isMovement: Double)`.
   Interfaz `TransactionSuggester` para poder inyectar un fake, igual que
   `DocumentAnalyzer`. `HttpTimeout` corto (15 s): Jev responde en ~100–300 ms,
   nada que ver con los 15–25 s de Gemini.
   Se suma a `AppGraph` como `suggestions`.
4. **UI, el botón de verdad** — `ReviewSource.Message(kind: EventSource, ref: String)` como tercer
   arm de la sealed interface; el `when` de carga llama al suggester y arma un
   `CandidateDraft` con `sourceKind`/`sourceRef`/`sourceTitle` ya poblados
   (todo lo demás de la pantalla queda igual). Botón «Crear transacción» en
   `NotificationDetailScreen`, arriba de la sección «Similares». Strings nuevas
   en `values/strings.xml`.
5. **Fake + shot** — `FakeSuggester` en desktopApp (las dos etapas juntas) y ruta
   `-Pshot.route=suggest` para ver la pantalla sin red ni sesión, como ya se
   hace con `FakeImportAnalyzer` y `FakeCategorySuggester`.
6. **Tests** — el armado del contexto y el mapeo respuesta→candidato son
   funciones puras en commonMain: tests en `commonTest` con una respuesta de
   Jev fija. La calidad del modelo se mide aparte, con un script en
   `prueba-jev/` sobre las notificaciones reales, **importando las preguntas
   del worker** para que no se desincronicen — que es como quedaron
   `scripts/chat.ts` y `chatQuestions`.

Sin cambios de esquema, sin bump de `SCHEMA_VERSION`, sin barrido nuevo.

---

## Después (no en esta tanda)

- El mismo botón en `EmailDetailScreen`: `ReviewSource.Message(Email, id)` ya
  queda soportado, sólo falta el botón y los candidatos de texto del mail.
- Sugerir en lote desde `InboxReviewRoute`: hoy `Ingest.kt` sólo reconoce lo
  que está en el allowlist; este camino cubre las 24 que el allowlist no ve.
  Ahí sí hace falta pensar el costo, porque son muchas llamadas a Gemini de
  una.
- Cerrar el círculo: cada vez que el usuario acepta o corrige una sugerencia
  queda un ejemplo etiquetado en `transaction_sources`, que es justo lo que
  alimenta `precedents` la próxima vez.

## Decidido

- **3 precedentes**, los tres vecinos más cercanos que tengan transacción.
- **`my_account` y `transfer_destination` son dos Choice separadas** sobre la
  misma lista: «de dónde salió» y «a dónde entró» son dos preguntas claras, y
  el modelo se confunde menos que con una sola partida por el signo. Los tokens
  de más no se notan al lado de la llamada a Gemini.
- **Sin regex en ningún punto del camino**: el importe y el comercio son
  lenguaje y los lee Gemini.
- **Sin cachear lo extraído** por ahora: ninguna columna nueva, ningún bump de
  `SCHEMA_VERSION`. Si más adelante se ve que la misma notificación se abre
  varias veces, se revisa.

## Preguntas abiertas

- Umbrales: los de arriba son un punto de partida, no una medición. Antes de
  fijarlos conviene correr las notificaciones reales por el endpoint y mirar la
  distribución, como se hizo con el umbral 0,6 de `classify.ts` y con el 16/17
  de `chat.ts`.
