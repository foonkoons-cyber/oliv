package com.depthmaker.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.depthmaker.app.toon.EncoderCapabilities
import com.depthmaker.app.toon.FpsPreset
import com.depthmaker.app.toon.ResolutionPreset
import com.depthmaker.app.toon.StylizeCancelled
import com.depthmaker.app.toon.ToonFilter
import com.depthmaker.app.toon.VideoStylizer
import com.depthmaker.app.util.PickResult
import com.depthmaker.app.util.VideoInspector
import com.depthmaker.app.util.VideoMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ToonState(
    val meta: VideoMeta? = null,
    val filter: ToonFilter = ToonFilter.CARTOON,
    val strength: Float = 0.85f,
    val resolution: ResolutionPreset = ResolutionPreset.P720,
    val fps: FpsPreset = FpsPreset.UP_TO_60,
    val running: Boolean = false,
    val progress: Float = 0f,
    val result: File? = null,
    val resultSummary: String? = null,
    val elapsedMs: Long = 0L,
    val warning: String? = null,
    val error: String? = null,
    val notice: String? = null
) {
    val supports720p60: Boolean get() = EncoderCapabilities.supports720p60()
}

/**
 * Drives the on-device stylizer. Nothing here touches the network — this path
 * has no backend at all, which is the whole point of it.
 */
class ToonViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ToonState())
    val state: StateFlow<ToonState> = _state.asStateFlow()

    private var stylizer: VideoStylizer? = null
    private var job: Job? = null

    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { VideoInspector.inspect(getApplication(), uri) }) {
                is PickResult.Ok -> _state.value = _state.value.copy(
                    meta = r.meta, result = null, resultSummary = null, warning = null, progress = 0f
                )
                is PickResult.Rejected -> _state.value = _state.value.copy(error = r.message)
            }
        }
    }

    fun setFilter(filter: ToonFilter) { _state.value = _state.value.copy(filter = filter) }
    fun setStrength(value: Float) { _state.value = _state.value.copy(strength = value) }
    fun setResolution(value: ResolutionPreset) { _state.value = _state.value.copy(resolution = value) }
    fun setFps(value: FpsPreset) { _state.value = _state.value.copy(fps = value) }
    fun clearSelection() { _state.value = ToonState(filter = _state.value.filter) }
    fun dismissError() { _state.value = _state.value.copy(error = null) }
    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }
    fun showNotice(text: String) { _state.value = _state.value.copy(notice = text) }

    fun export() {
        val s = _state.value
        val meta = s.meta ?: return
        if (s.running) return

        val output = File(getApplication<Application>().cacheDir, "toon_${System.currentTimeMillis()}.mp4")
        _state.value = s.copy(running = true, progress = 0f, result = null, resultSummary = null, warning = null)

        job = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val result = withContext(Dispatchers.Default) {
                    VideoStylizer(
                        context = getApplication(),
                        input = meta.uri,
                        output = output,
                        filter = s.filter,
                        strength = s.strength,
                        resolution = s.resolution,
                        fpsPreset = s.fps,
                        sourceFpsHint = meta.fps
                    ).also { stylizer = it }.stylize { progress ->
                        _state.value = _state.value.copy(progress = progress)
                    }
                }
                val elapsed = System.currentTimeMillis() - startedAt
                _state.value = _state.value.copy(
                    running = false,
                    progress = 1f,
                    result = result.file,
                    resultSummary = result.plan.summary(),
                    elapsedMs = elapsed,
                    warning = result.plan.downgradeReason
                )
            } catch (e: StylizeCancelled) {
                output.delete()
                _state.value = _state.value.copy(running = false, progress = 0f)
            } catch (e: CancellationException) {
                output.delete()
                throw e
            } catch (e: Exception) {
                output.delete()
                _state.value = _state.value.copy(
                    running = false,
                    progress = 0f,
                    error = "Export fail hua: ${e.message ?: e.javaClass.simpleName}"
                )
            } finally {
                stylizer = null
            }
        }
    }

    fun cancel() {
        stylizer?.cancel()
    }

    override fun onCleared() {
        stylizer?.cancel()
        job?.cancel()
        super.onCleared()
    }
}
