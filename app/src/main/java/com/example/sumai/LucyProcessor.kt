package com.example.sumai

import android.content.Context
import android.content.SharedPreferences
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class LucyProcessor(private val context: Context) {

    @Volatile
    private var isModelReady = false

    @Volatile
    private var modelFile: File? = null

    private var downloadJob: Job? = null

    companion object {
        private const val MODEL_URL = "https://huggingface.co/Menlo/Lucy-gguf/resolve/main/Lucy-Q4_K_M.gguf"
        private const val MODEL_FILENAME = "lucy.gguf"
        private const val TAG = "LucyProcessor"

        private const val PREF_TOTAL_SIZE = "total_size"
        private const val PREF_DOWNLOAD_COMPLETE = "download_complete"
        private const val PREF_SAVED_SIZE = "saved_size"
        private const val PREF_MODEL_PATH = "model_path"

        private const val MIN_MODEL_SIZE_BYTES = 4L * 1024 * 1024 * 1024
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)

    fun getModelFile(): File? = modelFile?.takeIf { it.exists() && isModelReady }

    fun getSavedModelPath(): String? = prefs.getString(PREF_MODEL_PATH, null)


    suspend fun checkAndRestoreModel(
        onProgress: (Int) -> Unit = {},
        onReady: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val file = File(context.getExternalFilesDir(null), MODEL_FILENAME)

        if (!file.exists()) {
            Log.d(TAG, "Файл модели не существует")
            onError("Файл модели не найден")
            return
        }

        val fileSize = file.length()
        val fileSizeMB = fileSize / (1024 * 1024)

        Log.d(TAG, "Найден файл модели: $fileSizeMB MB")

        // Если файл имеет полный размер (больше 3.5 GB для Lucy)
        if (fileSize > 4L * 1024 * 1024 * 1024) {
            Log.d(TAG, "✅ Файл модели имеет полный размер")

            // Восстанавливаем флаги
            prefs.edit().apply {
                putBoolean(PREF_DOWNLOAD_COMPLETE, true)
                putLong(PREF_TOTAL_SIZE, fileSize)
                putLong(PREF_SAVED_SIZE, fileSize)
                putString(PREF_MODEL_PATH, file.absolutePath)
                apply()
            }

            modelFile = file
            onReady(file)
        } else {
            Log.d(TAG, "Файл модели неполный: $fileSizeMB MB")
            onError("Файл модели неполный или повреждён")
        }
    }
    private fun hasEnoughSpace(sizeNeeded: Long): Boolean {
        return try {
            val path = context.getExternalFilesDir(null)?.path ?: return false
            val stat = StatFs(path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val requiredWithBuffer = sizeNeeded + (500 * 1024 * 1024)
            val hasSpace = availableBytes > requiredWithBuffer
            if (!hasSpace) {
                Log.e(TAG, "❌ Недостаточно места! Нужно: ${requiredWithBuffer / (1024 * 1024)} MB, Доступно: ${availableBytes / (1024 * 1024)} MB")
            }
            hasSpace
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки места: ${e.message}")
            true
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        Log.d(TAG, "Загрузка отменена")
    }

    private fun isModelActuallyReady(file: File): Boolean {
        if (!file.exists()) return false

        val fileSizeBytes = file.length()
        val fileSizeMB = fileSizeBytes / (1024 * 1024)

        // Проверка по флагу
        if (prefs.getBoolean(PREF_DOWNLOAD_COMPLETE, false) && fileSizeBytes > 0) {
            Log.d(TAG, "✅ Модель обнаружена по флагу, размер: $fileSizeMB MB")
            return true
        }

        // Проверка по размеру файла (главная защита от сброса флагов)
        if (fileSizeBytes > 4L * 1024 * 1024 * 1024) {
            Log.d(TAG, "✅ Найден полный файл модели размером $fileSizeMB MB")
            Log.d(TAG, "🔄 Восстанавливаем флаги...")

            // Восстанавливаем все флаги
            prefs.edit().apply {
                putBoolean(PREF_DOWNLOAD_COMPLETE, true)
                putLong(PREF_TOTAL_SIZE, fileSizeBytes)
                putLong(PREF_SAVED_SIZE, fileSizeBytes)
                putString(PREF_MODEL_PATH, file.absolutePath)
                apply()
            }
            return true
        }

        // Частичный файл (больше 1 GB но меньше 3.5 GB)
        if (fileSizeBytes > 1024 * 1024 * 1024) {
            Log.d(TAG, "📥 Найден частичный файл: $fileSizeMB MB")
            return false
        }

        return false
    }

    suspend fun loadModel(
        onProgress: (Int) -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            modelFile = File(context.getExternalFilesDir(null), MODEL_FILENAME)
            val file = modelFile ?: throw Exception("Не удалось создать файл модели")

            // ===== ГЛАВНАЯ ПРОВЕРКА: восстанавливаем модель если она есть =====
            if (isModelActuallyReady(file)) {
                val fileSizeMB = file.length() / (1024 * 1024)
                Log.d(TAG, "✅ Модель готова к использованию! Размер: $fileSizeMB MB")
                Log.d(TAG, "✅ Путь: ${file.absolutePath}")

                withContext(Dispatchers.Main) {
                    onProgress(100)
                    isModelReady = true
                    onReady()
                }
                return@withContext
            }
            // ===== КОНЕЦ ПРОВЕРКИ =====

            val isComplete = prefs.getBoolean(PREF_DOWNLOAD_COMPLETE, false)
            val totalSize = prefs.getLong(PREF_TOTAL_SIZE, 0)
            val existingSize = if (file.exists()) file.length() else 0L

            if (isComplete && existingSize >= totalSize && totalSize > 0) {
                prefs.edit().putString(PREF_MODEL_PATH, file.absolutePath).apply()
                withContext(Dispatchers.Main) {
                    onProgress(100)
                    isModelReady = true
                    onReady()
                }
                return@withContext
            }

            if (file.exists() && existingSize > 0) {
                val savedSize = prefs.getLong(PREF_SAVED_SIZE, 0)
                if (savedSize > 0 && savedSize == existingSize && totalSize > 0) {
                    val percent = ((existingSize * 100) / totalSize).toInt().coerceIn(0, 99)
                    withContext(Dispatchers.Main) { onProgress(percent) }
                } else {
                    withContext(Dispatchers.Main) { onProgress(0) }
                }
            }

            val sizeNeeded = if (totalSize > 0) totalSize else MIN_MODEL_SIZE_BYTES
            if (!hasEnoughSpace(sizeNeeded)) {
                withContext(Dispatchers.Main) {
                    onError("Недостаточно свободного места. Требуется ~${sizeNeeded / (1024 * 1024)} MB")
                }
                return@withContext
            }

            downloadModel(file, onProgress, onReady, onError)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка: ${e.message}", e)
            withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка") }
        }
    }

    private suspend fun downloadModel(
        destFile: File,
        onProgress: (Int) -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()

                var totalLength = prefs.getLong(PREF_TOTAL_SIZE, 0)
                var existingSize = if (destFile.exists()) destFile.length() else 0L

                if (totalLength == 0L) {
                    try {
                        withTimeoutOrNull(10000) {
                            val headRequest = Request.Builder().url(MODEL_URL).head().build()
                            client.newCall(headRequest).execute().use { headResponse ->
                                totalLength = headResponse.body?.contentLength() ?: 0L
                                if (totalLength > 0) {
                                    prefs.edit().putLong(PREF_TOTAL_SIZE, totalLength).apply()
                                    Log.d(TAG, "📊 Размер с сервера: ${totalLength / (1024 * 1024)} MB")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "HEAD запрос не удался: ${e.message}")
                    }
                }

                if (totalLength == 0L) {
                    totalLength = 4200L * 1024 * 1024
                    prefs.edit().putLong(PREF_TOTAL_SIZE, totalLength).apply()
                    Log.d(TAG, "📊 Используем примерный размер: ${totalLength / (1024 * 1024)} MB")
                }

                if (existingSize >= totalLength) {
                    Log.d(TAG, "✅ Файл уже имеет полный размер")
                    prefs.edit().apply {
                        putBoolean(PREF_DOWNLOAD_COMPLETE, true)
                        putString(PREF_MODEL_PATH, destFile.absolutePath)
                    }.apply()
                    withContext(Dispatchers.Main) {
                        onProgress(100)
                        isModelReady = true
                        onReady()
                    }
                    return@withContext
                }

                val request = if (existingSize > 0 && existingSize < totalLength) {
                    Log.d(TAG, "🔄 Докачка с ${existingSize / (1024 * 1024)} MB")
                    Request.Builder()
                        .url(MODEL_URL)
                        .addHeader("Range", "bytes=$existingSize-")
                        .build()
                } else {
                    Log.d(TAG, "🚀 Начинаем новую загрузку")
                    Request.Builder().url(MODEL_URL).build()
                }

                response = client.newCall(request).execute()

                if (response.code == 416) {
                    Log.w(TAG, "⚠️ Ошибка 416, начинаем загрузку с нуля")
                    if (destFile.exists()) destFile.delete()
                    prefs.edit().remove(PREF_DOWNLOAD_COMPLETE).apply()
                    val freshRequest = Request.Builder().url(MODEL_URL).build()
                    response.close()
                    response = client.newCall(freshRequest).execute()
                    existingSize = 0L
                }

                if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                    throw Exception("Ошибка: HTTP ${response.code}")
                }

                val body = response.body ?: throw Exception("Нет данных")
                destFile.parentFile?.mkdirs()

                val outputStream = FileOutputStream(destFile, existingSize > 0 && response.code == 206)
                var lastSaveTime = System.currentTimeMillis()
                var lastPercent = -1

                body.byteStream().use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesCopied = existingSize

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesCopied += bytesRead

                            val percent = ((totalBytesCopied * 100) / totalLength).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                Log.d(TAG, "📊 Прогресс: $percent% (${totalBytesCopied / (1024 * 1024)} / ${totalLength / (1024 * 1024)} MB)")
                                withContext(Dispatchers.Main) { onProgress(percent) }

                                val now = System.currentTimeMillis()
                                if (now - lastSaveTime > 5000) {
                                    prefs.edit().putLong(PREF_SAVED_SIZE, totalBytesCopied).apply()
                                    lastSaveTime = now
                                    Log.d(TAG, "💾 Прогресс сохранён: ${totalBytesCopied / (1024 * 1024)} MB")
                                }
                            }
                        }
                    }
                }

                prefs.edit().apply {
                    putBoolean(PREF_DOWNLOAD_COMPLETE, true)
                    putLong(PREF_TOTAL_SIZE, totalLength)
                    putLong(PREF_SAVED_SIZE, totalLength)
                    putString(PREF_MODEL_PATH, destFile.absolutePath)
                    apply()
                }

                Log.d(TAG, "✅ Загрузка завершена! Размер: ${destFile.length() / (1024 * 1024)} MB")
                Log.d(TAG, "✅ Путь: ${destFile.absolutePath}")

                withContext(Dispatchers.Main) {
                    onProgress(100)
                    isModelReady = true
                    onReady()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка загрузки: ${e.message}", e)
                if (destFile.exists()) {
                    prefs.edit().putLong(PREF_SAVED_SIZE, destFile.length()).apply()
                    Log.d(TAG, "💾 Прогресс сохранён при ошибке: ${destFile.length() / (1024 * 1024)} MB")
                }
                withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка скачивания") }
            } finally {
                response?.close()
            }
        }
    }

    fun loadModelAndGetFile(
        onProgress: (Int) -> Unit,
        onModelFileReady: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            loadModel(
                onProgress = onProgress,
                onReady = {
                    modelFile?.let { file ->
                        if (file.exists()) {
                            Log.d(TAG, "✅ Модель готова, передаём файл: ${file.absolutePath}")
                            onModelFileReady(file)
                        } else {
                            onError("Файл модели не найден после загрузки")
                        }
                    } ?: onError("Файл модели не инициализирован")
                },
                onError = onError
            )
        }
    }

    fun isReady(): Boolean = isModelReady

    fun close() {
        cancelDownload()
        isModelReady = false
        modelFile = null
    }
}