package com.gallery.core.security.vault

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class VaultEncryptionResult(
    val encryptedFilePath: String,
    val originalName: String,
    val iv: ByteArray,
    val originalSize: Long
)

@Singleton
class VaultCryptoManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MASTER_KEY_ALIAS_V2 = "gallery_vault_master_key_v2"
        private const val LEGACY_KEY_ALIAS_V1 = "gallery_vault_master_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val CONTENT_TRANSFORMATION = "AES/CTR/NoPadding"
        private const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming buffer
        private val MAGIC_HEADER = byteArrayOf(0x47, 0x56, 0x4C, 0x54) // "GVLT"
        private const val FORMAT_VERSION: Byte = 0x02
    }

    /**
     * Encrypts a media file from Uri and stores it in the app-private vault directory.
     * Uses industry-standard Envelope Encryption:
     * 1. A hardware-backed Android KeyStore master key encrypts a unique 256-bit symmetric data key.
     * 2. The data key streams the file content through high-performance AES-CTR with zero memory spikes.
     */
    suspend fun encryptToVault(
        sourceUri: Uri,
        originalName: String
    ): VaultEncryptionResult = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(sourceUri)
            ?: throw IOException("Cannot open source URI: $sourceUri")
        encryptStreamToVault(inputStream, originalName)
    }

    /**
     * Encrypts a file directly and stores it in the app-private vault directory.
     */
    suspend fun encryptFileToVault(
        sourceFile: File,
        originalName: String
    ): VaultEncryptionResult = withContext(Dispatchers.IO) {
        val inputStream = FileInputStream(sourceFile)
        encryptStreamToVault(inputStream, originalName)
    }

    /**
     * Encrypts a raw byte array directly to a destination file using Envelope Encryption.
     * Returns the content IV.
     */
    suspend fun encryptBytesToVaultFile(
        bytes: ByteArray,
        destinationFile: File
    ): ByteArray = withContext(Dispatchers.IO) {
        destinationFile.parentFile?.mkdirs()
        ByteArrayInputStream(bytes).use { input ->
            encryptStreamToDestination(input, destinationFile).first
        }
    }

    /**
     * Decrypts an encrypted vault file directly into an in-memory ByteArray without disk temp files.
     */
    suspend fun decryptBytesFromVaultFile(
        encryptedFile: File,
        iv: ByteArray = ByteArray(0)
    ): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        decryptToOutputStream(encryptedFile, iv, out)
        out.toByteArray()
    }

    /**
     * Ensures .nomedia markers exist in vault and secure temp directories.
     */
    fun ensureNomediaMarkers() {
        try {
            val vaultDir = File(context.filesDir, "vault").apply { mkdirs() }
            val nomedia1 = File(vaultDir, ".nomedia")
            if (!nomedia1.exists()) nomedia1.createNewFile()

            val thumbDir = File(vaultDir, "thumbnails").apply { mkdirs() }
            val nomedia2 = File(thumbDir, ".nomedia")
            if (!nomedia2.exists()) nomedia2.createNewFile()

            val tempDir = File(context.cacheDir, "vault_secure_temp").apply { mkdirs() }
            val nomedia3 = File(tempDir, ".nomedia")
            if (!nomedia3.exists()) nomedia3.createNewFile()
        } catch (_: Exception) {}
    }

    private fun encryptStreamToVault(
        inputStream: InputStream,
        originalName: String
    ): VaultEncryptionResult {
        val encryptedFileName = "${UUID.randomUUID()}.vault"
        val vaultDir = File(context.filesDir, "vault").apply { mkdirs() }
        val encryptedFile = File(vaultDir, encryptedFileName)
        try {
            val (contentIv, totalOriginalSize) = encryptStreamToDestination(inputStream, encryptedFile)
            if (totalOriginalSize <= 0L || !encryptedFile.exists() || encryptedFile.length() <= 0L) {
                if (encryptedFile.exists()) encryptedFile.delete()
                throw IOException("Source media stream was empty (0 bytes)")
            }

            return VaultEncryptionResult(
                encryptedFilePath = encryptedFile.absolutePath,
                originalName = originalName,
                iv = contentIv,
                originalSize = totalOriginalSize
            )
        } catch (e: Exception) {
            if (encryptedFile.exists()) encryptedFile.delete()
            throw e
        }
    }

    private fun encryptStreamToDestination(
        inputStream: InputStream,
        destinationFile: File
    ): Pair<ByteArray, Long> {
        // 1. Generate unique 256-bit data key
        val dataKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // 2. Encrypt the data key with Android KeyStore master key (single-shot GCM doFinal, 100% hardware reliable)
        val masterCipher = Cipher.getInstance(MASTER_TRANSFORMATION)
        masterCipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKeyV2())
        val masterIv = masterCipher.iv // 12 bytes
        val encryptedDataKey = masterCipher.doFinal(dataKeyBytes) // 32 bytes + 16-byte tag = 48 bytes

        // 3. Initialize streaming content cipher (AES/CTR/NoPadding with software provider)
        val contentCipher = Cipher.getInstance(CONTENT_TRANSFORMATION)
        val contentIv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        contentCipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(dataKeyBytes, "AES"),
            IvParameterSpec(contentIv)
        )

        destinationFile.parentFile?.mkdirs()
        var totalOriginalSize = 0L

        destinationFile.outputStream().use { fileOut ->
            // Header: Magic (4 bytes) + Version (1 byte)
            fileOut.write(MAGIC_HEADER)
            fileOut.write(FORMAT_VERSION.toInt())

            // Master IV
            fileOut.write(masterIv.size)
            fileOut.write(masterIv)

            // Encrypted Data Key length (2 bytes) + bytes
            fileOut.write((encryptedDataKey.size shr 8) and 0xFF)
            fileOut.write(encryptedDataKey.size and 0xFF)
            fileOut.write(encryptedDataKey)

            // Content IV
            fileOut.write(contentIv.size)
            fileOut.write(contentIv)

            // Encrypted content stream
            CipherOutputStream(fileOut, contentCipher).use { cos ->
                inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        cos.write(buffer, 0, bytesRead)
                        totalOriginalSize += bytesRead
                    }
                }
                cos.flush()
            }
        }

        return Pair(contentIv, totalOriginalSize)
    }

    /**
     * Decrypts a vault file to an in-memory ByteArray.
     */
    suspend fun decryptFromVault(
        encryptedFilePath: String,
        iv: ByteArray
    ): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArrayOutputStream()
        decryptToOutputStream(File(encryptedFilePath), iv, out)
        out.toByteArray()
    }

    /**
     * Stream decrypts from a vault file into an OutputStream (e.g. MediaStore output stream).
     */
    suspend fun decryptToStream(
        encryptedFilePath: String,
        iv: ByteArray,
        destinationStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        decryptToOutputStream(File(encryptedFilePath), iv, destinationStream)
    }

    /**
     * Decrypts directly into an app-private destination file.
     * Seamlessly handles both new Envelope Encrypted files (v2) and legacy files (v1).
     */
    suspend fun decryptToFile(
        encryptedFilePath: String,
        iv: ByteArray,
        destinationFile: File
    ) = withContext(Dispatchers.IO) {
        val encFile = File(encryptedFilePath)
        if (!encFile.exists()) {
            throw IOException("Encrypted vault file not found: $encryptedFilePath")
        }
        destinationFile.parentFile?.mkdirs()
        try {
            destinationFile.outputStream().use { out ->
                decryptToOutputStream(encFile, iv, out)
            }
            if (!destinationFile.exists() || destinationFile.length() <= 0L) {
                if (destinationFile.exists()) destinationFile.delete()
                throw IOException("Decrypted file was empty (0 bytes written from $encryptedFilePath)")
            }
        } catch (e: Exception) {
            if (destinationFile.exists()) destinationFile.delete()
            throw e
        }
    }

    suspend fun decryptToOutputStream(
        file: File,
        iv: ByteArray,
        destinationStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            throw IOException("Encrypted vault file not found: ${file.absolutePath}")
        }
        val fileSize = file.length()
        if (fileSize < 5) {
            throw IOException("Vault file too small: $fileSize bytes")
        }

        // Check for v2 Envelope Header
        val isV2 = FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            val read = input.read(magic)
            read == 4 && magic.contentEquals(MAGIC_HEADER)
        }

        if (isV2) {
            file.inputStream().use { fileIn ->
                // Skip magic (4 bytes)
                val magic = ByteArray(4)
                readFully(fileIn, magic)

                val version = fileIn.read()
                if (version != FORMAT_VERSION.toInt()) {
                    throw IOException("Unsupported vault format version: $version")
                }

                val masterIvLen = fileIn.read()
                val masterIv = ByteArray(masterIvLen)
                readFully(fileIn, masterIv)

                val keyLenHigh = fileIn.read()
                val keyLenLow = fileIn.read()
                val keyLen = (keyLenHigh shl 8) or (keyLenLow and 0xFF)
                val encryptedKey = ByteArray(keyLen)
                readFully(fileIn, encryptedKey)

                val contentIvLen = fileIn.read()
                val contentIv = ByteArray(contentIvLen)
                readFully(fileIn, contentIv)

                // Decrypt data key using master key in Keystore
                val masterCipher = Cipher.getInstance(MASTER_TRANSFORMATION)
                masterCipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKeyV2(), GCMParameterSpec(128, masterIv))
                val dataKeyBytes = masterCipher.doFinal(encryptedKey)

                // Explicit chunked streaming with AES/CTR/NoPadding
                val contentCipher = Cipher.getInstance(CONTENT_TRANSFORMATION)
                contentCipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(dataKeyBytes, "AES"),
                    IvParameterSpec(contentIv)
                )

                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (fileIn.read(buffer).also { bytesRead = it } != -1) {
                    val decryptedChunk = contentCipher.update(buffer, 0, bytesRead)
                    if (decryptedChunk != null && decryptedChunk.isNotEmpty()) {
                        destinationStream.write(decryptedChunk)
                    }
                }
                val finalChunk = contentCipher.doFinal()
                if (finalChunk != null && finalChunk.isNotEmpty()) {
                    destinationStream.write(finalChunk)
                }
                destinationStream.flush()
            }
            return@withContext
        }

        // Fallback for legacy v1 files
        decryptLegacyV1Stream(file, iv, destinationStream)
    }

    private fun decryptLegacyV1Stream(
        file: File,
        iv: ByteArray,
        destinationStream: OutputStream
    ) {
        val tempFile = File.createTempFile("legacy_v1", ".tmp", context.cacheDir)
        try {
            decryptLegacyV1File(file, iv, tempFile)
            tempFile.inputStream().use { it.copyTo(destinationStream) }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count == -1) throw EOFException("Unexpected EOF reading vault header")
            offset += count
        }
    }

    private fun decryptLegacyV1File(
        file: File,
        iv: ByteArray,
        destinationFile: File
    ) {
        val candidateKeys = getAllCandidateKeys()
        if (candidateKeys.isEmpty()) {
            throw IOException("Legacy master key unavailable")
        }

        destinationFile.parentFile?.mkdirs()
        var success = false
        val errors = mutableListOf<String>()

        for (secretKey in candidateKeys) {
            if (success) break

            // Strategy 1: Stream using 12-byte IV prepended at beginning of file
            if (file.length() >= 12 + 16) {
                try {
                    file.inputStream().use { fileIn ->
                        val fileIv = ByteArray(12)
                        readFully(fileIn, fileIv)
                        val cipher = Cipher.getInstance(MASTER_TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, fileIv))
                        destinationFile.outputStream().use { fileOut ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (fileIn.read(buffer).also { bytesRead = it } != -1) {
                                val decrypted = cipher.update(buffer, 0, bytesRead)
                                if (decrypted != null && decrypted.isNotEmpty()) {
                                    fileOut.write(decrypted)
                                }
                            }
                            val finalBytes = cipher.doFinal()
                            if (finalBytes != null && finalBytes.isNotEmpty()) {
                                fileOut.write(finalBytes)
                            }
                            fileOut.flush()
                        }
                    }
                    if (destinationFile.exists() && destinationFile.length() > 0) {
                        success = true
                    } else {
                        destinationFile.delete()
                    }
                } catch (e: Exception) {
                    errors.add("Strategy 1 (${secretKey.algorithm}): ${e.message}")
                    if (destinationFile.exists()) destinationFile.delete()
                }
            }

            // Strategy 2: Stream using IV from database entity (skipping 12-byte header if present)
            if (!success && iv.isNotEmpty() && file.length() >= 12 + 16) {
                try {
                    file.inputStream().use { fileIn ->
                        val skippedIv = ByteArray(12)
                        readFully(fileIn, skippedIv)
                        val cipher = Cipher.getInstance(MASTER_TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                        destinationFile.outputStream().use { fileOut ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (fileIn.read(buffer).also { bytesRead = it } != -1) {
                                val decrypted = cipher.update(buffer, 0, bytesRead)
                                if (decrypted != null && decrypted.isNotEmpty()) {
                                    fileOut.write(decrypted)
                                }
                            }
                            val finalBytes = cipher.doFinal()
                            if (finalBytes != null && finalBytes.isNotEmpty()) {
                                fileOut.write(finalBytes)
                            }
                            fileOut.flush()
                        }
                    }
                    if (destinationFile.exists() && destinationFile.length() > 0) {
                        success = true
                    } else {
                        destinationFile.delete()
                    }
                } catch (e: Exception) {
                    errors.add("Strategy 2 (${secretKey.algorithm}): ${e.message}")
                    if (destinationFile.exists()) destinationFile.delete()
                }
            }

            // Strategy 3: Stream using IV from database entity across entire file
            if (!success && iv.isNotEmpty() && file.length() >= 16) {
                try {
                    file.inputStream().use { fileIn ->
                        val cipher = Cipher.getInstance(MASTER_TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                        destinationFile.outputStream().use { fileOut ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (fileIn.read(buffer).also { bytesRead = it } != -1) {
                                val decrypted = cipher.update(buffer, 0, bytesRead)
                                if (decrypted != null && decrypted.isNotEmpty()) {
                                    fileOut.write(decrypted)
                                }
                            }
                            val finalBytes = cipher.doFinal()
                            if (finalBytes != null && finalBytes.isNotEmpty()) {
                                fileOut.write(finalBytes)
                            }
                            fileOut.flush()
                        }
                    }
                    if (destinationFile.exists() && destinationFile.length() > 0) {
                        success = true
                    } else {
                        destinationFile.delete()
                    }
                } catch (e: Exception) {
                    errors.add("Strategy 3 (${secretKey.algorithm}): ${e.message}")
                    if (destinationFile.exists()) destinationFile.delete()
                }
            }
        }

        // Strategy 4: Fallback for androidx.security.crypto.EncryptedFile
        if (!success) {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encFile = EncryptedFile.Builder(
                    context,
                    file,
                    masterKey,
                    EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
                ).build()
                encFile.openFileInput().use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (destinationFile.exists() && destinationFile.length() > 0) {
                    success = true
                } else {
                    destinationFile.delete()
                }
            } catch (e: Exception) {
                errors.add("Strategy 4 (EncryptedFile): ${e.message}")
                if (destinationFile.exists()) destinationFile.delete()
            }
        }

        if (!success) {
            if (destinationFile.exists()) destinationFile.delete()
            throw IOException("Failed to decrypt legacy file (${file.length()} bytes): ${errors.joinToString("; ")}")
        }
    }

    private fun getAllCandidateKeys(): List<SecretKey> {
        val keys = mutableListOf<SecretKey>()
        getLegacySecretKeyV1()?.let { keys.add(it) }
        try {
            keys.add(getOrCreateMasterKeyV2())
        } catch (_: Exception) {}
        return keys
    }

    private fun getOrCreateMasterKeyV2(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return if (keyStore.containsAlias(MASTER_KEY_ALIAS_V2)) {
            keyStore.getKey(MASTER_KEY_ALIAS_V2, null) as SecretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS_V2,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    private fun getLegacySecretKeyV1(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return if (keyStore.containsAlias(LEGACY_KEY_ALIAS_V1)) {
            keyStore.getKey(LEGACY_KEY_ALIAS_V1, null) as? SecretKey
        } else null
    }
}
