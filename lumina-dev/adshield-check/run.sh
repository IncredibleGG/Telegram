#!/bin/sh
# AdShield checker for the Android Java port (dev tool; the app build never compiles lumina-dev/).
#   sh lumina-dev/adshield-check/run.sh            # from anywhere; needs a JDK >= 9 (javac --release 8)
# Compiles the engine sources exactly as they are in the app module, plus the checker, into a temp dir
# and runs every conformance check. Exit status 0 = all checks passed.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/TMessagesProj/src/main/java/org/telegram/messenger/lumina"
OUT="${TMPDIR:-/tmp}/adshield-check.$$"
mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT
javac --release 8 -encoding UTF-8 -Xlint:all -Xlint:-serial -d "$OUT" \
  "$SRC"/AdShieldTables.java "$SRC"/LuminaAdShield.java "$SRC"/LuminaAdShieldStore.java \
  "$SRC"/LuminaAdShieldAccount.java "$SRC"/LuminaAdShieldThread.java \
  "$HERE"/src/org/telegram/messenger/lumina/*.java
java -cp "$OUT" org.telegram.messenger.lumina.AdShieldCheck "$HERE"
