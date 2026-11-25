#!/usr/bin/env bash
# Keeps the household TV build current with official Silo without losing our
# changes: merges upstream/main into ben/kids-genres, runs the tests, builds
# the TV APK (with the token and admin profiles from local.properties),
# pushes the branch to the fork, and installs the APK on the TV.
#
#   scripts/update-tv.sh 192.168.1.50   # first time: the TV's address
#   scripts/update-tv.sh                # later: uses silo.tvAddress
#
# If anything goes wrong, nothing on the TV changes and the branch is left as
# it was before the run.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
BRANCH="ben/kids-genres"
APK="androidTvApp/build/outputs/apk/debug/androidTvApp-universal-debug.apk"

prop() { [ -f local.properties ] && grep "^$1=" local.properties | tail -1 | cut -d= -f2- | tr -d '[:space:]' || true; }

# The TV: an argument wins, and is remembered for next time.
TV="${1:-$(prop silo.tvAddress)}"
if [ -n "${1:-}" ] && [ "$(prop silo.tvAddress)" != "$1" ]; then
  printf 'silo.tvAddress=%s\n' "$1" >> local.properties
fi

if [ "$(git branch --show-current)" != "$BRANCH" ]; then
  echo "✗ Switch to $BRANCH first (git switch $BRANCH)."; exit 1
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "✗ There are uncommitted changes. Commit or stash them first."; exit 1
fi
[ -n "$(prop silo.genreConfigToken)" ] || echo "! No silo.genreConfigToken in local.properties: the TV will ask for the token."

before="$(git rev-parse HEAD)"
undo() { git reset -q --hard "$before"; }

echo "› Checking official Silo for updates"
git fetch -q upstream
behind="$(git rev-list --count HEAD..upstream/main)"
if [ "$behind" -gt 0 ]; then
  echo "› Merging $behind new official changes"
  if ! git merge -q --no-edit upstream/main; then
    git merge --abort || true
    echo "✗ Official Silo changed the same code as our changes, so they need merging by hand."
    echo "  Nothing was changed. Ask Claude: \"merge upstream/main into $BRANCH\"."
    exit 1
  fi
else
  echo "› Already up to date with official Silo"
fi

echo "› Testing and building (a few minutes)"
if ! ./gradlew -q :shared:testDebugUnitTest :androidTvApp:testDebugUnitTest :androidTvApp:assembleDebug; then
  undo
  echo "✗ The tests or build failed after the update. The branch is back where it was and the TV is unchanged."
  echo "  Ask Claude to look at it."
  exit 1
fi

if [ "$(git rev-parse HEAD)" != "$before" ]; then
  echo "› Saving the updated branch to GitHub"
  git push -q origin "$BRANCH"
fi

if [ -z "$TV" ]; then
  echo "✓ Built $APK"
  echo "  No TV address yet: run scripts/update-tv.sh <TV IP address> to install it."
  exit 0
fi

# An emulator is already attached; a TV on the network needs connecting.
case "$TV" in
  emulator-*) target="$TV" ;;
  *:*) target="$TV" ;;
  *) target="$TV:5555" ;;
esac
case "$target" in
  emulator-*) ;;
  *)
    echo "› Connecting to the TV at $target"
    "$ADB" connect "$target" >/dev/null || true
    if ! "$ADB" -s "$target" get-state >/dev/null 2>&1; then
      echo "✗ Can't reach the TV at $target. Check it's on, Network debugging is on, and accept"
      echo "  the \"Allow debugging\" prompt on the TV if it shows one. Then run this again."
      exit 1
    fi
    ;;
esac

echo "› Installing on the TV"
if ! "$ADB" -s "$target" install -r "$APK"; then
  echo "✗ The install failed. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, the TV has Silo from"
  echo "  the Play Store or from another computer; see the note at the top of docs/ben-tv-updates.md."
  exit 1
fi
echo "✓ The TV now runs official Silo as of $(git log -1 --format=%cd --date=short upstream/main), with our changes."
