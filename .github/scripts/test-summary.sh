#!/usr/bin/env bash
# Summarises surefire/failsafe XML reports: a table in the job summary and one notice annotation per run.
# Usage: test-summary.sh <reports-dir>...
set -uo pipefail

attr() { # attribute name, xml file -> value from the <testsuite> element
  grep -m1 -o "<testsuite [^>]*" "$2" | grep -o " $1=\"[^\"]*\"" | head -1 | sed 's/.*="\(.*\)"/\1/'
}

rows=""; total=0; failed=0; skipped=0
for dir in "$@"; do
  for f in "$dir"/TEST-*.xml; do
    [ -f "$f" ] || continue
    name=$(attr name "$f"); name=${name##*.}
    t=$(attr tests "$f"); e=$(attr errors "$f"); fl=$(attr failures "$f"); s=$(attr skipped "$f")
    bad=$(( ${e:-0} + ${fl:-0} ))
    total=$(( total + ${t:-0} )); failed=$(( failed + bad )); skipped=$(( skipped + ${s:-0} ))
    status="✅"; [ "$bad" -gt 0 ] && status="❌"
    rows+="| $status | $name | ${t:-0} | $bad | ${s:-0} |"$'\n'
  done
done

{
  echo "### API tests: $total run, $failed failed, $skipped skipped"
  echo
  echo "| | Class | Tests | Failed | Skipped |"
  echo "|---|---|---:|---:|---:|"
  printf '%s' "$rows"
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"

lines=$(printf '%s' "$rows" | sed 's/^| [^ ]* | //; s/ |$//; s/ | / /g' | awk '{printf "%s: %s tests, %s failed%%0A", $1, $2, $3}')
echo "::notice title=API tests · $total run · $failed failed · $skipped skipped::$lines"
