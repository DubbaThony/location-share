// Share location viewer.
//
// URL shape: <origin>/?id=<accessKey>#<encKey>
//   - accessKey: 32-char ASCII string issued by the server during handshake.
//                In the query string (not the fragment) so swapping URLs
//                triggers a real page reload — fragment-only changes are
//                treated as in-page navigation by browsers, which made
//                testing different shares miserable.
//   - encKey:    inner-e2ee AES-256-GCM key (32 bytes, hex) in the fragment so
//                it never reaches the server. Minted by the Android sender at
//                session start; used to decrypt every binary frame in this tab.
//
// The viewer opens a websocket to /bind/<accessKey>, decodes incoming binary
// frames as UTF-8 JSON ({"type":"position",...} or {"type":"meta",...}), and
// updates the map + status pill. Text frames are control messages from the
// server (currently just `publisher_gone`).
//
// Module structure intentionally puts WS init BEFORE map init. The Map
// constructor throws on systems with broken WebGL (e.g. VM with software
// renderer); if that runs first, the entire module fails and the data
// connection never opens. Reading position updates is the primary purpose
// of this page — should survive a render failure.

import maplibregl from 'https://esm.sh/maplibre-gl@4.7.1';
import { Protocol } from 'https://esm.sh/pmtiles@3.0.6';
import protomapsLayers from 'https://esm.sh/protomaps-themes-base@4.0.1';
// Pure-JS AES-GCM. We can't use the native `crypto.subtle` because the
// browser only exposes Web Crypto in *secure contexts* (https / localhost),
// and this app is served from a LAN IP over plain http — `crypto.subtle`
// is literally `undefined`. @noble/ciphers gives us the same primitive
// synchronously, ~100x slower, which at 1 fix/s with ~80B payloads is a
// non-issue.
import { gcm } from 'https://esm.sh/@noble/ciphers@1/aes';

// --- Constants ---------------------------------------------------------------

/** Accuracy threshold (meters) above which we render the translucent ring. */
const ACCURACY_RING_THRESHOLD_M = 25;

/** Auto-reconnect backoff bounds, in ms. */
const RECONNECT_MIN_MS = 1_000;
const RECONNECT_MAX_MS = 30_000;

/** PMTiles served by the Go server at this path; fetched via HTTP range. */
const PMTILES_PATH = '/map/map.pmtiles';

// --- DOM handles -------------------------------------------------------------

const elValSpeed     = document.getElementById('val-speed');
const elValUpdate    = document.getElementById('val-update');
const elValStatus    = document.getElementById('val-status');
const elValStatusNote = document.getElementById('val-status-note');
const elBtnRotate    = document.getElementById('btn-rotate');
const elBtnLock      = document.getElementById('btn-lock');
const elBtnStyle     = document.getElementById('btn-style');
const elBtnIn        = document.getElementById('btn-zoom-in');
const elBtnOut       = document.getElementById('btn-zoom-out');
const elError        = document.getElementById('error-overlay');
const elDebug        = document.getElementById('debug-overlay');
const elConnecting   = document.getElementById('connecting-overlay');

/** `?debug=1` opt-in. Adds a live state overlay readable on-device without devtools. */
const DEBUG_OVERLAY = new URLSearchParams(window.location.search).get('debug') === '1';

// --- Status / stats UI -------------------------------------------------------

// Status LED semantics (mapped to CSS `data-state`):
//   - off      → no WS connection to the server (initial, closed, reconnecting) → red
//   - pending  → WS is open but no position frames have arrived yet             → yellow
//   - ok       → at least one position frame has been received                  → green
//   - gone     → server told us the publisher disconnected gracefully           → red
//   - bad-key  → received a frame we can't decrypt (URL key mismatch)           → red, blinking. Latches until WS close.
const STATUS_OFF     = 'off';
const STATUS_PENDING = 'pending';
const STATUS_OK      = 'ok';
const STATUS_GONE    = 'gone';
const STATUS_BAD_KEY = 'bad-key';

function setStatus(state) {
    elValStatus.setAttribute('data-state', state);
    // Big "Connecting..." curtain is tied 1:1 to the OFF state: shown while we
    // have no live WS to the server (initial dial + every reconnect backoff
    // window), hidden the moment we transition to anything else. Other
    // not-OK states (gone / bad-key) have their own muted messaging in the
    // top bar; they shouldn't slap a red curtain over the map.
    if (elConnecting) elConnecting.hidden = (state !== STATUS_OFF);
}

/** Free-text supplemental status, shown muted to the right of the LED. */
function setStatusNote(text) {
    elValStatusNote.textContent = text || '';
}

