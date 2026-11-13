#!/usr/bin/env bash
# Builds librespot (https://github.com/librespot-org/librespot, MIT) for Android and copies the
# executables into app/src/main/jniLibs/<abi>/liblibrespot.so.
# Needs: Rust (rustup) with the Android targets, cargo-ndk, and the Android NDK.
set -euo pipefail
VERSION="${LIBRESPOT_VERSION:-v0.8.0}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
# Paths with spaces break the C toolchain wrappers, so on Windows a space-free junction is used
# (C:/ohm-build/ndk → the NDK), the build output goes to C:/ohm-build/target, and the
# windows-gnu dlltool is replaced by a shim around the NDK's llvm-dlltool (see README).
if [ -d /c/ohm-build/ndk ]; then export ANDROID_NDK_HOME="C:/ohm-build/ndk"; export CARGO_TARGET_DIR="C:/ohm-build/target"; fi
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}"
OUT="${CARGO_TARGET_DIR:-target}"
export PATH="$HOME/.cargo/bin:$PATH"
# Windows (gnu host): rustup ships dlltool in a "self-contained" folder that isn't on PATH.
for d in "$HOME"/.rustup/toolchains/*-windows-gnu/lib/rustlib/*/bin/self-contained; do [ -d "$d" ] && export PATH="$PATH:$d"; done

if [ ! -d "$HERE/librespot" ]; then
  git clone --depth 1 --branch "$VERSION" https://github.com/librespot-org/librespot.git "$HERE/librespot"
fi
cd "$HERE/librespot"

# Ohm patch: on Android librespot would identify as Spotify's own Android app, whose client ID
# rejects librespot's OAuth redirect (http://127.0.0.1:5588/login → "invalid redirect URI").
# Use librespot's standard desktop identity (as on Linux) instead.
sed -i 's/"android" => ANDROID_CLIENT_ID,/"android" => KEYMASTER_CLIENT_ID, \/\/ ohm-patched/' core/src/config.rs
grep -q 'ohm-patched' core/src/config.rs || { echo "librespot patch failed — check core/src/config.rs"; exit 1; }

TARGETS=""
for abi in $ABIS; do TARGETS="$TARGETS -t $abi"; done
# pipe backend only (Ohm plays the PCM itself), rustls (no OpenSSL), pure-Rust mDNS discovery.
cargo ndk $TARGETS -P 26 build --release --locked --bin librespot \
  --no-default-features --features "rustls-tls-webpki-roots with-libmdns"

declare -A TRIPLE=([arm64-v8a]=aarch64-linux-android [armeabi-v7a]=armv7-linux-androideabi [x86_64]=x86_64-linux-android)
for abi in $ABIS; do
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$OUT/${TRIPLE[$abi]}/release/librespot" "$ROOT/app/src/main/jniLibs/$abi/liblibrespot.so"
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip" "$ROOT/app/src/main/jniLibs/$abi/liblibrespot.so" 2>/dev/null     || "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip "$ROOT/app/src/main/jniLibs/$abi/liblibrespot.so"
  echo "→ $abi: $(du -h "$ROOT/app/src/main/jniLibs/$abi/liblibrespot.so" | cut -f1)"
done
