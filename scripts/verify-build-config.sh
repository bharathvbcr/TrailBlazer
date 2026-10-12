#!/bin/sh
# Exercises build rules that no ordinary test run covers, against the committed tree (HEAD), in throwaway clones.
#
# Release signing and versioning (app/build.gradle.kts):
#   1. a partial signing configuration fails the build with the documented message;
#   2. a non-numeric -PversionCode fails;
#   3. -PversionCode reaches the merged release manifest;
#   4. a shallow clone derives no version, builds unsigned, and refuses a SIGNED release;
#   5. a complete clone refuses a signed release whose keystore does not exist.
# Apple test guard (core/build.gradle.kts), macOS only, with a stub `xcrun` that reports no simulator:
#   6. with CI=true the build fails instead of skipping the iOS simulator tests;
#   7. without CI the iOS simulator tests are disabled AND reported as NOT RUN;
#   8. with the real xcrun, the Intel-simulator gap on Apple Silicon is reported.
# The signing values are placeholders for the failure paths; no keystore is read or written.
#
# Usage: scripts/verify-build-config.sh [repo-root]   (needs JDK 21 and the Android SDK, like any build)
set -eu

ROOT=$(cd "${1:-$(dirname "$0")/..}" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILURES=0

gradle_in() { dir=$1; shift; (cd "$dir" && ./gradlew --console=plain --max-workers=3 "$@" >"$WORK/out.log" 2>&1); }

# expect <label> <exit: fail|pass> <expected text in output or "-"> <dir> <gradle args...>
expect() {
  label=$1 mode=$2 text=$3 dir=$4; shift 4
  if gradle_in "$dir" "$@"; then rc=pass; else rc=fail; fi
  if [ "$rc" != "$mode" ]; then
    echo "FAIL  $label: expected the build to $mode but it did $rc"; tail -n 15 "$WORK/out.log"; FAILURES=$((FAILURES + 1)); return
  fi
  if [ "$text" != "-" ] && ! grep -qF -- "$text" "$WORK/out.log"; then
    echo "FAIL  $label: output does not contain: $text"; tail -n 15 "$WORK/out.log"; FAILURES=$((FAILURES + 1)); return
  fi
  echo "ok    $label"
}

SIGN_ALL="-Ptrailblazer.signing.storeFile=$WORK/none.jks -Ptrailblazer.signing.storePassword=x -Ptrailblazer.signing.keyAlias=x -Ptrailblazer.signing.keyPassword=x"

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
  echo "FAIL  explicit versionCode reaches the release manifest: 4242 not found in the merged manifest"; FAILURES=$((FAILURES + 1))
fi
expect "shallow clone, unsigned release is allowed" pass - "$WORK/shallow" :app:verifyReleaseIdentity
expect "shallow clone refuses a signed release" fail "Refusing to build a SIGNED release" "$WORK/shallow" \
  :app:verifyReleaseIdentity $SIGN_ALL
expect "shallow clone accepts a signed release with an explicit versionCode (then misses the keystore)" fail \
  "Release keystore not found" "$WORK/shallow" :app:verifyReleaseIdentity -PversionCode=7 $SIGN_ALL
expect "full clone signed release with a missing keystore fails" fail "Release keystore not found" "$WORK/full" \
  :app:verifyReleaseIdentity $SIGN_ALL

if [ "$(uname)" = "Darwin" ]; then
  STUB="$WORK/stub-bin"
  mkdir -p "$STUB"
  printf '#!/bin/sh\nexit 0\n' > "$STUB/xcrun"
  chmod +x "$STUB/xcrun"

  # env is explicit so a CI variable inherited from the caller cannot flip the no-CI cases. --no-daemon: a running
  # daemon keeps the PATH it started with and would never see the stub; --no-configuration-cache: the stub's output
  # must be probed afresh each time.
  run_apple() { (cd "$WORK/full" && env "$@" >"$WORK/out.log" 2>&1); }

  if run_apple CI=true PATH="$STUB:$PATH" ./gradlew --console=plain --no-daemon --no-configuration-cache :core:iosSimulatorArm64Test --dry-run; then
    echo "FAIL  CI without an iOS simulator must fail the build, but it passed"; FAILURES=$((FAILURES + 1))
  elif grep -qF "A skipped iOS test must not pass CI" "$WORK/out.log"; then
    echo "ok    CI without an iOS simulator fails the build"
  else
    echo "FAIL  CI without an iOS simulator failed for another reason"; tail -n 15 "$WORK/out.log"; FAILURES=$((FAILURES + 1))
  fi

  if run_apple -u CI PATH="$STUB:$PATH" ./gradlew --console=plain --no-daemon --no-configuration-cache :core:iosSimulatorArm64Test :core:reportAppleTestsNotRun --dry-run \
    && grep -qF ":core:iosSimulatorArm64Test SKIPPED" "$WORK/out.log"; then
    echo "ok    no CI, no simulator: the iOS simulator test is disabled"
  else
    echo "FAIL  no CI, no simulator: the iOS simulator test is not disabled"; tail -n 15 "$WORK/out.log"; FAILURES=$((FAILURES + 1))
  fi
  if run_apple -u CI PATH="$STUB:$PATH" ./gradlew --console=plain --no-daemon --no-configuration-cache :core:reportAppleTestsNotRun \
    && grep -qF "NOT RUN: iosSimulatorArm64Test, iosX64Test: no iOS simulator runtime" "$WORK/out.log"; then
    echo "ok    no CI, no simulator: the gap is reported"
  else
    echo "FAIL  no CI, no simulator: the gap is not reported"; tail -n 15 "$WORK/out.log"; FAILURES=$((FAILURES + 1))
  fi

  if [ "$(uname -m)" = "arm64" ]; then
    # Needs a simulator runtime on this machine; CI=true makes a missing one fail loudly instead.
    if run_apple CI=true ./gradlew --console=plain --no-daemon --no-configuration-cache :core:reportAppleTestsNotRun \
      && grep -qF "NOT RUN: iosX64Test: the Intel iOS simulator cannot run on Apple Silicon" "$WORK/out.log"; then
      echo "ok    Apple Silicon: the iosX64Test gap is reported"
    else
      echo "FAIL  Apple Silicon: the iosX64Test gap is not reported"; tail -n 15 "$WORK/out.log"; FAILURES=$((FAILURES + 1))
    fi
  fi
else
  echo "skip  Apple test guard checks: not a macOS host"
fi

if [ "$FAILURES" -ne 0 ]; then echo "$FAILURES build-config check(s) failed"; exit 1; fi
echo "all build-config checks passed"
