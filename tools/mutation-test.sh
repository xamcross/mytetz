#!/usr/bin/env bash
#
# A mutation-testing harness for this repo.
#
#   ./tools/mutation-test.sh tools/mutations/task-1.11.sh
#
# For each mutation it edits one source file, runs a Gradle test task, classifies the result, and
# restores the file. A mutation is KILLED when a test fails, and SURVIVED when the suite still
# passes — a survivor means the behaviour it broke is not actually pinned by anything.
#
# ## Why the NO-GRADLE-OUTPUT and PATCH-DID-NOT-APPLY classifications exist
#
# The first version of this harness (Python, subprocess with shell=True) never actually invoked
# Gradle. Every mutation was reported KILLED, with no failing test names, and the report was written
# from it. It was caught only because two of those mutations could not possibly have compiled.
#
# A mutation report is a claim that the tests are load-bearing, so a harness that silently runs
# nothing produces the most confidently wrong artifact available. Hence:
#
#   - an empty Gradle log is NO-GRADLE-OUTPUT, never "killed";
#   - a patch that did not change the file is PATCH-DID-NOT-APPLY, never "killed";
#   - a KILLED result must name the failing tests, and a run that names none is not trustworthy.
#
# Read the results with that in mind: KILLED with no test names underneath is a bug in the harness.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MUTATIONS_FILE="${1:-}"
if [ -z "$MUTATIONS_FILE" ] || [ ! -f "$MUTATIONS_FILE" ]; then
  echo "usage: $0 <mutations-file>" >&2
  echo "  e.g. $0 tools/mutations/task-1.11.sh" >&2
  exit 2
fi

LOG_DIR="${MUTATION_LOG_DIR:-$(mktemp -d)}"
mkdir -p "$LOG_DIR"
RESULTS="$LOG_DIR/results.txt"
: > "$RESULTS"

killed=0
survived=0
broken=0

# mutate <label> <file> <python-expression-over-s> <gradle-task>
#
# The expression runs with the file's contents in `s` and must rebind `s`; use `.replace(old, new, 1)`
# so a mutation cannot silently apply in more places than intended.
mutate() {
  local label="$1" file="$2" expr="$3" task="$4"

  cp "$file" "$file.mutbak"
  python -c "
import io
p = r'$file'
s = io.open(p, encoding='utf-8').read()
$expr
io.open(p, 'w', encoding='utf-8').write(s)
"
  if cmp -s "$file" "$file.mutbak"; then
    echo "$label  PATCH-DID-NOT-APPLY" | tee -a "$RESULTS"
    broken=$((broken + 1))
    mv "$file.mutbak" "$file"
    return
  fi

  local log="$LOG_DIR/$label.log"
  ./gradlew "$task" --console=plain > "$log" 2>&1

  local status
  if [ ! -s "$log" ]; then
    status="NO-GRADLE-OUTPUT"
  elif grep -q "^e: " "$log"; then
    status="DID-NOT-COMPILE"
  elif grep -q "BUILD SUCCESSFUL" "$log"; then
    status="SURVIVED"
  else
    status="KILLED"
  fi

  echo "$label  $status" | tee -a "$RESULTS"
  case "$status" in
    KILLED)
      grep -oE "^[A-Za-z0-9_]+Test > .*FAILED" "$log" | sed 's/^/        - /' | tee -a "$RESULTS"
      killed=$((killed + 1))
      ;;
    SURVIVED) survived=$((survived + 1)) ;;
    *) broken=$((broken + 1)) ;;
  esac

  mv "$file.mutbak" "$file"
}

# shellcheck source=/dev/null
source "$MUTATIONS_FILE"

echo
echo "======== $MUTATIONS_FILE ========"
cat "$RESULTS"
echo
echo "killed=$killed survived=$survived unusable=$broken"
echo "logs: $LOG_DIR"

[ "$survived" -eq 0 ] && [ "$broken" -eq 0 ]
