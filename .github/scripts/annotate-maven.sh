#!/usr/bin/env bash
# Turns a failed Maven build into GitHub check annotations, so failures are readable from the Checks API
# without downloading logs. Usage: annotate-maven.sh <maven-log-file> <reports-dir>...
set -uo pipefail
log="$1"; shift

emit() { # title, body
  local body="${2//'%'/'%25'}"
  body="${body//$'\r'/}"
  body="${body//$'\n'/'%0A'}"
  echo "::error title=$1::${body:0:6000}"
}

# compiler / build errors
errors=$(grep -E '^\[ERROR\]' "$log" | grep -vE 'Help 1|re-run Maven|-> \[Help|^\[ERROR\] *$|For more information|full stack trace|To see the full' | head -40)
[ -n "$errors" ] && emit "Maven errors" "$errors"

# failed tests: first lines of each failure from surefire/failsafe XML reports
for dir in "$@"; do
  [ -d "$dir" ] || continue
  for f in "$dir"/TEST-*.xml; do
    [ -f "$f" ] || continue
    if grep -qE '<(failure|error)' "$f"; then
      name=$(basename "$f" .xml | sed 's/^TEST-//')
      # root causes first (deduplicated), then the first assertion/exception line of each failing test
      causes=$(grep -oE 'Caused by: [^&]*' "$f" | awk '!seen[$0]++' | tail -6)
      firsts=$(grep -oE '<(failure|error) message="[^"]{0,400}' "$f" | grep -v 'Failed to load ApplicationContext' | sed 's/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g' | head -8)
      described=$(grep -A4 -E '^Description:' "$f" | head -12)
      emit "$name" "CAUSES:
$causes
FIRST LINES:
$firsts
$described"
    fi
  done
done

# container / startup problems often only show up in the test output files
for dir in "$@"; do
  [ -d "$dir" ] || continue
  for f in "$dir"/*-output.txt; do
    [ -f "$f" ] || continue
    hits=$(grep -E 'APPLICATION FAILED TO START|Caused by:|Description:|Action:' -A3 "$f" | head -30)
    [ -n "$hits" ] && emit "$(basename "$f")" "$hits"
  done
done
exit 0
