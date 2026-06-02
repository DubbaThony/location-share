package pl.dubba.share.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.dubba.share.Config
import pl.dubba.share.R
import pl.dubba.share.protocol.AbstractFrame
import pl.dubba.share.protocol.ClientHandshakeFrame
import pl.dubba.share.protocol.Crypto
import pl.dubba.share.protocol.CtrlFrame
import pl.dubba.share.protocol.CtrlTy
import pl.dubba.share.protocol.DataFrame
import pl.dubba.share.protocol.EncryptedClientHandshakeFrame
import pl.dubba.share.protocol.FrameTy
import pl.dubba.share.protocol.HandshakeStatus
import pl.dubba.share.protocol.Marshaller
import pl.dubba.share.protocol.PROTO_VERSION
import pl.dubba.share.protocol.PingFrame
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Runs the handshake, then a ping loop + a child outbound-data sender. External
 * callers post payloads via [postData] (buffered channel, drained by the sender
 * coroutine inside [run]). Both senders share the TX counter via [AtomicLong]
 * so each frame gets a unique AEAD nonce - no Mutex needed because
 * `DatagramSocket.send` is thread-safe internally.
 *
 * Disconnect: [requestGracefulDisconnect] makes the ping loop wake from its
 * inter-ping delay, send a Ctrl-Bye burst, and exit. [stop] is the hard close.
 */
