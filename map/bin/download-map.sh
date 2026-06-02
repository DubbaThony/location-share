#!/usr/bin/env bash
# Downloads PMTiles vector map data for the share-location viewer.
# Source: Protomaps planet (https://build.protomaps.com), range-extracted to
# bbox by go-pmtiles. No tile-server, no preprocessing, served straight off the
# filesystem to the browser via HTTP range requests.
#
# Usage:
#   bin/download-map.sh list           # show available named regions
#   bin/download-map.sh <region>       # download by name (substring match ok)
#   bin/download-map.sh "<bbox>"       # raw "minLon,minLat,maxLon,maxLat"
#
# Output: ./map.pmtiles (relative to map/ root)
# Requires: go-pmtiles (preferred) OR docker.
#
# Optional resilience knobs for big extracts (continents / world).
# go-pmtiles by default packs the whole job into one mega HTTP/2 stream that
# stays open for hours and dies on any transient peer error (see
# protomaps/go-pmtiles#225). Off by default - country-scale extracts complete
# in one shot under defaults.
#   MAP_RESILIENT=1            enables --overfetch=0 (many small requests
#                              instead of one mega-stream) + parallel threads.
#                              Shrinks each request's failure window; doesn't
#                              add retry-from-failure (that's upstream's job).
#   MAP_DOWNLOAD_THREADS=N     parallel request count when resilient mode is
#                              on. Default 8. go-pmtiles default is 4.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAP_ROOT="$(cd "$SCRIPT_DIR/.." 2>/dev/null && pwd || echo "$SCRIPT_DIR")"
# Env override lets the docker image set this to /output while keeping the
# native-host default sensible. See map/bin/Dockerfile for the docker path.
OUTPUT_DIR="${OUTPUT_DIR:-$MAP_ROOT}"
OUTPUT_FILE="$OUTPUT_DIR/map.pmtiles"
BUILDS_JSON="https://build-metadata.protomaps.dev/builds.json"
BUILD_BASE_URL="https://build.protomaps.com"

# --- Named region bboxes ----------------------------------------------------
# Format: name|minLon,minLat,maxLon,maxLat
# Country bboxes come from rough OSM/Natural-Earth extents — good enough for
# a viewer map, since PMTiles fetches the actual coverage internally.
# To add more: drop a line below. No autocomplete; substring search picks
# the first match (or lists if ambiguous).
REGIONS=(
    # Tiny test
    "krakow|19.7,49.95,20.15,50.15"

    # Continents
    "europe|-25,34,45,72"
    "asia|26,-11,180,82"
    "africa|-20,-36,52,38"
    "north-america|-170,7,-50,84"
    "south-america|-82,-56,-34,13"
    "oceania|110,-50,180,0"
    "antarctica|-180,-90,180,-60"

    # Countries — Europe
    "poland|14,49,24.2,54.9"
    "germany|5.8,47.2,15.1,55.1"
    "france|-5,41,9.6,51.1"
    "spain|-9.4,35.9,3.4,43.8"
    "portugal|-9.6,36.9,-6.2,42.2"
    "italy|6.6,35.4,18.6,47.1"
    "uk|-8.7,49.8,1.8,60.9"
    "ireland|-10.6,51.4,-5.9,55.4"
    "netherlands|3.3,50.7,7.3,53.6"
    "belgium|2.5,49.5,6.4,51.5"
    "switzerland|5.9,45.8,10.5,47.8"
    "austria|9.5,46.4,17.2,49.0"
    "czechia|12.0,48.5,18.9,51.1"
    "slovakia|16.8,47.7,22.6,49.6"
    "hungary|16.1,45.7,22.9,48.6"
    "denmark|8.0,54.5,15.2,57.8"
    "sweden|11.0,55.3,24.2,69.1"
    "norway|4.6,57.9,31.3,71.2"
    "finland|20.5,59.7,31.6,70.1"
    "iceland|-24.6,63.3,-13.5,66.6"
    "ukraine|22.1,44.3,40.2,52.4"
    "romania|20.2,43.6,29.7,48.3"
    "bulgaria|22.3,41.2,28.6,44.2"
    "greece|19.3,34.8,28.3,41.8"
    "turkey|25.6,35.8,44.8,42.1"

    # Countries — other continents
    "usa|-125,24,-66,49.5"
    "canada|-141,41,-52,84"
    "mexico|-118,14,-86,33"
    "brazil|-74,-34,-34,5.3"
    "argentina|-74,-55,-53,-21.8"
    "japan|122,24,146,46"
    "china|73,18,135,54"
    "india|68,6,98,36"
    "russia|19,41,180,82"
    "australia|112,-44,154,-10"
    "new-zealand|166,-47,179,-34"
    "south-africa|16,-35,33,-22"

    # Planet
    "world|-180,-90,180,90"
)

