#!/bin/sh
# Copies the committed tree (HEAD) to the public repo github.com/rameezexe/taal, minus private notes,
# reverse-engineering docs and raw band captures, then commits there. Push is left to you.
# Usage: scripts/publish-public.sh "Commit message"   (public clone: ../taal-public, cloned on first use)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PUB=${PUBLIC_DIR:-"$ROOT/../taal-public"}
MSG=${1:-"Sync from the working repo"}

[ -d "$PUB/.git" ] || git clone https://github.com/rameezexe/taal.git "$PUB"

# Fresh copy of HEAD, keeping the public repo's .git.
find "$PUB" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
git -C "$ROOT" archive HEAD | tar -x -C "$PUB"

cd "$PUB"
rm -rf CLAUDE.md PLAN.md \
  docs/fit3-protocol.md docs/beta-plan.md docs/beta-recruiting.md docs/gps-accuracy-plan.md docs/routes-plan.md \
  docs/install-guide.md \
  core/src/test/resources/fit3 core/src/test/kotlin/com/rameez/fit3zone/core/BandProtocolTest.kt
mv scripts/public/README.md README.md
rm -rf scripts/public

# Stop if anything private slipped in.
if grep -rnIE "RZGL|74:19:0A|ModenByte|jadx|decompil|fit3plugin|galaxy-garmin|/Users/|fit3-protocol" . --exclude-dir=.git --exclude=publish-public.sh; then
  echo "Private text found above: fix it in the working repo first." >&2
  exit 1
fi

git add -A
git commit -q -m "$MSG" && echo "Committed in $PUB. Check it, then: git -C \"$PUB\" push"
