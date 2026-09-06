when you need to use a sdk or library use pi to spawn a subagent with deepseek-v4-flash-free to clone the repo in /tmp and explore it
after adding a swiftpm dependency remember to run ```XCODEPROJ_PATH='./app/iosApp/iosApp.xcodeproj' ./gradlew ':app:sharedUI:integrateLinkagePackage' -i```

## Build & verify

- Android: `./gradlew :app:androidApp:assembleDebug` (signs with `app/androidApp/debug.keystore`, pw "android", alias `finance-debug`)
- iOS Kotlin: `./gradlew :app:sharedUI:compileKotlinIosSimulatorArm64` (android main compile task is `:app:sharedLogic:compileAndroidMain`, not `compileDebugKotlinAndroid`)
- Full iOS app incl. Swift: `xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' -configuration Debug build CODE_SIGNING_ALLOWED=NO`

## Architecture

Auth is passkey-based via the auth worker in `/Users/fausto/sw/auth` (deployed at https://auth.fausto.ar, app slug `finance`, rp domain `finance.fausto.ar`). Flow: `AppGraph` (commonMain) wires `AuthRepository` (login→fallback register via `PasskeyCeremony`), `DatabaseProvider` (jwt → `session/refresh` on `401`/`unauthorized` in error text → re-login on 401), `AccountsRepository`. Each user gets their own Turso DB provisioned by the worker (libsql-type on Turso Cloud); local synced replicas live at `databases/{userId}/turso.db` (Android filesDir; iOS still uses the old libsql embedded replica at `databases/{userId}/local.db` via libsql-swift). Tokens + the `auth_finance` refresh cookie are in secure storage (EncryptedSharedPreferences / iOS Keychain via the Swift `KeychainStore` bridge). iOS passkey ceremony + secure store are Swift (`PasskeyManager.swift`, `KeychainStore.swift`) injected into `IosBridges` before `MainViewController()` runs.

Android DB is the new Turso sync engine (`turso` monorepo, `turso_sync_sdk_kit` crate `0.8.0-pre.8`): a vendored `libturso_sync_sdk_kit.so` (4 ABIs, 16 KB-aligned — Google Play page-size requirement) in `app/sharedLogic/src/androidMain/jniLibs/`, driven from Kotlin over JNA interface mapping (`ar.fausto.weil.TursoNative`). The engine's sync IO is caller-driven: `AndroidDatabase.drainOperation` pumps `turso_sync_operation_resume` until DONE and services the IO queue (HTTP via OkHttp with `Authorization: Bearer <jwt>` injected, atomic file read/write of sync metadata). Rebuild instructions: `app/sharedLogic/src/androidMain/jniLibs/README.md` (nix develop → cargo ndk).

Gotchas:
- Named params MUST use `:name` placeholders with map keys including the leading colon (`":name"`); `turso_statement_named_position` requires the prefix, and an unknown name silently binds nothing (verified 1-indexed positions on host smoke test).
- All DB calls (both engines) must run on the 16MB-stack dispatcher (`dbDispatcher` on Android, `DbDispatcher` on iOS) — the Rust parser overflows coroutine stacks otherwise. On Android the native work is deferred to first use (inside `DatabaseProvider.use`) so it never runs off-dispatcher; `AndroidDatabase_init` is cheap.
- Old libsql replica files (`local.db*`) are deleted on first open of the new engine — replicas are pull-only copies, the server is the source of truth.
- Android passkeys need `assetlinks.json` with **both** `delegate_permission/common.get_login_creds` and `delegate_permission/common.handle_all_urls`, plus an `asset_statements` `<meta-data>` in `AndroidManifest.xml` pointing to the file.
- Android passkeys need `assetlinks.json` with **both** `delegate_permission/common.get_login_creds` and `delegate_permission/common.handle_all_urls`, plus an `asset_statements` `<meta-data>` in `AndroidManifest.xml` pointing to the file.
- iOS needs the AASA file; both association files are served by the auth worker from the `finance` app's `android`/`ios` config.
- The auth worker's WebAuthn verification must include the Android APK key-hash origin (`android:apk-key-hash:<base64url(sha256(cert))>`) in `expectedOrigin`, because native Android responses use that instead of `https://finance.fausto.ar`.
- Kotlin exception classes can't be thrown from Swift as `Error`; the Swift bridge throws NSErrors whose localizedDescription contains "passkey cancelled"/"passkey not found", translated by `bridgedPasskeys()` in `IosAuth.kt`.