function setSpeed(speedMps) {
    if (speedMps == null) { elValSpeed.textContent = '—'; return; }
    const kmh = speedMps * 3.6;
    elValSpeed.textContent = `${kmh.toFixed(1)} km/h`;
}

let lastUpdateTs = null;
function setLastUpdate(ts) { lastUpdateTs = ts; }

/**
 * Field-debug state container — populated as events fire, read by the
 * 500 ms overlay refresher below. Only matters when DEBUG_OVERLAY is on.
 */
const debugState = {
    lastCenterTs: null,
    lastCenterAction: '—',
    framesRx: 0,
    framesDecrypted: 0,
};

function refreshLastUpdate() {
    if (lastUpdateTs == null) { elValUpdate.textContent = '—'; return; }
    const ago = Math.round((Date.now() - lastUpdateTs) / 1000);
    if (ago < 60)        elValUpdate.textContent = `${ago}s ago`;
    else if (ago < 3600) elValUpdate.textContent = `${Math.floor(ago / 60)}m ago`;
    else                 elValUpdate.textContent = `${Math.floor(ago / 3600)}h ago`;
}
setInterval(refreshLastUpdate, 1_000);

function showError(msg) {
    elError.textContent = msg;
    elError.hidden = false;
}

// --- URL parse ---------------------------------------------------------------

/**
 * Returns {accessKey, encKey} from the URL, or null if the access key is
 * missing. Access key from ?id=..., encryption key (hex, 64 chars / 32 bytes)
 * from the fragment.
 */
