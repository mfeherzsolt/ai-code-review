#!/usr/bin/env bash
# Push előtti lokális AI review.
#
# Használat (a reviewelendő ágon, a változások commitolása után):
#   scripts/local-review.sh [base-ág]        # alap: main
#
# Mit csinál:
#   1. lefordítja a demo appot (a fordítási kimenet bekerül a promptba, mint a CI-ban),
#   2. lefuttatja a reviewert az utolsó commitokra ($BASE...HEAD), a riportot modulonként a
#      <modul>/target/local-review/latest.md fájlba is elmenti (a modulba nem tartozó fájlok
#      riportja: target/local-review/latest.md),
#   3. ha nincs találat: pusholja az ágat; ha van, nem pushol.
#
# Kilépési kód: 0 siker; 3 találatok vannak (a reviewer EXIT_FINDINGS kódja); 2 nem commitolt változás van;
# minden más nem 0 érték hiba (pl. a reviewer elhasalt).
#
# Környezeti változók:
#   COPILOT_CMD       a copilot CLI útvonala
#   MIN_SEVERITY      a találatok küszöbe (alap: minor)
#   COPILOT_MODEL     a használt modell
#   NO_PUSH=1         csak review, nem pushol

set -euo pipefail

# A reviewer kilépési kódja, ha van találat (Main.EXIT_FINDINGS); az 1 a JVM hibakódja.
EXIT_FINDINGS=3

BASE="${1:-main}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REVIEWER_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEMO_DIR="$REVIEWER_ROOT/demo-countdown"
JAR="$REVIEWER_ROOT/reviewer/target/ai-reviewer.jar"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

# A riportok helye (<modul>/target/review) a repó gyökeréhez képest adódik.
cd "$REVIEWER_ROOT"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Van nem commitolt változás: előbb commitolj, a review a commitokat nézi." >&2
  exit 2
fi

echo "== Reviewer építése"
mvn -q -B -f "$REVIEWER_ROOT/reviewer/pom.xml" package -DskipTests

if [ -f "$DEMO_DIR/pom.xml" ]; then
  echo "== Demo app fordítása"
  # A fordítási hiba nem állítja meg a scriptet: a kimenet a reviewerhez kerül, ott critical találat lesz.
  if ! mvn -q -B -f "$DEMO_DIR/pom.xml" test-compile >"$LOG" 2>&1; then
    echo "A demo app fordítása sikertelen (a részletek a fordítási kimenetben)." | tee -a "$LOG" >&2
  fi
fi

echo "== Review: $BASE...$BRANCH"
set +e
LOCAL_BASE="$BASE" COMPILE_LOG="$LOG" RULES_FILE="${RULES_FILE:-$REVIEWER_ROOT/agent.md}" \
  SAVE_REPORT=true java -jar "$JAR"
STATUS=$?
set -e

case "$STATUS" in
  0) ;;
  "$EXIT_FINDINGS")
    echo "== Találatok vannak, nem pusholok." >&2
    exit "$EXIT_FINDINGS"
    ;;
  *)
    echo "== A reviewer hibával leállt (kilépési kód: $STATUS), nem pusholok." >&2
    exit 1
    ;;
esac

if [ "${NO_PUSH:-}" = "1" ]; then
  echo "== Nincs találat (NO_PUSH=1: nincs push)."
  exit 0
fi
echo "== Nincs találat, push"
git push origin "$BRANCH"
