package com.gallery.feature.editor.viewmodel

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gallery.core.domain.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

enum class ColorFilter(val displayName: String, val saturation: Float, val brightness: Float) {
    ORIGINAL("Original", 1.0f, 1.0f),
    VIVID("Vivid", 1.6f, 1.1f),
    MUTED("Muted", 0.5f, 0.95f),
    BW("B&W", 0.0f, 1.0f),
    WARM("Warm", 1.2f, 1.05f),
    COOL("Cool", 1.1f, 0.95f),
    FADE("Fade", 0.7f, 1.08f)
}

data class EditorUiState(
    val sourceUri: Uri? = null,
    val rotationDegrees: Int = 0,
    val activeFilter: ColorFilter = ColorFilter.ORIGINAL,
    val brightness: Float = 1.0f,
    val saturation: Float = 1.0f,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val savedUri: Uri? = null,
    val error: String? = null,
    val isVaultItem: Boolean = false,
    val vaultItemId: String? = null
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun setIsVault(isVault: Boolean, vaultItemId: String? = null) {
        _uiState.update { it.copy(isVaultItem = isVault, vaultItemId = vaultItemId) }
    }

    fun setSourceUri(uri: Uri) {
        _uiState.update { it.copy(sourceUri = uri) }
    }

    fun rotate(degrees: Int) {
        _uiState.update { state ->
            state.copy(rotationDegrees = (state.rotationDegrees + degrees).mod(360))
        }
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
        _uiState.update { it.copy(brightness = value.coerceIn(0.5f, 2.0f)) }
    }

    fun adjustSaturation(value: Float) {
        _uiState.update { it.copy(saturation = value.coerceIn(0f, 2.0f)) }
    }

    fun saveEdit(onSuccess: (Uri) -> Unit) {
        val state = _uiState.value
        val sourceUri = state.sourceUri ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val result = withContext(Dispatchers.IO) {
                try {
                    // 1. Decode original bitmap
                    val inputStream = if (sourceUri.scheme == "file" || sourceUri.path?.startsWith("/") == true) {
                        val file = if (sourceUri.scheme == "file") File(sourceUri.path ?: "") else File(sourceUri.toString())
                        if (file.exists()) java.io.FileInputStream(file) else context.contentResolver.openInputStream(sourceUri)
                    } else {
                        context.contentResolver.openInputStream(sourceUri)
                    }
                    val originalBitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (originalBitmap == null) {
                        return@withContext Result.failure(Exception("Could not decode image"))
                    }

                    // 2. Apply rotation if needed
                    val rotatedBitmap = if (state.rotationDegrees != 0) {
                        val matrix = Matrix().apply { postRotate(state.rotationDegrees.toFloat()) }
                        val rotated = Bitmap.createBitmap(
                            originalBitmap, 0, 0,
                            originalBitmap.width, originalBitmap.height,
                            matrix, true
                        )
                        if (rotated != originalBitmap) originalBitmap.recycle()
                        rotated
                    } else {
                        originalBitmap
                    }

                    // 3. Apply color filters / brightness / saturation
                    val finalBitmap = Bitmap.createBitmap(
                        rotatedBitmap.width,
                        rotatedBitmap.height,
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(finalBitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                    val cmSaturation = ColorMatrix().apply { setSaturation(state.saturation) }
                    val cmBrightness = ColorMatrix().apply {
                        val b = (state.brightness - 1.0f) * 128f
                        set(
                            floatArrayOf(
                                1f, 0f, 0f, 0f, b,
                                0f, 1f, 0f, 0f, b,
                                0f, 0f, 1f, 0f, b,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    }
                    cmBrightness.preConcat(cmSaturation)
                    paint.colorFilter = ColorMatrixColorFilter(cmBrightness)
                    canvas.drawBitmap(rotatedBitmap, 0f, 0f, paint)

                    if (rotatedBitmap != finalBitmap) rotatedBitmap.recycle()

                    // If editing an item from Secure Vault, encrypt directly into vault storage without public MediaStore exposure
                    if (state.isVaultItem) {
                        val cacheFolder = File(context.cacheDir, "vault_secure_temp").apply { mkdirs() }
                        val tempEditFile = File(cacheFolder, "vault_edit_${System.currentTimeMillis()}.jpg")
                        tempEditFile.outputStream().use { out ->
                            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        }
                        finalBitmap.recycle()

                        val vaultResult = vaultRepository.addFileToVault(
                            file = tempEditFile,
                            originalName = "IMG_EDIT_${System.currentTimeMillis()}.jpg",
                            mimeType = "image/jpeg",
                            isVideo = false,
                            albumName = "Default"
                        )

                        // Immediately purge any temporary UCrop output files
                        try {
                            context.cacheDir.listFiles { _, name -> name.startsWith("crop_") }?.forEach { it.delete() }
                            cacheFolder.listFiles { _, name -> name.startsWith("crop_") }?.forEach { it.delete() }
                        } catch (_: Exception) {}

                        if (vaultResult.isSuccess) {
                            return@withContext Result.success(Uri.EMPTY)
                        } else {
                            return@withContext Result.failure(vaultResult.exceptionOrNull() ?: Exception("Failed to save to Secure Vault"))
                        }
                    }

                    // 4. Save to Scoped Storage (Pictures/Gallery) for regular media
                    val fileName = "IMG_EDIT_${System.currentTimeMillis()}.jpg"
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/Gallery"
                            )
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }

                    val insertUri = context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    ) ?: throw Exception("Failed to create MediaStore entry")

                    context.contentResolver.openOutputStream(insertUri)?.use { out ->
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    finalBitmap.recycle()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(insertUri, contentValues, null, null)
                    }

                    Result.success(insertUri)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            result.onSuccess { uri ->
                _uiState.update { it.copy(isSaving = false, isSaved = true, savedUri = uri) }
                onSuccess(uri)
            }.onFailure { err ->
                _uiState.update { it.copy(isSaving = false, error = err.message) }
            }
        }
    }
}
