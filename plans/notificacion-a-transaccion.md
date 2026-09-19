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
      │ etapa 0 — gratis, local: ¿lo reconoce el allowlist de Ingest.kt?
      │           si sí, ya hay importe y comercio: se saltea la etapa 1.
      │
      ├ etapa 1 — POST /suggest/extract    → Gemini Flash Lite, responseSchema
      │           { amount, commodity, direction, merchant, accountHint }
      │
      ├ etapa 2 — device, offline, sin red
      │           · Reconcile.matchEvent(CandidateEvent(importe, fecha, ...))
      │             → si Confident, se corta acá: la fila abre en modo «asociar»
      │             y Jev no se llama
      │           · similar(embedding del texto normalizado) → precedentes
      │           · payees iguales o parecidos en el libro (like sobre payee)
      │           · reconcileFacts(t ± 20 h)
      │
      └ etapa 3 — POST /suggest/decide     → Jev elige las cuentas
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

La etapa 1 es salteable y hay que mantenerla así: si el allowlist de `Ingest.kt`
reconoce la plantilla, ya tiene importe y comercio parseados, gratis y offline.
Gemini sólo entra donde el allowlist no llega, que es justo donde está lo que
hoy no se registra.

La pantalla de revisión ya hace todo lo demás: corre `Reconcile` contra la
ventana de hechos, escribe con `addAll` en una transacción SQL, adjunta el
`transaction_sources(kind='notification', ref=<id>)` y deja el Snackbar de
deshacer. No hay pantalla nueva de preview: **`ImportReviewScreen` con un solo
candidato ya es esa pantalla**, y reusarla evita duplicar el camino de escritura
(que es el único lugar del código que puede arruinar el libro mayor).

---

## Dónde corre cada cosa

