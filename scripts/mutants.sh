#!/usr/bin/env bash
# Removes one rule at a time from a copy of the project and runs the tests that guard it; every
# mutant must make at least one test fail. Writes build/mutants/mutants.txt.
#
# Usage: scripts/mutants.sh [--with-stack] [--run]
#   default        a dry run: list the mutants, change nothing
#   --run          run the mutants that need no Docker (./gradlew test in the copy)
#   --with-stack   also the mutant whose test needs the lab stack (scripts/stack.sh up first)
# The project itself is never changed: each mutant is applied to its own copy under build/mutants.
set -euo pipefail

cd "$(dirname "$0")/.."
readonly MAIN="src/main/java/io/plainround/seatwave/recovery"
execute="no"
stack="no"
for option in "$@"; do
  case "$option" in
    --run) execute="yes" ;;
    --with-stack) stack="yes" ;;
    *) echo "usage: $0 [--with-stack] [--run]" >&2; exit 2 ;;
  esac
done

# Each mutant: what it breaks, the file, the text to find (exactly once), its replacement, and the
# Gradle task with the tests that must catch it.
names=(); files=(); finds=(); replaces=(); tasks=()
mutant() { names+=("$1"); files+=("$MAIN/$2"); finds+=("$3"); replaces+=("$4"); tasks+=("$5"); }
mutant "the checker declares a replay that stopped short good" restore/RestoreChecker.java \
  '          Result.error(
              new Refused(
                  compare(stopped.state(), manifest, "replay stopped short: " + stopped.reason())));' \
  '          Result.success(new Declared(stopped.state()));' test
mutant "an experiment with no abort condition is accepted" chaos/ChaosExperimentCli.java \
  'if (abortConditions.isEmpty()) {' 'if (abortConditions.isEmpty() && name.isEmpty()) {' test
mutant "the drill store's restore stops after the target, not before it" restore/PointInTimeRestore.java \
  'if (!change.committedAt().isBefore(stopBefore)) {' 'if (change.committedAt().isAfter(stopBefore)) {' test
mutant "the JDBC checker accepts a copy restored past its target" restore/ConfirmChecker.java \
  '    if (presentAfterTarget > 0) {' '    if (presentAfterTarget < 0) {' test
mutant "the JDBC checker accepts a copy whose replay stopped short" restore/ConfirmChecker.java \
  '    if (!missing.isEmpty()) {' '    if (missing.isEmpty() && !missing.isEmpty()) {' test
mutant "a backup whose verification never ran counts as good" backup/BackupHealth.java \
  '.filter(run -> run.verification() == Verification.PASSED)' \
  '.filter(run -> run.verification() != Verification.FAILED)' test
mutant "a backup file is restored without comparing it with the manifest" backup/BaseBackup.java \
  'if (bytes != Long.parseLong(entry[2]) || !digest.equals(entry[3])) {' \
  'if (bytes != Long.parseLong(entry[2]) && digest.isEmpty()) {' test
mutant "a runbook step that waits for a later step is accepted" runbook/RunbookCheckCli.java \
  '} else if (earlierIndex > index) {' '} else if (earlierIndex > steps.size()) {' test
mutant "the promotion does not advance the sequences" stack/PilotLightPromotion.java \
  '    done.addAll(advanceSequences(copyAdmin));' '    done.add("sequences left as they were");' \
  "stackTest --tests *PilotLightOnPostgresTest*"

echo "plan: ${#names[@]} mutants, each in its own copy under build/mutants (the project is not changed)"
for (( index = 0; index < ${#names[@]}; index++ )); do
  echo "  $((index + 1)). ${names[$index]}: ./gradlew ${tasks[$index]}"
done
if [[ "$execute" != "yes" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi

rm -rf build/mutants
mkdir -p build/mutants
report="build/mutants/mutants.txt"
: > "$report"
for (( index = 0; index < ${#names[@]}; index++ )); do
  number=$((index + 1))
  name="${names[$index]}"
  task="${tasks[$index]}"
  if [[ "$task" == stackTest* && "$stack" != "yes" ]]; then
    printf 'mutant %d: %s\n  not run here (needs the lab stack: --with-stack)\n' "$number" "$name" >> "$report"
    continue
  fi
  copy="build/mutants/$number"
  mkdir -p "$copy"
  # The copy keeps .secrets, so a stack test in it reaches the running lab.
  tar --exclude ./build --exclude ./.gradle -cf - . | tar -xf - -C "$copy"
  python3 -I -c '
import pathlib, sys
path = pathlib.Path(sys.argv[1]); text = path.read_text(encoding="utf-8")
if text.count(sys.argv[2]) != 1:
    sys.exit("the mutant text is not in " + sys.argv[1] + " exactly once")
path.write_text(text.replace(sys.argv[2], sys.argv[3]), encoding="utf-8")
' "$copy/${files[$index]}" "${finds[$index]}" "${replaces[$index]}"
  read -r -a task_words <<< "$task"
  set +e
  ( cd "$copy" && ./gradlew --offline -q spotlessApply > /dev/null 2>&1; ./gradlew --offline "${task_words[@]}" --continue > gradle.txt 2>&1 )
  status=$?
  set -e
  {
    printf 'mutant %d: %s\n' "$number" "$name"
    grep -E "^[A-Za-z]+Test > .* FAILED$" "$copy/gradle.txt" | sed 's/^/  failing: /' || true
    # What the first failure said, so the reason can be read, not only the test's name.
    grep -m 1 -E "^ +(org\.opentest4j|java\.lang|org\.postgresql|java\.sql)[A-Za-z.]*(Error|Exception)" "$copy/gradle.txt" \
      | sed -e 's/^ */  first failure: /' | cut -c1-200 || true
    if [[ "$status" -eq 0 ]]; then echo "  SURVIVED: no test failed"; fi
  } >> "$report"
  rm -rf "$copy"
done
cat "$report"
! grep -q "SURVIVED" "$report"
