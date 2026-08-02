#!/usr/bin/env bash
#
# A mutation-testing harness for this repo.
#
#   ./tools/mutation-test.sh tools/mutations/task-1.11.sh
#
# For each mutation it edits one source file, runs a Gradle test task, classifies the result, and
# restores the file. A mutation is KILLED when a named test fails, and SURVIVED when the suite still
# passes — a survivor means the behaviour it broke is not actually pinned by anything.
#
# ## This file is written to be distrusted
#
# A mutation report is a claim that a test suite is load-bearing. A harness that silently fails to
# run anything therefore produces the most confidently wrong artifact available, and this one has
# done exactly that: its first version (Python, `subprocess` with `shell=True`) never invoked Gradle
# at all and reported all 17 mutations KILLED. It was caught only because two of those mutations
# could not possibly have compiled.
#
# Its second version fixed that and still had the same disease in a milder form. `KILLED` was the
# *fallback* branch, so any outcome that was not recognisably a compile error or a success — a
# missing `gradlew`, a daemon that would not start, an OOM, a dependency-resolution failure, an
# interrupt — was reported as a kill. And although it collected the names of failing tests, it never
# checked that it had found any.
#
# So the rules here are:
#
#   1. **A baseline run comes first.** The unmutated tree must be green before any mutation runs.
#      Without it, "38/38 killed" in an environment with no database is byte-identical to "38/38
#      killed" in a working one. This is the single check that makes the output mean anything.
#   2. **KILLED must be positively established** — the task failed AND at least one failing test was
#      named. A run that fails without naming a test is `KILLED-BUT-UNNAMED` and counts as an error.
#   3. **Every unrecognised outcome is an error**, never a kill. There is no fallback branch.
#   4. **Sources are restored under a trap**, so an interrupt mid-run cannot leave a mutated file or
#      a stray `.mutbak` behind.
#
# Exit code is 0 only when every mutation was killed and nothing was unusable.
set -uo pipefail

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
unusable=0

# The file currently mutated, so the trap can put it back.
IN_FLIGHT=""

restore_all() {
  if [ -n "$IN_FLIGHT" ] && [ -f "$IN_FLIGHT.mutbak" ]; then
    mv -f "$IN_FLIGHT.mutbak" "$IN_FLIGHT"
  fi
  IN_FLIGHT=""
}
trap 'echo; echo "interrupted — restoring sources" >&2; restore_all; exit 130' INT TERM
trap restore_all EXIT

# gradle_status <log> — echoes SUCCESS | COMPILE-ERROR | TESTS-FAILED | UNKNOWN
gradle_status() {
  local log="$1"
  if [ ! -s "$log" ]; then echo "UNKNOWN"; return; fi
  if grep -q "^e: " "$log"; then echo "COMPILE-ERROR"; return; fi
  if grep -q "^BUILD SUCCESSFUL" "$log"; then echo "SUCCESS"; return; fi
  # A failing test task is the only kind of BUILD FAILED we are willing to read as a kill.
  if grep -q "^BUILD FAILED" "$log" && grep -qE "Task .*:test' *FAILED|Task .*:test FAILED" "$log"; then
    echo "TESTS-FAILED"; return
  fi
  echo "UNKNOWN"
}

# failing_tests <log> — one "Class > test name" per line.
#
# Tolerates a package prefix. Gradle's console output is not contractual: it prints simple class
# names in this repo, but a `testLogging` block or a Gradle upgrade can make it fully qualified, and
# an anchored simple-name pattern would then match nothing while the harness reported kills as usual.
failing_tests() {
  grep -oE "^([A-Za-z0-9_]+\.)*[A-Za-z0-9_]+ > .*FAILED" "$1" | sed 's/^/        - /'
}

# The Gradle tasks this mutation file uses, so the baseline covers exactly those.
TASKS=""
note_task() {
  case " $TASKS " in
    *" $1 "*) ;;
    *) TASKS="$TASKS $1" ;;
  esac
}

# mutate <label> <file> <python-expression-over-s> <gradle-task>
#
# The expression runs with the file's contents in `s` and must rebind `s`; use `.replace(old, new, 1)`
# so a mutation cannot silently apply in more places than intended.
mutate() {
  local label="$1" file="$2" expr="$3" task="$4"

  if [ "${COLLECT_ONLY:-0}" = "1" ]; then
    note_task "$task"
    return
  fi

  if [ ! -f "$file" ]; then
    echo "$label  NO-SUCH-FILE ($file)" | tee -a "$RESULTS"
    unusable=$((unusable + 1))
    return
  fi
  if ! cp "$file" "$file.mutbak"; then
    echo "$label  BACKUP-FAILED" | tee -a "$RESULTS"
    unusable=$((unusable + 1))
    return
  fi
  IN_FLIGHT="$file"

  # The patch reports for itself whether it changed anything, rather than the caller diffing the
  # file afterwards. Comparing bytes does not work: this repo is checked out with LF endings and
  # Python's text mode writes `os.linesep`, so the round trip alone rewrites every line and a no-op
  # replacement is indistinguishable from a real one. A smoke test caught that — with the byte
  # comparison, a mutation whose target string had drifted was reported SURVIVED, i.e. as a missing
  # test, rather than as the broken mutation it was.
  # Read with universal newlines so the text is always LF in memory, and write with `newline=''` so
  # Python does not translate it back on the way out. Both halves matter, and getting either wrong is
  # silent:
  #
  #   - reading with `newline=''` on this CRLF checkout leaves \r\n in the string, so any mutation
  #     pattern written with \n matches nothing. The PATCH-DID-NOT-APPLY check below caught exactly
  #     that on the first run after it was introduced;
  #   - writing without `newline=''` translates \n to os.linesep, which rewrites every line of the
  #     file and made the earlier byte-comparison version of this check unable to fire at all.
  #
  # The file is restored from its backup afterwards either way, so the temporary LF conversion of a
  # mutated file is not observable.
  local patch_result
  patch_result="$(python -c "
import io, sys
p = r'$file'
with io.open(p, encoding='utf-8') as f:
    before = f.read()
s = before
$expr
if s == before:
    sys.stdout.write('UNCHANGED')
else:
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)
    sys.stdout.write('CHANGED')
