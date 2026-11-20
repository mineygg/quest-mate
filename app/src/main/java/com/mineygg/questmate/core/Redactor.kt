package com.mineygg.questmate.core

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Last line of defence for the log: every line goes through [scrub] before it is stored, shown or copied,
 * so a token can't end up in the log even if some code path formats one into a message by mistake.
 *
 * Two layers: exact secrets the app knows ([register]: the account token, webhook URL, qos_token), and
 * patterns for anything that looks like a credential (Discord tokens, webhook URLs, Authorization/Cookie
 * values, `"token": "..."` pairs).
 */
object Redactor {
    private const val MASK = "***"
    private val secrets = CopyOnWriteArraySet<String>()

    /** Remembers a secret so it is masked wherever it shows up. Short values are ignored (they'd match everything). */
    fun register(secret: String?) {
        val s = secret?.trim()
        if (s != null && s.length >= 8) secrets.add(s)
    }

    private val whole = listOf(
        Regex("""mfa\.[\w-]{20,}"""),
        Regex("""[\w-]{20,32}\.[\w-]{5,8}\.[\w-]{25,}"""),
        Regex("""(?i)https?://(?:[\w-]+\.)?discord(?:app)?\.com/api/(?:v\d+/)?webhooks/\d+/[\w-]+"""),
        Regex("""(?i)\bBearer\s+[\w.~+/=-]+""")
    )

    private val keyed = Regex(
        """(?i)("?(?:token|qos_token|authorization|password|secret|cookie|set-cookie|cf_clearance|__dcfduid|__sdcfduid|__cfruid|session_id)"?\s*[:=]\s*)("[^"]*"|[^\s,;}&]+)"""
    )

    fun scrub(text: String): String {
        var out = text
        for (s in secrets.sortedByDescending { it.length }) out = out.replace(s, MASK)
        for (r in whole) out = r.replace(out, MASK)
        out = keyed.replace(out) { m ->
            m.groupValues[1] + if (m.groupValues[2].startsWith("\"")) "\"$MASK\"" else MASK
        }
        return out
    }
}
