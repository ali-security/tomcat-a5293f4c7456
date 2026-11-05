#!/usr/bin/env bash
# Sealed-build helper: print a test summary from the Ant JUnit plain-formatter
# logs (output/build/logs/TEST-*.txt). Prints, per suite, the counts; every
# skipped test with its reason (from the "SKIPPED: <msg>" line the formatter
# writes after "Testcase:"); a grouped skip list; and grand totals.
# Read-only: the pass/fail gate is Ant's own `test-status` target.
set -uo pipefail
LOGDIR="${1:-output/build/logs}"
shopt -s nullglob
files=("$LOGDIR"/TEST-*.txt)
if [ "${#files[@]}" -eq 0 ]; then
  echo "SEAL-SUMMARY: no TEST-*.txt logs found in $LOGDIR"
  exit 0
fi

awk '
function flush() {
  if (suite == "") return
  if (!hdr) { run = tc; fl = nf; er = ne; sk = ns }
  printf "SUITE %s: tests=%d failures=%d errors=%d skipped=%d passed=%d\n", suite, run, fl, er, sk, run - fl - er - sk
  S++; T += run; F += fl; E += er; K += sk
}
FNR == 1 { flush(); suite = ""; hdr = 0; tc = 0; nf = 0; ne = 0; ns = 0; cur = "" }
/^Testsuite: / { suite = substr($0, 12) }
/^Tests run: / && !hdr {
  hdr = 1
  line = $0; gsub(/,/, "", line); n = split(line, a, " ")
  for (i = 1; i <= n; i++) {
    if (a[i] == "run:") run = a[i+1]
    if (a[i] == "Failures:") fl = a[i+1]
    if (a[i] == "Errors:") er = a[i+1]
    if (a[i] == "Skipped:") sk = a[i+1]
  }
}
/^Testcase: / { tc++; cur = $2 }
/^[ \t]*FAILED/ { nf++; fails[++nfl] = suite "#" cur }
/^[ \t]*Caused an ERROR/ { ne++; fails[++nfl] = suite "#" cur " (ERROR)" }
/^[ \t]*SKIPPED/ {
  ns++
  r = $0; sub(/^[ \t]*SKIPPED:?[ \t]*/, "", r)
  if (r == "") r = "(no message)"
  skips[++nsk] = suite "#" cur " -- " r
  short = suite; sub(/.*\./, "", short)
  grp[short " -- " r]++
}
END {
  flush()
  print "================ SKIPPED TESTS (name -- reason) ================"
  for (i = 1; i <= nsk; i++) print "SKIPPED " skips[i]
  print "================ SKIP GROUPS ================"
  for (g in grp) printf "SKIP-GROUP %d x %s\n", grp[g], g
  if (nfl > 0) {
    print "================ FAILED/ERRORED TESTS ================"
    for (i = 1; i <= nfl; i++) print "FAILED " fails[i]
  }
  printf "SEAL-TOTALS: suites=%d tests=%d passed=%d failures=%d errors=%d skipped=%d\n", S, T, T - F - E - K, F, E, K
}
' "${files[@]}"
