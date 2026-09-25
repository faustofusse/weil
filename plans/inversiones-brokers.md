# Plan — inversiones: IOL, IBKR y resúmenes de custodia

Traer al ledger lo que pasa en las cuentas de inversión: compras, ventas,
rentas, dividendos, depósitos, y la **tenencia** valorizada. Tres fuentes
concretas y muy distintas entre sí — IOL (API con usuario y contraseña), IBKR
(reportes Flex en XML) y Galicia (resumen PDF mensual de la cuenta comitente) —
y un diseño que no sea de ninguna de las tres: un modelo común de eventos,
una función pura que los convierte en asientos, y un conector chico por
fuente.

El modelo contable sale de [ledger-cli](https://ledger-cli.org/doc/ledger3.html)
(§4.5, §5.10–5.22), no de inventarlo: un título es **un commodity más**, una
compra es un intercambio con **costo** (`@@`), los precios viven **aparte**
(`P`), el valor de mercado **se calcula al leer** y nunca se asienta, y el
saldo del broker se verifica con **aserciones**.

---

## Lo que ya existe (revisado sobre el código)

| pieza | dónde | qué aporta / qué limita |
| --- | --- | --- |
| `postings(amount_minor, commodity)` | `Schema.kt` | multi-commodity por cuenta ya existe: `13 MELI` y `$ 500` pueden convivir |
| `Money` / `formatMinorUnits` / `Money.parse` | `LedgerModels.kt` | **escala fija 2** en todos lados (`whole * 100 + frac`). 341.922,4514 cuotapartes no entra |
| `resolvePostings` | `LedgerModels.kt` | balancea **por commodity**; el cambio de moneda es una excepción angosta (exactamente 2 postings, la tasa en la prosa). Una compra con comisión (3 postings, 2 commodities) hoy se rechaza |
| `LedgerState.netWorth` | `LedgerState.kt` | suma por commodity, **sin valuación**: una tenencia se mostraría como «0,4939 TTWO» |
| `accounts.commodity` | `Schema.kt` | moneda declarada de una cuenta de activo; null = sin restricción (sirve para la cuenta de cartera) |
| `AccountType.Equity` | `LedgerModels.kt` | existe: `Patrimonio:Saldo inicial` para abrir con la tenencia actual |
| `transaction_sources(kind, ref, event_key)` | `Schema.kt` | idempotencia gratis: `('iol', numero)`, `('ibkr', transactionID)` |
| `Reconcile.kt` (`Mirror`) | sharedLogic | el depósito en el broker es la otra pata de una transferencia que el banco ya avisó por notificación/mail |
| `DocumentPicker` + `/import/analyze` | app + worker | la puerta para un XML de Flex o un PDF de custodia; hoy sólo devuelve **transacciones candidatas** |
| `email()` del worker | `app/worker/src/index.ts` + `email.ts` | ya recibe mails por usuario y archiva el crudo en R2; **descarta los adjuntos** |
| secure store | Keychain / EncryptedSharedPreferences | donde viviría la contraseña de IOL (local al device, no se sincroniza) |

---

## Hechos verificados de cada fuente

### IOL — `api.invertironline.com` (probado en vivo, sólo GET)

- Auth: `POST /token` con `grant_type=password` (usuario + contraseña). No hay
  OAuth delegado. **Access 15 min, refresh 20 min, rotativo y de un solo uso**
  (verificado: el refresh viejo deja de funcionar). Conclusión: para sincronizar
  sin pedir la contraseña cada vez **hay que guardarla** y re-loguear.
- CORS `*`, ~250 ms por request, sin 2FA en la cuenta probada; el token trae
  `tiene_producto_api: True` (la API se habilita por cuenta).
- `GET /api/v2/estadocuenta`: cuentas `inversion_Argentina_Pesos`,
  `inversion_Argentina_Dolares`, `inversion_Estados_Unidos_Dolares` con
  `disponible`, `saldo`, `titulosValorizados`, `saldos[]` por plazo de
  liquidación; `totalEnPesos`.
- `GET /api/v2/portafolio/{argentina|estados_Unidos}`: por posición `cantidad`,
  `ppc` (precio promedio de compra), `ultimoPrecio`, `valorizado`,
  `gananciaDinero`, `titulo{simbolo, mercado, tipo, moneda}`.
- `GET /api/v2/operaciones?filtro.estado=todas&filtro.fechaDesde&filtro.fechaHasta`:
  Compra, Venta, Suscripción FCI, Rescate FCI, Pago de Renta, Pago de
  Amortización. Sin filtros sólo devuelve los últimos días. Lo más viejo que
  devolvió fue 2025-08-14 (no se sabe si es el inicio de la cuenta o un límite).
- `GET /api/v2/operaciones/{n}`: `aranceles[]` (comisión, derechos de mercado),
  `arancelesARS/USD`, fills. **`montoOperado` del listado no incluye aranceles.**
- Rarezas: las rentas/amortizaciones vienen **de a pares** (una fila sin monto
  = salen los títulos, otra con monto = entra la plata); la moneda de la renta
  está en el símbolo (`"AO27 US$"`); el detalle puede traer `tipo: 9` en vez de
  texto; `bcba` vs `BCBA`; montos en float; fechas sin zona (hora ART).
- Letras y bonos cotizan **cada 100 VN**: 1.923.076 × 106,251 / 100 = 2.043.287.
- **No hay depósitos ni extracciones** (`/Asesor/Movimientos` es sólo para
  asesores: 401). Se infieren por diferencia de saldo o se reconcilian con el
  banco.
- `GET /api/v2/Cotizaciones/MEP/{simbolo}`: dólar MEP (lo único que da ARS↔USD).

### IBKR — Flex Web Service (probado con un reporte real de la cuenta)

- La Web API para cuentas individuales exige el **Client Portal Gateway** (Java
  local, login por browser en la misma máquina, sesión ≤ 24 h, `/tickle` cada
  5 min): no sirve para un teléfono ni un servidor. OAuth 1.0a/2.0 sólo para
  instituciones o vendors aprobados.
- **Flex**: query armada a mano en Client Portal (query id `1649339`, nombre
  `weil`), token de 6 h a 1 año, dos GET
  (`SendRequest?t&q&v=3[&fd&td]` → `ReferenceCode`, `GetStatement?t&q&v=3`),
  1 req/s y 10 req/min por token, error 1019 = todavía generando. **Sin CORS**
  (device o worker). Datos de fin de día; historia de 4 años + el corriente.
- **Delivery** = mail (o sFTP a pedido) con el reporte adjunto, programado. No
  es un webhook, pero apuntado a la dirección Weil del usuario lo es en la
  práctica.
- Estructura confirmada con el backfill (`FlexQueryResponse > FlexStatements >
  FlexStatement`): `AccountInformation`, `CashReport/CashReportCurrency`
  (una fila `BASE_SUMMARY` a ignorar + una por moneda con `endingCash`),
  `OpenPositions`, `Trades`, `CorporateActions`, `CashTransactions/CashTransaction`,
  `Transfers`, `SecuritiesInfo`, `ConversionRates/ConversionRate`.
- **Adelanto de depósito**: IBKR acredita `+1000 DEPOSIT ADVANCE`, después
  `-1000 CANCELLATION` y `+1895.07 CASH RECEIPTS`. Los dos ajustes comparten
  `clientReference` («First 1,000.00 of 1,895.07 Deposit»). Hay que **netearlos**:
  importarlos deja dos asientos basura y un +1000 que `Reconcile` podría
  aparear con cualquier cosa.
- **`reportDate` ≠ `dateTime`**: la cancelación está fechada 08-19 pero aparece
  el 08-25. Un watermark por fecha de movimiento pierde filas; la ventana de 30
  días + `transactionID` lo resuelve, y si hace falta watermark es por
  `reportDate`.
- `dateTime` de cash transactions es sólo fecha (`20260819`) → `time_known = 0`.
- `ConversionRates`: 39 monedas → USD por día, **sin ARS**. Es el 95 % del
  archivo; el parser guarda sólo las monedas que se tienen.
- `CashReport.endingCash` (1.895,07) = suma de las cash transactions: la
  aserción de saldo cierra exacto.
- **Pendiente**: el formato de `Trades`/`OpenPositions`/`SecuritiesInfo`. Hay una
  compra hecha (0,4939 TTWO por 99,98 USD, ≈ 202,43, `IB Order ID` 2124812991);
  el XML del día siguiente confirma signos de `proceeds`, `ibCommission`,
  `netCash`, `Cost Basis` y `Realized P/L`. Esperado:

  | campo | esperado |
  | --- | --- |
  | `quantity` | 0,4939 |
  | `tradePrice` | ≈ 202,43 |
  | `proceeds` | −99,98 |
  | `ibCommission` | ≈ −1,00 |
  | `netCash` | ≈ −100,98 |
  | `Cost Basis` | ≈ +100,98 |
  | `CashReport` USD | ≈ 1.794,09 |

### Galicia — resumen PDF de cuenta comitente (ejemplo de otra usuaria)

- Sólo **posición** a una fecha: FCI FIMA (cuotas con 2 decimales, valor cuota
  con 6) y CEDEARs (cantidad, última cotización, saldo valorizado). Cada fila
  cumple `cantidad × precio = valorizado` al centavo: el lector se autoverifica.
- «Movimientos» viene vacío ese mes, y la letra chica dice que cuando hay **no
  incluyen amortizaciones ni acreencias en especie y los montos no incluyen
  comisiones**. La plata no está en la comitente: se mueve por la cuenta
  bancaria vinculada, que la app ya ve.
- Consecuencia: Galicia aporta **snapshot + precios**. Las operaciones se
  reconstruyen como delta de cantidades entre dos resúmenes + la pata de caja
  que ya llegó por el banco.

---

## Decisiones

### 1. Un título es un commodity, con su propia escala

Tabla nueva:

```sql
create table if not exists commodities(
  id text primary key not null,   -- 'BCBA:MELI', 'NASDAQ:TTWO', 'FCI:FIMA AHORRO PLUS A', 'USD'
  symbol text not null,           -- lo que se muestra: 'MELI'
  name text,                      -- 'Cedear Mercadolibre Inc.'
  kind text not null,             -- currency | stock | cedear | bond | letra | fci | etf | ...
  scale integer not null,         -- decimales de cantidad: ARS 2, FCI 4, VN 0, IBKR fraccional 4
  price_per integer not null default 1,  -- 100 para bonos/letras que cotizan cada 100 VN
  quote_commodity text,           -- moneda en que cotiza: 'ARS', 'USD'
  isin text, conid text           -- alias para unir fuentes
);
```

- **El id es por mercado, no por broker.** El `MELI-CEDEAR` de Galicia y el
  `CEDEAR MELI` de IOL son el mismo instrumento de BYMA: un commodity
  `BCBA:MELI` en dos cuentas, y una cotización de IOL valoriza las dos. El
  `MELI` de NASDAQ es otro (`NASDAQ:MELI`), en otra moneda.
- `amount_minor` sigue siendo entero, pero su escala sale de
  `commodities.scale`; **sin fila, escala 2**. Así ninguna fila existente cambia
  de significado y ARS/USD siguen igual.
- Id determinístico (el string mismo) en vez de UUID: dos devices que crean el
  mismo commodity offline convergen en la misma fila, igual que las cuentas
  `External`.
- Riesgo de versiones viejas: un cliente sin esta tabla muestra `4939` de TTWO
  como `49,39`. No mezcla nada (es otro commodity), sólo lo formatea mal hasta
  que actualiza. Aceptable; anotarlo en AGENTS.md.

### 2. Costo en el posting (el `@@` de ledger)

```sql
alter table postings add column cost_minor integer;      -- costo total del posting
alter table postings add column cost_commodity text;     -- en qué moneda
```

- Regla de balance nueva: cada posting pesa **su costo si lo tiene, si no su
  monto**; la suma por commodity de esos pesos es cero. Es exactamente la regla
  de ledger, y una compra con comisión balancea:

  ```
  2026-09-25 Compra TTWO
      Activos:IBKR:Cartera      0,4939 NASDAQ:TTWO  @@ US$ 99,98
      Gastos:Inversiones:Comisiones                    US$ 1,00
      Activos:IBKR:Dólares                            US$ -100,98
  ```

- El cambio de moneda deja de ser excepción: `+US$ 100 @@ $ 154.586`. La
  excepción actual de 2 postings **queda** para leer filas viejas sin costo.
- `migrateSchema()` con `addColumn()`, bump de `SCHEMA_VERSION` (hoy 12).

### 3. Precios aparte, valor al leer

```sql
create table if not exists prices(
  id text primary key not null,   -- hash(commodity|quote|día|fuente): converge entre devices
  commodity text not null,
  quote_commodity text not null,
  at integer not null,            -- epoch ms
  price text not null,            -- decimal exacto como string ('1.06251', '202.43')
  source text not null            -- 'iol' | 'ibkr' | 'galicia' | 'trade'
);
create index if not exists idx_prices on prices(commodity, quote_commodity, at desc);
```

- Una fila por commodity/moneda/día/fuente (el id determinístico reemplaza a la
  del mismo día en vez de acumular).
- Fuentes: `ultimoPrecio` de IOL, `markPrice`/`closePrice` de IBKR, la
  cotización del resumen de Galicia, el propio precio de cada trade, el
  **oficial del BCRA** para USD en ARS (fuente `bcra`, ver pregunta 4), y
  `ConversionRates` de IBKR para otras monedas contra USD. El MEP de IOL puede
  guardarse como dato, pero no es el que usa la conversión.
- El valor de mercado **nunca se asienta** (lo contrario de lo que se propuso
  al principio: un asiento periódico de revaluación es exactamente lo que
  ledger evita). `netWorth` pasa a devolver el total **por moneda** con cada
  instrumento valorizado en su moneda de cotización (`Σ cantidad × último
  precio`), más una línea opcional convertida al oficial (pregunta 4).
- Precio como string decimal porque hay 6+ decimales (valor cuota 159,226590;
  1,06251 por VN). La multiplicación cantidad × precio necesita aritmética
  decimal exacta en commonMain (no hay `BigDecimal`): `Decimal` propio sobre el
  `BigInteger` de bignum (pregunta 2).

### 4. Comisiones capitalizadas; ganancia contra la base de costo

**Revisada en la fase 2.** La versión anterior decía «la ganancia la pone el
broker» y a la vez mandaba la comisión a `Gastos:Comisiones`: eso cuenta dos
veces la comisión, porque el `Realized P/L` de IBKR ya la descuenta (su
`Cost Basis` la incluye: ≈ 100,98 para la compra de TTWO). La regla
consistente:

- **La comisión se capitaliza**: la compra cuesta bruto + comisiones
  (`+0,4939 TTWO @@ US$ 100,98`) y la venta ingresa bruto − comisiones. Es la
  base que usan IBKR y AFIP. La comisión sigue visible en la nota de la
  transacción («Comisión US$ 1,00»); lo que se pierde es la línea de gasto
  aparte. Las comisiones de un cambio de moneda sí van a
  `Gastos:Comisiones`, porque no son costo de ningún instrumento.
- **La venta sale de la cartera a su base de costo**: la que informa el broker
  si la informa (IBKR, FIFO por lote), si no el **costo promedio** del ledger
  (IOL, resúmenes de custodia). La diferencia contra lo neto recibido es la
  ganancia realizada, en `Ingresos:Inversiones:Ganancias de capital`. Con la
  base de IBKR, la ganancia coincide exactamente con su `Realized P/L`.
- Vender más de lo que el ledger tiene sin base del broker es un *issue*, no
  una estimación: inventar la base sería inventar la ganancia.
- Cambiar a «comisión como gasto» es tocar una sola función pura
  (`planBrokerImport`), si alguna vez se prefiere.

### 5. Aserciones de saldo en vez de ajustes inventados

- Cada sync compara el ledger contra el snapshot del broker: caja por moneda
  (`estadocuenta` / `CashReport`) y cantidad por instrumento (`portafolio` /
  `OpenPositions` / resumen). Diferencia cero → nada. Diferencia ≠ 0 →
  se propone: primero buscar la pata en el banco (`Reconcile`, relación
  `Mirror`), si no, un ajuste contra `Patrimonio:Ajustes` que el usuario ve.
- Es lo que tapa el agujero de IOL (no informa depósitos) y el de Galicia (no
  informa operaciones).

### 6. Apertura

La historia de IOL arranca en 2025-08 y la de un resumen de Galicia es una
foto: la primera importación abre con `Patrimonio:Saldo inicial` por la
tenencia y la caja a la fecha más vieja disponible, y de ahí en adelante son
eventos.

### 7. Cuentas que crea la conexión

Al conectar un broker se crean (o el usuario elige existentes), y los ids se
guardan en `settings` (`broker.<fuente>.accounts`, JSON), así un rename no
rompe nada:

```
Activos:IOL                      (padre)
  Activos:IOL:Pesos              commodity ARS
  Activos:IOL:Dólares            commodity USD
  Activos:IOL:Cartera            commodity null (multi-instrumento)
Ingresos:Inversiones:{Intereses, Dividendos, Ganancias de capital}
Gastos:Inversiones:{Comisiones, Impuestos}
Patrimonio:{Saldo inicial, Ajustes}
```

---

## UI

### Navegación

- La barra pasa a **Inicio · Movimientos · [+] · Categorías · Inversiones**
  (`AppTab { Home, Movements, Categories, Investments }` en `AppBottomBar.kt`;
  `selectTab`/`currentTab`/`startTab` en `AppRoot.kt`). Ícono nuevo a mano en
  `Icons.kt` (línea de tendencia; hoy no hay ninguno de inversiones).
- **Perfil sale de la barra** y pasa al ícono genérico `AccountCircle` (ya está
  en `Icons.kt`; decidido: no la inicial del nombre) en el top bar de
  **Inicio**, al lado del ⋮ (decidido: sólo en Inicio). Abre
  `ProfileRoute` empujado: `ProfileScreen` ya soporta ese modo (`bottomBar ==
  null` → back arrow) y `entry<ProfileRoute> { profileScreen(null) }` ya existe
  en el stack de afuera. Sólo en Inicio: encaja con el saludo, y Movimientos ya
  tiene sus propias acciones y modo selección.
- Independiente del esquema: puede ir antes que la fase 1.

### `InvestmentsScreen` (la pestaña)

Una `LazyColumn`, mismo lenguaje que `HomeDashboardScreen`:

1. **Hero** (como `BalanceHero`): total valorizado **por moneda**, la línea
   «≈ US$ X al oficial 1.519,50 (24/09)» (pregunta 4) y la ganancia **no
   realizada** total (monto y %, coloreada). Censurable con `amountsHidden`.
2. **Avisos** (sólo si hay), tarjetas accionables: aserción fallida («IOL: la
   caja difiere en $ X del saldo de IOL»), hueco de cobertura («faltan datos de
   IBKR del X al Y»), credencial inválida («IOL: contraseña incorrecta»). Cada
   una abre donde se arregla.
3. **Brokers**: un tile por conexión (IOL, IBKR, Galicia) con valor total, caja
   y frescura («hace 5 min», «resumen al 31/07»). Tocar → la cuenta `Cartera`
   de ese broker con la vista de posiciones (pregunta 3).
4. **Posiciones consolidadas entre brokers**: `BCBA:MELI` en IOL y en Galicia es
   **una fila** con el desglose adentro. Símbolo, nombre, cantidad, valor,
   ganancia no realizada %. Orden por valor; chips por tipo (CEDEARs · Bonos ·
   Letras · FCI · Acciones). Las posiciones en cero en «Cerradas», plegado.
5. **Últimos movimientos** de las cuentas de inversión (compras, ventas,
   rentas, depósitos) con `TransactionRow` del journal y «Ver todo».

Pull-to-refresh sincroniza IOL y trae el oficial del BCRA. Overflow del top
bar: «Conectar broker», «Importar reporte (IBKR / resumen PDF)», «Cómo
configurar IBKR».

### Detalle de instrumento (tocar una posición)

Cantidad por broker, costo, valor, ganancia no realizada, último precio con
fecha, y el registro de compras/ventas/rentas de ese commodity. El gráfico de
precios (de la tabla `prices`) queda para después: no hay historia hasta que
se acumulen syncs.

### Estado vacío y conexión

Sin broker conectado, tres botones:

- **InvertirOnline**: sheet con usuario y contraseña («se guarda sólo en este
  dispositivo»), prueba el login, muestra las cuentas que va a crear (o deja
  elegir existentes) y corre la primera importación por la pantalla de
  revisión, con la apertura de la decisión 6.
- **Interactive Brokers**: instrucciones paso a paso para armar la query
  (secciones, niveles, sin campos personales en *Account Information*) +
  «Importar archivo».
- **Resumen de otro banco (PDF)**: el importador de custodia (fase 6).

### Inicio

El patrimonio incluye las inversiones valorizadas; los tiles de brokers son
cuentas como cualquier otra (entran en el orden de `home.account_order`).

### Harness de escritorio

`-Pshot.route=investments` (y `investments-empty`), con brokers, posiciones y
precios sembrados en `FakeDatabase`, para revisar la pestaña sin emulador.

---

## El modelo común (`Brokerage.kt`, sharedLogic commonMain, puro)

```kotlin
sealed interface BrokerEvent { val ref: String; val at: Long; val timeKnown: Boolean }

data class Trade(
    override val ref: String, override val at: Long, override val timeKnown: Boolean,
    val instrument: InstrumentRef, val quantity: Decimal,   // + compra, − venta
    val gross: Money,                                       // proceeds, con signo
    val fees: List<Fee>, val cashCommodity: String,
    val realizedGain: Money?,                               // ventas: lo que dice el broker
) : BrokerEvent

data class Income(... val kind: IncomeKind /* dividend, interest, coupon */,
                  val gross: Money, val tax: Money?, val instrument: InstrumentRef?) : BrokerEvent
data class Principal(... val instrument: InstrumentRef, val quantity: Decimal, val cash: Money) : BrokerEvent
data class CashTransfer(... val amount: Money /* + depósito, − extracción */) : BrokerEvent
data class FxConversion(... val from: Money, val to: Money, val fees: List<Fee>) : BrokerEvent
data class QuantityChange(... val instrument: InstrumentRef, val delta: Decimal, val reason: String) : BrokerEvent

data class BrokerSnapshot(
    val at: Long,
    val cash: Map<String, Money>,                 // por moneda
    val positions: List<Position>,                // instrumento, cantidad, precio, valor, costo?
)

data class BrokerBatch(val source: String, val account: String,
                       val events: List<BrokerEvent>, val snapshot: BrokerSnapshot?,
                       val instruments: List<InstrumentInfo>, val prices: List<PriceQuote>)
```

Y la única función que decide:

```kotlin
fun planBrokerImport(
    batch: BrokerBatch,
    accounts: BrokerAccounts,          // los ids de la sección 7
    ledger: LedgerView,                // saldos actuales por cuenta/commodity
    knownRefs: Set<String>,            // transaction_sources de este kind
): BrokerPlan                          // transacciones + commodities + precios + aserciones fallidas
```

Pura y testeada sin red, como `planAutoRecord`. Los conectores sólo buscan y
traducen:

| evento | IOL | IBKR Flex | Galicia |
| --- | --- | --- | --- |
| `Trade` | Compra/Venta/Suscripción/Rescate + detalle para aranceles | `Trades` (Execution) | delta entre resúmenes |
| `Income` | Pago de Renta (moneda por sufijo `US$`) | `CashTransactions`: Dividends + Withholding Tax, Broker/Bond Interest | — |
| `Principal` | Pago de Amortización (el par de filas) | rescates en `CorporateActions` | — (excluido por el banco) |
| `CashTransfer` | — (por aserción) | `Deposits/Withdrawals`, **neteando adelantos** por `clientReference` | — (la caja es del banco) |
| `FxConversion` | MEP | trades de forex (`EUR.USD`) | — |
| `QuantityChange` | — | `CorporateActions` (splits) | — |
| `BrokerSnapshot` | `estadocuenta` + `portafolio` | `CashReport` + `OpenPositions` | el resumen entero |

Idempotencia: `transaction_sources(kind = source, ref = event.ref)`; los
eventos con ref conocida se descartan antes de planear. Los pares de IOL
(renta/amortización) usan el `numero` de la fila con monto.

---

## Fases

### Fase 0 — fixtures (en curso)

- [x] IOL: estructura de todos los endpoints de lectura (en vivo).
- [x] IBKR: backfill con cash transactions, cash report, conversion rates.
- [ ] IBKR: XML con la compra de TTWO (`Trades`, `OpenPositions`,
      `SecuritiesInfo`). Correr `weil` con Last 30 Days el día hábil siguiente.
- [ ] Guardar fixtures **scrubbeados** en `app/sharedLogic/src/commonTest/resources/brokers/`:
      sin nombre, dirección, fecha de nacimiento, mail, DNI/CUIT; números de
      cuenta reemplazados. Nunca el crudo en el repo.
- [ ] Sacar los campos personales de *Account Information* de la query
      (dejar Account ID, Currency, Name, Account Type, Date Opened).

### Fase 0.5 — navegación (hecha)

- [x] Pestaña «Inversiones» en la barra (`InvestmentsScreen` con el estado
      vacío; las tres entradas marcadas «Pronto»), ícono `TrendingUp`.
- [x] Perfil al ícono `AccountCircle` del top bar de Inicio; `ProfileScreen`
      queda sólo empujada (se borró su modo pestaña).
- [x] Harness: `-Pshot.route=investments`. Compila en desktop, Android e iOS.

### Fase 1 — esquema y aritmética (hecha)

- [x] `commodities`, `prices` (en `SCHEMA_SQL`) y `postings.cost_minor` /
      `cost_commodity` (en `migrateSchema`, con `addColumn`);
      `SCHEMA_VERSION` 12 → 13.
- [x] `Decimal` (`Decimal.kt`) sobre el `BigInteger` de bignum 0.3.10: parseo de
      números de máquina (con exponente), suma/resta/producto exactos,
      `movePointLeft` para el precio cada 100 VN, `rescale`/`divide`/
      `toMinorUnits` con half-even propio, y `toMinorUnits` que tira en vez de
      desbordar. Tests comunes con los números reales de IOL/IBKR y el caso de
      #337 (`DecimalTest`, pasan en JVM y JS), más 17.000 casos al azar contra
      `java.math.BigDecimal` (`DecimalJvmTest`).
- [x] Regla de balance por **peso** en `resolvePostings`/`residualsOf`: un
      posting con costo pesa su costo. Costo validado (con moneda, distinta de
      la del monto, no cero, mismo signo que el monto; el posting elidido no
      puede llevar costo). La excepción vieja de cambio de moneda sólo aplica
      si ningún posting declara costo (`PostingCostTest`).
- [x] `Posting.toDraft()` lleva el costo; lo usan el editor, el deshacer del
      detalle y el del journal (el riesgo nombrado: una edición del payee que
      borraba el precio). `TransactionsRepository` lee el costo en las cuatro
      lecturas de postings (`postingOf`) y lo escribe sólo cuando hay.
      Test de integración sobre SQLite (`app/desktopApp/src/test`,
      `PostingCostPersistenceTest`): escritura, las cuatro lecturas, edición,
      deshacer de un borrado, migración idempotente.
- **Movido a la fase 5**: la escala por commodity en `Money.parse` /
  `formatMinorUnits`. El núcleo no la necesita: todo posting es un entero en
  minor units de su commodity, y los drafts armados por código viajan como
  `formatMinorUnits(minor)` a 2 decimales, que ida y vuelta da el mismo entero
  para cualquier escala (documentado en `DraftPosting`). Sólo mostrar y tipear
  «0,4939» en vez de «49,39» necesita la escala, y eso es UI.
- Notas de entorno: los tests nativos de iOS no linkean en este repo desde
  antes de este cambio («Unable to compile C bridges», por el cinterop de
  Turso), así que las pruebas multiplataforma corren en JVM y JS.

### Fase 2 — núcleo puro (hecha)

- [x] `Brokerage.kt`: modelo (`BrokerEvent` Trade/Income/Principal/
      CashTransfer/FxConversion/QuantityChange, `BrokerSnapshot`,
      `BrokerBatch`, `InstrumentInfo`, `PriceQuote`, `BrokerAccounts`,
      `BrokerLedgerView`) y `planBrokerImport` → `BrokerPlan` (transacciones,
      commodities nuevos, precios, diferencias, issues, refs salteadas).
- [x] `EventSource.Broker("broker")`, una sola para todos los brokers: el ref
      lleva el proveedor de prefijo (`brokerRef`: «iol:185183784»,
      «ibkr:42307900416»). Etiqueta «Broker» en el detalle de la transacción.
- [x] Reglas: decisión 4 revisada (comisiones capitalizadas), apertura contra
      `Patrimonio:Saldo inicial` cuando el ledger está vacío y hay snapshot
      (snapshot − efecto del lote; costo de apertura: el del broker pro rata, o
      la base de una venta posterior, o el primer precio del lote, o el precio
      del snapshot respetando `price_per`), transferencias contra la cuenta en
      tránsito marcadas `needsCounterpart`, diferencias como datos, y toda
      transacción planeada pasa por `resolvePostings` (un bug del planner es un
      issue, nunca una fila desbalanceada).
- [x] `BrokerageTest` (16): depósito de IBKR, compra de TTWO con comisión
      capitalizada, venta con base de IBKR, venta parcial a costo promedio,
      venta sin tenencia, amortización de S14G6, dividendo con retención,
      renta, MEP con comisión, split, refs conocidas, cuenta faltante,
      diferencias, apertura de IOL (letra valuada cada 100 VN), apertura de
      unidades vendidas en la ventana, apertura sin precio.
- Queda para los conectores (fases 3 y 4): netear el adelanto de depósito de
  IBKR por `clientReference`, emparejar las filas de renta/amortización de
  IOL, y armar `BrokerLedgerView` desde la base (saldos por cuenta y posición
  con costo de la cartera).

### Fase 3 — IBKR por archivo

- Parser Flex XML en commonMain (el XML es plano, todo atributos: un lector
  chico alcanza; si no, `xmlutil`).
- Entrada por `DocumentPicker` / compartir → pantalla de revisión (reusar el
  patrón de `ImportReviewScreen`: crear / asociar / omitir, un Snackbar con
  deshacer).
- Primera fuente porque no pide credenciales y el fixture ya existe.

### Fase 4 — IOL conectado (hecha)

- [x] `IolApi.kt`: DTOs (montos decodificados del texto JSON a `Decimal`, nunca
      por `Double`), `IolSource` (sólo lecturas) e `IolClient` (sólo `POST
      /token` y GETs: no puede operar). Re-login con las credenciales del
      secure store en cada sync: el refresh dura 20 min y no vale guardarlo.
- [x] `IolConnector.kt` (puro): IOL → `BrokerBatch`. Reglas sacadas de la
      historia real, no de la doc de IOL:
  - compra en pesos de un bono + venta de su ticker **D** (MEP) o **C**
    (cable) en dólares, misma cantidad, ≤ 5 días → un `FxConversion`
    (ref `compra+venta`); AL30→AL30D da $ 497.867,21 → US$ 379,95;
  - moneda y aranceles salen del detalle de cada orden; en un trade en
    dólares, el derecho de mercado se cobra en **pesos** → `foreignFees`
    (gasto desde la caja en pesos, no costo);
  - renta/amortización llegan de a pares: la fila con monto es el evento; la
    compañera sin monto de una amortización la vuelve rescate **total** de lo
    que se tiene (IOL redondea la cantidad en su texto: «-2,16832e+006» por
    2.168.316). Amortización sin compañera (parcial) → issue;
  - instrumentos: tipo por `titulo` del portafolio o `GET
    /{mercado}/Titulos/{simbolo}`; FCI → `FCI:SIMBOLO` escala 4; CEDEAR,
    acción, bono, letra, ON → `BCBA:SIMBOLO` escala 0; bonos/letras/ON
    `price_per` 100; las dos cuentas en dólares de IOL (AR y EE.UU.) se suman.
- [x] Cambios al planner que salieron de datos reales: `foreignFees`;
  `Principal` con cantidad nula = toda la posición (y la apertura de un
  instrumento rescatado entero es lo justo para no quedar en corto);
  **cobro en otra moneda que el costo** (una ON hard-dollar comprada con
  pesos: MGC9O) → los dólares recibidos llevan como costo la base en pesos,
  sin inventar ganancia; **cantidades exactas** (una cantidad con más
  decimales que la escala es issue, nunca redondeo); `transfers` opcional
  (IOL no informa depósitos).
- [x] `Brokers.kt` (`BrokersRepository`): crea/encuentra las cuentas
  (`IOL:{Pesos, Dólares, Cartera}`, `Rendimientos:{Intereses, Dividendos,
  Ganancias de capital}`, `Costos de inversión:{Impuestos, Comisiones}`,
  `Patrimonio:{Saldo inicial, Ajustes}` — raíces distintas porque un nombre
  de raíz es único entre los cinco tipos), la vista del ledger (saldos +
  `TransactionsRepository.holdings`: cantidad y costo por commodity), refs
  conocidas, escalas, y `apply` (commodities, precios con id determinístico,
  transacciones).
- [x] `IolRepository`: `connect` (verifica antes de guardar), `preview`,
  `apply`, ventana desde el último sync − 10 días (`broker.iol.synced_at`,
  sincronizado).
- [x] UI: fila de InvertirOnline en la pestaña (conectar / sincronizar /
  mantener apretado para desconectar), sheet de conexión que dice dónde vive
  la contraseña, y `BrokerImportScreen` (genérica): movimientos, diferencias,
  issues, «Importar N movimientos» con deshacer. Harness:
  `-Pshot.route=broker-import`.
- [x] Pruebas: `IolConnectorTest` (fixtures reales scrubbeados en
  `jvmTest/resources/iol`: **un año planeado sobre un ledger vacío cae
  exacto en el snapshot de IOL**, sin issues), `IolImportTest` (lo mismo por
  los repositorios y SQLite, segundo sync sin nada nuevo ni detalles
  repedidos) e `IolLiveTest` (opt-in con `IOL_USERNAME`/`IOL_PASSWORD`,
  contra la API real y una base descartable: 44 movimientos, 0 issues,
  0 diferencias).
- Pendiente: sync automático al abrir la app / pull-to-refresh de la pestaña
  (hoy es el botón «Sincronizar»), y WorkManager en segundo plano.

### Fase 5 — valuación en la UI

- `netWorth` valorizado por moneda + línea «≈ US$ X al oficial (fecha)»
  (setting `networth.currency`, default USD); fetch del oficial del BCRA al
  sincronizar, a `prices`.
- Vista de posiciones en `AccountDetailScreen` de cuentas multi-commodity
  (pregunta 3).
- `InvestmentsScreen` completa (hero, avisos, brokers, posiciones consolidadas,
  movimientos) y detalle de instrumento; ver sección UI.
- Pantalla de cartera por cuenta: instrumento, cantidad, último precio y
  fecha, valor, costo, ganancia no realizada (calculada, no asentada).
- Tiles de Home para las cuentas de broker.

### Fase 6 — entradas automáticas

- **IBKR Delivery por mail**: el `email()` del worker detecta un adjunto
  `<FlexQueryResponse>` (por contenido, no por remitente), lo guarda en R2 y
  lo referencia en `emails` (columna nueva + bump); el sweep del device lo
  parsea con el mismo parser de la fase 3. Chequear que el lookup por
  `message.to` funcione si llega reenviado desde Gmail.
- **Resúmenes PDF de custodia** (Galicia y parecidos): variante de
  `/import/analyze` con schema de posiciones
  (`{asOf, accountNumber, positions[{name, ticker?, kind, qty, price, value}]}`)
  y rechazo de filas donde `qty × price ≠ value`.

### Fase 7 — IBKR Flex por API (opcional)

- Sólo si hace falta algo más fresco que el mail diario o backfill a demanda.
  Token + query id en el secure store (o en el worker), `SendRequest` →
  `GetStatement` con reintento en 1019, respetando 1 req/s.

---

## Preguntas (todas decididas)

1. ~~**¿Dónde corre IOL?**~~ **Decidido: device.** La contraseña vive en el
   secure store de cada teléfono y nunca llega al servidor; sync al iniciar
   sesión y con pull-to-refresh (WorkManager en segundo plano, después). La
   lógica de importación es el Kotlin puro corriendo en el mismo proceso que el
   ledger, sin port a TS. Costo aceptado: cada device conecta IOL una vez (los
   syncs duplicados son inocuos por `transaction_sources`). Descartado por ahora:
   la contraseña sincronizada, cifrada con una clave derivada del passkey.
2. ~~**Aritmética decimal** en commonMain~~ **Decidido: `BigInteger` de `ionspin
   kotlin-multiplatform-bignum` + un `Decimal` propio encima, encerrado en un
   borde.** El almacenamiento no cambia (`amount_minor`/`cost_minor` `Long` en
   minor units del commodity, precios como string decimal); `Decimal`
   (`BigInteger` sin escala + `Int` escala, ~60 líneas) se usa sólo dentro de
   `Brokerage.kt` y la valuación, y se vuelve a `Long` al final, tirando si no
   entra. Multiplicar es exacto (producto + suma de escalas), `price_per = 100`
   es un corrimiento de escala, y el redondeo half-even a centavos es propio
   (división y resto de `BigInteger`). Motivo: con `Long` puro, cantidad × precio
   a 6 decimales deja ~×1.700 de margen sobre la cartera actual (5,4·10¹⁵ vs
   9,2·10¹⁸), y `Double` haría fallar aserciones por error de redondeo.
   **No se usa el `BigDecimal` de la librería**: tiene abiertos #337
   (`ROUND_HALF_TO_EVEN` redondea mal: 1.602.590/30 a precisión 7 da 53419,66) y
   #318/#331 (bugs de `divide`); el test de `Decimal` incluye ese caso.
   Verificado (subagente + chequeo): Apache 2.0, Kotlin 2.0, sólo stdlib,
   ~240–440 KB, publica `jvm`/`js`/`iosArm64`/`iosSimulatorArm64` (Android
   consume la variante `jvm`: confirmar con `:app:sharedLogic:compileAndroidMain`
   al agregarla), último commit 2025-09, último release 2024-07 (mantenimiento
   bajo, otra razón para usar sólo la parte chica).
3. ~~**Cartera por instrumento o una sola cuenta.**~~ **Decidido: una sola
   cuenta `Activos:<broker>:Cartera` multi-commodity (como ledger) + vista de
   posiciones.** Las letras rotan todos los meses y en un año IOL tuvo ~15
   instrumentos de los que quedan 2: una subcuenta por instrumento llenaría el
   árbol de cuentas en cero y obligaría a la importación a crear cuentas sola. La
   conexión crea las cuentas una vez; la importación nunca. `AccountDetailScreen`
   de una cuenta multi-commodity muestra primero las posiciones (cantidad, último
   precio y fecha, valor, costo, ganancia no realizada), tocar una filtra el
   registro por ese commodity (el saldo corrido ya es por commodity en
   `TransactionsRepository`), y las posiciones en cero van a «cerradas»
   plegadas. En Home, el tile del broker muestra el total valorizado. Si más
   adelante se quiere partir (`Cartera:Bonos` / `Cartera:CEDEARs`) es un move
   manual, y `settings` dice qué cuenta recibe qué tipo de instrumento.
4. ~~**Moneda de valuación** del patrimonio~~ **Decidido: por moneda + línea
   convertida, al tipo de cambio oficial.** La cifra principal sigue como hoy:
   un total por moneda sin supuestos, con cada instrumento sumado a su moneda de
   cotización. Debajo, opcional, «≈ US$ X al oficial 1.519,50 (24/09)»; setting
   `networth.currency` (`ARS` | `USD` | apagado), **default USD**. El tipo es el
   **oficial del BCRA** (`api.bcra.gob.ar/estadisticascambiarias/v1.0/Cotizaciones/USD?fechadesde&fechahasta`,
   público, sin auth, con historia por fecha; verificado), no el MEP: fuente
   primaria, sirve a todos los usuarios aunque no tengan IOL, y permite usar el
   tipo de la fecha de cada precio. Se guarda en `prices` como `USD` en `ARS`,
   fuente `bcra`. Sin tipo conocido o con más de ~3 días hábiles de antigüedad,
   la línea no se muestra. La vista de posiciones valoriza cada instrumento en
   su propia moneda, sin conversión.
5. ~~**Ventas de Galicia** sin pata de caja~~ **Decidido: buscar primero,
   crear con estimación si no aparece.** Galicia entra por PDF y pantalla de
   revisión, así que esto es qué *sugiere* la pantalla, no qué escribe sola. Por
   cada delta de cantidad entre dos resúmenes:
   1. se busca la pata en la cuenta bancaria vinculada dentro del período entre
      resúmenes (signo opuesto, monto cerca de cantidad × precio con tolerancia
      amplia porque el precio se movió en el mes, idealmente descripción que
      nombra la especie) → se sugiere **asociar** y el monto real sale del banco;
   2. si no aparece → se sugiere **crear** al precio del resumen marcado
      **«monto estimado»**, editable, con cuenta de caja elegible (default: la
      bancaria de Galicia vinculada); si después se captura el movimiento real,
      `Reconcile` lo ve como `Duplicate` de esa venta y ofrece fusionar;
   3. la ganancia realizada (Galicia no la informa) se calcula del ledger:
      monto de venta − **costo promedio** de lo vendido, con el costo de los
      `cost_minor` de las compras anteriores (el mismo criterio que IOL, así los
      dos brokers se comportan igual);
   4. las compras (delta positivo), lo mismo con el signo invertido.
   Si el usuario no confirma nada, la aserción contra el resumen queda como
   diferencia visible («SPY: resumen 45, ledger 51»), sin ajuste automático.
6. ~~**Watermark de IBKR**~~ **Decidido: cobertura, no watermark.** Las filas
   **nunca se filtran por fecha** (IBKR reporta filas con `dateTime` anterior al
   reporte que las trae: la cancelación fechada 08-19 apareció el 08-25); los
   duplicados los descarta `transaction_sources('ibkr', transactionID)`. Lo que
   se guarda es `broker.ibkr.coverage` en `settings`: los rangos ya importados,
   de `FlexStatement.fromDate`/`toDate`. Sirve sólo para **detectar huecos**: si
   un reporte nuevo arranca después del día siguiente al último cubierto, la
   importación avisa «faltan datos de IBKR del X al Y: corré la query `weil`
   con ese rango y compartí el archivo» (la fase 3 ya da la salida). La fase 7
   lo reusa como `fd` para rellenar huecos sola. Sincronizado, así un segundo
   device no repite el aviso.
