#!/usr/bin/env bash
# Push előtti lokális AI review.
#
# Használat (a reviewelendő ágon, a változások commitolása után):
#   scripts/local-review.sh [base-ág]        # alap: main
#
# Mit csinál:
#   1. lefordítja a demo appot (a fordítási kimenet bekerül a promptba, mint a CI-ban),
#   2. lefuttatja a reviewert az utolsó commitokra ($BASE...HEAD), a riportot a
#      target/local-review/latest.md fájlba is elmenti,
#   3. ha nincs találat: pusholja az ágat; ha van, 1-es kóddal kilép és nem pushol.
#
# Környezeti változók:
#   COPILOT_CMD       a copilot CLI útvonala
#   MIN_SEVERITY      a találatok küszöbe (alap: minor)
#   COPILOT_MODEL     a használt modell
#   NO_PUSH=1         csak review, nem pushol
#   RESET_ON_FAIL=1   találatnál a még nem pusholt utolsó commitot soft reset-eli (a változások staged maradnak)
set -euo pipefail

BASE="${1:-main}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REVIEWER_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEMO_DIR="$REVIEWER_ROOT/demo-countdown"
JAR="$REVIEWER_ROOT/reviewer/target/ai-reviewer.jar"
REPORT_DIR="$REVIEWER_ROOT/target/local-review"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Van nem commitolt változás: előbb commitolj, a review a commitokat nézi." >&2
  exit 2
fi

echo "== Reviewer építése"
mvn -q -B -f "$REVIEWER_ROOT/reviewer/pom.xml" package -DskipTests

if [ -f "$DEMO_DIR/pom.xml" ]; then
  echo "== Demo app fordítása"
  mvn -q -B -f "$DEMO_DIR/pom.xml" test-compile >"$LOG" 2>&1 || true
fi

echo "== Review: $BASE...$BRANCH"
mkdir -p "$REPORT_DIR"
set +e
LOCAL_BASE="$BASE" COMPILE_LOG="$LOG" RULES_FILE="${RULES_FILE:-$REVIEWER_ROOT/agent.md}" \
  java -jar "$JAR" | tee "$REPORT_DIR/latest.md"
STATUS=${PIPESTATUS[0]}
set -e
echo "== Riport: $REPORT_DIR/latest.md"

if [ "$STATUS" -ne 0 ]; then
  echo "== Találatok vannak, nem pusholok." >&2
  if [ "${RESET_ON_FAIL:-}" = "1" ] && ! { git rev-parse --verify -q "origin/$BRANCH" >/dev/null \
    && [ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$BRANCH")" ]; }; then
    git reset --soft HEAD~1
    echo "== Az utolsó commit soft reset-elve (a változások staged állapotban)." >&2
  fi
  exit 1
fi

if [ "${NO_PUSH:-}" = "1" ]; then
  echo "== Nincs találat (NO_PUSH=1: nincs push)."
  exit 0
fi
echo "== Nincs találat, push"
git push origin "$BRANCH"
