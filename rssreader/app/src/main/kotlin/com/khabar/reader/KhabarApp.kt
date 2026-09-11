package com.khabar.reader

import android.app.Application
import com.khabar.reader.data.KhabarDatabase
import com.khabar.reader.data.PrefsRepository
import com.khabar.reader.net.FeedFetcher
import com.khabar.reader.repo.FeedRepository
import com.khabar.reader.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app's object graph. Small enough that a DI framework would cost more than it saves —
 * three singletons, built on first use.
 */
class KhabarApp : Application() {

    val database: KhabarDatabase by lazy { KhabarDatabase.open(this) }
    val prefsRepository: PrefsRepository by lazy { PrefsRepository(this) }
    val repository: FeedRepository by lazy {
        FeedRepository(database.feedDao(), database.articleDao(), FeedFetcher())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Re-apply on every start: an interval changed in Settings and then a reboot would
        // otherwise leave the old schedule in place.
        scope.launch { RefreshScheduler.apply(this@KhabarApp, prefsRepository.current()) }
    }
}
