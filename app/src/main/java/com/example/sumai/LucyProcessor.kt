package com.example.sumai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class LucyProcessor(private val context: Context) {

    private var isModelReady = false

    companion object {
        private const val MODEL_URL = "https://huggingface.co/Menlo/Lucy-gguf/resolve/main/Lucy-Q4_K_M.gguf"
        private const val MODEL_FILENAME = "lucy.gguf"
    }

    /**
     * Скачивает модель из интернета (без загрузки в память)
     */

    suspend fun loadModel(
        onProgress: (Int) -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val modelFile = File(context.filesDir, MODEL_FILENAME)

            // Если модели нет — скачиваем
            if (!modelFile.exists()) {
                downloadModel(modelFile, onProgress)
            } else {
                withContext(Dispatchers.Main) { onProgress(100) }
            }

            isModelReady = true
            withContext(Dispatchers.Main) { onReady() }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка скачивания Lucy") }
        }
    }

    /**
     * Скачивает модель с прогрессом
     */
    private suspend fun downloadModel(destFile: File, onProgress: (Int) -> Unit) {
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(MODEL_URL)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Ошибка: HTTP ${response.code}")
                }

                val body = response.body ?: throw Exception("Нет данных")
                val contentLength = body.contentLength()

                destFile.parentFile?.mkdirs()

                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesCopied = 0L
                        var bytesRead: Int
                        var lastPercent = -1

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesCopied += bytesRead

                            if (contentLength > 0) {
                                val percent = ((bytesCopied * 100) / contentLength).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Основной метод для обработки текста лекции (без реальной LLM)
     */
    suspend fun processLecture(
        rawText: String,
        onStatus: (String) -> Unit,
        onSearchRequest: suspend (String) -> String?
    ): String {
        if (!isModelReady) return "Модель не загружена"

        onStatus("📝 Анализ текста лекции...")

        // Простой анализ: извлекаем ключевые фразы для поиска
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
                searchResults.forEach { (query, results) ->
                    append("**По запросу:** $query\n\n")
                    append("$results\n\n")
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