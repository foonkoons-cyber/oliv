package com.depthmaker.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.depthmaker.app.data.Settings
import com.depthmaker.app.data.SettingsRepository
import com.depthmaker.app.util.PickResult
import com.depthmaker.app.util.VideoInspector
import com.depthmaker.app.util.VideoMeta
import com.depthmaker.app.work.DepthJobWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class Screen { Home, Processing, Result, Settings }

data class ResultInfo(
    val file: File,
    val mime: String,
    val originalName: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val durationMs: Long
)

data class UiState(
    val screen: Screen = Screen.Home,
    val meta: VideoMeta? = null,
    val progress: Int = 0,
    val stage: String = "",
    val etaSeconds: Int = -1,
    val result: ResultInfo? = null,
    val error: String? = null,
    val notice: String? = null,
    val settings: Settings = Settings("", "", "vits", "mp4")
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Kept so the Result screen can report source dimensions after a restart. */
    private var lastMeta: VideoMeta? = null

    init {
        viewModelScope.launch {
            repo.settings.collect { s -> _state.value = _state.value.copy(settings = s) }
        }
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(DepthJobWorker.UNIQUE_WORK)
                .collect { infos -> onWorkInfo(infos.lastOrNull()) }
        }
    }

    // ---------- picking ----------

    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            when (val r = VideoInspector.inspect(getApplication(), uri)) {
                is PickResult.Ok -> {
                    lastMeta = r.meta
                    _state.value = _state.value.copy(meta = r.meta, error = null, screen = Screen.Home)
                }
                is PickResult.Rejected ->
                    _state.value = _state.value.copy(error = r.message)
            }
        }
    }

    fun clearSelection() {
        _state.value = _state.value.copy(meta = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun showNotice(text: String) {
        _state.value = _state.value.copy(notice = text)
    }

    fun openSettings() {
        _state.value = _state.value.copy(screen = Screen.Settings)
    }

    fun closeSettings() {
        _state.value = _state.value.copy(
            screen = if (_state.value.result != null) Screen.Result else Screen.Home
        )
    }

    fun newVideo() {
        workManager.pruneWork()
        _state.value = _state.value.copy(
            screen = Screen.Home, meta = null, result = null,
            progress = 0, stage = "", etaSeconds = -1
        )
    }

    // ---------- job control ----------

    fun startJob() {
        val meta = _state.value.meta ?: return
        val s = _state.value.settings
        if (!SettingsRepository.isValidServerUrl(s.serverUrl)) {
            _state.value = _state.value.copy(
                error = "Server URL set nahi hai. Settings me https:// URL daalo."
            )
            return
        }

        val data = Data.Builder()
            .putString(DepthJobWorker.KEY_SOURCE_URI, meta.uri.toString())
            .putString(DepthJobWorker.KEY_DISPLAY_NAME, meta.displayName)
            .putString(DepthJobWorker.KEY_SERVER_URL, s.serverUrl)
            .putString(DepthJobWorker.KEY_TOKEN, s.token)
            .putString(DepthJobWorker.KEY_MODEL, s.model)
            .putString(DepthJobWorker.KEY_FORMAT, s.format)
            .build()

        val request = OneTimeWorkRequestBuilder<DepthJobWorker>()
            .setInputData(data)
            .build()

        _state.value = _state.value.copy(
            screen = Screen.Processing, progress = 0, stage = "Shuru ho raha hai", etaSeconds = -1,
            result = null, error = null
        )
        workManager.enqueueUniqueWork(
            DepthJobWorker.UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request
        )
    }

    fun cancelJob() {
        workManager.cancelUniqueWork(DepthJobWorker.UNIQUE_WORK)
        _state.value = _state.value.copy(screen = Screen.Home, progress = 0, stage = "")
    }

    fun retry() = startJob()

    // ---------- settings ----------

    fun setModel(v: String) = viewModelScope.launch { repo.setModel(v) }
    fun setFormat(v: String) = viewModelScope.launch { repo.setFormat(v) }
    fun setToken(v: String) = viewModelScope.launch { repo.setToken(v.trim()) }

    fun setServerUrl(v: String): Boolean {
        val trimmed = v.trim()
        if (!SettingsRepository.isValidServerUrl(trimmed)) {
            _state.value = _state.value.copy(error = "Sirf https:// URL chalega")
            return false
        }
        viewModelScope.launch { repo.setServerUrl(trimmed) }
        return true
    }

    // ---------- work observation ----------

    private fun onWorkInfo(info: WorkInfo?) {
        if (info == null) return
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                val p = info.progress
                val pct = p.getInt(DepthJobWorker.KEY_PROGRESS, _state.value.progress)
                val stage = p.getString(DepthJobWorker.KEY_STAGE) ?: _state.value.stage
                val eta = p.getInt(DepthJobWorker.KEY_ETA, -1)
                _state.value = _state.value.copy(
                    screen = if (_state.value.screen == Screen.Settings) Screen.Settings else Screen.Processing,
                    progress = maxOf(_state.value.progress, pct),
                    stage = stage,
                    etaSeconds = eta
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                val out = info.outputData
                val path = out.getString(DepthJobWorker.KEY_OUT_FILE) ?: return
                val meta = lastMeta
                _state.value = _state.value.copy(
                    screen = Screen.Result,
                    progress = 100,
                    result = ResultInfo(
                        file = File(path),
                        mime = out.getString(DepthJobWorker.KEY_OUT_MIME) ?: "video/mp4",
                        originalName = out.getString(DepthJobWorker.KEY_OUT_NAME) ?: "video",
                        sizeBytes = out.getLong(DepthJobWorker.KEY_OUT_SIZE, 0L),
                        width = meta?.width ?: 0,
                        height = meta?.height ?: 0,
                        fps = meta?.fps ?: 0f,
                        durationMs = meta?.durationMs ?: 0L
                    )
                )
            }
            WorkInfo.State.FAILED -> {
                val msg = info.outputData.getString(DepthJobWorker.KEY_ERROR)
                    ?: "Kuch galat ho gaya. Dobara try karo."
                _state.value = _state.value.copy(screen = Screen.Home, error = msg, progress = 0)
            }
            WorkInfo.State.CANCELLED -> {
                if (_state.value.screen == Screen.Processing) {
                    _state.value = _state.value.copy(screen = Screen.Home, progress = 0, stage = "")
                }
            }
        }
    }
}
