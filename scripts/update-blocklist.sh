#!/usr/bin/env bash
# Refreshes the blocklist bundled in the APK (the app also self-updates weekly at runtime).
# Source: StevenBlack/hosts unified list (adware + malware), MIT licensed.
set -euo pipefail
cd "$(dirname "$0")/.."
dest=app/src/main/assets/adblock/hosts.txt
tmp=$(mktemp)
curl -fsSL https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts -o "$tmp"
count=$(grep -c '^0\.0\.0\.0 ' "$tmp" || true)
if [ "$count" -lt 1000 ]; then echo "Refusing to update: only $count entries" >&2; exit 1; fi
mv "$tmp" "$dest"
curl -fsSL https://raw.githubusercontent.com/StevenBlack/hosts/master/license.txt \
  -o app/src/main/assets/adblock/LICENSE-StevenBlack-hosts.txt
echo "Updated $dest ($count entries)"
