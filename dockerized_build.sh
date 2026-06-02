#!/usr/bin/env bash
# One-shot build that produces everything you need to ship a working server.
#
# Typical first run on a fresh checkout:
#     git clone <repo> share && cd share
#     ./dockerized_build.sh
#     scp -r out/* me@myserver:~/loc-share/
#     ssh me@myserver
#         cd ~/loc-share && cp .env.sample .env && nano .env
#         # download the map (one-time, on the server):
#         docker build -t share-mapdl ./map/bin   # if you haven't already
#         docker run --rm -it -v "$(pwd)/map:/output" share-mapdl world
#         ./share-server
#
# Required on the build host: docker, go (1.21+).
# Outputs in ./out/:
#     share-server        - Linux/amd64 static binary (no glibc dep)
#     share-release.apk   - signed release APK
#     .env.sample         - copy to .env on the server and edit
#
# Keystore is created on first run if ./keystore/release.jks is missing -
# you'll be prompted for a password (kept in ./keystore/storepass.txt,
# mode 600). Set KEYSTORE_PASS env var to skip the prompt for CI / unattended.
# Back up ./keystore/ - lose it and you can never push a signed upgrade
# to the same install.
#
# Override the binary's target with GOOS / GOARCH env vars if you're not
# shipping to linux/amd64. Default is linux/amd64 since that's most VPS.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$REPO_ROOT/out"
KEYSTORE_DIR="$REPO_ROOT/keystore"
GOOS="${GOOS:-linux}"
GOARCH="${GOARCH:-amd64}"

require_cmd() {
    command -v "$1" >/dev/null || {
        echo "Required tool not on PATH: $1" >&2
        echo "Install it and re-run." >&2
        exit 1
    }
}
require_cmd docker
require_cmd go

mkdir -p "$OUT"

# --- Keystore (one-time) ----------------------------------------------------

if [[ ! -f "$KEYSTORE_DIR/release.jks" ]]; then
    echo "==> No release keystore at $KEYSTORE_DIR/release.jks; generating one."
    mkdir -p "$KEYSTORE_DIR"

    if [[ -z "${KEYSTORE_PASS:-}" ]]; then
        if [[ ! -t 0 ]]; then
            echo "Non-interactive shell and KEYSTORE_PASS is not set." >&2
            echo "Either run this interactively, or export KEYSTORE_PASS=... first." >&2
            exit 1
        fi
        read -rsp "Choose a keystore password (saved to $KEYSTORE_DIR/storepass.txt): " KEYSTORE_PASS
        echo
        if [[ -z "$KEYSTORE_PASS" ]]; then
            echo "Empty password rejected." >&2
            exit 1
        fi
    fi
    printf '%s' "$KEYSTORE_PASS" > "$KEYSTORE_DIR/storepass.txt"
    chmod 600 "$KEYSTORE_DIR/storepass.txt"

    docker build -f "$REPO_ROOT/client/Android/docker/Dockerfile.keygen" \
        --build-arg KEY_ALIAS=share \
        --build-arg DNAME='CN=DubbaThony Share, O=DubbaThony, C=PL' \
        --secret id=storepass,src="$KEYSTORE_DIR/storepass.txt" \
        --output="$KEYSTORE_DIR" \
        "$REPO_ROOT/client/Android/docker"

    echo "==> Keystore created at $KEYSTORE_DIR/release.jks. BACK IT UP."
fi

# --- APK --------------------------------------------------------------------

echo "==> Building release APK (Docker)..."
# Build context is client/ (not client/Android/) so the Docker build can
# pull in client/Android.env.sample alongside the Android module. See the
# Dockerfile header for the full reasoning.
docker build -f "$REPO_ROOT/client/Android/docker/Dockerfile" --target=binaries-release \
    --secret id=keystore,src="$KEYSTORE_DIR/release.jks" \
    --secret id=keystore_pass,src="$KEYSTORE_DIR/storepass.txt" \
    --output="$OUT" \
    "$REPO_ROOT/client"

# --- Bundle frontend + APK into server's embed dir --------------------------

echo "==> Refreshing server/static embed..."
rm -rf "$REPO_ROOT/server/static"
cp -rp "$REPO_ROOT/web/static" "$REPO_ROOT/server/static"
cp "$OUT/share-release.apk" "$REPO_ROOT/server/static/pl.dubba.share.apk"

# --- Build the Go binary ----------------------------------------------------

echo "==> Building server binary ($GOOS/$GOARCH)..."
(
    cd "$REPO_ROOT/server"
    CGO_ENABLED=0 GOOS="$GOOS" GOARCH="$GOARCH" \
        go build -trimpath -ldflags='-s -w' -o "$OUT/share-server" .
)

# --- .env.sample ------------------------------------------------------------

cp "$REPO_ROOT/server/.env.sample" "$OUT/.env.sample"

# --- Summary ----------------------------------------------------------------

echo
echo "==> Build complete. Artifacts in $OUT:"
ls -lh "$OUT"
echo
echo "Ship it:"
echo "    scp -r $OUT/* user@host:~/loc-share/"
echo "    ssh user@host"
echo "        cd ~/loc-share && cp .env.sample .env   # edit before running"
echo "        # one-time: get a map on the server (much faster than uploading)"
echo "        docker build -t share-mapdl /path/to/repo/map/bin"
echo "        docker run --rm -it -v \"\$(pwd)/map:/output\" share-mapdl world"
echo "        ./share-server"
