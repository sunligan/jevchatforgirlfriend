#!/usr/bin/env bash
# Builds the co-installable debug APK. No API key, phone, or release signing key is needed.
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLCHAIN="${JEV_ANDROID_TOOLCHAIN:-$HOME/.local/share/jev-android-toolchain}"
if [[ -z "${JAVA_HOME:-}" && -x "$TOOLCHAIN/jdk/bin/java" ]]; then
  export JAVA_HOME="$TOOLCHAIN/jdk"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$TOOLCHAIN/sdk" ]]; then
  export ANDROID_HOME="$TOOLCHAIN/sdk"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
GRADLE=(./gradlew)
if [[ -x "$TOOLCHAIN/gradle-8.9/bin/gradle" ]]; then GRADLE=("$TOOLCHAIN/gradle-8.9/bin/gradle"); fi
"${GRADLE[@]}" :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain -Pandroid.builder.sdkDownload=false "$@"
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/jev-customer-service-0.1-beta.apk
sha256sum dist/jev-customer-service-0.1-beta.apk > dist/jev-customer-service-0.1-beta.apk.sha256
printf '\nAPK: %s/dist/jev-customer-service-0.1-beta.apk\n' "$PWD"
