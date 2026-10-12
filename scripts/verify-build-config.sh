#!/bin/sh
# Exercises build rules that no ordinary test run covers, against the committed tree (HEAD), in throwaway clones.
#
# Release signing and versioning (app/build.gradle.kts):
#   1. a partial signing configuration fails the build with the documented message;
#   2. a non-numeric -PversionCode fails;
#   3. -PversionCode reaches the merged release manifest;
#   4. a shallow clone derives no version, builds unsigned, and refuses a SIGNED release;
#   5. a complete clone refuses a signed release whose keystore does not exist.
# Apple test guard (core/build.gradle.kts), macOS only. These run the real test tasks (their native compile/link
# dependencies are excluded with -x, since the guard acts on the test task itself):
#   6. with a stub `xcrun` that reports no simulator and CI=true, the build fails instead of skipping iOS tests;
#   7. the same without CI: the task is really SKIPPED and the build reports it as NOT RUN;
#   8. on Apple Silicon with the real xcrun, running iosX64Test directly reports it as NOT RUN, plus a GitHub
#      Actions ::warning:: annotation when GITHUB_ACTIONS=true.
# The signing values are placeholders for the failure paths; no keystore is read or written.
#
# Usage: scripts/verify-build-config.sh [repo-root]   (needs JDK 21 and the Android SDK, like any build)
set -eu

