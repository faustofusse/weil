# Desktop (JVM) target + Compose Hot Reload — plan

## Where we actually are (verified, not assumed)

- `:app:desktopApp` already exists (`kotlin("jvm")` + `compose`, `mainClass = ar.fausto.weil.MainKt`)
  but `app/desktopApp/src/main/kotlin/ar/fausto/weil/main.kt` still renders the **wizard template
  `App()`**, not `RootScreen`.
- `jvm()` is already declared on `:core`, `:app:sharedLogic`, `:app:sharedUI`.
  `./gradlew :app:desktopApp:compileKotlin` is **green** → the entire Compose UI already compiles for JVM.
- JVM actuals that exist: `Platform.jvm.kt`, `NotificationAccess.jvm.kt` (`null`),
  `DecodeIcon.jvm.kt` (Skia), `JvmAuth.kt` (CIO client; `captureSessionCookie`/`storedCookieHeader`
  are **no-ops**).
- Missing at *runtime* only: `SecureStore`, `PasskeyCeremony`, `QrScanner` (null is fine), `Database`.
- No hot-reload plugin anywhere — `README.md` advertises `hotRun --auto`, which currently does not exist.
- Toolchain: Gradle 9.5 / daemon JDK 21, foojay resolver already in `settings.gradle.kts`.
  Compose Hot Reload (CHR) 1.2.0 needs CMP ≥ 1.10 — we're on 1.11.1. ✅

## Phase 0 — hot reload plumbing (~15 min, do this first)

1. `gradle/libs.versions.toml`: `composeHotReload = "1.2.0"` + `composeHotReload = { id = "org.jetbrains.compose.hot-reload", version.ref = "composeHotReload" }`.
2. Root `build.gradle.kts`: `alias(libs.plugins.composeHotReload) apply false`.
3. `app/desktopApp/build.gradle.kts`: apply the alias. Module is `kotlin("jvm")` → task is
   **`:app:desktopApp:hotRun`** (not `hotRunJvm`); main class is already set via `compose.desktop.application`.
4. Apply it to `:app:sharedUI` too — 100 % of the UI lives in its `commonMain`, and this is what makes
   edits there recompile + reload (also gives `:app:sharedUI:hotReloadJvmMain`).
5. JBR: comes from foojay automatically. If it doesn't resolve, add
   `compose.reload.jbr.autoProvisioningEnabled=true` to `gradle.properties`.
6. **Risk:** `org.gradle.configuration-cache=true` is on. If `hotRun` trips on it, run with
   `--no-configuration-cache` first, then decide whether to disable it for that task only.
7. Verify: `./gradlew :app:desktopApp:hotRun --auto`, edit a `Text(...)` in `HomeScreen.kt`, save.

Update `README.md` once true.

## Phase 1 — show the real app + offline dev harness (~1–2 h, the 80/20)

`RootScreen(graph)` gates on `AuthState`, and desktop has neither passkeys nor a DB, so a plain
swap of `App()` → `RootScreen()` lands on the login screen and dead-ends. Fix in one pass:

1. `main.kt`: build an `AppGraph`, wrap in `FinanceTheme`, `Window(state = rememberWindowState(width = 420.dp, height = 900.dp))`
   so the phone layouts read correctly.
2. `JvmSecureStore` (desktopApp or `sharedLogic/jvmMain`): `Properties` file at `~/.weil/dev-store.properties`.
   Plaintext — dev only, never ship it as-is (~25 lines).
3. `JvmPasskeys : PasskeyCeremony` that throws `PasskeyNotFound` — keeps the graph constructible and
   surfaces a clear error instead of a crash. `qrScanner = { null }` is already handled by `ChainState`
   (`login_qr_unavailable`).
