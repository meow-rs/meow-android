package io.github.madeye.meow.subscription

import java.util.Locale

/**
 * Plan usage a provider reports in the `subscription-userinfo` response header,
 * e.g. `upload=455727941; download=6174315083; total=1073741824000; expire=1798675200`.
 *
 * Everything here is provider-reported and counted across every device on the
 * account, unlike the profile's `tx`/`rx`, which are this device's own traffic.
 * 0 means "not reported": a [total] of 0 is an unknown (or unlimited) quota and
 * an [expire] of 0 a plan without an end date.
 *
 * Stored on `ClashProfile` as an embedded column group, so the property names
 * are part of the Room schema.
 */
data class SubscriptionUserInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    /** Unix epoch **seconds**, the header's unit. */
    val expire: Long = 0,
) {
    /** Saturates, so two huge reported counters can't wrap negative. */
    val used: Long
        get() = if (upload > Long.MAX_VALUE - download) Long.MAX_VALUE else upload + download

    val hasQuota: Boolean get() = total > 0

    val hasExpiry: Boolean get() = expire > 0

    /** Share of [total] used, clamped to 0..1 — providers keep counting past the quota. */
    fun usedFraction(): Float =
        if (total <= 0) 0f else (used.toDouble() / total).toFloat().coerceIn(0f, 1f)

    fun isExpired(nowEpochSeconds: Long): Boolean = hasExpiry && expire <= nowEpochSeconds

    companion object {
        /** The response header; `HttpURLConnection` looks it up case-insensitively. */
        const val HEADER = "subscription-userinfo"

        val NONE = SubscriptionUserInfo()

        /**
         * Parses a header value, leniently: the format is a convention, not a
         * spec, and panels differ in the details. Keys may come in any order
         * and case, with or without spaces around `;` and `=`; missing keys
         * read as 0 and unknown ones are ignored.
         *
         * A value that isn't a number reads as 0 (not reported) rather than
         * failing the parse, so one bad field doesn't hide the good ones.
         * Negatives clamp to 0, decimals truncate, and a value past
         * [Long.MAX_VALUE] saturates.
         *
         * Returns [NONE] for a missing header, so callers store "not reported"
         * without a null check.
         */
        fun parse(header: String?): SubscriptionUserInfo {
            if (header.isNullOrBlank()) return NONE
            var upload = 0L
            var download = 0L
            var total = 0L
            var expire = 0L
            for (part in header.split(';')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val value = parseAmount(part.substring(eq + 1).trim())
                when (part.substring(0, eq).trim().lowercase(Locale.ROOT)) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = value
                }
            }
            return SubscriptionUserInfo(upload, download, total, expire)
        }

        private fun parseAmount(raw: String): Long {
            raw.toLongOrNull()?.let { return it.coerceAtLeast(0) }
            // Not a plain Long: either a float some panels emit (`1.5E10`) or
            // digits past Long.MAX_VALUE. Double.toLong() truncates the first,
            // saturates the second, and maps NaN to 0.
            val asDouble = raw.toDoubleOrNull() ?: return 0
            return asDouble.toLong().coerceAtLeast(0)
        }
    }
}
