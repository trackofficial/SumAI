package com.example.sumai.llm

import android.content.Context
import com.example.sumai.LlamaBridge
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class LLMEdge(private val context: Context, private val scope: CoroutineScope) {

    private val bridge = LlamaBridge(context)

    fun loadModel(onComplete: () -> Unit) {
        scope.launch {
            bridge.loadModel(
                modelFileName = "lucy.gguf",
                onReady = { onComplete() },
                onError = { error -> println("Error: $error") }
            )
        }
    }

    suspend fun generate(prompt: String): String {
        return bridge.generate(prompt)
    }
}