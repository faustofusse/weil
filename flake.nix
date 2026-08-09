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
          systemImageTypes = [ "google_apis" ]; # or "google_apis_playstore" for Play Store
        };
        androidSdk = androidComposition.androidsdk;

        commonPackages = [
          androidCli
          androidSdk
          pkgs.jdk17
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
          ${pkgs.gnused}/bin/sed -i \
            -e 's/^hw\.keyboard=.*/hw.keyboard=yes/' \
            -e 's/^hw\.lcd\.width=.*/hw.lcd.width=1080/' \
            -e 's/^hw\.lcd\.height=.*/hw.lcd.height=2400/' \
            -e 's/^hw\.lcd\.density=.*/hw.lcd.density=420/' \
            -e 's/^hw\.initialOrientation=.*/hw.initialOrientation=portrait/' \
            -e 's/^hw\.gpu\.enabled=.*/hw.gpu.enabled=yes/' \
            -e 's/^hw\.gpu\.mode=.*/hw.gpu.mode=host/' \
            -e 's/^avd\.name=.*/avd.name=${avdName}/' \
            -e 's/^hw\.mainKeys=.*/hw.mainKeys=no/' \
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
            "hw.mainKeys=no"; do
            key="''${key_val%%=*}"
            if ! grep -q "^''${key}=" "${configIni}"; then
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
        '' + avdScript;

        androidCli = let
          platformData = {
            x86_64-linux = {
              url = "https://dl.google.com/android/cli/latest/linux_x86_64/android";
              hash = "sha256-fQ9LQeZRGrbu6uxLiFRC8Cre0nDPg8t19SGsosA9WT0=";
            };
            aarch64-darwin = {
              url = "https://dl.google.com/android/cli/latest/darwin_arm64/android";
              hash = "sha256-TURp8m8eCQK5Ptql2WmTTqbmnx1hngkqT4kyFgQqhI8=";
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
            pkgs.mkShell {
              name = "android-phone-shell";
              buildInputs = commonPackages;
              shellHook = commonShellEnv;
            };
      }
    );
}
