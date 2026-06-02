package pl.dubba.share.net.version

import android.content.Context
import android.content.pm.PackageManager
import pl.dubba.share.util.Hex
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Local-side hashing helpers used by the About screen's "matches the server"
 * check. Three operations:
 *
 *   1. SHA-512 over the running APK file at [android.content.pm.ApplicationInfo.sourceDir].
 *      Matches the server's `preffered_app_build_hash` (server hashes the
 *      embedded APK with SHA-512 too - see `appHash` in `server/main.go`).
 *
 *   2. SHA-256 over the running APK's signing certificate, pulled via
 *      [PackageManager.GET_SIGNING_CERTIFICATES]. This is the value `keytool`
 *      and `apksigner --print-certs` call "Signer #1 certificate SHA-256 digest".
 *
 *   3. SHA-256 over the X.509 cert embedded in a PKCS#7 SignedData blob the
 *      server hands us via `local_signer`. CertificateFactory parses PKCS#7
 *      out of the box; we take the first cert (Android's signing block has
 *      exactly one, even when v1+v2+v3 are all present - same cert, repackaged).
 *
 * The whole point of doing (1) and (2) on the running APK is so the user can
 * compare them against the server's published values without the server ever
 * sending us a pre-computed hash. If we trusted the server's hash we'd be
 * comparing it against itself.
 */
object AppFingerprint {

    /**
     * SHA-512 of the running APK file, as lowercase hex (no `0x` prefix -
     * matches Go's `hex.EncodeToString` default).
     *
     * Reads in 64 KiB chunks; cost is ~150-200 ms on a midrange phone for a
     * ~25 MB APK, so the caller MUST run this off the main thread.
     */
    fun selfApkSha512(context: Context): String {
        val md = MessageDigest.getInstance("SHA-512")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return Hex.encode(md.digest())
    }

    /**
     * SHA-256 of the running APK's first signing certificate, as lowercase
     * hex. Same value `apksigner verify --print-certs` prints as the cert
     * digest. Returns null only if the platform somehow returns a
     * non-signed-package state - for a sideloaded APK on minSdk 28 that
     * shouldn't happen, but we don't crash if it does.
     */
    fun selfSignerSha256(context: Context): String? {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        if (signers.isEmpty()) return null
        return Hex.encode(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()))
    }

    /**
     * Parse the server's `local_signer` field (raw PKCS#7 SignedData blob
     * hex-encoded - the bytes of `META-INF/SHARE.RSA` from the embedded APK)
     * and return SHA-256 of the X.509 cert inside it, as lowercase hex.
     *
     * Returns null on any parse failure (bad hex, truncated blob, no cert
     * in the SignedData). Callers should treat null as "server didn't ship
     * a parseable signer fingerprint" rather than "signer mismatch" - those
     * are different UX states.
     */
    fun parseServerSignerSha256(localSignerHex: String): String? {
        val blob = Hex.decode(localSignerHex) ?: return null
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificates(blob.inputStream()).firstOrNull() as? X509Certificate
                ?: return null
            Hex.encode(MessageDigest.getInstance("SHA-256").digest(cert.encoded))
        } catch (_: Exception) {
            null
        }
    }
}
