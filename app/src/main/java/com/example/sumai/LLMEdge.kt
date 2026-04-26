package com.example.sumai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

class LLMEdge(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "LLMEdge"
    }

    private val lucyProcessor = LucyProcessor(context)
    private var llamaBridge: LlamaBridge? = null
    private var isModelLoading = false

    fun loadModel(
        onProgress: (Int) -> Unit = {},
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isModelLoading) return
        if (isReady()) {
            onComplete()
            return
        }

        isModelLoading = true

        lucyProcessor.loadModelAndGetFile(
            onProgress = { progress ->
                scope.launch(Dispatchers.Main) {
                    onProgress(progress / 2)
                }
            },
            onModelFileReady = { modelFile ->
                Log.d(TAG, "✅ Модель скачана: ${modelFile.absolutePath}")

                val bridge = LlamaBridge(context)
                bridge.loadModelFromFile(
                    modelFile = modelFile,
                    onProgress = { loadProgress ->
                        scope.launch(Dispatchers.Main) {
                            val totalProgress = 50 + (loadProgress / 2)
                            onProgress(totalProgress)
                        }
                    },
                    onReady = {
                        llamaBridge = bridge
                        isModelLoading = false
                        Log.d(TAG, "✅ Модель полностью загружена и готова!")
                        scope.launch(Dispatchers.Main) {
                            onProgress(100)
                            onComplete()
                        }
                    },
                    onError = { error ->
                        isModelLoading = false
                        Log.e(TAG, "❌ Ошибка загрузки в память: $error")
                        scope.launch(Dispatchers.Main) {
                            onError("Ошибка загрузки модели: $error")
                        }
                    }
                )
            },
            onError = { error ->
                isModelLoading = false
                Log.e(TAG, "❌ Ошибка скачивания: $error")
                scope.launch(Dispatchers.Main) {
                    onError("Ошибка скачивания: $error")
                }
            }
        )
    }

    suspend fun generate(prompt: String, onToken: ((String) -> Unit)? = null): String {
        return if (llamaBridge?.isReady() == true) {
            llamaBridge?.generate(prompt, onToken) ?: "Модель не инициализирована"
        } else {
            "Модель ещё не загружена"
        }
    }

    suspend fun createLectureSummary(
        rawText: String,
        internetSearch: suspend (String) -> String?,
        onStatus: (String) -> Unit
    ): String {
        if (!isReady()) {
            return "Модель ещё не загружена. Пожалуйста, подождите."
        }

        val bridge = llamaBridge ?: return "Ошибка: модель не инициализирована"
        val summarizer = LectureSummarizer(bridge)
        return summarizer.createSummary(rawText, internetSearch, onStatus)
    }

    fun isReady(): Boolean = lucyProcessor.isReady() && llamaBridge?.isReady() == true
    fun isLoading(): Boolean = isModelLoading

    fun close() {
        llamaBridge?.close()
        lucyProcessor.close()
        llamaBridge = null
        isModelLoading = false
    }

    suspend fun loadModelIfExists(
        onProgress: (Int) -> Unit = {},
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val existingModelFile = lucyProcessor.getSavedModelPath()?.let { File(it) }
            ?: lucyProcessor.getModelFile()

        if (existingModelFile != null && existingModelFile.exists() && existingModelFile.length() > 0) {
            Log.d(TAG, "Найден существующий файл модели. Загружаем в память...")
            if (llamaBridge?.isReady() != true) {
                val bridge = LlamaBridge(context)
                bridge.loadModelFromFile(
                    modelFile = existingModelFile,
                    onProgress = onProgress,
                    onReady = {
                        llamaBridge = bridge
                        onComplete()
                    },
                    onError = onError
                )
            } else {
                onComplete()
            }
        } else {
            Log.d(TAG, "Файл модели не найден. Запускаем полную загрузку.")
            loadModel(onProgress, onComplete, onError)
        }
    }
}