usage() {
    cat <<EOF
Usage:
  $(basename "$0") list                 - show available named regions
  $(basename "$0") <region-name>        - download by name (substring match)
  $(basename "$0") "<bbox>"             - raw "minLon,minLat,maxLon,maxLat"

Examples:
  $(basename "$0") poland
  $(basename "$0") europe
  $(basename "$0") "19.7,49.95,20.15,50.15"
EOF
}

list_regions() {
    printf "%-18s  %s\n" "NAME" "BBOX (minLon,minLat,maxLon,maxLat)"
    printf "%-18s  %s\n" "----" "----"
    for entry in "${REGIONS[@]}"; do
        local name="${entry%%|*}"
        local bbox="${entry#*|}"
        printf "%-18s  %s\n" "$name" "$bbox"
    done
}

# Returns bbox for a region name. Tries exact match first, then unique
# substring match. Multiple matches -> list and bail.
resolve_region() {
    local query="$1"
    local lower_query
    lower_query="$(echo "$query" | tr '[:upper:]' '[:lower:]')"

    # Exact
    for entry in "${REGIONS[@]}"; do
        local name="${entry%%|*}"
        if [[ "$name" == "$lower_query" ]]; then
            echo "${entry#*|}"
            return 0
        fi
    done

    # Substring
    local matches=()
    for entry in "${REGIONS[@]}"; do
        local name="${entry%%|*}"
        if [[ "$name" == *"$lower_query"* ]]; then
            matches+=("$entry")
        fi
    done

    if [[ ${#matches[@]} -eq 0 ]]; then
        echo "No region matching '$query'. Try: $0 list" >&2
        return 1
    fi
    if [[ ${#matches[@]} -gt 1 ]]; then
        echo "Ambiguous '$query', matches:" >&2
        for m in "${matches[@]}"; do
            echo "  ${m%%|*}" >&2
        done
        return 1
    fi
    echo "${matches[0]#*|}"
}

is_bbox() {
    [[ "$1" =~ ^-?[0-9]+(\.[0-9]+)?,-?[0-9]+(\.[0-9]+)?,-?[0-9]+(\.[0-9]+)?,-?[0-9]+(\.[0-9]+)?$ ]]
}

confirm_world() {
    # The planet PMTiles is ~120 GB at z15. Warn the user and require a
    # y to proceed — too easy to launch by mistake otherwise.
    cat <<EOF >&2
WARNING: Downloading the entire world at full zoom (~120 GB).
   The Protomaps planet PMTiles is huge — only proceed if you have the disk
   and bandwidth. To get a smaller world, edit this script and pass
   --maxzoom=10 (or 12) to go-pmtiles further down (search 'maxzoom').
EOF
    read -p "Continue? [y/N] " ans
    [[ "$ans" =~ ^[Yy]$ ]] || { echo "Aborted." >&2; exit 1; }
}

get_latest_build() {
    curl -sf "$BUILDS_JSON" | jq -r '.[-1].key'
}

ensure_tooling() {
    # Prefer native go-pmtiles. If absent, tell the user the exact install
    # command and exit — falling through to docker silently produces baffling
    # errors when docker is half-broken (containerd shim mismatch, etc.).
    if command -v go-pmtiles >/dev/null; then
        return 0
    fi

    cat <<EOF >&2
go-pmtiles not found on PATH.

Install it (recommended):
    go install github.com/protomaps/go-pmtiles@latest

This drops a binary at \$(go env GOPATH)/bin/go-pmtiles — make sure that
directory is on your PATH (\$HOME/go/bin by default), then re-run this script.

If you'd rather use docker, the image is protomaps/go-pmtiles:latest:
    docker run --rm -v "$OUTPUT_DIR":/output protomaps/go-pmtiles:latest \\
        extract <planet-url> /output/map.pmtiles --bbox=<bbox> --maxzoom=15
(Inline only — this script no longer attempts docker, since most failures
there are environment-specific and the error messages mislead.)
EOF
    return 1
}

run_world_direct() {
    # World is a special case: extracting --bbox=-180,-90,180,90 from the
    # planet file is identity (you'd get back the same bytes), and the extract
    # path collapses to one mega HTTP/2 stream with no resume. So we skip
    # go-pmtiles entirely and fetch the planet PMTiles directly with
    # `wget -c` - HTTP Range gives us real resume across runs. If it dies,
    # rerun this script (or the printed wget command) and it picks up at the
    # byte offset of the partial file.
    local latest_build
    latest_build="$(get_latest_build)"
    if [[ -z "$latest_build" ]]; then
        echo "Could not determine latest Protomaps build (network down? jq missing?)" >&2
        return 1
    fi
    local planet_url="${BUILD_BASE_URL}/${latest_build}"

    echo "Source: $planet_url"
    echo "Output: $OUTPUT_FILE"
    echo "Mode:   world direct download (bypasses go-pmtiles; resume works via wget -c)"
    echo
    echo "If this dies mid-stream, resume by re-running the script - or"
    echo "manually with:"
    echo "    wget -c -O '$OUTPUT_FILE' '$planet_url'"
    echo

    mkdir -p "$OUTPUT_DIR"
    wget -c -O "$OUTPUT_FILE" "$planet_url"
}

run_extract() {
    local bbox="$1"
    ensure_tooling || return 1

    local latest_build
    latest_build="$(get_latest_build)"
    if [[ -z "$latest_build" ]]; then
        echo "Could not determine latest Protomaps build (network down? jq missing?)" >&2
        return 1
    fi
    local planet_url="${BUILD_BASE_URL}/${latest_build}"

    echo "Source: $planet_url"
    echo "Bbox:   $bbox"
    echo "Output: $OUTPUT_FILE"
    mkdir -p "$OUTPUT_DIR"

    if [[ "${MAP_RESILIENT:-}" == "1" ]]; then
        local threads="${MAP_DOWNLOAD_THREADS:-8}"
        echo "Mode:   resilient (--overfetch=0, --download-threads=$threads)"
        echo
        go-pmtiles extract "$planet_url" "$OUTPUT_FILE" \
            --bbox="$bbox" --maxzoom=15 \
            --overfetch=0 --download-threads="$threads"
    else
        echo
        go-pmtiles extract "$planet_url" "$OUTPUT_FILE" --bbox="$bbox" --maxzoom=15
    fi
}

main() {
    if [[ $# -lt 1 ]]; then
        usage
        exit 1
    fi
    case "$1" in
        list|-h|--help|help) [[ "$1" == "list" ]] && list_regions || usage; exit 0 ;;
    esac

    local input="$1"
    local bbox

    if is_bbox "$input"; then
        bbox="$input"
    else
        bbox="$(resolve_region "$input")" || exit 1
        if [[ "${input,,}" == "world" ]]; then
            confirm_world
            # World bypasses go-pmtiles entirely - see run_world_direct for why.
            run_world_direct
            exit $?
        fi
    fi

    run_extract "$bbox"
}

main "$@"