")"

  if [ "$?" -ne 0 ] || [ -z "$patch_result" ]; then
    echo "$label  PATCH-ERROR" | tee -a "$RESULTS"
    unusable=$((unusable + 1))
    restore_all
    return
  fi

  if [ "$patch_result" = "UNCHANGED" ]; then
    echo "$label  PATCH-DID-NOT-APPLY" | tee -a "$RESULTS"
    unusable=$((unusable + 1))
    restore_all
    return
  fi

  local log="$LOG_DIR/$label.log"
  ./gradlew "$task" --console=plain > "$log" 2>&1
  local outcome names
  outcome="$(gradle_status "$log")"
  names="$(failing_tests "$log")"

  case "$outcome" in
    SUCCESS)
      echo "$label  SURVIVED" | tee -a "$RESULTS"
      survived=$((survived + 1))
      ;;
    TESTS-FAILED)
      if [ -z "$names" ]; then
        # Rule 2. The task failed, but nothing here can say which test did it, so this is not
        # evidence of anything.
        echo "$label  KILLED-BUT-UNNAMED (see $log)" | tee -a "$RESULTS"
        unusable=$((unusable + 1))
      else
        echo "$label  KILLED" | tee -a "$RESULTS"
        echo "$names" | tee -a "$RESULTS"
        killed=$((killed + 1))
      fi
      ;;
    COMPILE-ERROR)
      echo "$label  DID-NOT-COMPILE" | tee -a "$RESULTS"
      unusable=$((unusable + 1))
      ;;
    *)
      # Rule 3. Missing gradlew, dead daemon, OOM, dependency failure, interrupt — none of these is
      # a kill, and the previous harness called all of them kills.
      echo "$label  UNUSABLE-RUN (see $log)" | tee -a "$RESULTS"
      unusable=$((unusable + 1))
      ;;
  esac

  restore_all
}

# ------------------------------------------------------------------ baseline, then the real run
#
# The mutations file is sourced twice: once with `mutate` collecting task names only, so the baseline
# covers exactly the tasks that will be used, and once for real.

# A `.mutbak` left in the tree means a previous run did not restore its source. The `trap` below
# covers an ordinary interrupt, but it is not a guarantee: bash defers a signal trap until the
# foreground command returns, so a hard kill while `./gradlew` is running can take the script out
# before it restores anything — which is exactly what happened once during this task. Refusing to
# start on that evidence is what turns a silently mutated working tree into a message.
stale="$(find . -name '*.mutbak' -not -path './*/build/*' 2>/dev/null)"
if [ -n "$stale" ]; then
  echo "refusing to start: a previous run left mutated sources behind." >&2
  echo "$stale" | sed 's/\.mutbak$//' | sed 's/^/  mutated: /' >&2
  echo "restore each with: mv -f FILE.mutbak FILE" >&2
  exit 4
fi

COLLECT_ONLY=1
# shellcheck source=/dev/null
source "$MUTATIONS_FILE"
COLLECT_ONLY=0

echo "baseline: verifying the unmutated tree is green before mutating anything"
baseline_failed=0
for task in $TASKS; do
  log="$LOG_DIR/baseline$(echo "$task" | tr ':' '_').log"
  ./gradlew "$task" --console=plain > "$log" 2>&1
  status="$(gradle_status "$log")"
  echo "  baseline $task  $status"
  [ "$status" = "SUCCESS" ] || baseline_failed=1
done

if [ "$baseline_failed" -ne 0 ]; then
  echo
  echo "BASELINE FAILED — refusing to run mutations." >&2
  echo "Every mutation would report KILLED and the result would mean nothing." >&2
  echo "logs: $LOG_DIR" >&2
  exit 3
fi
echo

# shellcheck source=/dev/null
source "$MUTATIONS_FILE"

echo
echo "======== $MUTATIONS_FILE ========"
cat "$RESULTS"
echo
echo "killed=$killed survived=$survived unusable=$unusable"
echo "logs: $LOG_DIR"

[ "$survived" -eq 0 ] && [ "$unusable" -eq 0 ]
