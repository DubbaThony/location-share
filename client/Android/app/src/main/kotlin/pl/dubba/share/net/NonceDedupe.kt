package pl.dubba.share.net

/**
 * Bounded "have I seen this nonce already?" set. Used to drop duplicate Ctrl
 * frames bursted by the server - per spec we ACK every copy but process only
 * the first one.
 *
 * Bounded so we never need a TTL sweep: when at [capacity], inserting a new
 * nonce evicts the oldest one. At the spec's 600 s window with our cadence
 * (≤ a handful of distinct server-sent ctrls per minute), 256 is comfortable.
 *
 * Not thread-safe - only the ping-loop coroutine touches it.
 */
internal class NonceDedupe(private val capacity: Int) {
    // LinkedHashSet preserves insertion order - eldest is `iterator().next()`.
    private val seen = LinkedHashSet<Int>(capacity * 2)

    /**
     * Returns true if this nonce is the first time we've seen it (caller
     * should process the frame). False if it's a duplicate - caller should
     * still ACK it but drop the payload.
     */
    fun markSeen(nonce: Int): Boolean {
        if (nonce in seen) return false
        if (seen.size >= capacity) {
            // Evict eldest.
            val it = seen.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        seen.add(nonce)
        return true
    }
}
