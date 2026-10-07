package com.s1ambient

import java.security.MessageDigest

/** Rate limit is shared across connections, preventing reconnects from bypassing it. */
internal class PairingGate {
    private var failures = 0
    private var retryAt = 0L
    fun pair(code: String, expected: String, now: Long): Int {
        if (now < retryAt) return 429
        if (!equal(code, expected)) {
            failures++
            if (failures >= 5) { retryAt = now + 60_000; failures = 0 }
            return 401
        }
        failures = 0
        return 200
    }
    companion object {
        fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
