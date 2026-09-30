#!/usr/bin/env bash
# Points the local flavor at the local Supabase of drafft-backend (`supabase start` there first).
# Writes local.private.properties (gitignored) with its URL and publishable key, read over
# config/local.properties by app/build.gradle.kts. Prints nothing secret. Port of drafft's
# scripts/local-backend.sh (which writes Local.private.xcconfig).
#
#   scripts/local-backend.sh            # emulator: 10.0.2.2, the Mac seen from the emulator
#   scripts/local-backend.sh --device   # phone on the same Wi-Fi: this Mac's LAN address
#
# DRAFFT_BACKEND overrides where drafft-backend lives (default: next to this repository).
set -euo pipefail
cd "$(dirname "$0")/.."

backend=${DRAFFT_BACKEND:-../drafft-backend}
[ -d "$backend/supabase" ] || { echo "No drafft-backend at $backend (set DRAFFT_BACKEND)." >&2; exit 1; }

host=10.0.2.2
case "${1:-}" in
  "") ;;
  --device)
    host=$(ipconfig getifaddr en0 || true)
    [ -n "$host" ] || { echo "No Wi-Fi address on en0: connect the Mac to the phone's network." >&2; exit 1; }
    ;;
  *) echo "Usage: $0 [--device]" >&2; exit 64 ;;
esac

status=$(cd "$backend" && supabase status -o env 2>/dev/null) \
  || { echo "Local Supabase isn't running: cd $backend && supabase start" >&2; exit 1; }
value() { printf %s "$status" | grep -E "^$1=" | tail -n 1 | sed -E 's/^[^=]*=//; s/^"(.*)"$/\1/'; }

api_url=$(value API_URL)
key=$(value PUBLISHABLE_KEY)
[ -n "$api_url" ] && [ -n "$key" ] || { echo "supabase status gave no API_URL or PUBLISHABLE_KEY." >&2; exit 1; }
port=${api_url##*:}

out=local.private.properties
umask 077
cat > "$out" <<EOF
# Written by scripts/local-backend.sh. Machine-specific, never committed.
SUPABASE_URL=http://$host:$port
SUPABASE_PUBLISHABLE_KEY=$key
EOF
echo "Wrote $out: http://$host:$port. Build the local flavor: ./gradlew installLocalDebug"
