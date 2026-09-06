# Vendored native libs

`libturso_sync_sdk_kit.so` — new Turso (SQLite rewrite) core + Cloud sync engine,
C ABI from the `turso` monorepo (`sdk-kit` + `sync/sdk-kit` crates). Replaces
`libsql-android` ("lib/liblibsql_android.so"), which shipped 4 KB-aligned LOAD
segments (Google Play 16 KB page-size requirement, Nov 2025+).

Built with: 東 turso monorepo @ `7e2fc39de` (app API `0.8.0-pre.8`), via

```
nix develop   # provides rustup (toolchain 1.88 auto-installs from the turso
              # repo's rust-toolchain.toml), cargo-ndk 4.1.2, NDK r29
cd ~/.local/share/opencode/repos/github.com/tursodatabase/turso@main
for t in aarch64-linux-android armv7-linux-androideabi \
         x86_64-linux-android i686-linux-android; do
  cargo ndk --target $t --platform 31 -- \
    build --release --package turso_sync_sdk_kit
  # strip to ~10-18MB (release profile ships debuginfo)
  llvm-strip --strip-unneeded target/$t/release/libturso_sync_sdk_kit.so
done
```

Map triple → jniLibs dir: `aarch64-linux-android`→`arm64-v8a`,
`armv7-linux-androideabi`→`armeabi-v7a`, `x86_64-linux-android`→`x86_64`,
`i686-linux-android`→`x86`.

Verified with NDK `llvm-readelf -lW` — all LOAD segments align `0x4000`
(16 KB) in every ABI. Kotlin talks to the C ABI via JNA direct mapping;
the headers it was generated against live in `../turso-headers/`.
