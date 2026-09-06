when you need to use a sdk or library use pi to spawn a subagent with deepseek-v4-flash-free to clone the repo in /tmp and explore it
after adding a swiftpm dependency remember to run ```XCODEPROJ_PATH='./app/iosApp/iosApp.xcodeproj' ./gradlew ':app:sharedUI:integrateLinkagePackage' -i```

## Build & verify

- Android: `./gradlew :app:androidApp:assembleDebug` (signs with `app/androidApp/debug.keystore`, pw "android", alias `finance-debug`)
- iOS Kotlin: `./gradlew :app:sharedUI:compileKotlinIosSimulatorArm64` (android main compile task is `:app:sharedLogic:compileAndroidMain`, not `compileDebugKotlinAndroid`)
- Full iOS app incl. Swift: `xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' -configuration Debug build CODE_SIGNING_ALLOWED=NO`

## Architecture

Auth is passkey-based via the auth worker in `/Users/fausto/sw/auth` (deployed at https://auth.fausto.ar, app slug `finance`, rp domain `finance.fausto.ar`). Flow: `AppGraph` (commonMain) wires `AuthRepository` (login→fallback register via `PasskeyCeremony`), `DatabaseProvider` (jwt → `session/refresh` on libsql auth failure → re-login on 401), `AccountsRepository`. Each user gets their own Turso DB provisioned by the worker; local embedded replicas live at `databases/{userId}/local.db` (Android filesDir / iOS Documents). Tokens + the `auth_finance` refresh cookie are in secure storage (EncryptedSharedPreferences / iOS Keychain via the Swift `KeychainStore` bridge). iOS passkey ceremony + secure store are Swift (`PasskeyManager.swift`, `KeychainStore.swift`) injected into `IosBridges` before `MainViewController()` runs.

Gotchas:
- libsql named params MUST use `:name` placeholders with map keys including the colon (`":name"`); a missing colon silently binds NULL.
- All libsql calls must run on the 16MB-stack dispatcher (`dbDispatcher` on Android, `DbDispatcher` on iOS) — Rust parser overflows coroutine stacks otherwise.
- Android passkeys need `assetlinks.json` with **both** `delegate_permission/common.get_login_creds` and `delegate_permission/common.handle_all_urls`, plus an `asset_statements` `<meta-data>` in `AndroidManifest.xml` pointing to the file.
- iOS needs the AASA file; both association files are served by the auth worker from the `finance` app's `android`/`ios` config.
- The auth worker's WebAuthn verification must include the Android APK key-hash origin (`android:apk-key-hash:<base64url(sha256(cert))>`) in `expectedOrigin`, because native Android responses use that instead of `https://finance.fausto.ar`.
- Kotlin exception classes can't be thrown from Swift as `Error`; the Swift bridge throws NSErrors whose localizedDescription contains "passkey cancelled"/"passkey not found", translated by `bridgedPasskeys()` in `IosAuth.kt`.
