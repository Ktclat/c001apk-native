#!/usr/bin/env bash
set -euo pipefail
: "${NATIVE_TOOLCHAIN_ROOT:?Source the task environment first}"
[[ "$NATIVE_TOOLCHAIN_ROOT" == /* ]] || { echo 'Expected absolute toolchain root' >&2; exit 1; }
# ADB 37 also consults HOME, not just ANDROID_USER_HOME. Keep its generated
# configuration/keys inside this disposable task, without altering OS permissions.
mkdir -p "$NATIVE_TOOLCHAIN_ROOT/adb-home"
exec env HOME="$NATIVE_TOOLCHAIN_ROOT/adb-home" \
    "$NATIVE_TOOLCHAIN_ROOT/android-sdk/platform-tools/adb" "$@"