function parseShareUrl() {
    const accessKey = new URLSearchParams(window.location.search).get('id');
    if (!accessKey) return null;
    const encKey = window.location.hash.replace(/^#/, '');
    return { accessKey, encKey };
}

// --- Inner-e2ee ---------------------------------------------------------------
//
// Sender encrypts every payload with AES-256-GCM, wire shape
// `nonce(12) || ciphertext || tag(16)`. Nonce layout matches the outer
// protocol: `3B zero || 1B frameType || 8B counter LE`, with frameType = 0x00
// for inner. Receiver reads nonce from the head of each frame — no counter
// tracking required.

/** Parse 64-char hex → 32-byte Uint8Array. Returns null on malformed input. */
function hexToBytes(hex) {
    if (typeof hex !== 'string' || hex.length % 2 !== 0) return null;
    const out = new Uint8Array(hex.length / 2);
    for (let i = 0; i < out.length; i++) {
        const b = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
        if (Number.isNaN(b)) return null;
        out[i] = b;
    }
    return out;
}

let innerKeyBytes = null;      // raw 32-byte key once parsed, null otherwise
let badKeyLatched  = false;    // sticky bad-key indicator until WS reconnects

/** Synchronously parse the hex key into 32 bytes; returns whether it worked. */
function loadInnerKey(hex) {
    const bytes = hexToBytes(hex);
    if (!bytes || bytes.length !== 32) {
        console.warn('[viewer] inner key in URL is not 32 bytes of hex; cannot decrypt');
        innerKeyBytes = null;
        return false;
    }
    innerKeyBytes = bytes;
    return true;
}

// --- Live position state (shared between WS path and map path) ---------------
//
// The map paints these whenever it can. If the map failed to initialize, the
// WebSocket still updates the values and the stats bar still reflects them.

let lastDirectionDeg = null;
let lastPositionLngLat = null;
let lastAccuracyM = null;

// Map-side handles, populated after successful map init. The WS callbacks
// guard on (map != null) before touching them so a WebGL failure can't break
// the data path.
let map = null;
let marker = null;
// Wraparound clones at +/-360 deg longitude. maplibre's DOM markers do NOT
// repeat across world copies (the GL layers do, via renderWorldCopies, but
// HTML elements don't). At low zoom we see multiple world copies, so without
// these the indicator only renders in one copy and the others go blank.
// Three positions (-360, 0, +360) covers the visible range up to zoom ~0,
// which is the practical floor for a vector map.
let markerWest = null;
let markerEast = null;
let positionAdded = false;

// --- WebSocket — primary data path. Must succeed even if map fails. --------
//
// State declarations MUST come before the call to `connect()` below — they're
// `let` bindings (not `var`/`function`), so reading them earlier in the
// module hits the temporal dead zone and throws.

let backoffMs = RECONNECT_MIN_MS;
let ws = null;
// Latched the moment the server tells us the publisher has gracefully gone.
// Disables reconnect — if we kept retrying, the WS would 404 in a loop until
// the user closes the tab. They need to open a new URL anyway (the access key
// is dead server-side), so we just stop and tell them.
let publisherGoneLatched = false;

function wsUrl(accessKey) {
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${scheme}//${window.location.host}/bind/${encodeURIComponent(accessKey)}`;
}

const share = parseShareUrl();
if (!share) {
    showError('No share key in URL — open a link like `…/?id=<key>#<encKey>`.');
    setStatus(STATUS_OFF);
    console.warn('[viewer] no ?id=... in URL — not dialing the server');
} else {
    console.log('[viewer] starting WS with accessKey=' + share.accessKey);
    // Parse the inner key synchronously, before opening the WS — that way
    // the very first frame can be decrypted; no startup race window.
    if (!loadInnerKey(share.encKey)) {
        console.warn('[viewer] no usable inner key — every binary frame will fail to decrypt');
    }
    connect(share.accessKey);
}

// Flips true the first time any WS instance opens. Sticky for the page
// lifetime — so a close-without-open at this page's very first attempt
// means the session probably doesn't exist (or never did), and we route
// to /static/expired.html instead of retrying forever.
let wsHasEverOpened = false;

function connect(accessKey) {
    // STATUS_OFF (red) until the WS actually opens — dialing isn't "connected
    // to the server" yet. Yellow waits for the open event.
    setStatus(STATUS_OFF);
    const url = wsUrl(accessKey);
    console.log('[viewer] WS dialing ' + url);
    try {
        ws = new WebSocket(url);
    } catch (e) {
        console.error('[viewer] WS constructor threw', e);
        scheduleReconnect(accessKey);
        return;
    }
    ws.binaryType = 'arraybuffer';

    ws.addEventListener('open', () => {
        console.log('[viewer] WS open');
        wsHasEverOpened = true;
        backoffMs = RECONNECT_MIN_MS;
        // Connected to the server, but no positions yet — yellow until the
        // first position frame flips us to green in onPosition().
        setStatus(STATUS_PENDING);
        setStatusNote('');   // any stale note from a previous session is moot
        // New connection → clear the latched bad-key flag so a successful
        // reconnect after a URL fix actually recovers visually.
        badKeyLatched = false;
    });

    ws.addEventListener('message', (ev) => {
        if (ev.data instanceof ArrayBuffer) {
            debugState.framesRx++;
            // Single chokepoint: every binary frame is encrypted. Decrypt,
            // then JSON-parse, then dispatch.
            const plain = decryptInnerFrame(ev.data);
            if (plain != null) {
                debugState.framesDecrypted++;
                handleJsonFrame(plain);
            }
            // null already triggered the bad-key state inside decryptInnerFrame.
        } else if (typeof ev.data === 'string') {
            handleControlFrame(ev.data);
        }
    });

    ws.addEventListener('close', (ev) => {
        console.log(`[viewer] WS close code=${ev.code} reason=${ev.reason || '(none)'}; reconnect in ${backoffMs}ms`);
        if (elValStatus.getAttribute('data-state') !== 'gone') {
            setStatus(STATUS_OFF);
        }
        setStatusNote('');   // sender state isn't meaningful while we're not connected
        // Close without ever having opened = bind endpoint rejected us, which
        // for this server means the access key doesn't map to a live session.
        // Route to expired.html — pure-cosmetic delay so the user sees the
        // OFF status flip before navigation.
        if (!wsHasEverOpened && !publisherGoneLatched) {
            console.log('[viewer] WS never opened — session expired or never existed, redirecting');
            setTimeout(() => { window.location.replace('/static/expired.html'); }, 1000);
            return;
        }
        scheduleReconnect(accessKey);
    });

    ws.addEventListener('error', (ev) => {
        // close fires after error; don't double-schedule. Logging only.
        console.warn('[viewer] WS error', ev);
    });
}

function scheduleReconnect(accessKey) {
    if (publisherGoneLatched) {
        console.log('[viewer] publisher gone — not reconnecting; reload the page to start a new session');
        return;
    }
    const delay = backoffMs;
    backoffMs = Math.min(backoffMs * 2, RECONNECT_MAX_MS);
    setTimeout(() => connect(accessKey), delay);
}

function decodeUtf8(buf) {
    return new TextDecoder('utf-8').decode(new Uint8Array(buf));
}

/**
 * Decrypts a single inner-e2ee frame. Returns the UTF-8 string on success,
 * null on any failure — and latches the bad-key indicator on the way out so
 * the UI calls out that the URL is wrong. The latch is sticky until the WS
 * reopens (we don't want the LED to flutter between blink/yellow per frame).
 *
 * Synchronous because @noble/ciphers' AES-GCM is synchronous. We can't use
 * `crypto.subtle` here — see the top-of-file import comment.
 */
function decryptInnerFrame(arrayBuffer) {
    if (badKeyLatched) return null;        // already flagged; skip the work
    if (!innerKeyBytes) {
        triggerBadKey('inner key not ready');
        return null;
    }
    const buf = new Uint8Array(arrayBuffer);
    if (buf.length < 12 + 16) {
        triggerBadKey('frame too short for nonce+tag');
        return null;
    }
    const nonce = buf.subarray(0, 12);
    const cipherTagged = buf.subarray(12);
    try {
        // gcm(key, nonce) returns an object with encrypt/decrypt; decrypt
        // throws on tag mismatch — caught below to flip the bad-key state.
        const plain = gcm(innerKeyBytes, nonce).decrypt(cipherTagged);
        return new TextDecoder('utf-8').decode(plain);
    } catch (e) {
        triggerBadKey('AES-GCM decrypt failed (tag mismatch / wrong key)');
        return null;
    }
}

function triggerBadKey(reason) {
    if (badKeyLatched) return;
    badKeyLatched = true;
    console.warn('[viewer] bad-key:', reason);
    setStatus(STATUS_BAD_KEY);
    setStatusNote('invalid key — check your URL');
}

function handleJsonFrame(text) {
    let obj;
    try { obj = JSON.parse(text); }
    catch (e) {
        console.warn('[viewer] non-JSON binary frame:', text);
        return;
    }
    // Per-frame trace so you can confirm in devtools whether frames are
    // arriving during e.g. background-tab throttling windows. Spammy but
    // cheap; turn the level down later if it gets noisy.
    console.debug('[viewer] rx', new Date().toISOString(), obj.type, obj);
    switch (obj.type) {
        case 'position': onPosition(obj); break;
        case 'meta':     onMeta(obj); break;
        default:         console.warn('[viewer] unknown payload type:', obj.type);
    }
}

function handleControlFrame(text) {
    let obj;
    try { obj = JSON.parse(text); }
    catch {
        console.warn('[viewer] non-JSON control frame:', text);
        return;
    }
    console.log('[viewer] control frame:', obj);
    if (obj.msg_type === 'publisher_gone') {
        setStatus(STATUS_GONE);
        setStatusNote('share ended — reload for new');
        publisherGoneLatched = true;
        // Brief delay so the user sees the "share ended" message in the bar
        // before the page navigates. 2 s feels intentional rather than abrupt.
        setTimeout(() => { window.location.replace('/static/expired.html'); }, 2000);
    }
}

function onPosition(payload) {
    const lon = payload.lon;
    const lat = payload.lat;
    if (typeof lon !== 'number' || typeof lat !== 'number') return;

    const speed = typeof payload.speed === 'number' ? payload.speed : null;
    const acc   = typeof payload.accuracy === 'number' ? payload.accuracy : null;
    const dir   = typeof payload.direction === 'number' ? payload.direction : null;

    setSpeed(speed);
    setLastUpdate(Date.now());
    setStatus(STATUS_OK);
    setStatusNote('');   // positions are flowing — nothing supplemental to say
    refreshLastUpdate();

    lastPositionLngLat = [lon, lat];
    lastAccuracyM = acc;
    if (dir != null) lastDirectionDeg = dir;

    if (map && marker) {
        setMarkersLngLat(lastPositionLngLat);
        applyMarkerRotation();
        if (!positionAdded) {
            addMarkersToMap();
            positionAdded = true;
            // First fix always centers + zooms regardless of lock state — we
            // need to put the user on the map at least once.
            const firstOpts = { center: lastPositionLngLat, zoom: 14 };
            if (rotationMode === ROTATION_FOLLOW && lastDirectionDeg != null) {
                firstOpts.bearing = lastDirectionDeg;
            }
            map.jumpTo(firstOpts);
            debugState.lastCenterTs = Date.now();
            debugState.lastCenterAction = 'jumpTo (first)';
        } else if (followLock) {
            // Subsequent fixes only auto-recenter while locked. When unlocked,
            // the marker still moves but the camera stays where the user put it.
            //
            // CRITICAL: when in ROTATION_FOLLOW we fold the bearing into THIS
            // easeTo call. MapLibre's easeTo replaces the in-flight animation
            // wholesale — a separate `map.easeTo({bearing})` would use the
            // current (interrupted) center as its target and effectively
            // cancel the center motion. So center+bearing must go together.
            const opts = { center: lastPositionLngLat, duration: 800 };
            if (rotationMode === ROTATION_FOLLOW && lastDirectionDeg != null) {
                opts.bearing = lastDirectionDeg;
            }
            map.easeTo(opts);
            debugState.lastCenterTs = Date.now();
            debugState.lastCenterAction = 'easeTo';
        } else if (rotationMode === ROTATION_FOLLOW && lastDirectionDeg != null) {
            // Lock off but rotation following — bearing still needs updating;
            // safe to issue alone since no center animation is in flight to fight.
            map.easeTo({ bearing: lastDirectionDeg, duration: 300 });
            debugState.lastCenterAction = 'SKIPPED (lock off); bearing only';
        } else {
            debugState.lastCenterAction = 'SKIPPED (lock off)';
        }
        ensureAccuracyLayer();
        refreshAccuracyRing();
    }
}

function onMeta(payload) {
    // Either meta value means "we're connected to the server but the sender
    // isn't producing positions right now" — yellow LED plus a muted note
    // explaining which sub-state of yellow it is. Red is reserved for
    // "no socket to the server at all."
    switch (payload.gps) {
        case 'acquiring':
            setStatus(STATUS_PENDING);
            setStatusNote('gps on, no fix');
            break;
        case 'off':
            setStatus(STATUS_PENDING);
            setStatusNote('gps off');
            break;
    }
}

// --- MapLibre style ----------------------------------------------------------
//
// Layer set comes from the official `protomaps-themes-base` package so the
// layer IDs / source-layer names / pmap:kind values track the schema version
// of the planet build. Hand-rolling those was the original cause of "only
// the water renders." We post-process the returned layers to override a few
// road `line-color` values for stronger highway/major-road contrast — the
// stock palette is intentionally muted, which makes road-hierarchy reading
// hard at a glance.

/**
 * Bumps the line-color of road layers to a higher-contrast palette while
 * preserving the rest of the protomaps-themes-base set. Matches by layer-ID
 * substring so this survives minor schema shuffles.
 *
 * Casings (the outer dark outline strokes) are left as-is; bumping just the
 * fills is the cheap way to get a strong visual hierarchy.
 */
function bumpRoadColors(layers, theme) {
    const palette = theme === 'dark' ? {
        highway: '#ff7a1a',
        major:   '#e0a020',
        medium:  '#9c8a40',
        // Minor roads on dark theme default to a low-contrast slate (~#3a3a3a)
        // which essentially disappears against the dark land. A medium gray
        // makes street grids legible without overpowering the colored hierarchy.
        minor:   '#7a7a7a',
    } : {
        highway: '#ff8c2e',
        major:   '#f0b820',
        medium:  '#c4ad42',
        // Light theme defaults minor to #ffffff — already at max readability.
        // Leaving undefined so the override is skipped and the theme value sticks.
    };

    return layers.map((l) => {
        if (l.type !== 'line' || !l.id) return l;
        if (l.id.includes('casing')) return l;     // outer outline — keep theme default
        if (!l.id.includes('roads')) return l;

        let color = null;
        if (l.id.includes('highway')) color = palette.highway;
        else if (l.id.includes('major')) color = palette.major;
        else if (l.id.includes('medium')) color = palette.medium;
        else if (l.id.includes('minor') && palette.minor) color = palette.minor;
        if (!color) return l;

        return {
            ...l,
            paint: { ...(l.paint || {}), 'line-color': color },
        };
    });
}

/**
 * Bumps the text-color of road label layers on dark theme. Same rationale as
 * [bumpRoadColors]: the stock palette is muted enough that street-name labels
 * disappear into the dark land at glance. Light theme is fine — the default
 * dark text on light backgrounds already reads.
 *
 * Matches symbol layers whose ID mentions "roads" so the layer set's exact
 * naming (`roads_labels_major`, `roads_labels_minor`, …) doesn't matter.
 */
function bumpRoadLabelContrast(layers, theme) {
    if (theme !== 'dark') return layers;
    const BUMPED = '#c4c4c4';   // light gray — readable, not glaring
    return layers.map((l) => {
        if (l.type !== 'symbol' || !l.id) return l;
        if (!l.id.includes('roads')) return l;
        return {
            ...l,
            paint: { ...(l.paint || {}), 'text-color': BUMPED },
        };
    });
}

/**
 * Bumps the fill color of water-polygon layers. Protomaps' default dark
 * theme paints lakes a low-saturation slate that's barely distinguishable
 * from the land underneath — a saturated blue makes them read as water at
 * a glance without overpowering the rest of the map. Light theme gets a
 * softer blue that matches the rest of the pastel palette.
 *
 * Matches any layer whose id mentions "water" and whose type is "fill"
 * (so waterway LINES — rivers, streams — aren't touched; only the polygon
 * fills for lakes, ponds, seas).
 */
function bumpWaterColors(layers, theme) {
    const fill = theme === 'dark' ? '#26527c' : '#a8c8e8';
    return layers.map((l) => {
        if (l.type !== 'fill' || !l.id) return l;
        if (!l.id.toLowerCase().includes('water')) return l;
        return {
            ...l,
            paint: { ...(l.paint || {}), 'fill-color': fill },
        };
    });
}

function buildStyle(pmtilesUrl, themeName) {
    let layers = protomapsLayers('basemap', themeName);
    layers = bumpRoadColors(layers, themeName);
    layers = bumpRoadLabelContrast(layers, themeName);
    layers = bumpWaterColors(layers, themeName);
    return {
        version: 8,
        glyphs: 'https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf',
        sprite: `https://protomaps.github.io/basemaps-assets/sprites/v4/${themeName}`,
        sources: {
            basemap: {
                type: 'vector',
                url: pmtilesUrl,
                attribution: '<a href="https://openstreetmap.org">© OpenStreetMap</a> · <a href="https://protomaps.com">Protomaps</a>',
            },
        },
        layers,
    };
}

// --- Map init (best-effort) --------------------------------------------------

// Style cycle: auto follows the OS dark-mode media query; explicit modes
// override it. Persisted to localStorage so a refresh keeps the choice.
// (localStorage is client-only — never sent to the server — so this stays
// consistent with the "ZERO server-side cookies / persistence" promise.)
const STYLE_KEY = 'share.viewer.mapstyle';
const STYLE_MODES = ['auto', 'light', 'dark'];
const STYLE_LABELS = { auto: 'A', light: 'L', dark: 'D' };
const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');

let mapStyleMode = 'auto';
try {
    const saved = localStorage.getItem(STYLE_KEY);
    if (saved && STYLE_MODES.includes(saved)) mapStyleMode = saved;
} catch (_) { /* incognito / disabled storage — silently fall back to default */ }

function resolveStyleScheme(mode) {
    if (mode === 'auto') return darkQuery.matches ? 'dark' : 'light';
    return mode;
}

const pmtilesUrl = `pmtiles://${window.location.origin}${PMTILES_PATH}`;

// PMTiles protocol shim — translates `pmtiles://...` requests into HTTP range
// requests against the underlying file URL.
maplibregl.addProtocol('pmtiles', new Protocol().tile);

try {
    map = new maplibregl.Map({
        container: 'map',
        style: buildStyle(pmtilesUrl, resolveStyleScheme(mapStyleMode)),
        center: [19.937, 50.061],   // Kraków — replaced as soon as the first fix lands.
        zoom: 10,
        attributionControl: { compact: true },
    });
    map.on('error', (e) => {
        if (e && e.error && /pmtiles/i.test(String(e.error.message || ''))) {
            showError('Map data missing or failed to load (' + e.error.message + ')');
        }
    });
} catch (e) {
    console.error('[viewer] map init failed; data path will continue without a map', e);
    showError('Map renderer failed (likely WebGL). Data path still live — check console.');
}

// --- Position marker (only meaningful if map exists) ------------------------

if (map) {
    const buildMarkerEl = () => {
        const el = document.createElement('div');
        el.style.width  = '28px';
        el.style.height = '28px';
        el.style.pointerEvents = 'none';
        el.innerHTML = `
            <svg viewBox="-16 -16 32 32" xmlns="http://www.w3.org/2000/svg">
                <!-- Outer darker shell + inner red fill so the marker reads against any
                     background instead of being a pure red blob. -->
                <polygon points="0,-14 -9,12 0,7 9,12"
                         fill="#e93232" stroke="#5e0a0a" stroke-width="1.4" stroke-linejoin="round"/>
                <circle cx="0" cy="0" r="2" fill="#5e0a0a"/>
            </svg>`;
        return el;
    };
    const primaryEl = buildMarkerEl();
    primaryEl.id = 'pos-marker';
    marker     = new maplibregl.Marker({ element: primaryEl,         rotationAlignment: 'map' });
    markerWest = new maplibregl.Marker({ element: buildMarkerEl(),   rotationAlignment: 'map' });
    markerEast = new maplibregl.Marker({ element: buildMarkerEl(),   rotationAlignment: 'map' });
    map.on('move', refreshAccuracyRing);
    map.on('zoom', refreshAccuracyRing);
}

/**
 * Position the primary marker + its +/-360-deg wraparound clones. Caller
 * passes the canonical lng/lat; the clones get the same lat with lng shifted
 * by one world span each side, so they appear in the visible adjacent world
 * copies at low zoom.
 */
function setMarkersLngLat(lngLat) {
    if (!marker) return;
    const [lng, lat] = lngLat;
    marker.setLngLat([lng,        lat]);
    markerWest.setLngLat([lng - 360, lat]);
    markerEast.setLngLat([lng + 360, lat]);
}

function applyMarkerRotation() {
    if (!marker) return;
    const r = lastDirectionDeg == null ? 0 : lastDirectionDeg;
    marker.setRotation(r);
    markerWest.setRotation(r);
    markerEast.setRotation(r);
}

function addMarkersToMap() {
    if (!marker || !map) return;
    marker.addTo(map);
    markerWest.addTo(map);
    markerEast.addTo(map);
}

function ensureAccuracyLayer() {
    if (!map) return;
    if (map.getSource('accuracy')) return;
    map.addSource('accuracy', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
    });
    map.addLayer({
        id: 'accuracy-ring',
        type: 'circle',
        source: 'accuracy',
        paint: {
            'circle-radius': ['get', 'radius_px'],
            'circle-color': '#e93232',
            'circle-opacity': 0.10,
            'circle-stroke-color': '#e93232',
            'circle-stroke-opacity': 0.45,
            'circle-stroke-width': 1,
        },
    });
}

