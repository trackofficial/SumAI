package com.example.sumai

import android.content.ContentResolver
import android.content.Context
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.asSharedFlow
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import java.io.FileOutputStream

class LucyProcessor(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver
    private val viewModelJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + viewModelJob)

    private val _llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val llmFlow: SharedFlow<LlamaHelper.LLMEvent> = _llmFlow.asSharedFlow()

    private val llamaHelper by lazy {
        LlamaHelper(
            contentResolver = contentResolver,
            scope = scope,
            sharedFlow = _llmFlow
        )
    }

    private var isModelReady = false
    private var currentResponse = ""

    suspend fun loadModel(
        onProgress: (Int) -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Копируем модель из assets
            val modelFile = File(context.filesDir, "lucy.gguf")
            if (!modelFile.exists()) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onProgress(10) }
                context.assets.open("llm_model/lucy.gguf").use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post { onProgress(50) }
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post { onProgress(70) }

            llamaHelper.load(
                path = modelFile.absolutePath,
                contextLength = 2048,
                loaded = { jobId: Long ->
                    isModelReady = true
                    // Используем Handler вместо withContext
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onProgress(100)
                        onReady()
                    }
                }
            )

        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onError(e.message ?: "Ошибка загрузки Lucy")
            }
        }
    }
    suspend fun generate(prompt: String, onToken: (String) -> Unit = {}): String {
        if (!isModelReady) return "Модель не загружена"

        currentResponse = ""

        return withContext(Dispatchers.IO) {
            // Запускаем сбор токенов
            scope.launch {
                llmFlow.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Ongoing -> {
                            currentResponse += event.word
                            withContext(Dispatchers.Main) {
                                onToken(event.word)
                            }
                        }
                        is LlamaHelper.LLMEvent.Done -> {
                            llamaHelper.stopPrediction()
                        }
                        is LlamaHelper.LLMEvent.Error -> {
                            llamaHelper.stopPrediction()
                        }
                        else -> {}
                    }
                }
            }

            // Запускаем генерацию
            llamaHelper.predict(prompt)

            currentResponse
        }
    }

    suspend fun processLecture(
        rawText: String,
        onStatus: (String) -> Unit,
        onSearchRequest: suspend (String) -> String?
    ): String {
        if (!isModelReady) return "Модель не загружена"

        // Шаг 1: Анализ текста
        onStatus("📝 Анализ текста лекции...")

        val analysisPrompt = buildAnalysisPrompt(rawText)
        val analysis = generate(analysisPrompt)
        val searchQueries = extractSearchQueries(analysis)

        // Шаг 2: Поиск информации
        onStatus("🔍 Поиск информации...")
        val searchResults = mutableMapOf<String, String>()
        for (query in searchQueries) {
            onStatus("🌐 Поиск: $query")
            val results = onSearchRequest(query)
            if (results != null) {
                searchResults[query] = results
            }
        }

        // Шаг 3: Создание конспекта
        onStatus("✍️ Создание конспекта...")
        val finalPrompt = buildFinalPrompt(rawText, searchResults)
        return generate(finalPrompt)
    }

    private fun buildAnalysisPrompt(text: String): String = """
        Ты — ассистент профессора. Проанализируй следующий текст лекции.
        
        Задачи:
        1. Раздели текст на логические пункты
        2. Для каждого пункта определи, нужна ли дополнительная информация из интернета
        3. Если нужна, сформулируй краткий поисковый запрос
        
        Текст: $text
        
        Ответ в формате:
        ВОПРОС ДЛЯ ПОИСКА: [запрос 1]
        ВОПРОС ДЛЯ ПОИСКА: [запрос 2]
    """.trimIndent()

    private fun buildFinalPrompt(originalText: String, searchResults: Map<String, String>): String {
        val searchContext = if (searchResults.isEmpty()) {
            "\nДополнительная информация не найдена."
        } else {
            buildString {
                append("\n\n## Результаты поиска:\n")
                searchResults.forEach { (query, info) ->
                    append("По запросу '$query' найдено:\n$info\n")
                }
            }
        }
        return """
            Ты — ассистент профессора. Создай структурированный конспект лекции в Markdown.
            
            ## Исходный текст лекции:
            $originalText
            
            $searchContext
            
            ## Инструкция:
            - Раздели конспект на разделы с заголовками ##
            - Выделяй ключевые термины **жирным**
            - Используй списки -
            - В конце добавь ### Ключевые выводы
            
            Если есть информация из поиска, включи её со ссылками.
        """.trimIndent()
    }

    private fun extractSearchQueries(analysis: String): List<String> {
        val queries = mutableListOf<String>()
        val regex = Regex("ВОПРОС ДЛЯ ПОИСКА:\\s*(.+)")
        regex.findAll(analysis).forEach { match ->
            match.groupValues.getOrNull(1)?.let { queries.add(it.trim()) }
        }
        return queries
    }

    fun isReady(): Boolean = isModelReady

    fun close() {
        llamaHelper.abort()
        llamaHelper.release()
        viewModelJob.cancel()
        isModelReady = false
    }
}