4. `dbDispatcher` for JVM: single thread, 16 MB stack (copy Android's) — parity, and required the moment
   Phase 2b lands.
5. **`FakeDatabase`** behind `-Dweil.dev=fake`: implement the 4 `Database` methods over `org.xerial:sqlite-jdbc`
   (jvmMain-only), apply `SCHEMA_SQL` + `migrateSchema()`, seed demo accounts/transactions, `sync()` = no-op.
   Seed the store with a fake `user_id`/`jwt`/`db_url` so `AuthRepository.restore()` lands on `LoggedIn`.
   - Gotcha: JDBC won't take the colon-prefixed map keys the Turso engine needs. Write a ~20-line binder
     that rewrites `:name` → `?` and orders the args.

Result: full UI, realistic data, zero network, zero passkeys, hot reload on every screen.

## Phase 2 — real data on desktop (pick one)

**2a. Remote-only HTTP — recommended, ~150 LOC, no native code.**
Implement `Database` over the libsql/Turso HTTP pipeline (`POST /v2/pipeline`) with ktor CIO and
`Authorization: Bearer <jwt>`; `sync()` = no-op, no local replica. Reuse the `^(libsql|turso)://` → `https://`
normalization already in `Database.android.kt`. Trade-off: a round-trip per query, no offline.

**2b. Native engine parity — ~half a day + a Rust build.**
`cargo build --release -p turso_sync_sdk_kit` for `aarch64-apple-darwin` → `libturso_sync_sdk_kit.dylib`;
hoist `TursoNative.kt` + the driver from `androidMain` into a shared `jvmShared` source set
(Android `Context` → plain `File` paths; OkHttp works unchanged on JVM); ship the dylib as a JVM resource.
Real offline sync parity with Android, and it forces the jniLibs build into something reproducible.

## Phase 3 — real auth on desktop (optional, ~half a day + a worker change)

There is no WebAuthn on the JVM. The clean answer is the loopback-browser flow:

- Desktop starts `com.sun.net.httpserver` on `127.0.0.1:<random>`, opens
  `https://finance.fausto.ar/desktop?port=…&state=…` in the default browser; the page runs the normal
  browser WebAuthn ceremony (login, or `chain/join` to enroll the desktop as a chain device) and POSTs the
  token payload back to the loopback URL; desktop persists it via `JvmSecureStore`.
- Needs a page served by the auth worker (`app/webApp` is still the template). `expectedOrigin` already
  covers `https://finance.fausto.ar`, so no worker verification change.
- Also implement `captureSessionCookie` / `storedCookieHeader` in `JvmAuth.kt` — they're no-ops today, so
  `session/refresh` and every `ChainRepository` call would 401. Mirror the Android impl: read `Set-Cookie`
  off the response, persist under `COOKIE_STORE_PREFIX`.

## Order

Phase 0, then Phase 1. That's hot-reloadable full UI in an afternoon. Phase 2a when you want live data
on the desktop; Phase 2b/3 only if desktop graduates from "UI workbench" to a real target.

## Status (implemented)

- **Phase 0** — done. `composeHotReload` **1.3.0-alpha01** applied to `:app:desktopApp` and `:app:sharedUI`.
  `./gradlew :app:desktopApp:hotRun --auto`. Verified end to end: JBR 25 is auto-provisioned via the
  foojay resolver, the app launches, and edits in `:app:sharedUI` recompile and reload into the running
  JVM without a restart.

  > **Gotcha — do not "upgrade" to CHR 1.2.0.** With this project's Kotlin 2.4.20-RC / AGP 9.3.1,
  > merely putting CHR **1.2.0** on the plugin classpath (even `apply false` in the root build, and even
  > when applied only in `:app:desktopApp`) breaks *`:app:androidApp`'s build-script compilation*:
  > `android {}`, `kotlin {}` and the `libs` catalog accessors all become unresolved references.
  > It's a shared plugin-classpath clash — the CHR Gradle plugin is a shadow jar, so it isn't a
  > transitive-version conflict you can fix with a constraint. 1.1.1 and 1.3.0-alpha01 are both fine;
  > 1.2.0 reproducibly is not. Bisected by toggling versions against `:app:androidApp:tasks --dry-run`.
  >
  > This failure hides behind the configuration cache (`org.gradle.configuration-cache=true`): a stale
  > cache entry keeps builds green until something invalidates it. If you touch the plugin setup, verify
  > with a config-cache miss, e.g. `./gradlew :app:androidApp:tasks --dry-run` after editing a build file.
- **Phase 1** — done. `main.kt` renders `RootScreen`. `JvmSecureStore` (`~/.weil/dev-store.properties`,
  pre-seeded fake session), `JvmDevPasskeys` (stub), `FakeDatabase` (`org.xerial:sqlite-jdbc` at
  `~/.weil/dev.db`, runs `SCHEMA_SQL`/`migrateSchema()`, seeds demo data).
- **Phase 2a** — done. `HttpDatabase.kt` (`app/sharedLogic/src/jvmMain`) over the libsql `v2/pipeline`
  HTTP API. Picked automatically over `FakeDatabase` once the store holds a real session
  (`JvmSecureStore.isDevSession == false`).
- **Phase 3** — done (real auth), pending one production deploy of the auth worker.

### Desktop auth design (why it's shaped this way)

The JVM has no WebAuthn, and two constraints drive the design:

1. WebAuthn validates the calling origin against the RP ID, so the ceremony page **must** be served from
   `finance.fausto.ar`. Conveniently the auth worker already serves that host as a custom domain
   (`wrangler.jsonc` routes), so the page lives in the worker — no separate webApp deployment.
2. The `auth_finance` refresh cookie is **httpOnly**, so a browser-side flow cannot hand it to the app.
   A design where the browser calls login/finish and passes tokens back would produce a session with
   **no working refresh and no chain access** — useless for sync-chain work.

So the split is: the browser does *only* `navigator.credentials.create/get`; the app makes every API
call itself.

```
desktop  --(login/start, own ktor client)-->  auth worker
desktop  --opens browser-->  finance.fausto.ar/desktop-pair#<base64 options+port+state>
browser  --navigator.credentials.get/create-->  platform authenticator (Touch ID, etc.)
browser  --form POST (navigation: no CORS, no PNA)-->  127.0.0.1:<ephemeral>/finish
desktop  --(login/finish, own ktor client)-->  auth worker  => Set-Cookie lands on the app
```

Because this satisfies the shared `PasskeyCeremony` interface, **login, register and `chain/join` all
work through the unmodified `AuthRepository` code paths**, and the cookie-authed chain endpoints work
afterwards.

- `app/desktopApp/.../BrowserPasskeys.kt` — the ceremony (ephemeral loopback listener, `state` nonce,
  base64url fragment so options never reach the server, timeout, browser error text mapped onto
  `PasskeyCancelled`/`PasskeyNotFound` like the Swift bridge does). Prints the pairing URL as a
  fallback if the browser fails to launch.
- `auth` repo: `src/desktopPair.ts` + a `GET /desktop-pair` route in `src/index.ts`, gated to hostnames
  that are some app's rp_id (same mechanism as the `.well-known` association files).
- `JvmAuth.kt`'s `captureSessionCookie`/`storedCookieHeader` are now real (mirroring Android), which is
  what makes `session/refresh` and `ChainRepository` work.

**Deploy required:** `/desktop-pair` must be live on `finance.fausto.ar` before desktop auth works.
From the `auth` repo: `npx wrangler deploy`.

Known limitation: desktop has no camera, so it cannot *scan* a QR to approve another device
(`ChainState` degrades to "scanner unavailable"). It can still show its own invite QR for a phone to
scan, and can join an existing chain by showing a pairing-request QR.

### Run modes

- `./gradlew :app:desktopApp:hotRun --auto` — real auth + live Turso data, with hot reload.
- `./gradlew :app:desktopApp:run -Dweil.mode=fake` — offline UI workbench: pre-seeded fake session,
  local SQLite, no network or passkeys.
- Sign out completely by deleting `~/.weil/dev-store.properties` (it holds a real refresh cookie and
  Turso JWT in plaintext — dev convenience, not a secure store).
