#!/bin/sh
# Keyword-filter paging checker (LuminaGram #22 paging; dev tool, the app build never compiles lumina-dev/).
#   sh lumina-dev/history-paging-check/run.sh      # from anywhere; needs a JDK >= 9 (javac --release 8)
# Compiles LuminaHistoryPaging exactly as it is in the app module, plus the checker, into a temp dir and
# runs the unit checks and the paging model. Exit status 0 = all checks passed.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$REPO/TMessagesProj/src/main/java/org/telegram/messenger"
OUT="${TMPDIR:-/tmp}/history-paging-check.$$"
mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT
javac --release 8 -encoding UTF-8 -Xlint:all -d "$OUT" \
  "$SRC"/LuminaHistoryPaging.java \
  "$HERE"/src/org/telegram/messenger/LuminaHistoryPagingCheck.java
java -cp "$OUT" org.telegram.messenger.LuminaHistoryPagingCheck
