# Self-contained PMTiles downloader for the share-location viewer.
#
# Multi-stage: golang:alpine builds go-pmtiles, then a slim alpine carries
# the binary + the region-resolving bash script. Final image is ~50 MB. No
# Go toolchain leaks into the runtime layer - once built, run on a box with
# nothing but Docker installed.
#
# Build (from repo root):
#     docker build -t share-mapdl ./map/bin
#
# Use (writes to ./map/map.pmtiles on the host):
#     docker run --rm -it \
#         -v "$(pwd)/map:/output" \
#         share-mapdl poland
#
# Same arg semantics as the bare script:
#     docker run --rm share-mapdl list           # show region catalog
#     docker run --rm -it -v "$(pwd)/map:/output" share-mapdl europe
#     docker run --rm -it -v "$(pwd)/map:/output" share-mapdl "19.7,49.95,20.15,50.15"
#
# Big extract (continent / world)? Pass -e MAP_RESILIENT=1 to enable
# small-request mode (--overfetch=0 + parallel threads). See script header.
#     docker run --rm -it -e MAP_RESILIENT=1 -v "$(pwd)/map:/output" share-mapdl world

FROM golang:1.25-alpine AS builder
RUN go install github.com/protomaps/go-pmtiles@latest

FROM alpine:3.20
RUN apk add --no-cache bash curl jq ca-certificates
COPY --from=builder /go/bin/go-pmtiles /usr/local/bin/go-pmtiles

# Script writes to $OUTPUT_DIR; the bind mount target lives at /output.
# Caller's `-v host:/output` carries the file back out.
ENV OUTPUT_DIR=/output
COPY download-map.sh /usr/local/bin/download-map.sh
RUN chmod +x /usr/local/bin/download-map.sh

ENTRYPOINT ["/usr/local/bin/download-map.sh"]
