#!/usr/bin/env bash
# Push előtti lokális AI review.
#
# Használat (a reviewelendő ágon, a változások commitolása után):
#   scripts/local-review.sh [base-ág]        # alap: main
#
# Mit csinál:
#   1. lefordítja a demo appot (a fordítási kimenet bekerül a promptba, mint a CI-ban),
#   2. lefuttatja a reviewert az utolsó commitokra ($BASE...HEAD),
#   3. ha van találat: a még nem pusholt utolsó commitot soft reset-eli (a változások staged maradnak),
#      kiírja a találatokat, és 1-es kóddal kilép -> javítás, új commit, script újra,
#   4. ha nincs találat: pusholja az ágat.
#
# Környezeti változók: COPILOT_CMD (a copilot CLI útvonala), MIN_SEVERITY (alap: major), COPILOT_MODEL,
# NO_PUSH=1 (csak review, se reset, se push).
set -euo pipefail

BASE="${1:-main}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REVIEWER_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$REVIEWER_ROOT/reviewer/target/ai-reviewer.jar"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Van nem commitolt változás: előbb commitolj, a review a commitokat nézi." >&2
  exit 2
fi

echo "== Reviewer építése"
mvn -q -B -f "$REVIEWER_ROOT/reviewer/pom.xml" package -DskipTests

if [ -f demo-countdown/pom.xml ]; then
  echo "== Demo app fordítása"
  mvn -q -B -f demo-countdown/pom.xml test-compile >"$LOG" 2>&1 || true
fi

echo "== Review: $BASE...$BRANCH"
set +e
LOCAL_BASE="$BASE" COMPILE_LOG="$LOG" RULES_FILE="${RULES_FILE:-$REVIEWER_ROOT/agent.md}" \
  java -jar "$JAR"
STATUS=$?
set -e

if [ "$STATUS" -ne 0 ]; then
  if [ "${NO_PUSH:-}" = "1" ]; then
    echo "== Találatok vannak (NO_PUSH=1: nincs reset)." >&2
    exit 1
  fi
  if git rev-parse --verify -q "origin/$BRANCH" >/dev/null && [ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$BRANCH")" ]; then
    echo "== Találatok vannak, de az utolsó commit már fel van pusholva: nem resetelek." >&2
    exit 1
  fi
  git reset --soft HEAD~1
  echo "== Találatok vannak: az utolsó commit soft reset-elve (a változások staged állapotban). Javítsd, commitolj, futtasd újra." >&2
  exit 1
fi

if [ "${NO_PUSH:-}" = "1" ]; then
  echo "== Nincs találat (NO_PUSH=1: nincs push)."
  exit 0
fi
echo "== Nincs találat, push"
git push origin "$BRANCH"