/**
 * The accuracy ring is drawn in pixels, not meters, so it needs to be re-emitted
 * whenever the camera changes (different ground-meters-per-pixel after pan/zoom).
 * Only visible when accuracy is worse than ACCURACY_RING_THRESHOLD_M — within
 * that, the marker is more precise than the ring would suggest, so showing it
 * is misleading clutter.
 */
function refreshAccuracyRing() {
    if (!map) return;
    if (!map.getSource('accuracy')) return;
    if (lastAccuracyM == null || lastAccuracyM <= ACCURACY_RING_THRESHOLD_M || lastPositionLngLat == null) {
        map.getSource('accuracy').setData({ type: 'FeatureCollection', features: [] });
        return;
    }
    const radiusPx = metersToPixelsAtLat(lastAccuracyM, lastPositionLngLat[1], map.getZoom());
    map.getSource('accuracy').setData({
        type: 'FeatureCollection',
        features: [{
            type: 'Feature',
            geometry: { type: 'Point', coordinates: lastPositionLngLat },
            properties: { radius_px: radiusPx },
        }],
    });
}

// Standard Web Mercator pixel-per-meter at given lat & zoom (256 px tile world).
function metersToPixelsAtLat(meters, latDeg, zoom) {
    const earthCircumference = 40_075_016.686;
    const mPerPx = (earthCircumference * Math.cos(latDeg * Math.PI / 180)) / (256 * Math.pow(2, zoom));
    return meters / mPerPx;
}

