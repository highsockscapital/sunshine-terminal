#!/usr/bin/env bash
# Sunshine guest provisioner — runs as root inside the Debian guest.
# Stdin protocol:
#   line 1: session token
#   line 2: bundle version (integer)
#   line 3: network mode (open|lockdown)
# Reads payload files from /tmp/sunshine-guest/bundle.json (keys are the
# filenames). Idempotent — safe to re-run on every boot.
set -u

WORK="/tmp/sunshine-guest"
TOKEN_RUN_DIR="/run/sunshine"
NFT_DST="/etc/nftables.d/sunshine.nft"

IFS= read -r TOKEN || { echo "provision: missing token" >&2; exit 2; }
IFS= read -r VERSION || VERSION="0"
IFS= read -r MODE || MODE="open"

[ -n "$TOKEN" ] || { echo "provision: empty token" >&2; exit 2; }
[ "$(id -u)" = "0" ] || { echo "provision: must run as root" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "provision: python3 required" >&2; exit 2; }

mkdir -p "$TOKEN_RUN_DIR"
if ! mountpoint -q "$TOKEN_RUN_DIR" 2>/dev/null; then
  mount -t tmpfs -o mode=700,size=1m tmpfs "$TOKEN_RUN_DIR" 2>/dev/null || true
fi

# --- 1. Install payload files from bundle.json ---
python3 - "$WORK/bundle.json" <<'PYEOF'
import json, sys, os
bundle_path = sys.argv[1]
with open(bundle_path) as f:
    bundle = json.load(f)
targets = {
    "sunshine-exec": ("/usr/local/bin/sunshine-exec", 0o755),
    "sunshine-vsock-agent.py": ("/usr/local/bin/sunshine-vsock-agent.py", 0o755),
    "sunshine-vsock-agent.service": ("/etc/systemd/system/sunshine-vsock-agent.service", 0o644),
    "sunshine-agent.slice": ("/etc/systemd/system/sunshine-agent.slice", 0o644),
    "nftables-sunshine.nft": ("/etc/nftables.d/sunshine.nft", 0o644),
}
os.makedirs("/etc/nftables.d", exist_ok=True)
for name, (dst, mode) in targets.items():
    if name not in bundle:
        print(f"provision: bundle missing {name}", file=sys.stderr)
        sys.exit(3)
    with open(dst, "w") as f:
        f.write(bundle[name])
    os.chmod(dst, mode)
PYEOF

# --- 2. Session token into tmpfs (0600) ---
printf '%s' "$TOKEN" > "$TOKEN_RUN_DIR/session-token"
chmod 600 "$TOKEN_RUN_DIR/session-token"

# --- 3. Slice activation + vsock agent ---
if command -v systemctl >/dev/null 2>&1; then
  systemctl daemon-reload 2>/dev/null || true
  # Vsock exec agent: host <-> guest over kernel buffers, no sshd needed
  # for steady-state exec once this unit is up.
  systemctl enable --now sunshine-vsock-agent.service 2>/dev/null || \
    systemctl restart sunshine-vsock-agent.service 2>/dev/null || true
fi

# --- 4. Firewall ---
if command -v nft >/dev/null 2>&1; then
  if [ "$MODE" = "lockdown" ]; then
    if ! grep -q "sunshine-drop-out" "$NFT_DST" 2>/dev/null; then
    cat >> "$NFT_DST" <<'NFTEOF'

table inet sunshine {
  chain output {
    type filter hook output priority 0; policy drop;
    ct state established,related accept
    oif "lo" accept
    # Lockdown: extend with explicit allow rules, e.g.
    # tcp dport { 80, 443 } accept
    log prefix "sunshine-drop-out: " limit rate 5/minute
  }
}
NFTEOF
    fi
  fi
  if grep -q "include \"$NFT_DST\"" /etc/nftables.conf 2>/dev/null; then
    : # already included
  else
    echo "include \"$NFT_DST\"" >> /etc/nftables.conf 2>/dev/null || true
  fi
  nft -c -f /etc/nftables.conf 2>/dev/null && nft -f /etc/nftables.conf 2>/dev/null || \
    echo "provision: nftables apply skipped (check syntax)" >&2
else
  echo "provision: nft not present, firewall skipped" >&2
fi

# --- 5. Record version ---
echo "$VERSION" > "$TOKEN_RUN_DIR/bundle-version"
echo "provision: sunshine guest bundle v$VERSION active (mode=$MODE)"
