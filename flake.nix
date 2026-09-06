{
  description = "android";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };

        platformVersion = "36";
        # Keep the userdata partition small: a 6G partition needs ~7.2G of free
        # space to create, which fails on full disks.
        dataPartitionSize = "4G";
        abiVersion = if pkgs.stdenv.isLinux then "x86_64" else "arm64-v8a";
        buildToolsVersion = "36.0.0";
        cmdLineToolsVersion = "20.0";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          cmdLineToolsVersion = cmdLineToolsVersion;
          buildToolsVersions = [ buildToolsVersion ];
          platformVersions = [ platformVersion ];
          abiVersions = [ abiVersion ];
          includeEmulator = true;
          includeSystemImages = true;
          # NDK is required by cargo-ndk when building
          # libturso_sync_sdk_kit.so (see jniLibs README).
          includeNDK = true;
          ndkVersions = [ "29.0.14206865" ];
          systemImageTypes = [ "google_apis" ]; # or "google_apis_playstore" for Play Store
        };
        androidSdk = androidComposition.androidsdk;

        commonPackages = [
          androidCli
          androidSdk
          pkgs.jdk17
          # Rustup for building libturso_sync_sdk_kit.so (vendored in
          # app/sharedLogic/src/androidMain/jniLibs). The turso repo pins
          # channel 1.88 + Android tier-2 targets via rust-toolchain.toml;
          # rustup auto-installs them on first build. cargo-ndk drives the
          # NDK clang from $ANDROID_NDK_ROOT.
          pkgs.rustup
          pkgs.cargo-ndk
        ];

        avdName = "pixel";
        avdDevice = "pixel_8"; # see `avdmanager list device`
        avdPackage = "system-images;android-${platformVersion};google_apis;${abiVersion}";
        avdDir = "$PWD/.android/avd";
        avdPath = "${avdDir}/${avdName}.avd";
        configIni = "${avdPath}/config.ini";
        avdScript = ''
          export ANDROID_AVD_HOME="${avdDir}"
          export ANDROID_USER_HOME="$PWD/.android"

          # Create AVD if missing
          if [ ! -d "${avdPath}" ]; then
            echo "Creating AVD '${avdName}'..."
            yes "" | avdmanager create avd --force -n ${avdName} -k "${avdPackage}" -p "${avdPath}" -d "${avdDevice}"
          fi

          # Fall back to a dynamic skin if the pixel_8 skin isn't bundled with the SDK
          if [ ! -d "$ANDROID_HOME/skins/${avdDevice}" ]; then
            ${pkgs.gnused}/bin/sed -i \
              -e 's/^skin\.name=.*/skin.name=1080x2400/' \
              -e '/^skin\.path=/d' \
              "${configIni}" 2>/dev/null || true
          fi

          # Ensure the AVD config is correct for the Pixel 8
          # NB: the emulator rewrites config.ini as 'key = value' (with spaces,
          # byte sizes) on boot, so all patterns below allow optional spaces.
          ${pkgs.gnused}/bin/sed -i \
            -e 's/^hw\.keyboard[[:space:]]*=.*/hw.keyboard=yes/' \
            -e 's/^hw\.lcd\.width[[:space:]]*=.*/hw.lcd.width=1080/' \
            -e 's/^hw\.lcd\.height[[:space:]]*=.*/hw.lcd.height=2400/' \
            -e 's/^hw\.lcd\.density[[:space:]]*=.*/hw.lcd.density=420/' \
            -e 's/^hw\.initialOrientation[[:space:]]*=.*/hw.initialOrientation=portrait/' \
            -e 's/^hw\.gpu\.enabled[[:space:]]*=.*/hw.gpu.enabled=yes/' \
            -e 's/^hw\.gpu\.mode[[:space:]]*=.*/hw.gpu.mode=host/' \
            -e 's/^avd\.name[[:space:]]*=.*/avd.name=${avdName}/' \
            -e 's/^hw\.mainKeys[[:space:]]*=.*/hw.mainKeys=no/' \
            -e 's/^disk\.dataPartition\.size[[:space:]]*=.*/disk.dataPartition.size=${dataPartitionSize}/' \
            -e 's/^disk\.dataPartition\.path[[:space:]]*=.*/disk.dataPartition.path=userdata-qemu.img/' \
            "${configIni}" 2>/dev/null || true

          # Add missing keys if not present
          for key_val in \
            "hw.keyboard=yes" \
            "hw.lcd.width=1080" \
            "hw.lcd.height=2400" \
            "hw.lcd.density=420" \
            "hw.initialOrientation=portrait" \
            "hw.gpu.enabled=yes" \
            "hw.gpu.mode=host" \
            "avd.name=${avdName}" \
            "hw.mainKeys=no" \
            "disk.dataPartition.size=${dataPartitionSize}" \
            "disk.dataPartition.path=userdata-qemu.img"; do
            key="''${key_val%%=*}"
            # `key = value` (spaced) and `key=value` forms both count as present
            if ! grep -qE "^''${key}[[:space:]]*=" "${configIni}"; then
              echo "$key_val" >> "${configIni}"
            fi
          done
        '';

        commonShellEnv = ''
          export JAVA_HOME="${pkgs.jdk17.home}"
          export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME" # deprecada
          export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk-bundle"
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/${buildToolsVersion}/aapt2"
        '' + avdScript + ''

          # Android Studio needs a stable SDK path; it can't follow the
          # per-build Nix store path on its own.
          if [ ! -e "$PWD/.android/sdk/libexec/android-sdk" ]; then
            echo "hint: run 'nix build .#sdk -o .android/sdk' so Android Studio can use the Nix SDK"
          fi
        '';

        androidCli = let
          platformData = {
            x86_64-linux = {
              url = "https://dl.google.com/android/cli/latest/linux_x86_64/android";
              hash = "sha256-fQ9LQeZRGrbu6uxLiFRC8Cre0nDPg8t19SGsosA9WT0=";
            };
            aarch64-darwin = {
              url = "https://dl.google.com/android/cli/latest/darwin_arm64/android";
              hash = "sha256-D6ND/alDO3x05OuR06GM7GBHeinAFaAY3RiSIqPQ0CA=";
            };
            x86_64-darwin = {
              url = "https://dl.google.com/android/cli/latest/darwin_x86_64/android";
              hash = "sha256-AQvn4mgHjMbGkchHvrij2iLjbTLHWIjfoTedLAD9Hag=";
            };
          };
          info = platformData.${system} or (throw "Unsupported system: ${system}");
        in pkgs.stdenv.mkDerivation {
          pname = "androidCli";
          version = "latest";
          src = pkgs.fetchurl {
            inherit (info) url hash;
          };
          dontUnpack = true;
          installPhase = ''
            mkdir -p $out/bin
            cp $src $out/bin/android
            chmod +x $out/bin/android
          '';
          meta = {
            description = "Android CLI tool";
            homepage = "https://developer.android.com/studio/command-line";
          };
        };
      in
      {
        devShells.default =
          if pkgs.stdenv.isLinux then
            (pkgs.buildFHSEnv {
              name = "my-fhs-shell";
              targetPkgs = pkgs: commonPackages;
              runScript = "zsh";
              profile = commonShellEnv + ''
                export QT_QPA_PLATFORM="wayland;xcb"
                export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath [ pkgs.vulkan-loader pkgs.libGL ]}"
              '';
            }).env
          else
            # mkShellNoCC: this shell only needs the JDK + Android SDK. The
            # standard mkShell drags in the Darwin C toolchain, whose setup
            # hooks point SDKROOT/DEVELOPER_DIR at the Nix Apple SDK and put
            # the xcbuild `xcrun` shim on PATH — all of which break the real
            # Xcode toolchain (SwiftPM integration, Kotlin/Native, xcrun).
            pkgs.mkShellNoCC {
              name = "android-phone-shell";
              buildInputs = commonPackages;
              shellHook = commonShellEnv;
            };

        # `nix run .#emulator` — launches the Pixel AVD with the Nix SDK env,
        # no need to remember ANDROID_* variables (your shell profile points
        # at ~/Library/Android/sdk where this system image does not exist).
        apps.emulator = {
          type = "app";
          program = "${pkgs.writeShellScriptBin "emulator-pixel" ''
            export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export PATH="${androidSdk}/bin:${androidSdk}/libexec/android-sdk/platform-tools:$PATH"
            ${avdScript}
            exec emulator @${avdName} "$@"
          ''}/bin/emulator-pixel";
        };

        # `nix build .#sdk -o .android/sdk` — exposes the Nix Android SDK at a
        # stable, GC-rooted path inside the project so Android Studio can use it
        # (point local.properties sdk.dir at .android/sdk/libexec/android-sdk).
        # A per-project link avoids conflicts between similar flakes.
        # Re-run after flake updates.
        packages = {
          sdk = androidSdk;
          default = androidSdk;
        };
      }
    );
}
