package com.best.player

import java.util.Locale

object TimeFormat {
    /** 1:02:03 / 02:03, or with milliseconds (02:03.456) while scrubbing. */
    fun format(ms: Long, withMillis: Boolean = false): String {
        val safe = ms.coerceAtLeast(0)
        val totalSec = safe / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val base = if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
        return if (withMillis) String.format(Locale.US, "%s.%03d", base, safe % 1000) else base
    }

    /** File-name friendly timestamp, e.g. 01-02-03.456 */
    fun forFileName(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val totalSec = safe / 1000
        return String.format(
            Locale.US, "%02d-%02d-%02d.%03d",
            totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60, safe % 1000,
        )
    }
}