| pieza | dónde | por qué |
| --- | --- | --- |
| etapa 0, allowlist | device (`Ingest.kt`) | ya existe, offline, determinista |
| etapa 1, extracción | worker → Gemini (`/suggest/extract`) | la API key es un secreto de servidor; reusa el fallback gateway→Google de `import.ts` |
| etapa 2, recuperación y matcher | device (`SuggestRepository`, sharedLogic) | los vectores, el árbol y el libro están locales, incluidas las filas sin sincronizar; el worker sólo ve estado *del servidor* |
| etapa 3, preguntas + Jev | worker (`/suggest/decide`) | `TYPESAFE_API_KEY` es un secreto de servidor, nunca viaja al teléfono |
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

  // lo que devolvió la etapa 1 (o el allowlist), no lo que Jev tiene que adivinar
  "extracted": { "amount": "$ 12.400", "commodity": "ARS",
                 "merchant": "Coto", "direction": "expense",
                 "account_hint": "Mercado Pago" },

  // los spans crudos van igual: si la extracción se equivocó, que Jev pueda
  // elegir otro en vez de quedar atado al error
  "amounts_in_text": ["$ 12.400", "12.400 ARS"],
  "payee_candidates": ["Coto", "COTO CICSA", "Mercado Pago"]
}
```

De dónde sale cada cosa:

- **extracted**: la salida de la etapa 1, o del allowlist cuando reconoció la
  plantilla. Es también lo que arma el `CandidateEvent` del matcher.
- **precedents**: `EmbeddingsRepository.similar(Notification, id, into = Notification, k = 8)`,
  y para cada vecino `ledger.transactionsForSource(EventSource.Notification, vecino.id)`.
  Se quedan los 3–5 que tienen transacción. Si no hay ninguno (hoy el device
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
- **amounts_in_text**: `MONEY_TEXT` de `Ingest.kt`, el mismo regex del prefiltro.
  Con la extracción andando queda como red: el span que eligió Gemini va primero
  en la lista y es el que Jev casi siempre confirma.

Con la extracción por delante, la recuperación mejora en dos frentes: el
embedding de consulta se calcula sobre la **frase normalizada** («Coto, gasto»)
en vez del texto del aviso, y el re-ranking por importe que ya tiene `similar()`
recibe un importe confiable en vez del `biggestMoney` del texto. Los vectores
guardados no se tocan: lo que cambia es el vector de la *consulta*.
- **payee_candidates**: spans del texto (mayúsculas, lo que sigue a «en», «a»,
  «de») + los payees de los precedentes. Cobertura: **el modelo no puede elegir
  un valor que no le pasamos**.

Todo el texto del estado va en español porque los datos son en español; las
`instructions` van en inglés, que es el idioma primario de Jev.

---

## Las preguntas

Una sola request, todas las preguntas en paralelo, incluidas las especulativas
(las ramas que no aplican se ignoran en código). Las claves de `criteria` son
**los paths de las cuentas**, no los ids: los paths son únicos, el modelo los
entiende y el device los mapea de vuelta a id sin ambigüedad. El límite de
Choice son 255 opciones; el árbol real tiene decenas, así que va entero — no se
manda un shortlist.

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

  "amount": {
    "type": "choice",
    "instructions": "Which entry of `amounts_in_text` is the amount of the movement itself, not a balance, a limit, an instalment plan or a discount?",
    "criteria": { "<span 1>": null, "<span 2>": null, "none": "None of them is the amount of the movement" }
  },

  "payee": {
    "type": "choice",
    "instructions": "Which entry of `payee_candidates` names the merchant, person or institution on the other side of this movement?",
    "criteria": { "Coto": null, "COTO CICSA": null, "none": "None of them names the counterparty" }
  },

  "my_account": {
    "type": "choice",
    "instructions": "Which of the recipient's own accounts did the money move through? `precedents` shows how similar notifications were recorded before.",
    "criteria": {
      "Activos:Mercado Pago": "Mercado Pago wallet, ARS",
      "Pasivos:Visa Santander": "Santander Visa credit card, ARS",
      "unknown": "The notification does not say which account, and the precedents do not settle it"
    }
  },

  // especulativa: se lee sólo si direction = expense
  "expense_category": {
    "type": "choice",
    "instructions": "Assuming this is an expense, which category does it belong to?",
    "criteria": { "Gastos:Supermercado": "groceries", "...": null,
                  "unknown": "No category fits; the user should pick" }
  },

  // especulativa: sólo si direction = income
  "income_category": { "type": "choice", "instructions": "Assuming this is income, where did the money come from?", "criteria": { "...": null, "unknown": "..." } },

  // especulativa: sólo si direction = transfer
  "transfer_destination": { "type": "choice", "instructions": "Assuming this is a transfer between the recipient's own accounts, which account did the money arrive in?", "criteria": { "...": null, "unknown": "..." } },

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
   decisiones; separadas se pueden mirar, ajustar y combinar en código. Si
   mañana el importe se saca con regex sin ambigüedad, se borra `amount` y
   nada más cambia.
2. **Elegir, no generar.** Importe y comercio existen literalmente en el texto:
   se sacan spans con regex y Jev elige. Un modelo de decisión no inventa un
   número, y el código normaliza el span elegido con `parseMoney`.
3. **Opciones cerradas = valores válidos por construcción.** Cualquier cosa que
   Jev devuelva en `my_account` es un path real del árbol. No hay «la categoría
   que sugirió no existe».
4. **Siempre una salida de escape.** `unknown` / `none` en cada Choice. Sin
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
7. **Qué NO se pregunta**: la fecha (es `postTime`, dato duro), la moneda (sale
   del span y de la cuenta), el signo (sale de `direction`), y si la
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
| `confidence < 0.5` o `unknown` | campo vacío, cae el default de `SettingsRepository` (`resolveDefault`) |
| `amount = none` | el importe queda vacío y el teclado abre en ese campo |

Las probabilidades crudas se devuelven al device y se muestran en la fila (una
línea tipo «Mercado Pago · 0,92»), no se esconden: es la misma decisión de
diseño que tomó `ImportReviewScreen` con los `MatchReason`.

---

## Pasos

1. **Worker, extracción** — `app/worker/src/suggest.ts`: `POST /suggest/extract`,
   Gemini con `responseSchema` reusando el cliente y el fallback
   gateway→Google de `import.ts`, con `GEMINI_MODELS` reordenado para probar
   primero el lite (igual que el camino de WhatsApp: un mensaje de un renglón
   no necesita el modelo grande). Devuelve importe, moneda, dirección, comercio
   normalizado y pista de cuenta; nada más.
2. **Worker, decisión** — `POST /suggest/decide` en el mismo archivo, secreto `TYPESAFE_API_KEY`
   (`wrangler secret put`). Sin SDK: un `fetch` a
   `https://api.typesafe.ai/v1/systemone` con `model: "jev-latest"` (una
   dependencia menos en un worker, y el contrato son 30 líneas de tipos).
   Arma las preguntas desde las listas del payload, mapea la respuesta a la
   forma `WireCandidate` que `ImportRepository` ya sabe leer, más un bloque
   `confidence` por campo. **Ninguno de los dos escribe en Turso.**
