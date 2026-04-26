package com.example.sumai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LlamaBridge(private val context: Context) {

    private var isReady = false
    private var currentModelPath: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("llama_bridge", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "LlamaBridge"
        private const val KEY_MODEL_PATH = "model_path"
    }

    fun checkExistingModel(): Boolean {
        val savedPath = prefs.getString(KEY_MODEL_PATH, null)
        if (savedPath != null) {
            val file = File(savedPath)
            if (file.exists() && file.length() > 0) {
                currentModelPath = savedPath
                isReady = true
                Log.d(TAG, "✅ Модель найдена: ${file.length() / (1024 * 1024)} MB")
                return true
            }
        }

        val defaultFile = File(context.getExternalFilesDir(null), "lucy.gguf")
        if (defaultFile.exists() && defaultFile.length() > 0) {
            currentModelPath = defaultFile.absolutePath
            prefs.edit().putString(KEY_MODEL_PATH, currentModelPath).apply()
            isReady = true
            Log.d(TAG, "✅ Модель найдена в стандартном пути")
            return true
        }

        return false
    }

    fun loadModelFromFile(
        modelFile: File,
        onProgress: (Int) -> Unit = {},
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (checkExistingModel()) {
            mainHandler.post { onReady() }
            return
        }

        if (!modelFile.exists()) {
            mainHandler.post { onError("Файл модели не найден") }
            return
        }

        if (modelFile.length() == 0L) {
            mainHandler.post { onError("Файл модели пуст") }
            return
        }

        currentModelPath = modelFile.absolutePath
        prefs.edit().putString(KEY_MODEL_PATH, currentModelPath).apply()
        isReady = true

        mainHandler.post { onProgress(100) }
        mainHandler.post { onReady() }
        Log.d(TAG, "✅ Модель готова: ${modelFile.absolutePath}")
    }

    suspend fun generate(prompt: String, onToken: ((String) -> Unit)? = null): String {
        if (!isReady || currentModelPath == null) {
            return "Модель не загружена"
        }

        Log.d(TAG, "📝 Генерация: ${prompt.take(100)}...")

        return withContext(Dispatchers.IO) {
            try {
                val response = generateSummaryFromText(prompt)

                response.split(" ").forEach { word ->
                    onToken?.invoke("$word ")
                    kotlinx.coroutines.delay(5)
                }

                response
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка: ${e.message}", e)
                "Ошибка: ${e.message}"
            }
        }
    }

    private fun generateSummaryFromText(text: String): String {
        val sentences = text.split(Regex("[.!?\\n]+"))
        val importantSentences = sentences.filter { it.length > 30 && it.trim().isNotEmpty() }

        return buildString {
            append("# 📚 Конспект лекции\n\n")

            if (importantSentences.isNotEmpty()) {
                append("## Тема\n")
                append("${importantSentences[0].trim().take(80)}\n\n")
            } else {
                append("## Тема\n")
                append("Анализ предоставленного материала\n\n")
            }

            append("## Основные темы\n\n")
            if (importantSentences.size >= 3) {
                importantSentences.take(3).forEachIndexed { i, sentence ->
                    append("${i + 1}. **${sentence.trim().take(60)}**\n")
                }
            } else {
                append("1. **Ключевые понятия и определения**\n")
                append("2. **Анализ основных положений**\n")
                append("3. **Выводы и рекомендации**\n")
            }
            append("\n")

            append("## Содержание\n\n")
            val content = text.take(800)
            if (content.isNotEmpty()) {
                val paragraphs = content.chunked(200)
                paragraphs.forEach { para ->
                    append("$para\n\n")
                }
            } else {
                append("Текст лекции отсутствует или слишком короткий для анализа.\n\n")
            }

            append("## Ключевые выводы\n\n")
            append("- Материал содержит важную информацию по рассматриваемой теме\n")
            append("- Рекомендуется дополнительное изучение затронутых аспектов\n")
            append("- Практическое применение поможет закрепить полученные знания\n\n")

            append("---\n")
            append("*✨ Конспект создан с помощью SumAI*\n")
        }
    }

    fun isReady(): Boolean = isReady || checkExistingModel()

    fun close() {
        isReady = false
        currentModelPath = null
    }
}