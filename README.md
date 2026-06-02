# Private Location Sharing app

## I dont have time for this

If you aren't developer and/or just want to get an apk and use my server...
[You can do that here](https://location.dubba.pl), assuming it's up (At time of writing global map
is fetched, but no guarantees it's up to date map!).

## What it is

Self-hosted, on-demand, end-to-end-encrypted GPS sharing.

```
[Android phone]  --UDP+AES-GCM-->  [Go relay]  --WebSocket-->  [Browser]
                 (sender, owns                                  (viewer,
                  inner key)                                     has URL
                                                                 with key)
```

The relay never sees plaintext positions - inner AES-256-GCM key is in the URL
fragment, which browsers don't send to servers. The Android sender mints the
key per share session and pastes a URL like:

```
https://your-server/?id=<accessKey>#<encKey>
```

into a "current link" field you can copy and send to a friend.

---

## What you need for a setup

- **Go 1.21+** (for building the relay server) - `https://go.dev/dl`
- **go-pmtiles** (downloads the map data) - installed via Go
- **Android Studio** *or* `gradlew` CLI, plus a phone you can `adb install` to.
  Docker works too if you'd rather not install the Android SDK.
- **A box the phone can reach.** Your LAN desktop is fine for test; a VPS with TLS in
  front of the HTTP port is the proper deployment. The phone is pointed at the
  box by hostname or raw IP - neither is special. On a real deployment the
  Android sender talks to UDP `8050` directly, but the HTTP port (`8049`)
  stays bound to localhost / behind the firewall and is reached only via your
  TLS reverse proxy on `:443`.

That's it. No Docker (unless you opt in), no Kubernetes, no analytics, no
third-party tile server at runtime.

---

## Build & deploy - the proper flow

You build everything on a workstation, then ship a single Go binary + a `.env`
+ the PMTiles map to whatever box will host the server. Map data is by far the
biggest piece, so it's worth pulling that step out into "download once on the
server" if uploading 50 MB - 120 GB over your home upload is not your idea of
fun.

### 1. Clone

```bash
git clone https://github.com/DubbaThony/location-share.git share
cd share
```

> **Want one command?** `./dockerized_build.sh` runs steps 2-4 in one shot
> (generates the keystore on first run, builds the APK, refreshes the embed,
> compiles the Go binary) and drops everything under `./out/` ready to scp.
> Steps 5-7 (map + .env + deploy) still happen on the server. Requires
> `docker` and `go` on the build host. The rest of this section spells out
> the same steps manually for when you want to mix-and-match.

### 2. Build the release APK

The Android sender mints the per-session inner-encryption key, so the APK
needs to be signed with a keystore you own - debug keys aren't upgrade-stable,
and you'll want to install over the same key forever.

> **Customize defaults for your fork**: create `client/Android.env`
> (gitignored). Gradle merges it per-KEY over `client/Android.env.defaults`
> (tracked schema). Copy the whole defaults file and edit, or just write
> the few KEYs you care about - both work.

Easiest path is the Docker flow (no host JDK / SDK install needed) - I build like so:

```bash
# One-time: generate the release keystore. Back this up - lose it and you
# can never push a signed upgrade to the same install.
mkdir -p ./keystore
printf '%s' 'YOUR-STORE-PASS' > ./keystore/storepass.txt
chmod 600 ./keystore/storepass.txt
docker build -f client/Android/docker/Dockerfile.keygen \
  --build-arg KEY_ALIAS=share \
  --build-arg DNAME='CN=DubbaThony Share, O=DubbaThony, C=PL' \
  --secret id=storepass,src=./keystore/storepass.txt \
  --output=./keystore \
  client/Android/docker

# Release APK → ./out/share-release.apk
# Build context is client/ (not client/Android/) so the build can see
# client/Android.env.sample alongside the Android module. See the Dockerfile
# header for the reasoning.
docker build -f client/Android/docker/Dockerfile --target=binaries-release \
  --secret id=keystore,src=./keystore/release.jks \
  --secret id=keystore_pass,src=./keystore/storepass.txt \
  --output=./out \
  client/
```

If you'd rather build natively (host JDK + Android SDK):

```bash
cd client/Android
./gradlew assembleRelease   # signs with your local debug key by default;
                            # configure signingConfig for a real release key.
```

Either way, the APK you want to publish ends up named `share-release.apk`.

> Don't run more than one `docker build` in parallel - they'd race on the
> shared Gradle cache and corrupt the build.

### 3. Bundle the frontend + APK into the server's embed dir

The Go binary embeds everything under `server/static/` at compile time -
viewer HTML/JS, landing page, **and the APK that the landing page's `Get .APK`
button serves**. The single command refreshes all of it:

```bash
# From repo root:
rm -r server/static && cp -rp web/static server/static
cp ./out/share-release.apk server/static/pl.dubba.share.apk
```

The APK filename is hard-coded to `pl.dubba.share.apk` so debug builds (which
ship as `pl.dubba.share.dev`) can never overwrite the release download.

### 4. Build the Go binary

```bash
cd server
go build -o share-server .
```

That's the only thing you ship to the server box. Frontend, APK, and viewer
code are baked into it via `embed.FS`.

### 5. Get a map

`go-pmtiles` is the tool that pulls map tiles from Protomaps' planet build.
(thank you [Protomaps](https://protomaps.com/) <3)

```bash
go install github.com/protomaps/go-pmtiles@latest
# Make sure $(go env GOPATH)/bin is on PATH - usually $HOME/go/bin.
```

Then pick a region:

```bash
cd map
./bin/download-map.sh list       # see all available regions
./bin/download-map.sh poland     # by name, substring match works
./bin/download-map.sh europe     # bigger
./bin/download-map.sh krakow     # tiny, ~50 MB - good for first test
```

The file lands at `map/map.pmtiles`. Sizes range: `krakow` ~50 MB,
`poland` a few GB, `europe` tens of GB, `world` ~120 GB (script will ask "are
you sure?" for that one). If your bbox isn't in the list, pass raw coords:
`./bin/download-map.sh "minLon,minLat,maxLon,maxLat"`.

> **Continent scale?** Pass `MAP_RESILIENT=1` (or `-e MAP_RESILIENT=1` on
> `docker run`). go-pmtiles' default packs the whole job into a single HTTP/2
> stream that stays open for hours and dies on any transient peer error.
> Resilient mode flips on `--overfetch=0` (many small requests) plus parallel
> threads, so each individual request is small and short-lived. No
> retry-from-failure - that's upstream's gap to close.

> **World scale?** The `world` keyword bypasses go-pmtiles entirely and
> downloads the planet PMTiles file directly with `wget -c`. Resume works
> for free - if the stream dies mid-download, rerun the same command and
> it picks up at the byte offset of the partial file. The script also
> prints the exact `wget -c` command so you can resume manually outside
> the container if you'd rather. `MAP_RESILIENT` has no effect for `world`.

> **Docker alternative** if you don't want Go on the host:
> ```bash
> docker build -t share-mapdl ./map/bin
> docker run --rm -it -v "$(pwd)/map:/output" share-mapdl poland
> # Continent / world:
> docker run --rm -it -e MAP_RESILIENT=1 -v "$(pwd)/map:/output" share-mapdl world
> ```

You can run this step either on the workstation (and upload the `.pmtiles`
later) or directly on the server box. Doing it on the server is usually saner
for anything larger than `krakow` - saves a long upload over a residential
link.

### 6. Configure with a `.env`

`server/.env.sample` is the template:

```
UDP_PORT=8050
API_LISTEN=0.0.0.0:8049
LOG_LEVEL=debug
TRACES_IN_LOGS=false
MAP_LOCATION=./map
ADMIN_CONTACT=somebody@example.com
METERED_SOCKET=true
```

Copy it next to the binary as `.env` and edit:

- **`UDP_PORT`** - the port the Android sender talks to. Default `8050`.
- **`API_LISTEN`** - `<bind-ip>:<port>` for HTTP. Default listens on all
  interfaces, port `8049`. Put a TLS reverse proxy in front for real
  deployments (see below).
- **`LOG_LEVEL`** - `trace` / `debug` / `info` / `warn` / `error` / `fatal` /
  `silent`. `trace` will dump per-frame hex; useful for debugging, noisy in
  prod.
- **`TRACES_IN_LOGS`** - file:line caller info on every log line. Off in prod.
- **`MAP_LOCATION`** - directory that holds `map.pmtiles`. Relative to the
  binary's CWD.
- **`ADMIN_CONTACT`** - email address shown in the privacy policy (web
  page + in-app About screen, fetched from `/gpdr-email` at runtime).
  Technically required by GDPR.
- **`METERED_SOCKET`** - when `true`, wraps the UDP socket in a metering
  layer that logs average rx/tx rates periodically (info-level when there's
  traffic, debug-level when idle). Negligible overhead, useful for "is
  anything actually flowing right now" eyeballing. Set `false` to disable.

### 7. Deploy

Ship the binary, the `.env`, and the map data to your server. Layout:

```
/wherever/you/want/
├── share-server
├── .env
└── map/
    └── map.pmtiles
```

Run it under whatever supervisor you like (`systemd`, `tmux`, `screen`...).
Two ports matter (default values, you can change in .env):

- **UDP `8050`** - the sender protocol. Open this to the public internet
  (port-forward / firewall rule) so the phone can reach it.
- **TCP `8049`** - viewer page, PMTiles, `/identity`, APK download. On a real
  deployment this stays **behind the firewall** - bind it to `127.0.0.1` in
  `.env` (or leave it on `0.0.0.0` and drop it at the firewall) and put a TLS
  reverse proxy on `:443` in front of it. The public-facing surface is
  `https://your-domain/`, never `http://your-server:8049/` directly.

For a LAN-only test you can skip the reverse proxy and let the phone talk to
`http://<lan-ip>:8049/` directly - fine for "does the wire actually work" or
for VPN LAN setups, not for anything you'd share with someone outside the network.

### 8. Install the Android app

The release APK you built in step 2 is already on the server (you bundled it
into the embed). Pull it from `https://<your-server>/static/pl.dubba.share.apk`
on the phone's browser and install - or `adb install` it locally:

```bash
adb install -r out/share-release.apk
```

You should see "Success" from `adb`. The app appears as **Share location** on
the launcher.

> If you only want to iterate locally, `./gradlew installDebug` from
> `client/Android/` installs the debug variant (which coexists with release as
> `pl.dubba.share.dev`).

### 9. Configure & share

1. Open **Share location** on the phone.
2. Tap the **gear icon** (bottom-right of the main screen).
3. Set:
   - **Server host** - your server's hostname or IP (e.g. `share.example.com`
     or `192.168.1.123`)
   - **Server port** - `8050` (or whatever you set `UDP_PORT` to)
   - **API address** - `https://share.example.com/` or `http://192.168.1.42:8049/`
     (note: for http without `s`, you need to enable it in advanced settings)
4. Back on the main screen, toggle **GPS** on. Grant the location permission
   (**must be Precise** - the app refuses to share approximate location).
5. Toggle **Server** on. Grant notification permission if asked.
6. Rotate the dials to pick how long to share (1 min - ∞) and how often to
   send position fixes (1/60s - 10/s).
7. Wait a few seconds for the **status pill** to go green. The "current link"
   field will populate with a URL like
   `https://share.example.com/?id=abc...#def...`
8. Tap the link to copy it. Paste into any browser on any device.

### 10. View

Open the link in any browser. You should see:

- A map of your downloaded region
- A red triangle marking the phone's position, rotated to direction of travel
- Speed / last-update / status in the top bar
- Bottom-right buttons: dark mode, follow-lock toggle, rotation toggle, zoom in/out

The lock toggle (default on) auto-recenters on every position update and
disables manual panning. Unlock to look around without the camera jumping back.

---

## Operating

### Stopping a share

Three ways, any one works:
- Toggle **Server** off on the phone
- Tap **Stop** on the foreground notification
- Let the share timer expire (whatever you set on the share-time dial)

The server sends a "publisher gone" message to every active viewer; they show
"share ended - reload for new" and stop reconnecting.

### Re-running after frontend or APK changes

The frontend and APK both live inside the Go binary's embed, so any change to
either means a fresh `server/static/`, a fresh `go build`, and a redeploy:

```bash
# From the repo root:
rm -r server/static && cp -rp web/static server/static
cp ./out/share-release.apk server/static/pl.dubba.share.apk
cd server && go build -o share-server .
# ...then xfer share-server to your box and restart it.
```

The `rm -r` nukes the whole embed dir, so the APK copy has to happen **after**
the frontend refresh - otherwise you'll wipe the APK you just put there.

### Updating / Re-downloading the map

```bash
./map/bin/download-map.sh <region>
```

Map data is served from `MAP_LOCATION` at runtime (NOT embedded), so updating
the `.pmtiles` on the server takes effect without a rebuild - copy the new
file into `map/`, that's it. Don't download into live file though as that
will break current web clients. Use `mv` instead.

### Exporting debug logs from the phone

Open the app → **Gear icon** → **Debug view** → **Export**. The system share
sheet lets you mail/save/Drive/Signal the rolled-up log file (~64 KB max).
Logs persist across app kills and survive a rotation when they exceed the cap.
If you can't export it in given moment, there is also button to pause logger, 
so you can capture issue without ex. specifying email address etc. in given moment.

---

## Public-internet deployment

If your server is on your LAN, you're done - the phone and any browser on the
same network reach `http://<lan-ip>:8049/` directly. For a server reachable
from the public internet you want:

- **UDP `8050` exposed.** Port-forward (home router) or firewall-allow (VPS)
  the sender port - Android talks to it directly, no proxy possible (it's a
  custom UDP protocol, not HTTP).
- **TCP `8049` NOT exposed.** Bind it to `127.0.0.1` in `.env`
  (`API_LISTEN=127.0.0.1:8049`) and/or block it at the firewall. The HTTP
  port carries the viewer page, PMTiles, `/identity`, and the APK download -
  none of which should be served over plaintext on a public address.
- **TLS reverse proxy on `:443`.** Apache2, nginx, Caddy, or whatever you like,
  with a real certificate (feel free to use let's encrypt), proxying to 
  `http://127.0.0.1:8049/`. Point the **API address** in the Android app
  at `https://your-domain/`. Remember that the config of proxy must support
  websockets.

Use TLS for anything you'd actually share publicly.

> The sender UDP port (`8050`) doesn't ride TLS - the protocol does its own
> ECDH + AES-GCM handshake. That's the whole point of the `/identity`
> endpoint: the phone fetches the server's Ed25519 public key over the TLS
> HTTPS port (authenticated by your real cert), then uses it to verify the
> UDP handshake signature.

## Server min specs
Assuming some minimal system, running nothing else but reverse proxy, ssh and relay
- 512M of memory
- amd64 1 core, anything at least as modern as Ivy Bridge (and not Atom-tier)
- 10G of disk for single-country map deployment
- 140G of disk for world map deployment

---

## Troubleshooting

**Phone says "GPS subscription is running with no fix" for a long time.**
You're indoors, under heavy foliage, or in a basement. Walk outside.

**Viewer LED blinks red, label says "invalid key - check your URL".**
URL got mangled (truncated, copy-paste split, browser auto-link removed the
fragment). Re-copy from the app - the fragment after `#` carries the
decryption key.

**Viewer shows yellow LED with "gps off" forever.**
Phone toggled GPS off but kept the Server connection up. Toggle GPS back on.

**Viewer shows yellow LED with "gps on, no fix" forever.**
Phone's GPS subsystem hasn't locked yet. Same situation as the first item.

**Viewer says "share ended - reload for new".**
Sender disconnected gracefully. Open a fresh URL after they restart the share.

**Viewer never connects (red LED, no progress).**
Server-side or network-side problem. Check `go run .` log on the server for
incoming `/bind/...` requests. If you don't see them, the browser couldn't
reach the server - firewall, port-forward, or wrong host in the URL.

**Phone log shows "server port unreachable - session unrecoverable".**
Server crashed/restarted while the sender was active. Toggle the **Server**
switch off then on to mint a new session.

**Map renders only water.**
Schema version mismatch between the downloaded PMTiles and the layer
definitions. Re-download the map; if it persists, file a bug.

**Map doesn't render at all (desktop only - phone works).**
WebGL is broken in your desktop browser. Usually a VM or software-only rendering.
Try the phone instead.

---

## Repo layout

```
share/
├── spec/proto.md             # UDP wire format spec (frozen)
├── server/                   # Go relay
│   ├── main.go               # routes, port wiring
│   ├── protocol/             # outer crypto, frame types, handler
│   ├── publisher/            # bytes-in → fan-out → WebSocket
│   ├── ifaces/, state/, util/
│   ├── static/               # FRONTEND EMBED - copied from web/static + release APK
│   └── .env.sample           # template for runtime config (UDP_PORT, API_LISTEN, etc.)
├── client/Android/           # Sender app
│   ├── app/                  # main module
│   └── protocol/             # shared crypto + marshalling with server
├── web/                      # Source-of-truth viewer frontend
│   └── static/               # HTML/CSS/JS - what gets copied to server/static
└── map/                      # Map data + downloader (not embedded; served at runtime)
    ├── bin/download-map.sh   # PMTiles downloader
    └── map.pmtiles           # downloaded artifact (gitignored)
```

Should this be four separate repositories? Probably. Is it? No. Move on.

Claude code was used in development.
