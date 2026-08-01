#!/usr/bin/env bash
#
# reap-stale-herdr.sh — one-time cleanup of orphaned herdr-watch frame loops
# on remote hosts.
#
# herdr-watch used to leak a `frame() { ... herdr workspace list ... }` loop on
# the REMOTE host on every ssh reconnect (network drop / remote sleep): the loop
# had no way to notice its client was gone, so it kept spinning. The code fix
# (self-terminating `while read -t D`) stops NEW accumulation — this script
# drains whatever already piled up.
#
# Usage: scripts/reap-stale-herdr.sh <host> [host ...]
#   e.g. scripts/reap-stale-herdr.sh dqa1 dqa2
#
# Matches both legacy and current loops via the `herdr workspace list` substring.
# The `[w]` bracket-trick keeps this script's OWN pgrep/ssh command line from
# matching itself. It may also transiently match a child `herdr workspace list`
# query — harmless (short-lived). Manual/explicit on purpose: review before use.
#
set -euo pipefail

if [ "$#" -eq 0 ]; then
    echo "usage: $0 <host> [host ...]" >&2
    exit 1
fi

# Runs on the REMOTE host (single-quoted: expanded there, not locally).
remote='
  pat="herdr [w]orkspace list"
  before=$(pgrep -f "$pat" 2>/dev/null | grep -c . || true)
  pids=$(pgrep -f "$pat" 2>/dev/null || true)
  if [ -n "$pids" ]; then kill $pids 2>/dev/null || true; fi
  sleep 1
  after=$(pgrep -f "$pat" 2>/dev/null | grep -c . || true)
  printf "  matched %s, remaining %s\n" "$before" "$after"
'

for host in "$@"; do
    printf '== %s ==\n' "$host"
    ssh -o BatchMode=yes -o ConnectTimeout=10 "$host" "$remote" \
        || echo "  (ssh to $host failed)"
done