// --- Rotation toggle ---------------------------------------------------------

const ROTATION_NORTH_UP = 'north-up';
const ROTATION_FOLLOW   = 'follow';
// Default to heading-follow — that's the typical "driving" view and the one
// you almost always want when watching a moving share. Tap the button to
// drop back to north-up.
let rotationMode = ROTATION_FOLLOW;
elBtnRotate.setAttribute('data-mode', rotationMode);

elBtnRotate.addEventListener('click', () => {
    rotationMode = (rotationMode === ROTATION_NORTH_UP) ? ROTATION_FOLLOW : ROTATION_NORTH_UP;
    elBtnRotate.setAttribute('data-mode', rotationMode);
    applyMapBearing();
});

function applyMapBearing() {
    if (!map) return;
    if (rotationMode === ROTATION_NORTH_UP) {
        map.easeTo({ bearing: 0, duration: 300 });
    } else if (lastDirectionDeg != null) {
        map.easeTo({ bearing: lastDirectionDeg, duration: 300 });
    }
}

// --- Follow-position lock toggle --------------------------------------------
//
// Locked (default): the map auto-recenters on every position update and
// drag-to-pan is disabled — you can't accidentally pan off the marker.
// Pinch-zoom / zoom buttons / rotation still work. Unlock to look around.

