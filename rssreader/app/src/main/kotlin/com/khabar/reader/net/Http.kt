package com.khabar.reader.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {

    /**
     * One client for the whole app so the connection pool is shared. The User-Agent is explicit
     * because a fair number of CDNs answer OkHttp's default with 403, which then looks to the
     * user like a broken feed.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    const val USER_AGENT = "Khabar/1.0 (Android; +https://github.com/foonkoons-cyber/oliv)"
}
