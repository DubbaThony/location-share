package pl.dubba.share.net.version

import android.content.Context
import pl.dubba.share.R
import pl.dubba.share.net.HttpJson

/**
 * Fetches the operator contact (compliance email) from the server's
 * `/gpdr-email` endpoint. Shape (matches `BasicResponse[string]`):
 *
 *   { "status": true, "result": "admin@example.com" }
 *
 * The operator may leave this unset; we treat blank or whitespace result as
 * "no contact published" rather than "server is broken" - same UX bucket as
 * a transport failure from the screen's point of view.
 *
 * Transport, SSL bypass, and the envelope check all live in [HttpJson].
 */
object ContactFetcher {

    sealed class Result {
        /** Non-blank operator contact string (typically an email address). */
        data class Success(val contact: String) : Result()

        /** HTTP-layer failure or server returned no contact info. */
        data class Unavailable(val message: String) : Result()
    }

    fun fetch(
        context: Context,
        urlPrefix: String,
        timeoutMs: Int = 5000,
        trustAllCerts: Boolean = false,
    ): Result {
        return when (val r = HttpJson.fetch(context, urlPrefix, "gpdr-email", timeoutMs, trustAllCerts)) {
            is HttpJson.Result.HttpFailure -> Result.Unavailable(r.message)
            is HttpJson.Result.BadResponse -> Result.Unavailable(r.message)
            is HttpJson.Result.Success -> {
                val contact = r.body.optString("result", "").trim()
                if (contact.isEmpty()) {
                    Result.Unavailable(context.getString(R.string.privacy_contact_unset))
                } else {
                    Result.Success(contact)
                }
            }
        }
    }
}
