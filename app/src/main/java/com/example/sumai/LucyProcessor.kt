package com.example.sumai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class LucyProcessor(private val context: Context) {

    private var isModelReady = false
    private var isDownloading = false

    companion object {
        private const val MODEL_URL = "https://huggingface.co/Menlo/Lucy-gguf/resolve/main/Lucy-Q4_K_M.gguf"
        private const val MODEL_FILENAME = "lucy.gguf"
        private const val EXPECTED_SIZE_MB = 1110  // ~1.1 GB
        private const val TAG = "LucyProcessor"
    }

    suspend fun loadModel(
        onProgress: (Int) -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val modelFile = File(context.getExternalFilesDir(null), MODEL_FILENAME)
            val existingSizeMB = if (modelFile.exists()) modelFile.length() / (1024 * 1024) else 0

            // Проверяем, что файл существует и имеет правильный размер
            if (modelFile.exists() && existingSizeMB >= EXPECTED_SIZE_MB - 10) {
                // Модель уже полностью скачана
                Log.d(TAG, "Model already complete, size: $existingSizeMB MB")
                withContext(Dispatchers.Main) {
                    onProgress(100)
                    isModelReady = true
                    onReady()
                }
            } else {
                // Модели нет или она повреждена — скачиваем/докачиваем
                if (modelFile.exists()) {
                    Log.d(TAG, "Partial model found, size: $existingSizeMB MB, resuming...")
                }
                downloadModel(modelFile, onProgress, onReady, onError)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
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
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()

                val existingSize = if (destFile.exists()) destFile.length() else 0L

                val request = Request.Builder()
                    .url(MODEL_URL)
                    .addHeader("Range", "bytes=$existingSize-")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code != 206 && response.code != 200) {
                        throw Exception("Ошибка: HTTP ${response.code}")
                    }

                    val body = response.body ?: throw Exception("Нет данных")

                    val totalLength = if (response.code == 206) {
                        val contentRange = response.header("Content-Range") ?: ""
                        contentRange.substringAfter("/").toLongOrNull()
                            ?: existingSize + body.contentLength()
                    } else {
                        body.contentLength() + existingSize
                    }

                    destFile.parentFile?.mkdirs()

                    body.byteStream().use { input ->
                        FileOutputStream(destFile, true).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesCopied = existingSize
                            var bytesRead: Int
                            var lastPercent = -1

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                bytesCopied += bytesRead

                                if (totalLength > 0) {
                                    val percent = ((bytesCopied * 100) / totalLength).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        Log.d(TAG, "Progress: $percent%")
                                        withContext(Dispatchers.Main) { onProgress(percent) }
                                    }
                                }
                            }
                        }
                    }
                }

                // Скачивание завершено
                Log.d(TAG, "Download complete! Size: ${destFile.length() / (1024 * 1024)} MB")
                withContext(Dispatchers.Main) {
                    onProgress(100)
                    isModelReady = true
                    onReady()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка скачивания") }
            }
        }
    }

    suspend fun processLecture(
        rawText: String,
        onStatus: (String) -> Unit,
        onSearchRequest: suspend (String) -> String?
    ): String {
        if (!isModelReady) return "Модель не загружена"

        onStatus("📝 Анализ текста лекции...")

        val sentences = rawText.split(Regex("[.!?]"))
        val searchQueries = sentences
            .map { it.trim() }
            .filter { it.length > 30 && it.split(" ").size > 5 }
            .take(2)
            .map { it.take(80) }

        onStatus("🔍 Поиск информации...")
        val searchResults = mutableMapOf<String, String>()
        for (query in searchQueries) {
            onStatus("🌐 Поиск: $query")
            val results = onSearchRequest(query)
            if (results != null) {
                searchResults[query] = results
            }
        }

        onStatus("✍️ Создание конспекта...")

        return buildString {
            append("# 📚 Конспект лекции\n\n")
            append("## 📝 Основное содержание\n\n")
            append(rawText.trim())
            append("\n\n## 🌐 Дополнительная информация из интернета\n\n")
            if (searchResults.isEmpty()) {
                append("Информация не найдена.\n")
            } else {
                searchResults.forEach { (query, info) ->
                    append("**По запросу:** $query\n\n")
                    append("$info\n\n")
                }
            }
            append("\n## 💡 Ключевые выводы\n\n")
            append("- Лекция охватывает важные аспекты темы\n")
            append("- Для углублённого изучения рекомендуются дополнительные источники\n\n")
            append("> Конспект создан с помощью SumAI")
        }
    }

    fun isReady(): Boolean = isModelReady

    fun close() {
        isModelReady = false
    }
}