3. **sharedLogic** — `SuggestRepository.kt`: orquesta las cuatro etapas
   (allowlist, extracción, matcher + recuperación, decisión) y devuelve
   `Suggestion(candidate: ImportCandidate, confidences, duplicateOf: String?, isMovement: Double)`.
   Interfaz `TransactionSuggester` para poder inyectar un fake, igual que
   `DocumentAnalyzer`. `HttpTimeout` corto (15 s): Jev responde en ~100–300 ms,
   nada que ver con los 15–25 s de Gemini.
   Se suma a `AppGraph` como `suggestions`.
4. **UI** — `ReviewSource.Message(kind: EventSource, ref: String)` como tercer
   arm de la sealed interface; el `when` de carga llama al suggester y arma un
   `CandidateDraft` con `sourceKind`/`sourceRef`/`sourceTitle` ya poblados
   (todo lo demás de la pantalla queda igual). Botón «Crear transacción» en
   `NotificationDetailScreen`, arriba de la sección «Similares». Strings nuevas
   en `values/strings.xml`.
5. **Fake + shot** — `FakeSuggester` en desktopApp (las dos etapas juntas) y ruta
   `-Pshot.route=suggest` para ver la pantalla sin red ni sesión, como ya se
   hace con `FakeImportAnalyzer`.
6. **Tests** — el armado del contexto y el mapeo respuesta→candidato son
   funciones puras en commonMain: tests en `commonTest` con una respuesta de
   Jev fija. La calidad del modelo se mide aparte, con un script en
   `prueba-jev/` sobre las notificaciones reales (ahí ya está el arnés).

Sin cambios de esquema, sin bump de `SCHEMA_VERSION`, sin barrido nuevo.

---

## Después (no en esta tanda)

- El mismo botón en `EmailDetailScreen`: `ReviewSource.Message(Email, id)` ya
  queda soportado, sólo falta el botón y los candidatos de texto del mail.
- Sugerir en lote desde `InboxReviewRoute`: hoy `Ingest.kt` sólo reconoce lo
  que está en el allowlist; Jev cubre las 24 que el allowlist no ve.
- Cerrar el círculo: cada vez que el usuario acepta o corrige una sugerencia
  queda un ejemplo etiquetado en `transaction_sources`, que es justo lo que
  alimenta `precedents` la próxima vez.

## Preguntas abiertas

- ¿Cuántos precedentes conviene mandar? 3 alcanzan para el patrón; 8 empiezan a
  contradecirse entre sí cuando la misma app manda avisos de cosas distintas.
- ¿`my_account` y `transfer_destination` como dos Choice sobre la misma lista, o
  una sola pregunta «qué cuenta propia» más un `transfer` que la parta? Lo
  primero es más claro de leer, lo segundo gasta menos tokens.
- Umbrales: los de arriba son un punto de partida, no una medición. Antes de
  fijarlos conviene correr las ~148 notificaciones con moneda por el endpoint y
  mirar la distribución, como se hizo con el umbral 0,6 de `classify.ts`.
- ¿Qué pasa si la extracción y el regex no coinciden en el importe? Hoy el plan
  manda los dos y deja que Jev elija; la otra opción es confiar en Gemini y
  mostrar el conflicto en la fila. Se decide mirando cuántas veces pasa.
- ¿Conviene guardar lo extraído en `notifications` (como `jev_score` en
  `prueba-jev`) para no volver a pagar Gemini si el usuario vuelve a entrar?
  Serían dos columnas y un bump de `SCHEMA_VERSION`; vale la pena recién si la
  pantalla se abre más de una vez por aviso, que hoy no se sabe.
