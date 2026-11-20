package com.gallery.feature.editor.viewmodel

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.gallery.core.domain.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

const val MIN_VIDEO_CLIP_DURATION_MS = 500L
private const val FILMSTRIP_FRAME_COUNT = 9

enum class VideoEditorTab(val label: String) {
    TRIM("Trim"),
    TRANSFORM("Transform"),
    FILTERS("Filters"),
    ADJUST("Adjust"),
    AUDIO_SPEED("Audio")
}

data class VideoEditorUiState(
    val sourceUri: Uri? = null,
    val durationMs: Long = 0L,
    val videoWidth: Int = 16,
    val videoHeight: Int = 9,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val currentPositionMs: Long = 0L,
    val isPlaying: Boolean = true,
    val rotationDegrees: Int = 0,
    val isFlippedHorizontal: Boolean = false,
    val activeFilter: ColorFilter = ColorFilter.ORIGINAL,
    val brightness: Float = 1.0f,
    val saturation: Float = 1.0f,
    val playbackSpeed: Float = 1.0f,
    val isMuted: Boolean = false,
    val selectedTab: VideoEditorTab = VideoEditorTab.TRIM,
    val filmstripFrames: List<Bitmap> = emptyList(),
    val isLoadingMetadata: Boolean = true,
    val isExporting: Boolean = false,
    val exportProgress: Int = 0,
    val error: String? = null
) {
    val trimmedDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    val videoAspectRatio: Float
        get() = if (videoWidth > 0 && videoHeight > 0) {
            (videoWidth.toFloat() / videoHeight.toFloat()).coerceIn(0.35f, 3.0f)
        } else {
            16f / 9f
        }

    val hasTrim: Boolean
        get() = durationMs > 0L && (trimStartMs > 0L || trimEndMs < durationMs)

    val hasColorEdits: Boolean
        get() = activeFilter != ColorFilter.ORIGINAL || brightness != 1.0f || saturation != 1.0f
}

/**
 * Builds an Android 5x4 [ColorMatrix] matching the selected [ColorFilter], brightness, and saturation.
 */
