# wa-bridge

The only piece of Weil that cannot live on Cloudflare: WhatsApp's multi-device
protocol needs a permanently open authenticated websocket (keepalives every
20–30 s) and a live Signal session store, which rules out Workers, Durable
Objects and anything request-scoped.

So this is a **dumb pipe**, deliberately free of business logic:

```
WhatsApp ⟷ wa-bridge ──POST /whatsapp/inbound──▶ api.finance.fausto.ar
                     ◀──── {"reply": "..."} ────
                     ◀──────  POST /send  ──────  (out-of-band messages)
```

Categorisation, auth, the ledger and the AI calls all stay in the worker. The
bridge knows how to decrypt a message, hand the text over, and say whatever it
is told to say back.

## State

whatsmeow's session store (Signal keys, identities, app-state) is a `database/sql`
container. We point it at a **Turso** database — the driver
(`libsql-client-go`) is pure Go and speaks hrana over HTTP, which is the one
remote-SQLite flavour that supports the interactive transactions
`store/sqlstore` needs (`DoTxn`). D1 cannot back it: its HTTP API only takes
batches sent up front.

Keeping state remote makes the container stateless: killing and redeploying the
machine does **not** require re-pairing.

The dialect string passed to `sqlstore.NewWithDB` is `sqlite3` — that is only a
label for `dbutil`'s SQL flavour, unrelated to the driver name.

## Configuration

| var | meaning |
| --- | --- |
| `TURSO_URL` | `libsql://wa-bridge-<org>.turso.io` |
| `TURSO_AUTH_TOKEN` | token for that database |
| `WEBHOOK_URL` | `https://api.finance.fausto.ar/whatsapp/inbound` |
| `BRIDGE_SECRET` | shared secret, HMAC-SHA256 both ways |
| `PORT` | default `8080` |
| `LOG_LEVEL` | default `INFO` |

## Pairing the bot number

The spare number must already be a registered WhatsApp account on a phone; the
bridge links itself as a *companion device* (max 4, and the primary phone has
to come online every couple of weeks or companions get unlinked).

```sh
curl -X POST localhost:8080/pair -H "content-type: application/json" \
     -d '{"phone":"5491122334455"}'   # → {"code":"ABCD-EFGH"}
```

Then on the phone: WhatsApp → Linked devices → Link with phone number.

`GET /health` reports `{connected, logged_in, jid}`. On `events.LoggedOut` the
bridge drops the device row and waits for a new `/pair`.

## Endpoints

- `POST /whatsapp/inbound` is what the bridge *calls*, signed with
  `X-Bridge-Signature: sha256=<hex hmac of the body>`.
- `POST /send` `{"to":"5491122334455","text":"..."}` — same signature required
  from the caller.
- `GET /health`, `POST /pair`, `POST /logout`.

## Run

```sh
go run .                       # local, with the vars above exported
fly deploy                     # see fly.toml — 256 MB, never auto-stopped
```

The session database is `wa-bridge` in the **finance** Turso group
(`libsql://wa-bridge-faustofusse.aws-us-east-1.turso.io`), so the host should
sit in **us-east**: every message decrypt does several round trips to it.

## Where to run it

The workload is one always-on process, ~30 MB RAM, one outbound websocket.
Anything that sleeps on idle is disqualified — a stopped machine is an
unlinked device.

| host | cost | notes |
| --- | --- | --- |
| **Fly.io `iad`** | ~$2/mo | what `fly.toml` targets; secrets, health checks and `auto_stop_machines = false` out of the box |
| **GCP e2-micro `us-east1`** | free forever | the only genuinely free option that is also always-on; you own a VM and a systemd unit |
| Oracle Always Free | free | generous (ARM, 24 GB) but capacity errors and surprise reclamations are common |
| Railway | ~$2-3 of the $5 Hobby credit | as easy as Fly |
| Render free | — | **spins down after 15 min idle**, which unlinks the device; the $7 Starter fixes it but costs more than Fly |
| AWS | $3.5+ (Lightsail) | Fargate/App Runner are ~$10+ for this; nothing here is free anymore |
| Cloudflare Containers | $10+ | sleeps on idle and bills per active second — the worst fit despite being on-brand |
