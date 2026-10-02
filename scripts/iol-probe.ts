// Read-only probe of the IOL API: dumps raw responses to /tmp/iol-probe/ so
// what the connector could import can be checked against IOL's own export
// (MovimientosHistoricos.xls). Only /token and GETs, like IolClient.
//
//   IOL_USERNAME=… IOL_PASSWORD=… bun scripts/iol-probe.ts [--from 2025-01-01]
//
// The output holds account data (amounts, order numbers), never the password
// or the token.
import { mkdirSync, writeFileSync } from "node:fs";

const BASE = "https://api.invertironline.com";
const OUT = "/tmp/iol-probe";
const username = process.env.IOL_USERNAME;
const password = process.env.IOL_PASSWORD;
if (!username || !password) {
  console.error("set IOL_USERNAME and IOL_PASSWORD");
  process.exit(2);
}
const fromArg = process.argv.indexOf("--from");
const from = fromArg > 0 ? process.argv[fromArg + 1] : "2025-01-01";
const to = new Date(Date.now() + 86400e3).toISOString().slice(0, 10);

mkdirSync(OUT, { recursive: true });

const tokenRes = await fetch(`${BASE}/token`, {
  method: "POST",
  headers: { "content-type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams({ username, password, grant_type: "password" }),
});
if (!tokenRes.ok) {
  console.error(`token: ${tokenRes.status}`);
  process.exit(1);
}
const { access_token } = (await tokenRes.json()) as { access_token: string };

const index: Record<string, number> = {};
async function get(path: string, file: string): Promise<unknown> {
  const res = await fetch(BASE + path, { headers: { authorization: `Bearer ${access_token}` } });
  const text = await res.text();
  index[file] = res.status;
  writeFileSync(`${OUT}/${file}`, text);
  try {
    return res.ok ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}

type Op = { numero: number; tipo: string; simbolo: string; estado: string; montoOperado: number | null };
const ops = ((await get(
  `/api/v2/operaciones?filtro.estado=todas&filtro.fechaDesde=${from}&filtro.fechaHasta=${to}`,
  "operaciones.json",
)) ?? []) as Op[];
await get("/api/v2/estadocuenta", "estadocuenta.json");
// IOL's reference MEP rate vs the AL30/AL30D last-trade ratio the app falls back to.
for (const s of ["AL30", "AL30D", "GD30"]) await get(`/api/v2/Cotizaciones/MEP/${s}`, `mep-${s}.json`);
for (const s of ["AL30", "AL30D"]) await get(`/api/v2/bcba/Titulos/${s}/Cotizacion`, `cotizacion-${s}.json`);
// Price history around a purchase day: does IOL keep intraday rows (to read the MEP at the trade's minute)?
const day = process.env.IOL_DAY ?? "2026-08-14";
const next = new Date(Date.parse(day) + 2 * 86_400_000).toISOString().slice(0, 10);
for (const s of ["AL30", "AL30D"]) await get(`/api/v2/bcba/Titulos/${s}/Cotizacion/seriehistorica/${day}/${next}/sinAjustar`, `serie-${s}.json`);

// Every non-trade row: payments (both halves of each pair) and anything else.
const trades = new Set(["compra", "venta", "suscripción fci", "rescate fci"]);
const others = ops.filter((o) => !trades.has(o.tipo.toLowerCase()) && o.estado.toLowerCase() === "terminada");
for (const o of others) await get(`/api/v2/operaciones/${o.numero}`, `detalle-${o.numero}.json`);

// Guesses at an account-movements endpoint (credits, interest on balance).
for (const [path, file] of [
  ["/api/v2/estadocuenta/movimientos", "guess-estadocuenta-movimientos.json"],
  ["/api/v2/movimientos", "guess-movimientos.json"],
  [`/api/v2/movimientos?fechaDesde=${from}&fechaHasta=${to}`, "guess-movimientos-fechas.json"],
  ["/api/v2/Cuentas/Movimientos", "guess-cuentas-movimientos.json"],
] as const) {
  await get(path, file);
}

const types: Record<string, number> = {};
for (const o of ops) types[o.tipo] = (types[o.tipo] ?? 0) + 1;
writeFileSync(`${OUT}/_summary.json`, JSON.stringify({ from, to, types, status: index }, null, 1));
console.log(`${ops.length} operations, ${others.length} non-trade details → ${OUT}`);
console.log(types);