class ConnectionDriver(
    /** Application context - used only for resolving localized [UiError] strings. */
    private val appContext: Context,
    private val host: String,
    private val port: Int,
    /**
     * The Viewer URL prefix (from Settings). Used to derive the APK download
     * URL we surface in the proto-version-mismatch dialog - "this server
     * probably hosts the APK that matches this proto version, here's a
     * one-tap jump-out to it." Blank means "user hasn't configured it";
     * the dialog falls back to a non-actionable message in that case.
     */
    private val urlPrefix: String,
    /**
     * The server's trusted long-term Ed25519 public key (32 bytes), or null
     * if the user explicitly disabled verification in Settings.
     *
     * When non-null, the driver verifies the HandshakeServer signature. On
     * mismatch it tries [refreshIdentity] once - which goes through HTTPS
     * (TLS is the trust anchor; SSL-strip or interception will fail the
     * fetch and surface to the user). If the fresh key also doesn't verify,
     * that's real MITM and the driver refuses the session.
     *
     * When null, signature verification is skipped entirely and a loud
     * warning is logged - the user has opted out of MITM protection.
     */
    private val serverIdentity: ByteArray?,
    /**
     * Called by the driver when the cached identity fails to verify the
     * handshake signature. Implementation should fetch /identity over HTTPS,
     * persist the result, fire the user's "identity refreshed" notification
     * per their setting, and return the new pubkey. Returns null when the
     * fetch failed OR the user has Auto-refresh off (no manual path inside
     * the handshake - they need to use the Refresh button explicitly).
     */
    private val refreshIdentity: suspend () -> ByteArray?,
    private val log: (String) -> Unit,
    private val pingIntervalMs: Long = Config.PING_INTERVAL_MS,
    private val onAccessKey: (String) -> Unit = {},
    private val onFirstPong: () -> Unit = {},
    private val onSubCount: (Long) -> Unit = {},
    /**
     * Fires once at the start of [run], with 32 freshly random bytes - the
     * inner-e2ee key for this session. Bound to the lifecycle of the very
     * first handshake frame: it's a per-session artifact, born when the
     * session is, so the parent can install the encrypted payload encoder
     * before any data flows.
     */
    private val onInnerKey: (ByteArray) -> Unit = {},
    /**
     * Returns the current "network instability" silence threshold in seconds
     * (user-configurable in settings, read live so changes take effect mid-
     * session). When no pong has been received for this many seconds, the
     * driver fires [onNetworkUnstable] once per gap - does NOT terminate
     * the session. Defaults to a sane 60 s if not provided.
     */
    private val netUnstableThresholdSec: () -> Int = { 60 },
    /**
     * Fired once per silence gap when the configured threshold is crossed.
     * The driver dedupes within a gap; resets the dedupe when a pong arrives
     * and the next gap can start fresh.
     */
    private val onNetworkUnstable: (silenceSec: Int) -> Unit = {},
    /**
     * Fired when a pong arrives AFTER [onNetworkUnstable] had fired in this
     * gap - i.e. the unstable condition resolved. Service uses this to call
     * [AlertClass.resolve] so the heads-up notification clears if the user
     * has autoCancelOnResolve enabled.
     */
    private val onNetworkStable: () -> Unit = {},
) {
    @Volatile
    private var socket: DatagramSocket? = null

    private val disconnectSignal = Channel<Unit>(Channel.CONFLATED)
    private val outboundData = Channel<ByteArray>(capacity = 64)

    /**
     * Set when the server tells us to bail (Bye / Fatal / ShuttingDown). The
     * ping loop checks this between iterations and exits cleanly. We don't
     * call [requestGracefulDisconnect] from the Ctrl handler because that's
     * also OUR Bye path - if the server told us Bye, sending another Bye back
     * is pointless.
     */
    @Volatile
    private var serverInitiatedExit: Boolean = false

    /**
     * Tracks recently-seen ctrlNonces so the spec-mandated "drop dupes, ACK
     * every copy" behaviour works for server-side ctrl bursts. The set is
     * bounded - when full, the oldest entries drop out - which means we don't
     * need a real TTL sweep. Capacity is generous vs the spec's 600 s × N-ctrls
     * window.
     */
    private val rxCtrlDedupe = NonceDedupe(capacity = 256)

    /** Hard close - for cleanup / safety. Use [requestGracefulDisconnect] first. */
    fun stop() {
        socket?.close()
    }

    /** Asks the run loop to send a Ctrl-Bye and exit. Non-blocking. */
    fun requestGracefulDisconnect() {
        disconnectSignal.trySend(Unit)
    }

    /**
     * Queue a payload for delivery as a [DataFrame]. Buffered (cap 64); if the
     * sender is keeping up, trySend succeeds. If the channel is full, the
     * oldest unsent payload still wins - returns false here means "queue full,
     * dropping this fix." For our cadence (1 fix/s) we will essentially never
     * see that.
     */
    fun postData(payload: ByteArray): Boolean {
        val result = outboundData.trySend(payload)
        val ok = result.isSuccess
        if (!ok) {
            log("⚠ postData: trySend failed (${result}) - payload ${payload.size}B dropped")
        }
        return ok
    }

    suspend fun run() = withContext(Dispatchers.IO) {
        // Defensive: handleStartConnection should have rejected this already,
        // but if anything ever slips past (stale DataStore, intent extras
        // corrupted by the system, bad migration, ...), at least we don't crash
        // in DatagramSocket.connect - instead surface a UiError the user can
        // act on.
        if (port !in 1..65535) {
            log("✗ driver got invalid port $port - refusing to open socket")
            ConnectionState.setLastError(UiError(
                title = appContext.getString(R.string.err_invalid_port_title),
                detail = appContext.getString(R.string.err_invalid_port_detail, port),
            ))
            return@withContext
        }
        if (host.isBlank()) {
            log("✗ driver got blank host - refusing to open socket")
            ConnectionState.setLastError(UiError(
                title = appContext.getString(R.string.err_invalid_host_title),
                detail = appContext.getString(R.string.err_invalid_host_detail),
            ))
            return@withContext
        }
        // All post-handshake send/recv consult the [socket] field instead of a
        // local handle so rebindSocket() can replace it mid-session without
        // updating every caller. Handshake stages still set up via the field
        // directly.
        //
        // `also { it. ... }` not `apply { ... }`: DatagramSocket has its own
        // `port` getter (returning the connected port - `-1` before connect),
        // which would shadow our outer `port` field inside an `apply` block.
        // `also` puts the socket in `it` instead of `this`, so unqualified
        // `port` / `host` here resolve to the ConnectionDriver fields.
        socket = DatagramSocket().also {
            it.connect(InetAddress.getByName(host), port)
            it.soTimeout = Config.RECV_TIMEOUT_MS
        }
        try {
            log("socket → $host:$port")

            // --- handshake ---
            val kp = Crypto.generateKeyPair()
            val myPub = Crypto.encodePublicKey(kp.public)
            val clientConnId = SecureRandom().nextLong()
            log("P-256 keypair ready, clientConnId=${u64hex(clientConnId)}")

            // Inner-e2ee key: mint right here, alongside the outer-handshake
            // material. The key is a session artifact - same lifecycle as
            // this driver run - so generating it next to the first handshake
            // frame keeps the "all session secrets are born together" story
            // honest. The bytes are not put on the wire; only the URL
            // fragment carries them (via the onInnerKey → service path).
            val innerKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            onInnerKey(innerKey)

            sendFrame(clientConnId, FrameTy.HandshakeClient,
                Marshaller.marshalHandshakeClient(ClientHandshakeFrame(myPub, PROTO_VERSION)))
            log("→ HandshakeClient (proto v$PROTO_VERSION)")

            val r1 = recvFrame() ?: run {
                log("✗ timeout waiting for HandshakeServer")
                ConnectionState.setLastError(UiError(
                    title = appContext.getString(R.string.err_no_response_title),
                    detail = appContext.getString(R.string.err_no_response_detail, host, port, Config.RECV_TIMEOUT_MS / 1000),
                ))
                return@withContext
            }
            if (r1.frameType != FrameTy.HandshakeServer) {
                log("✗ expected HandshakeServer, got ${r1.frameType}")
                ConnectionState.setLastError(UiError(
                    title = appContext.getString(R.string.err_unexpected_response_title),
                    detail = appContext.getString(R.string.err_unexpected_response_detail, r1.frameType, host, port),
                ))
                return@withContext
            }
            val hs = Marshaller.unmarshalHandshakeServer(r1.body) ?: run {
                log("✗ malformed HandshakeServer")
                ConnectionState.setLastError(UiError(
                    title = appContext.getString(R.string.err_malformed_response_title),
                    detail = appContext.getString(R.string.err_malformed_response_detail),
                ))
                return@withContext
            }
            when (hs.status) {
                HandshakeStatus.OK -> {}
                HandshakeStatus.ProtoTooHigh -> {
                    // Server speaks an older protocol than us. This server is
                    // probably running for an older release of the app, and the
                    // matching older APK is almost certainly hosted on the
                    // same server. Note: the older APK is likely signed with
                    // a different key (different self-hoster, or an old build
                    // of yours), so Android will refuse to install it over
                    // your current install - uninstall first.
                    log("✗ server says proto too HIGH")
                    ConnectionState.setLastError(UiError(
                        title = appContext.getString(R.string.err_proto_too_high_title),
                        detail = appContext.getString(R.string.err_proto_too_high_detail, urlPrefix.ifBlank { host }),
                        actions = apkDownloadActions(appContext.getString(R.string.err_proto_download_label_old)),
                    ))
                    return@withContext
                }
                HandshakeStatus.ProtoTooLow -> {
                    // Our protocol is older than the server's. The server is
                    // hosting the APK that matches its protocol - grab it.
                    // Same uninstall caveat applies if the new APK is signed
                    // by a different key than the one currently installed.
                    log("✗ server says proto too LOW")
                    ConnectionState.setLastError(UiError(
                        title = appContext.getString(R.string.err_proto_too_low_title),
                        detail = appContext.getString(R.string.err_proto_too_low_detail, urlPrefix.ifBlank { host }),
                        actions = apkDownloadActions(appContext.getString(R.string.err_proto_download_label_new)),
                    ))
                    return@withContext
                }
                else -> {
                    log("✗ server status=${hs.status}")
                    ConnectionState.setLastError(UiError(
                        title = appContext.getString(R.string.err_server_refused_title),
                        detail = appContext.getString(R.string.err_server_refused_detail, hs.status),
                    ))
                    return@withContext
                }
            }
            val serverConnId = hs.connectionId
            log("← HandshakeServer ok, serverConnId=${u64hex(serverConnId)}")

            // --- server identity check ---
            // The server signs (clientECDH, serverECDH, clientConnId,
            // serverConnId) with its long-term Ed25519 key. We verify against
            // the pubkey resolved out-of-band via HTTPS /identity.
            //
            // On mismatch with the CACHED key: this could be either (a) a
            // legitimate key rotation on the server, or (b) a real UDP-MITM
            // serving us a forged ECDH+sig combo. We don't know yet. We
            // try [refreshIdentity], which goes through HTTPS - TLS is the
            // trust anchor here. If TLS validation is intact, the fetched
            // key is authentic; if a MITM is also intercepting the HTTPS
            // call (SSL-strip / cert injection), the fetch fails and the
            // user sees an error. If we get a fresh key and IT verifies,
            // it was a rotation - persist (refreshIdentity already did)
            // and continue. If even the fresh key doesn't verify, that's
            // confirmed UDP-MITM - refuse.
            //
            // If serverIdentity is null, the user has explicitly disabled
            // verification. Honour it; log loudly.
            if (serverIdentity != null) {
                val signInput = Crypto.protoSignatureInput(myPub, hs.ecdh, clientConnId, serverConnId)
                if (!Crypto.verifyEd25519(serverIdentity, signInput, hs.signature)) {
                    log("⚠ signature mismatch against cached identity - attempting refresh via HTTPS")
                    val fresh = refreshIdentity()
                    if (fresh == null) {
                        log("✗ refresh failed or auto-refresh disabled - connection refused")
                        ConnectionState.setLastError(UiError(
                            title = appContext.getString(R.string.err_identity_check_failed_title),
                            detail = appContext.getString(R.string.err_identity_check_failed_detail, host),
                        ))
                        return@withContext
                    }
                    // Three diagnostic branches based on what HTTPS told us:
                    //   1. Fresh key verifies the UDP signature → legitimate rotation.
                    //   2. Fresh key == cached key → HTTPS confirmed identity didn't
                    //      rotate, so the UDP-side mismatch can ONLY be UDP MITM
                    //      (HTTPS layer is intact and authoritative).
                    //   3. Fresh key differs from cached AND still doesn't verify
                    //      → server is misconfigured, or both HTTPS and UDP are
                    //      compromised. Either way, refuse.
                    if (Crypto.verifyEd25519(fresh, signInput, hs.signature)) {
                        log("✓ identity rotated - verified against fresh key")
                    } else if (fresh.contentEquals(serverIdentity)) {
                        log("✗ HTTPS-fetched identity is unchanged but UDP signature still fails - UDP MITM confirmed")
                        ConnectionState.setLastError(UiError(
                            title = appContext.getString(R.string.err_identity_mitm_title),
                            detail = appContext.getString(R.string.err_identity_mitm_detail, host),
                        ))
                        return@withContext
                    } else {
                        log("✗ fresh identity differs from cached AND still doesn't verify - server or both layers compromised")
                        ConnectionState.setLastError(UiError(
                            title = appContext.getString(R.string.err_identity_critical_title),
                            detail = appContext.getString(R.string.err_identity_critical_detail),
                        ))
                        return@withContext
                    }
                } else {
                    log("✓ server identity verified")
                }
            } else {
                log("⚠ server identity verification DISABLED in settings - MITM possible")
            }

            val shared = Crypto.ecdh(kp.private, Crypto.decodePublicKey(hs.ecdh))
            val keys = Crypto.deriveKeys(shared, serverConnId, clientConnId)
            val txKey = keys.k2
            val rxKey = keys.k1
            val txCounter = AtomicLong(0L)
            log("ECDH + KDF done (txKey=k2, rxKey=k1)")

            sendFrame(serverConnId, FrameTy.EncryptedHandshakeClient,
                Marshaller.marshalEncryptedHandshakeClient(EncryptedClientHandshakeFrame(1L), txKey, txCounter.getAndIncrement()))
            log("→ EncryptedHandshakeClient (featFlag=1)")

            val r2 = recvFrame() ?: run {
                log("✗ timeout waiting for EncryptedHandshakeServer")
                ConnectionState.setLastError(UiError(
                    title = appContext.getString(R.string.err_midhandshake_quiet_title),
                    detail = appContext.getString(R.string.err_midhandshake_quiet_detail),
                ))
                return@withContext
            }
            if (r2.frameType != FrameTy.EncryptedHandshakeServer) {
                log("✗ expected EncryptedHandshakeServer, got ${r2.frameType}")
                ConnectionState.setLastError(UiError(
                    title = appContext.getString(R.string.err_unexpected_response_title),
                    detail = appContext.getString(R.string.err_unexpected_response_encrypted_detail, r2.frameType),
                ))
                return@withContext
            }
            val ehs = Marshaller.unmarshalEncryptedHandshakeServer(r2.body, rxKey)
                ?: run {
                    log("✗ failed to decrypt EncryptedHandshakeServer (key mismatch?)")
                    ConnectionState.setLastError(UiError(
                        title = appContext.getString(R.string.err_crypto_handshake_title),
                        detail = appContext.getString(R.string.err_crypto_handshake_detail),
                    ))
                    return@withContext
                }
            val accessKey = String(ehs.publicAlias, Charsets.US_ASCII)
            log("← EncryptedHandshakeServer ok")
            log("✓ CONNECTED - access key: $accessKey")
            onAccessKey(accessKey)

            // --- data sender child coroutine ---
            // Drains outboundData, marshals each payload as a DataFrame with the
            // next atomic counter, and writes it. Cancellation via cancelAndJoin
            // in the outer finally below.
            val dataJob = launch {
                while (coroutineContext.isActive) {
                    val payload = outboundData.receiveCatching().getOrNull() ?: break
                    try {
                        val ctr = txCounter.getAndIncrement()
                        val body = Marshaller.marshalData(DataFrame(payload), txKey, ctr)
                        val ok = sendFrame(serverConnId, FrameTy.Data, body)
                        // Payload is encrypted on the wire (nonce+ciphertext+tag),
                        // so the old UTF-8 preview was garbage. Just log size +
                        // outcome - if "send=false" shows up, sendFrame's own
                        // transient-error log will have the exception detail.
                        log("→ Data ctr=$ctr (${payload.size}B inner, ${body.size}B framed) send=${if (ok) "ok" else "FAIL"}")
                    } catch (e: Exception) {
                        log("⚠ Data send threw: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            // Per spec, every acking-type ctrl we send MUST be ACKed by
            // the server. If it isn't ACKed after the full retransmit
            // schedule, the connection is dead. We track each in-flight
            // ctrl by ctrlNonce → its retx coroutine. On Ack we cancel
            // the matching job. The map is only touched from the ping
            // loop (this single thread), so it's safe without a mutex.
            //
            // Declared HERE (outside the inner try) so the inner finally
            // can drain it on exit - otherwise structured concurrency
            // would make this withContext block wait for the retx
            // coroutines' delay()s to expire (up to 60 s) before the
            // service's connectionJob.finally can run.
            val pendingCtrlAcks = mutableMapOf<Int, kotlinx.coroutines.Job>()

            // --- ping loop ---
            try {
                var pingNonce = 1
                var firstPongDelivered = false
                // sinceRebind drives "rebind because the network probably
                // changed under us"; gets reset on every rebind. The previous
                // totalSilent counter has been replaced by a time-based
                // "network instability" signal that only NOTIFIES - it doesn't
                // kill the session. The only thing that kills the session is
                // the per-ctrl retx deadline (CTRL_DEAD_TIMEOUT_MS), which is
                // the spec-mandated definition of "connection deemed dead".
                var sinceRebind = 0
                var lastPongAtMs = System.currentTimeMillis()
                var instabilityNotifiedThisGap = false

                fun launchCtrlRetx(ctrl: CtrlFrame): kotlinx.coroutines.Job = launch {
                    // Spec: "retransmitted with possibly tight timeouts (such as
                    // 500ms, 2s, 5s, 10s, 30s, 60s). The specific value is
                    // unspecified" AND "if no ACK is given in timeout, connection
                    // is deemed dead". The retx *schedule* and the *connection
                    // dead* timeout are independent things - once the initial
                    // backoff ladder is exhausted we keep retransmitting at the
                    // max cadence (60 s) until the connection-dead deadline
                    // fires.
                    val backoffsMs = longArrayOf(500L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)
                    val maxBackoffMs = backoffsMs.last()
                    val deadlineAt = System.currentTimeMillis() + CTRL_DEAD_TIMEOUT_MS
                    var attempt = 0
                    while (System.currentTimeMillis() < deadlineAt) {
                        val backoff = backoffsMs.getOrElse(attempt) { maxBackoffMs }
                        delay(backoff)
                        if (System.currentTimeMillis() >= deadlineAt) break
                        // Fresh AEAD nonce per retx - same ctrlNonce so the
                        // server dedupes (and ACKs) the burst correctly.
                        val ctr = txCounter.getAndIncrement()
                        val body = Marshaller.marshalCtrl(ctrl, txKey, ctr)
                        val ok = sendFrame(serverConnId, FrameTy.Ctrl, body)
                        log("⟳ retx ctrl type=${ctrl.ctrlType} nonce=${ctrl.ctrlNonce} attempt ${attempt + 1} send=${if (ok) "ok" else "FAIL"}")
                        attempt++
                    }
                    log("✗ ctrl type=${ctrl.ctrlType} nonce=${ctrl.ctrlNonce} no ACK in ${CTRL_DEAD_TIMEOUT_MS / 1000}s - declaring connection dead per spec")
                    handleServerCtrlExit(
                        title = appContext.getString(R.string.err_ctrl_timed_out_title),
                        detail = appContext.getString(R.string.err_ctrl_timed_out_detail, (CTRL_DEAD_TIMEOUT_MS / 1000).toInt()),
                    )
                }
                while (coroutineContext.isActive) {
                    if (serverInitiatedExit) {
                        // Server already announced termination - exit without
                        // a Bye-burst back. The Ctrl handler has set the
                        // UiError; the outer finally cleans up the socket.
                        return@withContext
                    }
                    if (disconnectSignal.tryReceive().isSuccess) {
                        sendBye(serverConnId, txKey, txCounter)
                        return@withContext
                    }

                    val pingOk = sendFrame(serverConnId, FrameTy.Ping,
                        Marshaller.marshalPing(PingFrame(pingNonce), txKey, txCounter.getAndIncrement()))

                    // Piggyback a subscriber-count query on every ping. Server
                    // responds with an ACKing CtrlFrame whose extra is an
                    // 8-byte LE uint64. Same cadence as ping (~5s) is more
                    // than enough for a viewer-count display.
                    //
                    // This is acking-type so the server is required to ACK
                    // our send. We track the nonce → retx job below; on Ack
                    // receipt we cancel that job. If it ever exhausts the
                    // backoff schedule without an Ack, handleServerCtrlExit
                    // declares the connection dead (per spec).
                    val subCtrlNonce = SecureRandom().nextInt() and 0xFFFF
                    val getSubCtrl = CtrlFrame(CtrlTy.GetSubCount, subCtrlNonce, ByteArray(0))
                    val ctrlOk = sendFrame(serverConnId, FrameTy.Ctrl,
                        Marshaller.marshalCtrl(getSubCtrl, txKey, txCounter.getAndIncrement()))
                    pendingCtrlAcks[subCtrlNonce] = launchCtrlRetx(getSubCtrl)

                    // Two outstanding responses (pong + sub-count ack) - recv
                    // both within RECV_TIMEOUT_MS total, bail early once both
                    // are in. Tighten soTimeout to the remaining budget on
                    // each iteration so a missing response can't burn the
                    // full window twice.
                    val deadline = System.currentTimeMillis() + Config.RECV_TIMEOUT_MS.toLong()
                    var gotPong = false
                    var gotSub = false
                    while (!gotPong || !gotSub) {
                        val remain = (deadline - System.currentTimeMillis()).toInt()
                        if (remain <= 0) break
                        socket?.soTimeout = remain
                        val frame = recvFrame() ?: break
                        when (frame.frameType) {
                            FrameTy.Ping -> {
                                val p = Marshaller.unmarshalPing(frame.body, rxKey)
                                if (p != null) {
                                    gotPong = true
                                    if (!firstPongDelivered) {
                                        firstPongDelivered = true
                                        onFirstPong()
                                    }
                                }
                            }
                            FrameTy.Ctrl -> {
                                val c = Marshaller.unmarshalCtrl(frame.body, rxKey)
                                if (c == null) {
                                    log("⚠ ctrl frame failed to decrypt / unmarshal")
                                } else if (c.ctrlType == CtrlTy.Ack) {
                                    // ACKs reference the nonce of OUR sent
                                    // ctrl; cancel its retx job. Not subject
                                    // to incoming-ctrl dedupe (the key space
                                    // is our outgoing nonces, not theirs) and
                                    // no ACK-an-ACK behavior - straight cancel.
                                    pendingCtrlAcks.remove(c.ctrlNonce)?.cancel()
                                } else {
                                    // ACKing-type Ctrl frames (CtrlTy.isAcking,
                                    // i.e. type <= 200) MUST be acknowledged by
                                    // the receiver with Ctrl(Ack=255, same nonce).
                                    // Without this the server keeps a retx timer
                                    // and after `TimeoutConnected` declares the
                                    // ACK timed out and kills the connection
                                    // (see server/protocol/acker.go:130).
                                    if (CtrlTy.isAcking(c.ctrlType)) {
                                        val ackBody = Marshaller.marshalCtrl(
                                            CtrlFrame(CtrlTy.Ack, c.ctrlNonce, ByteArray(0)),
                                            txKey,
                                            txCounter.getAndIncrement(),
                                        )
                                        sendFrame(serverConnId, FrameTy.Ctrl, ackBody)
                                    }

                                    // Dedupe by ctrlNonce - server bursts
                                    // acking ctrls (ctrlTyBurstSize × the same
                                    // frame), and per spec the receiver ACKs
                                    // every copy but processes only the first.
                                    if (rxCtrlDedupe.markSeen(c.ctrlNonce)) {
                                        when (c.ctrlType) {
                                            CtrlTy.GetSubCount -> {
                                                if (c.extra.size == 8) {
                                                    val n = ByteBuffer.wrap(c.extra)
                                                        .order(ByteOrder.LITTLE_ENDIAN)
                                                        .long
                                                    onSubCount(n)
                                                    gotSub = true
                                                }
                                            }
                                            CtrlTy.Bye -> handleServerCtrlExit(
                                                appContext.getString(R.string.err_server_bye_title),
                                                appContext.getString(R.string.err_server_bye_detail),
                                            )
                                            CtrlTy.FatalError -> handleServerCtrlExit(
                                                appContext.getString(R.string.err_server_fatal_title),
                                                appContext.getString(R.string.err_server_fatal_detail),
                                            )
                                            CtrlTy.FatalMsgError -> handleServerCtrlExit(
                                                appContext.getString(R.string.err_server_fatal_title),
                                                appContext.getString(
                                                    R.string.err_server_fatal_msg_detail,
                                                    runCatching { String(c.extra, Charsets.US_ASCII) }
                                                        .getOrNull() ?: appContext.getString(R.string.err_server_fatal_msg_unreadable),
                                                ),
                                            )
                                            CtrlTy.ShuttingDown -> handleServerCtrlExit(
                                                appContext.getString(R.string.err_server_shutting_down_title),
                                                appContext.getString(R.string.err_server_shutting_down_detail),
                                            )
                                            // Any future acking ctrl type lands here; ACK was already sent above.
                                            else -> log("⚠ unhandled Ctrl type ${c.ctrlType}")
                                        }
                                    }
                                }
                            }
                            else -> log("⚠ unexpected frame type ${frame.frameType}")
                        }
                    }
                    socket?.soTimeout = Config.RECV_TIMEOUT_MS
                    log("⇄ ping #$pingNonce ${if (gotPong) "pong✓" else "pong✗"} ${if (gotSub) "sub✓" else "sub✗"}")
                    pingNonce++

                    if (gotPong) {
                        sinceRebind = 0
                        lastPongAtMs = System.currentTimeMillis()
                        // New pong arrived → next silence gap can fire its own
                        // notification independently of any previous gap.
                        // Also let the service clear any in-flight unstable-net
                        // notification if the user opted into autoCancel.
                        if (instabilityNotifiedThisGap) onNetworkStable()
                        instabilityNotifiedThisGap = false
                    } else {
                        sinceRebind++
                        // Heads-up only (no kill): if we've been silent longer
                        // than the user's configured threshold, fire the
                        // "network unstable" notification once per gap. The
                        // session continues - the spec's ctrl-retx deadline
                        // is the only thing that actually terminates it.
                        val silenceSec = ((System.currentTimeMillis() - lastPongAtMs) / 1000L).toInt()
                        if (!instabilityNotifiedThisGap && silenceSec >= netUnstableThresholdSec()) {
                            log("⚠ no pong for ${silenceSec}s - firing network-instability notification (session continues)")
                            instabilityNotifiedThisGap = true
                            onNetworkUnstable(silenceSec)
                        }
                    }

                    // Rebind triggers - either:
                    //   1. send threw a non-PortUnreachable IOException
                    //      (network change / interface gone)
                    //   2. N consecutive cycles got zero pongs
                    //      (silent path where sends succeeded into the void)
                    val sendFailed = !pingOk || !ctrlOk
                    if (sendFailed || sinceRebind >= SILENT_FAILURE_REBIND_AFTER) {
                        try {
                            rebindSocket()
                            sinceRebind = 0
                        } catch (e: IOException) {
                            log("⚠ rebind failed (${e.javaClass.simpleName}: ${e.message}) - will retry next cycle")
                        }
                    }

                    val signaled = withTimeoutOrNull(pingIntervalMs) {
                        disconnectSignal.receive()
                    } != null
                    if (signaled) {
                        sendBye(serverConnId, txKey, txCounter)
                        return@withContext
                    }
                }
            } finally {
                dataJob.cancelAndJoin()
                // Critical: cancel + join every in-flight ctrl-retx coroutine.
                // They're children of this withContext block, and structured
                // concurrency would otherwise make withContext wait for their
                // delay()s (up to 60 s each) to complete - leaving the parent
                // service's connectionJob suspended forever and the UI's
                // "server toggle is green, can't toggle off" symptom that
                // showed up when ICMP killed the session.
                pendingCtrlAcks.values.forEach { it.cancel() }
                pendingCtrlAcks.values.forEach { it.join() }
                pendingCtrlAcks.clear()
            }
        } catch (e: PortUnreachableException) {
            // Definitive: server refused our packet (restarted with empty
            // session table, or never came up). No point retrying - even if it
            // comes back, it has no ECDH state for our connID.
            log("✗ server port unreachable - session unrecoverable")
            ConnectionState.setLastError(UiError(
                title = appContext.getString(R.string.err_port_unreachable_title),
                detail = appContext.getString(R.string.err_port_unreachable_detail, host, port),
            ))
        } catch (e: Exception) {
            log("✗ ${e.javaClass.simpleName}: ${e.message}")
            ConnectionState.setLastError(UiError(
                title = appContext.getString(R.string.err_network_title),
                detail = appContext.getString(
                    R.string.err_network_detail,
                    e.javaClass.simpleName,
                    e.message ?: appContext.getString(R.string.err_network_no_detail),
                    port,
                ),
            ))
        } finally {
            socket?.close()
            socket = null
            log("socket closed")
        }
    }

    private fun sendBye(connId: Long, key: ByteArray, counter: AtomicLong) {
        // NACK ctrl type - server won't ACK. Burst Config.BYE_BURST_SIZE times
        // for UDP-loss tolerance. Same ctrlNonce on each so the server's
        // rxNonces dedupe handles the duplicates; the AEAD nonce differs per
        // send because the counter advances.
        try {
            val ctrlNonce = SecureRandom().nextInt() and 0xFFFF
            val ctrlFrame = CtrlFrame(CtrlTy.Bye, ctrlNonce, ByteArray(0))
            repeat(Config.BYE_BURST_SIZE) {
                val ctr = counter.getAndIncrement()
                val byeBody = Marshaller.marshalCtrl(ctrlFrame, key, ctr)
                sendFrame(connId, FrameTy.Ctrl, byeBody)
            }
            log("→ Bye ×${Config.BYE_BURST_SIZE} (graceful disconnect)")
        } catch (e: Exception) {
            log("⚠ failed to send Bye: ${e.message}")
        }
    }

    /**
     * Send a single frame. Transient IO errors (NoRouteToHost, generic
     * SocketException - typically ICMP-driven temporary unreachability) are
     * logged and swallowed; the next ping cycle retries. [PortUnreachableException]
     * is the one ICMP signal we treat as definitive: it means the server
     * either isn't listening (never started) or restarted and lost the ECDH
     * state for our connID. Either way, our session is unrecoverable, so we
     * let it propagate to the outer catch in [run].
     */
    private fun sendFrame(connId: Long, frameType: Int, body: ByteArray): Boolean {
        val s = socket ?: return false
        val wire = Marshaller.marshalAbstractFrame(AbstractFrame(connId, frameType, body))
        return try {
            s.send(DatagramPacket(wire, wire.size))
            true
        } catch (e: PortUnreachableException) {
            throw e
        } catch (e: IOException) {
            log("⚠ transient send error (${e.javaClass.simpleName}): ${e.message}")
            false
        }
    }

    /**
     * Receive a single frame. [SocketTimeoutException] is the no-data path -
     * returns null. Transient IOExceptions are logged + swallowed (same
     * rationale as [sendFrame]). [PortUnreachableException] propagates so the
     * session terminates.
     */
    private fun recvFrame(): AbstractFrame? {
        val s = socket ?: return null
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            s.receive(pkt)
            Marshaller.unmarshalAbstractFrame(buf.copyOfRange(0, pkt.length))
                .also { if (it == null) log("⚠ dropped frame (bad CRC / malformed)") }
        } catch (_: SocketTimeoutException) {
            null
        } catch (e: PortUnreachableException) {
            throw e
        } catch (e: IOException) {
            log("⚠ transient recv error (${e.javaClass.simpleName}): ${e.message}")
            null
        }
    }

    /**
     * Sets a [UiError] from a server-initiated termination ctrl and arms the
     * loop to exit cleanly without sending Bye back. Idempotent - second and
     * later calls just no-op (the first server-side reason wins).
     */
    private fun handleServerCtrlExit(title: String, detail: String) {
        if (serverInitiatedExit) return
        serverInitiatedExit = true
        log("✗ server-initiated exit: $title")
        ConnectionState.setLastError(UiError(title = title, detail = detail))
    }

    /**
     * Replaces [socket] with a freshly-bound one. Used to roam between
     * networks (WiFi ↔ cellular) without tearing down the cryptographic
     * session: the new socket binds to whatever the OS now considers the
     * default interface, the next packet teaches the server our new
     * source addr (server identifies sessions by connID, not source addr),
     * and the session continues. txKey / rxKey / serverConnId / txCounter
     * all survive untouched.
     */
    private fun rebindSocket() {
        val old = socket
        // Same scoping foot-gun as the initial bind: `apply` would shadow our
        // outer `port` field with DatagramSocket's own `port` getter. Use
        // `also { it. ... }` so `port` / `host` resolve to the class fields.
        val fresh = DatagramSocket().also {
            it.connect(InetAddress.getByName(host), port)
            it.soTimeout = Config.RECV_TIMEOUT_MS
        }
        socket = fresh
        try { old?.close() } catch (_: Throwable) { /* best-effort */ }
        log("↻ socket rebound to $host:$port (network change?)")
    }

    private fun u64hex(v: Long): String = "%016x".format(v)

    /**
     * Builds the "Download APK from this server" action list for a UiError.
     * Empty when the user hasn't configured a Viewer URL (nothing to point
     * at), so the dialog just shows the textual heads-up without a dead
     * button. URL convention matches the landing-page publishing flow -
     * `<urlPrefix>/static/pl.dubba.share.apk`.
     */
    private fun apkDownloadActions(label: String): List<UiErrorAction> {
        if (urlPrefix.isBlank()) return emptyList()
        val url = urlPrefix.trimEnd('/') + "/static/pl.dubba.share.apk"
        return listOf(UiErrorAction.OpenUrl(label, url))
    }

    companion object {
        /**
         * Number of consecutive ping cycles with no pong before we assume the
         * sends are going into a dead interface and trigger a socket rebind.
         * At the default 5s ping cadence, 3 cycles = ~15s of silent
         * unresponsiveness, which is well past any transient packet-loss
         * window while still being responsive enough that the user notices
         * the LED only briefly drop.
         */
        private const val SILENT_FAILURE_REBIND_AFTER: Int = 3

        /**
         * Per-ctrl connection-dead deadline. Matches the spec's
         * `TimeoutConnected` (the inactivity timeout the server uses for the
         * same purpose on its side; both sides should agree). Retx schedule
         * starts at 500 ms and walks up to 60 s, then keeps re-firing at 60 s
         * until either the server ACKs or this deadline is hit. At 600 s we
         * give up on this particular ctrl and declare the connection dead.
         */
        private const val CTRL_DEAD_TIMEOUT_MS: Long = 600_000L
    }
}
