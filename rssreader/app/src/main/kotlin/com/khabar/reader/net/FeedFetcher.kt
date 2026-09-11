package com.khabar.reader.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Fetches a feed (or the web page a feed might be discovered in).
 *
 * Never throws: a reader is used on trains and in lifts, so a failed fetch is a normal state the
 * UI shows next to the feed, not an exception anyone has to catch.
 */
class FeedFetcher(private val client: OkHttpClient = Http.client) {

    sealed interface Result {
        data class Ok(
            val bytes: ByteArray,
            val charset: String?,
            val etag: String?,
            val lastModified: String?,
            val finalUrl: String,
            val contentType: String?
        ) : Result

        data object NotModified : Result

        data class Failed(val message: String) : Result
    }

    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null
    ): Result = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Http.USER_AGENT)
                .header(
                    "Accept",
                    "application/rss+xml, application/atom+xml, application/xml;q=0.9, " +
                        "text/xml;q=0.9, application/rdf+xml;q=0.8, text/html;q=0.7, */*;q=0.5"
                )
                .apply {
                    if (!etag.isNullOrBlank()) header("If-None-Match", etag)
                    if (!lastModified.isNullOrBlank()) header("If-Modified-Since", lastModified)
                }
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 304) return@withContext Result.NotModified
                if (!response.isSuccessful) {
                    return@withContext Result.Failed(httpMessage(response.code))
                }
                val body = response.body
                    ?: return@withContext Result.Failed("Server ne khali jawab bheja.")
                val bytes = body.byteStream().use { readCapped(it) }
                if (bytes.isEmpty()) {
                    return@withContext Result.Failed("Feed khali hai.")
                }
                Result.Ok(
                    bytes = bytes,
                    charset = body.contentType()?.charset()?.name(),
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                    finalUrl = response.request.url.toString(),
                    contentType = response.header("Content-Type")
                )
            }
        } catch (e: UnknownHostException) {
            Result.Failed("Address nahi mila — internet ya URL check karo.")
        } catch (e: SocketTimeoutException) {
            Result.Failed("Server time-out ho gaya.")
        } catch (e: SSLException) {
            Result.Failed("Secure connection nahi ban payi.")
        } catch (e: IOException) {
            Result.Failed("Network error: ${e.message ?: "connect nahi ho paya"}")
        } catch (e: IllegalArgumentException) {
            Result.Failed("URL sahi nahi hai.")
        } catch (e: IllegalStateException) {
            Result.Failed("Feed padha nahi ja saka.")
        }
    }

    /** Stops at 8 MB. A feed that big is a misconfigured site, not something to hold in memory. */
    private fun readCapped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            total += n
            if (total > MAX_BYTES) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun httpMessage(code: Int): String = when (code) {
        401, 403 -> "Is feed ne access mana kar diya ($code)."
        404, 410 -> "Feed ab exist nahi karta ($code)."
        429 -> "Bahut zyada requests — thodi der baad try karo (429)."
        in 500..599 -> "Server down lag raha hai ($code)."
        else -> "Server ne $code bheja."
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
