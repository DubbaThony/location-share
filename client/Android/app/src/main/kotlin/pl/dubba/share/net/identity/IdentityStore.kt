package pl.dubba.share.net.identity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.net.URI

/**
 * Per-server Ed25519 identity store. Keyed by the **Viewer URL prefix** -
 * specifically its normalized origin (scheme + host + port). That's the
 * entity whose TLS cert chain we use to anchor identity, so the identity
 * naturally belongs to it. Switching UDP host but keeping the same
 * urlPrefix reuses the cached identity (correct - same authority);
 * switching urlPrefix creates a separate cache entry (correct - different
 * authority).
 *
 * **Storage location: `cacheDir`, not `filesDir`.** Identity is rebuildable
 * - the server's `/identity` endpoint is the source of truth and a stale
 * or missing entry just costs one HTTPS round-trip on next connect (no
 * MITM window opens, because HTTPS is still the trust anchor for whatever
 * key arrives). Putting it in cacheDir gives us standard Android cache
 * semantics for free: the "Clear cache" button in app info nukes it, and
 * the OS reclaims it under storage pressure. Survives reboot / app kill /
 * update - cache only gets cleared by explicit user action or genuine
 * storage scarcity.
 *
 * Stored as hex strings; raw 32-byte Ed25519 public keys. Parsing is
 * lenient (`0x` prefix optional, mixed case OK) so the same store can hold
 * whatever the user pastes manually and what the fetcher returns from
 * `/identity` (which uses `0x` + uppercase per Go's `%X` format).
 */
private object IdentityDataStoreHolder {
    // Volatile + synchronized init so the first DataStore.create call wins
    // even if multiple threads hit IdentityStore at the same time (rare in
    // practice - service start and UI screen open could race in theory).
    @Volatile
    private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: PreferenceDataStoreFactory.create(
                produceFile = {
                    val dir = File(context.applicationContext.cacheDir, "datastore")
                        .apply { mkdirs() }
                    File(dir, "identity.preferences_pb")
                },
            ).also { instance = it }
        }
    }
}

object IdentityStore {

    /**
     * Normalize the URL prefix down to its origin (scheme + host + port).
     * That's the relevant unit for identity - a different path or a trailing
     * slash on the same host doesn't mean a different server. URI parse
     * fallback for malformed inputs: lowercase + trim and use as-is, so the
     * caller doesn't crash on a typo-in-progress.
     */
    private fun originOf(urlPrefix: String): String {
        val trimmed = urlPrefix.trim()
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return trimmed.lowercase()
            val host = uri.host?.lowercase() ?: return trimmed.lowercase()
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "$scheme://$host$port"
        } catch (_: Exception) {
            trimmed.lowercase()
        }
    }

    private fun keyForServer(urlPrefix: String) =
        stringPreferencesKey("identity_${originOf(urlPrefix)}")

    /** Returns the stored 32-byte pubkey for the server at [urlPrefix], or null if none. */
    suspend fun get(context: Context, urlPrefix: String): ByteArray? {
        val hex = IdentityDataStoreHolder.get(context).data.first()[keyForServer(urlPrefix)] ?: return null
        return parseHex(hex)
    }

    /** Observe identity changes for a single server. Emits null when the key is unset. */
    fun observe(context: Context, urlPrefix: String): Flow<ByteArray?> =
        IdentityDataStoreHolder.get(context).data.map { it[keyForServer(urlPrefix)]?.let(::parseHex) }

    /** Persist [pubkey] (must be 32 bytes) for the server at [urlPrefix]. */
    suspend fun put(context: Context, urlPrefix: String, pubkey: ByteArray) {
        require(pubkey.size == 32) { "Ed25519 pubkey must be 32 bytes, got ${pubkey.size}" }
        IdentityDataStoreHolder.get(context).edit { it[keyForServer(urlPrefix)] = pubkey.toHexUpper() }
    }

    /** Remove the stored identity for the server at [urlPrefix]. */
    suspend fun clear(context: Context, urlPrefix: String) {
        IdentityDataStoreHolder.get(context).edit { it.remove(keyForServer(urlPrefix)) }
    }

    // Storage is uppercase hex without `0x` prefix, matching the server's
    // `%X`-formatted /identity response - so a round-trip put(get(...)) is
    // stable byte-for-byte against the wire format. The shared [Hex.decode]
    // tolerates either case and either prefix on read; [Hex.encode] with
    // uppercase=true matches the on-disk convention on write.

    private fun parseHex(s: String): ByteArray? =
        pl.dubba.share.util.Hex.decode(s, expectedBytes = 32)

    private fun ByteArray.toHexUpper(): String =
        pl.dubba.share.util.Hex.encode(this, uppercase = true)
}
