#!/usr/bin/env bash
# Generates a trips CSV on stdout: city,distance,price
#   ./scripts/gen-trips.sh [rows] [sorted|shuffled] > trips_big.csv
# distance runs 1..rows, so sorted input gives partitions with tight disjoint
# min/max ranges and shuffled input gives partitions that each span everything.
set -euo pipefail

rows=${1:-5000}
order=${2:-sorted}

generate() {
  awk -v rows="$rows" 'BEGIN {
    split("Copenhagen Aarhus Odense Aalborg Esbjerg Roskilde", city, " ")
    srand(7)
    for (i = 1; i <= rows; i++)
      printf "%s,%d,%.2f\n", city[int(rand() * 6) + 1], i, i * 1.5
  }'
}

case "$order" in
  sorted)   generate ;;
  shuffled) generate | awk 'BEGIN { srand(7) } { printf "%.9f\t%s\n", rand(), $0 }' \
                      | sort -k1,1 | cut -f2- ;;
  *) echo "usage: $0 [rows] [sorted|shuffled]" >&2; exit 2 ;;
esac
