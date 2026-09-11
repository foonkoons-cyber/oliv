package com.khabar.reader.feed

import java.net.URI

/** URL handling for user input, relative links in feeds, and duplicate detection. */
object Urls {

    /**
     * Accepts whatever a user pastes: a bare domain, a full URL, or one of the pseudo-schemes
     * that "subscribe" buttons still emit. Returns null when there is nothing usable — a feed
     * reader should say "ye URL samajh nahi aaya", not try to fetch javascript:.
     */
    fun normalizeInput(raw: String?): String? {
        var s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        s = s.removePrefix("<").removeSuffix(">").trim()
        // feed:https://x/rss and feed://x/rss are both in the wild, and mean the same thing.
        for (prefix in listOf("feed:", "rss:", "atom:")) {
            if (s.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
                s = s.substring(prefix.length)
                if (s.startsWith("//")) s = "https:$s"
                s = s.trim()
                break
            }
        }
        val lower = s.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") ||
            lower.startsWith("data:") || lower.startsWith("file:") ||
            lower.startsWith("ftp:") || lower.startsWith("tel:")
        ) return null

        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (s.contains("://")) return null      // some other scheme we do not speak
            s = "https://$s"
        }
        val uri = try { URI(s) } catch (e: Exception) { return null }
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        // Reject "https://foo" — a single label is a typo far more often than an intranet host.
        if (!host.contains('.') && !host.equals("localhost", ignoreCase = true)) return null
        return s
    }

    /** Resolves a possibly-relative reference against a base URL. Null when unusable. */
    fun resolve(base: String?, ref: String?): String? {
        val r = ref?.trim() ?: return null
        if (r.isEmpty()) return null
        val lower = r.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") ||
            lower.startsWith("data:") || lower.startsWith("tel:")
        ) return null
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return try { URI(r).toString() } catch (e: Exception) { null }
        }
        if (base.isNullOrBlank()) return null
        return try {
            val resolved = URI(base).resolve(URI(r.replace(" ", "%20")))
            val scheme = resolved.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") resolved.toString() else null
        } catch (e: Exception) {
            null
        }
    }

    /** Host without "www.", for display. */
    fun host(url: String?): String? {
        val u = url ?: return null
        return try {
            URI(u).host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Key for "already subscribed". Scheme and www are ignored on purpose: http/https and
     * www/non-www of the same feed are the same subscription to everyone except a URL parser.
     */
    fun canonical(url: String?): String? {
        val u = url?.trim() ?: return null
        if (u.isEmpty()) return null
        return try {
            val uri = URI(if (u.contains("://")) u else "https://$u")
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            if (host.isEmpty()) return null
            val port = uri.port.takeIf { it != -1 && it != 80 && it != 443 }
            val path = (uri.rawPath ?: "").trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            buildString {
                append(host)
                if (port != null) append(':').append(port)
                append(path)
                append(query)
            }
        } catch (e: Exception) {
            null
        }
    }
}