ROOT=$(cd "${1:-$(dirname "$0")/..}" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
trap 'exit 130' INT TERM
FAILURES=0

# --no-daemon: the clones are deleted on exit, and a daemon would keep their files open.
gradle_in() { dir=$1; shift; (cd "$dir" && ./gradlew --console=plain --no-daemon --max-workers=3 "$@" >"$WORK/out.log" 2>&1); }

fail() { echo "FAIL  $1"; tail -n 15 "$WORK/out.log"; FAILURES=$((FAILURES + 1)); }

# expect <label> <exit: fail|pass> <expected text in output or "-"> <dir> <gradle args...>
expect() {
  label=$1 mode=$2 text=$3 dir=$4; shift 4
  if gradle_in "$dir" "$@"; then rc=pass; else rc=fail; fi
  if [ "$rc" != "$mode" ]; then fail "$label: expected the build to $mode but it did $rc"; return; fi
  if [ "$text" != "-" ] && ! grep -qF -- "$text" "$WORK/out.log"; then fail "$label: output does not contain: $text"; return; fi
  echo "ok    $label"
}

SIGN_FILE="$WORK/none.jks"

# A full clone has the history the version is derived from; a depth-1 clone has not.
git clone --quiet "file://$ROOT" "$WORK/full"
git clone --quiet --depth 1 "file://$ROOT" "$WORK/shallow"
if [ -f "$ROOT/local.properties" ]; then
  cp "$ROOT/local.properties" "$WORK/full/local.properties"
  cp "$ROOT/local.properties" "$WORK/shallow/local.properties"
fi

expect "partial signing config fails" fail "Incomplete release signing configuration" "$WORK/full" \
  :app:verifyReleaseIdentity -Ptrailblazer.signing.storeFile=/nonexistent
expect "non-numeric versionCode fails" fail "-PversionCode must be a positive integer" "$WORK/full" \
  :app:verifyReleaseIdentity -PversionCode=abc
expect "explicit versionCode reaches the release manifest" pass - "$WORK/full" \
  :app:processReleaseMainManifest -PversionCode=4242
if ! grep -rqs 'android:versionCode="4242"' "$WORK/full/app/build/intermediates/merged_manifest/release"; then
  fail "explicit versionCode reaches the release manifest: 4242 not found in the merged manifest"
fi
expect "shallow clone, unsigned release is allowed" pass - "$WORK/shallow" :app:verifyReleaseIdentity
expect "shallow clone refuses a signed release" fail "Refusing to build a SIGNED release" "$WORK/shallow" \
  :app:verifyReleaseIdentity "-Ptrailblazer.signing.storeFile=$SIGN_FILE" -Ptrailblazer.signing.storePassword=x \
  -Ptrailblazer.signing.keyAlias=x -Ptrailblazer.signing.keyPassword=x
expect "shallow clone accepts a signed release with an explicit versionCode (then misses the keystore)" fail \
  "Release keystore not found" "$WORK/shallow" :app:verifyReleaseIdentity -PversionCode=7 \
  "-Ptrailblazer.signing.storeFile=$SIGN_FILE" -Ptrailblazer.signing.storePassword=x \
  -Ptrailblazer.signing.keyAlias=x -Ptrailblazer.signing.keyPassword=x
expect "full clone signed release with a missing keystore fails" fail "Release keystore not found" "$WORK/full" \
  :app:verifyReleaseIdentity "-Ptrailblazer.signing.storeFile=$SIGN_FILE" -Ptrailblazer.signing.storePassword=x \
  -Ptrailblazer.signing.keyAlias=x -Ptrailblazer.signing.keyPassword=x

if [ "$(uname)" = "Darwin" ]; then
  STUB="$WORK/stub-bin"
  mkdir -p "$STUB"
  printf '#!/bin/sh\nexit 0\n' > "$STUB/xcrun"
  chmod +x "$STUB/xcrun"

  # env is explicit so a CI variable inherited from the caller cannot flip the no-CI cases. --no-daemon: a running
  # daemon keeps the PATH it started with and would never see the stub; --no-configuration-cache: the stub's output
  # must be probed afresh each time.
  run_apple() { (cd "$WORK/full" && env "$@" >"$WORK/out.log" 2>&1); }
  GRADLE="./gradlew --console=plain --no-daemon --no-configuration-cache"

  # shellcheck disable=SC2086 # $GRADLE is a fixed word list
  if run_apple CI=true PATH="$STUB:$PATH" $GRADLE :core:iosSimulatorArm64Test -x :core:linkDebugTestIosSimulatorArm64; then
    fail "CI without an iOS simulator must fail the build, but it passed"
  elif grep -qF "A skipped iOS test must not pass CI" "$WORK/out.log"; then
    echo "ok    CI without an iOS simulator fails the build"
  else
    fail "CI without an iOS simulator failed for another reason"
  fi

  # shellcheck disable=SC2086
  if ! run_apple -u CI PATH="$STUB:$PATH" $GRADLE :core:iosSimulatorArm64Test -x :core:linkDebugTestIosSimulatorArm64; then
    fail "no CI, no simulator: the build should pass with the iOS tests skipped"
  elif ! grep -qF "> Task :core:iosSimulatorArm64Test SKIPPED" "$WORK/out.log"; then
    fail "no CI, no simulator: the iOS simulator test was not skipped"
  elif ! grep -qF "WARNING: NOT RUN: core:iosSimulatorArm64Test: no iOS simulator runtime" "$WORK/out.log"; then
    fail "no CI, no simulator: the skip was not reported as NOT RUN"
  else
    echo "ok    no CI, no simulator: the iOS simulator test is skipped and reported NOT RUN"
  fi

  if [ "$(uname -m)" = "arm64" ]; then
    # Needs a simulator runtime on this machine; CI=true makes a missing one fail loudly instead.
    # shellcheck disable=SC2086
    if ! run_apple CI=true GITHUB_ACTIONS=true $GRADLE :core:iosX64Test -x :core:linkDebugTestIosX64; then
      fail "Apple Silicon: running iosX64Test directly failed"
    elif ! grep -qF "WARNING: NOT RUN: core:iosX64Test: skipped because this" "$WORK/out.log"; then
      fail "Apple Silicon: the iosX64Test gap is not reported"
    elif ! grep -qF "::warning title=Apple tests NOT RUN::core:iosX64Test" "$WORK/out.log"; then
      fail "Apple Silicon: no GitHub Actions annotation for the iosX64Test gap"
    else
      echo "ok    Apple Silicon: the iosX64Test gap is reported, with a GitHub annotation"
    fi
  fi
else
  echo "skip  Apple test guard checks: not a macOS host"
fi

if [ "$FAILURES" -ne 0 ]; then echo "$FAILURES build-config check(s) failed"; exit 1; fi
echo "all build-config checks passed"
