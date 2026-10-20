#!/usr/bin/env bash
# Compares a wire recording with the committed baseline, test class by test class.
#
#   ./mvnw test -Dtest=<broker tests> -Drahdar.wire.record=/path/to/run
#   scripts/wire-compare.sh /path/to/run
#
# A recording is the set of distinct, normalised requests each test class made (WireRecorder). The
# report lists the request shapes the run made that the baseline never saw ("new") and the ones the
# baseline holds that the run did not make ("missing"). Scrape ticks and sampled consumers depend on
# timing, so a missing line can be a run that ended before a tick: read each one before calling it a
# change. Exits 1 when anything is new.
set -euo pipefail

run=${1:?usage: scripts/wire-compare.sh <recording dir> [baseline dir]}
baseline=${2:-src/test/resources/wire/baseline}
new=0
for file in "$run"/*.txt; do
  name=$(basename "$file")
  base="$baseline/$name"
  if [ ! -f "$base" ]; then
    echo "== $name: no baseline"
    continue
  fi
  added=$(LC_ALL=C comm -13 <(LC_ALL=C sort -u "$base") <(LC_ALL=C sort -u "$file"))
  removed=$(LC_ALL=C comm -23 <(LC_ALL=C sort -u "$base") <(LC_ALL=C sort -u "$file"))
  if [ -n "$added" ] || [ -n "$removed" ]; then
    echo "== $name"
    [ -n "$added" ] && printf '%s\n' "$added" | sed 's/^/  new:     /' && new=1
    [ -n "$removed" ] && printf '%s\n' "$removed" | sed 's/^/  missing: /'
  fi
done
exit $new