let followLock = true;
elBtnLock.setAttribute('data-locked', String(followLock));
if (map) map.dragPan.disable();   // start locked

elBtnLock.addEventListener('click', () => {
    followLock = !followLock;
    elBtnLock.setAttribute('data-locked', String(followLock));
    if (!map) return;
    if (followLock) {
        map.dragPan.disable();
        // Re-snap to current position so the user sees the lock take effect.
        if (lastPositionLngLat) {
            map.easeTo({ center: lastPositionLngLat, duration: 300 });
        }
    } else {
        map.dragPan.enable();
    }
});

// Zoom anchoring: maplibre's default scroll-zoom anchors on the cursor,
// which is the right thing when freely panning the map - it lets you "zoom
// into" whatever you're hovering. But when followLock is on, the map keeps
// re-centering on the marker on every position frame; a cursor-anchored
// zoom in that mode causes the camera to lurch toward the cursor and snap
// back on the next fix. So: disable maplibre's scroll-zoom and replace
// with our own wheel handler that branches on followLock - cursor anchor
// when unlocked, marker anchor when locked. Pinch zoom (touch) is left
// alone; in locked mode it briefly pans during the gesture and the next
// position frame re-centers. That glitch is small and isolating it would
// require a custom touch handler.
if (map) {
    map.scrollZoom.disable();

    const lockedAnchor = () => lastPositionLngLat || map.getCenter().toArray();

    map.getContainer().addEventListener('wheel', (e) => {
        e.preventDefault();
        // Normalize the per-tick magnitude across mouse wheel / trackpad
        // so deltaY's exact value doesn't dominate the step size.
        const dir = e.deltaY > 0 ? -1 : 1;
        const step = e.ctrlKey ? 0.25 : 0.5;
        const newZoom = map.getZoom() + dir * step;
        let around;
        if (followLock) {
            around = lockedAnchor();
        } else {
            const rect = map.getContainer().getBoundingClientRect();
            around = map.unproject([e.clientX - rect.left, e.clientY - rect.top]).toArray();
        }
        map.easeTo({ zoom: newZoom, around, duration: 120 });
    }, { passive: false });

    elBtnIn.addEventListener('click', () => {
        map.easeTo({ zoom: map.getZoom() + 1, around: followLock ? lockedAnchor() : map.getCenter().toArray(), duration: 200 });
    });
    elBtnOut.addEventListener('click', () => {
        map.easeTo({ zoom: map.getZoom() - 1, around: followLock ? lockedAnchor() : map.getCenter().toArray(), duration: 200 });
    });
} else {
    elBtnIn.disabled  = true;
    elBtnOut.disabled = true;
    elBtnLock.disabled = true;
}