fun buildVideoColorMatrix(
    filter: ColorFilter,
    brightness: Float,
    saturation: Float
): ColorMatrix {
    val result = ColorMatrix()
    result.setSaturation(saturation)

    // Subtle artistic tint per preset beyond saturation/brightness
    when (filter) {
        ColorFilter.WARM -> {
            val warm = ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, 6f,
                    0f, 1.02f, 0f, 0f, 2f,
                    0f, 0f, 0.90f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            result.postConcat(warm)
        }
        ColorFilter.COOL -> {
            val cool = ColorMatrix(
                floatArrayOf(
                    0.92f, 0f, 0f, 0f, 0f,
                    0f, 1.00f, 0f, 0f, 2f,
                    0f, 0f, 1.10f, 0f, 8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            result.postConcat(cool)
        }
        ColorFilter.FADE -> {
            val fade = ColorMatrix(
                floatArrayOf(
                    0.90f, 0f, 0f, 0f, 16f,
                    0f, 0.90f, 0f, 0f, 16f,
                    0f, 0f, 0.90f, 0f, 16f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            result.postConcat(fade)
        }
        else -> Unit
    }

    val b = (brightness - 1.0f) * 128f
    if (b != 0f) {
        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(brightnessMatrix)
    }

    return result
}

/**
 * Converts an Android 5x4 [ColorMatrix] into a column-major 4x4 OpenGL matrix for Media3 [RgbMatrix].
 */
fun ColorMatrix.toOpenGl4x4Matrix(): FloatArray {
    val a = array
    return floatArrayOf(
        a[0], a[5], a[10], 0f,
        a[1], a[6], a[11], 0f,
        a[2], a[7], a[12], 0f,
        a[4] / 255f, a[9] / 255f, a[14] / 255f, 1f
    )
}

@UnstableApi
class ColorMatrixRgbEffect(
    private val matrix4x4: FloatArray
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix4x4
}

@HiltViewModel
class VideoEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoEditorUiState())
    val uiState: StateFlow<VideoEditorUiState> = _uiState.asStateFlow()

    private var metadataJob: Job? = null
    private var progressJob: Job? = null
    private var activeTransformer: Transformer? = null
    private var activeTempFile: File? = null

    fun loadVideo(uri: Uri) {
        if (_uiState.value.sourceUri == uri && _uiState.value.durationMs > 0L) return

        metadataJob?.cancel()
        recycleFilmstripFrames()

        _uiState.update {
            VideoEditorUiState(
                sourceUri = uri,
                isLoadingMetadata = true
            )
        }

        metadataJob = viewModelScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L) ?: 0L
                val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 16
                val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 9
                val videoRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0

                val (displayWidth, displayHeight) = if (videoRotation == 90 || videoRotation == 270) {
                    rawHeight to rawWidth
                } else {
                    rawWidth to rawHeight
                }

                _uiState.update { current ->
                    current.copy(
                        durationMs = durationMs,
                        videoWidth = displayWidth,
                        videoHeight = displayHeight,
                        trimStartMs = 0L,
                        trimEndMs = durationMs,
                        currentPositionMs = 0L,
                        isLoadingMetadata = false
                    )
                }

                // Extract evenly spaced filmstrip thumbnails asynchronously
                if (durationMs > 0L) {
                    val frames = mutableListOf<Bitmap>()
                    val stepUs = (durationMs * 1000L) / FILMSTRIP_FRAME_COUNT.coerceAtLeast(1)
                    for (i in 0 until FILMSTRIP_FRAME_COUNT) {
                        if (!isActive) break
                        val timeUs = (i * stepUs + stepUs / 2L).coerceIn(0L, (durationMs * 1000L).coerceAtLeast(0L))
                        val frame = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(
                                    timeUs,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    160,
                                    160
                                ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            } else {
                                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            }
                        } catch (_: Exception) {
                            null
                        }
                        if (frame != null) {
                            frames.add(frame)
                        }
                    }
                    if (isActive) {
                        _uiState.update { it.copy(filmstripFrames = frames) }
                    } else {
                        frames.forEach { if (!it.isRecycled) it.recycle() }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingMetadata = false,
                        error = e.message ?: "Unable to load video metadata"
                    )
                }
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }
        }
    }

    fun onPlayerDurationKnown(durationMs: Long) {
        if (durationMs <= 0L) return
        _uiState.update { state ->
            if (state.durationMs <= 0L) {
                state.copy(
                    durationMs = durationMs,
                    trimStartMs = 0L,
                    trimEndMs = durationMs,
                    isLoadingMetadata = false
                )
            } else {
                state
            }
        }
    }

    fun selectTab(tab: VideoEditorTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun setPlaying(playing: Boolean) {
        _uiState.update { it.copy(isPlaying = playing) }
    }

    fun togglePlayPause() {
        _uiState.update { it.copy(isPlaying = !it.isPlaying) }
    }

    fun updateCurrentPosition(positionMs: Long) {
        _uiState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun updateTrimStart(startMs: Long) {
        _uiState.update { state ->
            val maxStart = (state.trimEndMs - MIN_VIDEO_CLIP_DURATION_MS).coerceAtLeast(0L)
            val clampedStart = startMs.coerceIn(0L, maxStart)
            state.copy(
                trimStartMs = clampedStart,
                currentPositionMs = clampedStart
            )
        }
    }

    fun updateTrimEnd(endMs: Long) {
        _uiState.update { state ->
            val minEnd = (state.trimStartMs + MIN_VIDEO_CLIP_DURATION_MS).coerceAtMost(state.durationMs)
            val clampedEnd = endMs.coerceIn(minEnd, state.durationMs)
            state.copy(
                trimEndMs = clampedEnd,
                currentPositionMs = clampedEnd
            )
        }
    }

    fun resetTrim() {
        _uiState.update { state ->
            state.copy(
                trimStartMs = 0L,
                trimEndMs = state.durationMs,
                currentPositionMs = 0L
            )
        }
    }

    fun rotate(degrees: Int) {
        _uiState.update { state ->
            state.copy(rotationDegrees = (state.rotationDegrees + degrees).mod(360))
        }
    }

    fun toggleFlipHorizontal() {
        _uiState.update { it.copy(isFlippedHorizontal = !it.isFlippedHorizontal) }
    }

    fun applyFilter(filter: ColorFilter) {
        _uiState.update { state ->
            state.copy(
                activeFilter = filter,
                saturation = filter.saturation,
                brightness = filter.brightness
            )
        }
    }

    fun adjustBrightness(value: Float) {
        _uiState.update { it.copy(brightness = value.coerceIn(0.5f, 1.5f)) }
    }

    fun adjustSaturation(value: Float) {
        _uiState.update { it.copy(saturation = value.coerceIn(0f, 2.0f)) }
    }

    fun resetAdjustments() {
        _uiState.update {
            it.copy(
                activeFilter = ColorFilter.ORIGINAL,
                brightness = 1.0f,
                saturation = 1.0f
            )
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed.coerceIn(0.25f, 4.0f)) }
    }

    fun toggleMute() {
        _uiState.update { it.copy(isMuted = !it.isMuted) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    @OptIn(UnstableApi::class)
    fun exportVideo(onSaveSuccess: (Uri) -> Unit) {
        val state = _uiState.value
        val sourceUri = state.sourceUri ?: return
        if (state.isExporting) return

        // Pause preview while hardware encoder runs
        _uiState.update { it.copy(isPlaying = false, isExporting = true, exportProgress = 0, error = null) }

        val tempFile = File(context.cacheDir, "vid_export_${System.currentTimeMillis()}.mp4")
        activeTempFile = tempFile

        val mediaItemBuilder = MediaItem.Builder().setUri(sourceUri)
        if (state.hasTrim) {
            val endMs = state.trimEndMs.coerceAtLeast(state.trimStartMs + MIN_VIDEO_CLIP_DURATION_MS)
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(state.trimStartMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
        }
        val mediaItem = mediaItemBuilder.build()

        val audioProcessors = mutableListOf<AudioProcessor>()
        val videoEffects = mutableListOf<Effect>()

        if (state.playbackSpeed != 1.0f) {
            videoEffects.add(SpeedChangeEffect(state.playbackSpeed))
            if (!state.isMuted) {
                val sonic = SonicAudioProcessor().apply {
                    setSpeed(state.playbackSpeed)
                    setPitch(1.0f)
                }
                audioProcessors.add(sonic)
            }
        }

        if (state.rotationDegrees != 0 || state.isFlippedHorizontal) {
            videoEffects.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees((-state.rotationDegrees).toFloat())
                    .setScale(if (state.isFlippedHorizontal) -1f else 1f, 1f)
                    .build()
            )
        }

        if (state.hasColorEdits) {
            val colorMatrix = buildVideoColorMatrix(
                filter = state.activeFilter,
                brightness = state.brightness,
                saturation = state.saturation
            )
            videoEffects.add(ColorMatrixRgbEffect(colorMatrix.toOpenGl4x4Matrix()))
        }

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(state.isMuted)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                progressJob?.cancel()
                activeTransformer = null
                _uiState.update { it.copy(exportProgress = 100) }

                viewModelScope.launch {
                    val savedResult = withContext(Dispatchers.IO) {
                        saveExportedVideoToMediaStore(tempFile)
                    }
                    cleanupTempFile(tempFile)
                    savedResult.onSuccess { savedUri ->
                        _uiState.update { it.copy(isExporting = false, exportProgress = 100) }
                        onSaveSuccess(savedUri)
                    }.onFailure { err ->
                        _uiState.update {
                            it.copy(
                                isExporting = false,
                                exportProgress = 0,
                                error = err.message ?: "Failed to save edited video"
                            )
                        }
                    }
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                progressJob?.cancel()
                activeTransformer = null
                cleanupTempFile(tempFile)
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 0,
                        error = exportException.message ?: "Video export failed"
                    )
                }
            }
        }

        try {
            val transformer = Transformer.Builder(context)
                .addListener(listener)
                .build()
            activeTransformer = transformer
            transformer.start(editedMediaItem, tempFile.absolutePath)

            val progressHolder = ProgressHolder()
            progressJob?.cancel()
            progressJob = viewModelScope.launch(Dispatchers.Main) {
                while (isActive && activeTransformer === transformer) {
                    val progressState = transformer.getProgress(progressHolder)
                    if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        _uiState.update { it.copy(exportProgress = progressHolder.progress.coerceIn(0, 99)) }
                    }
                    delay(100L)
                }
            }
        } catch (e: Exception) {
            progressJob?.cancel()
            activeTransformer = null
            cleanupTempFile(tempFile)
            _uiState.update {
                it.copy(
                    isExporting = false,
                    exportProgress = 0,
                    error = e.message ?: "Unable to start video export"
                )
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun cancelExport() {
        progressJob?.cancel()
        progressJob = null
        try {
            activeTransformer?.cancel()
        } catch (_: Exception) {}
        activeTransformer = null
        cleanupTempFile(activeTempFile)
        _uiState.update { it.copy(isExporting = false, exportProgress = 0) }
    }

    private suspend fun saveExportedVideoToMediaStore(sourceFile: File): Result<Uri> {
        val isVault = _uiState.value.sourceUri?.toString()?.contains("vault") == true
        if (isVault) {
            return try {
                val result = vaultRepository.addFileToVault(
                    file = sourceFile,
                    originalName = "VID_EDITED_${System.currentTimeMillis()}.mp4",
                    mimeType = "video/mp4",
                    isVideo = true,
                    albumName = "Default"
                )
                if (result.isSuccess) {
                    Result.success(Uri.EMPTY)
                } else {
                    Result.failure(result.exceptionOrNull() ?: Exception("Failed to encrypt video to Secure Vault"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                if (sourceFile.exists()) sourceFile.delete()
            }
        }

        return try {
            if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                return Result.failure(IllegalStateException("Exported video file is empty"))
            }
            val fileName = "VID_EDITED_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Gallery")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    @Suppress("DEPRECATION")
                    val galleryDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Gallery"
                    ).apply { mkdirs() }
                    @Suppress("DEPRECATION")
                    put(MediaStore.Video.Media.DATA, File(galleryDir, fileName).absolutePath)
                }
            }

            val insertUri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return Result.failure(IllegalStateException("Failed to create MediaStore video entry"))

            context.contentResolver.openOutputStream(insertUri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                }
                outputStream.flush()
            } ?: return Result.failure(IllegalStateException("Failed to open video output stream"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(insertUri, publishValues, null, null)
            }

            Result.success(insertUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cleanupTempFile(file: File?) {
        if (file == null) return
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
        if (activeTempFile === file) {
            activeTempFile = null
        }
    }

    private fun recycleFilmstripFrames() {
        val frames = _uiState.value.filmstripFrames
        if (frames.isNotEmpty()) {
            _uiState.update { it.copy(filmstripFrames = emptyList()) }
            frames.forEach { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCleared() {
        super.onCleared()
        metadataJob?.cancel()
        progressJob?.cancel()
        try {
            activeTransformer?.cancel()
        } catch (_: Exception) {}
        activeTransformer = null
        cleanupTempFile(activeTempFile)
        recycleFilmstripFrames()
    }
}
