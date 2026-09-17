# Vendored native libs (iOS)

`libturso_sync_sdk_kit.a` — the same new Turso (SQLite rewrite) core + Cloud
sync engine Android loads as a `.so`, built here as a Rust **staticlib** for
`aarch64-apple-ios` (device) and `aarch64-apple-ios-sim` (simulator), and
embedded into the Kotlin/Native klib by the `turso` cinterop
(`../../nativeInterop/cinterop/turso.def`).

These files are ~170 MB each and are **not committed** (`.gitignore`). Build
them with:

```
scripts/build-turso-ios.sh          # clones turso into /tmp/turso-repo if needed
scripts/build-turso-ios.sh ~/src/turso
```

Layout expected by `sharedLogic/build.gradle.kts`:

```
nativeLibs/iosArm64/libturso_sync_sdk_kit.a
nativeLibs/iosSimulatorArm64/libturso_sync_sdk_kit.a
```

Headers are shared with Android (`../../androidMain/turso-headers/`) and the
build script refuses to run if they drift from the checked-out turso commit —
a header/ABI mismatch is silent memory corruption, not a compile error.