// --- Map-style toggle ------------------------------------------------------
//
// Cycle button: auto → light → dark → auto. State persists via localStorage
// (client-only). When in 'auto', system theme-change events flow through
// so flipping the OS theme retints the map live; explicit modes ignore that
// signal until the user explicitly cycles back to auto.
function refreshStyleBtn() {
    elBtnStyle.textContent = STYLE_LABELS[mapStyleMode];
    elBtnStyle.setAttribute('data-mode', mapStyleMode);
}
function applyMapStyle() {
    if (!map) return;
    map.setStyle(buildStyle(pmtilesUrl, resolveStyleScheme(mapStyleMode)));
}
refreshStyleBtn();
elBtnStyle.addEventListener('click', () => {
    const idx = STYLE_MODES.indexOf(mapStyleMode);
    mapStyleMode = STYLE_MODES[(idx + 1) % STYLE_MODES.length];
    try { localStorage.setItem(STYLE_KEY, mapStyleMode); } catch (_) { /* ignore */ }
    refreshStyleBtn();
    applyMapStyle();
});
if (!map) elBtnStyle.disabled = true;
darkQuery.addEventListener('change', () => {
    if (mapStyleMode === 'auto') applyMapStyle();
});

// --- Field-debug overlay refresher ------------------------------------------
//
// Toggled via `?debug=1` in the URL. Shows every value relevant to the
// "autocenter doesn't work" investigation in one place, refreshing twice a
// second. Counters and timestamps come from event handlers above; everything
// else is read live each tick.
if (DEBUG_OVERLAY) {
    elDebug.hidden = false;
    const fmtAgo = (ts) => ts == null ? '—' : `${Math.max(0, Math.round((Date.now() - ts) / 100) / 10)}s ago`;
    const fmtPos = (p) => p ? `${p[1].toFixed(5)}, ${p[0].toFixed(5)}` : '—';
    setInterval(() => {
        const center = map ? map.getCenter() : null;
        const lines = [
            `lock           ${followLock ? 'ON' : 'OFF'}`,
            `rotMode        ${rotationMode}`,
            `pos            ${fmtPos(lastPositionLngLat)}`,
            `dir            ${lastDirectionDeg == null ? '—' : lastDirectionDeg.toFixed(0) + '°'}`,
            `mapCenter      ${center ? center.lat.toFixed(5) + ', ' + center.lng.toFixed(5) : '—'}`,
            `posAdded       ${positionAdded}`,
            `lastRx         ${fmtAgo(lastUpdateTs)}`,
            `lastCenter     ${fmtAgo(debugState.lastCenterTs)} (${debugState.lastCenterAction})`,
            `wsState        ${ws ? ['CONNECTING','OPEN','CLOSING','CLOSED'][ws.readyState] : '—'}`,
            `frames rx/ok   ${debugState.framesRx}/${debugState.framesDecrypted}`,
            `badKey         ${badKeyLatched}`,
            `pubGone        ${publisherGoneLatched}`,
        ];
        elDebug.textContent = lines.join('\n');
    }, 500);
}
