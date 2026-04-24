package com.example.sumai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Простой мост для работы с GGUF моделями через llamacpp-kotlin
 *
 * Использует библиотеку: io.github.ljcamargo:llamacpp-kotlin:0.2.0
 *
 * Эта библиотека:
 * - Работает напрямую с GGUF файлами
 * - Не требует компиляции нативных библиотек
 * - Имеет простой Kotlin API
 */
class LlamaBridge(private val context: Context) {

    private var helper: LlamaHelper? = null
    private var isReady = false
    private var currentResponse = ""

    companion object {
        init {
            // Загружаем нативную библиотеку llama.cpp
            System.loadLibrary("llama")
        }
    }

    /**
     * Загружает модель из assets
     */
    suspend fun loadModel(
        modelFileName: String = "lucy.gguf",
        onProgress: (Int) -> Unit = {},
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        try {
            // 1. Копируем модель из assets
            val modelFile = File(context.filesDir, modelFileName)
            if (!modelFile.exists()) {
                onProgress(10)
                context.assets.open("llm_assets/$modelFileName").use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                        onProgress(50)
                    }
                }
            }

            onProgress(70)

            // 2. Создаем Helper с нужными параметрами
            // Используем suspendCancellableCoroutine для callback -> suspend
            helper = LlamaHelper().apply {
                load(
                    path = modelFile.absolutePath,
                    contextLength = 2048,
                    threadCount = Runtime.getRuntime().availableProcessors()
                )
            }

            isReady = true
            onProgress(100)
            withContext(Dispatchers.Main) { onReady() }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message ?: "Ошибка загрузки") }
        }
    }

    /**
     * Генерация текста
     */
    suspend fun generate(prompt: String, onToken: ((String) -> Unit)? = null): String {
        if (!isReady || helper == null) return "Модель не загружена"

        currentResponse = ""

        return withContext(Dispatchers.IO) {
            try {
                helper?.predict(prompt) { token ->
                    currentResponse += token
                    onToken?.invoke(token)
                }
                currentResponse
            } catch (e: Exception) {
                "Ошибка генерации: ${e.message}"
            }
        }
    }

    fun isReady(): Boolean = isReady
}

/**
 * Упрощенная версия Helper (должна быть заменена на реальную из библиотеки)
 *
 * Реальная реализация будет из зависимостей:
 * implementation("io.github.ljcamargo:llamacpp-kotlin:0.2.0")
 */
class LlamaHelper {
    fun load(path: String, contextLength: Int, threadCount: Int) {
        // Реальная реализация из библиотеки
    }

    fun predict(prompt: String, callback: (String) -> Unit) {
        // Реальная реализация из библиотеки
    }
}