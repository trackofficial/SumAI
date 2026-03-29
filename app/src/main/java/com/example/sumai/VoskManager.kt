package com.example.sumai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException

class VoskManager(private val context: Context) {

    private var model: Model? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val audioData = mutableListOf<Byte>()

    var onResult: ((String) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    suspend fun initializeModel(languageCode: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val modelPath = when (languageCode) {
                "en" -> "model-en"
                "ru" -> "model-ru"
                else -> throw IllegalArgumentException("Unsupported language: $languageCode")
            }
            val modelDir = File(context.filesDir, modelPath)
            if (!modelDir.exists()) {
                copyAssetFolder(context.assets, modelPath, modelDir)
            }
            model = Model(modelDir.absolutePath)
            Log.d("VoskManager", "Модель загружена: ${modelDir.absolutePath}")
            true
        } catch (e: IOException) {
            Log.e("VoskManager", "Ошибка загрузки модели", e)
            onError?.invoke(e)
            false
        }
    }

    fun startRecording(sampleRate: Int = 16000) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError?.invoke(Exception("Нет разрешения на запись звука"))
            return
        }

        // Очищаем данные перед новой записью
        audioData.clear()

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onError?.invoke(Exception("Ошибка: неподдерживаемые аудиопараметры"))
            return
        }

        Log.d("VoskManager", "bufferSize: $bufferSize, sampleRate: $sampleRate")

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke(Exception("Не удалось инициализировать микрофон"))
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (bytesRead > 0) {
                        synchronized(audioData) {
                            for (i in 0 until bytesRead) {
                                audioData.add(buffer[i])
                            }
                        }
                    }
                }
                Log.d("VoskManager", "Запись остановлена. Собрано байт: ${audioData.size}")
            }
        } catch (e: SecurityException) {
            onError?.invoke(e)
        }
    }

    suspend fun stopAndRecognize() = withContext(Dispatchers.IO) {
        isRecording = false
        recordingJob?.join()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        // Получаем аудиоданные
        val audioBytes: ByteArray
        synchronized(audioData) {
            audioBytes = audioData.toByteArray()
        }

        Log.d("VoskManager", "Аудиоданных: ${audioBytes.size} байт")

        if (audioBytes.isEmpty()) {
            Log.w("VoskManager", "Нет аудиоданных для распознавания")
            onResult?.invoke("")
            return@withContext
        }

        // Конвертируем байты в short
        val shortData = ShortArray(audioBytes.size / 2)
        for (i in shortData.indices) {
            shortData[i] = (audioBytes[i * 2].toInt() and 0xFF or (audioBytes[i * 2 + 1].toInt() shl 8)).toShort()
        }

        // Конвертируем short в float
        val floatData = FloatArray(shortData.size)
        for (i in shortData.indices) {
            floatData[i] = shortData[i].toFloat() / 32768.0f
        }

        Log.d("VoskManager", "floatData размер: ${floatData.size}")

        // Распознаём
        val recognizer = Recognizer(model, 16000.0f)

        // Отправляем данные по частям
        val chunkSize = 4000 // 4000 семплов за раз
        var offset = 0
        while (offset < floatData.size) {
            val end = minOf(offset + chunkSize, floatData.size)
            val chunk = floatData.sliceArray(offset until end)
            recognizer.acceptWaveForm(chunk, chunk.size)
            offset = end
        }

        // Получение результата
        val result = recognizer.getResult()
        Log.d("VoskManager", "Результат Vosk: $result")

        val text = parseResult(result)
        if (text.isEmpty()) {
            val partial = recognizer.getPartialResult()
            Log.d("VoskManager", "Partial результат: $partial")
            val partialText = parsePartialResult(partial)
            if (partialText.isNotEmpty()) {
                onResult?.invoke(partialText)
            } else {
                onResult?.invoke("")
            }
        } else {
            onResult?.invoke(text)
        }

        recognizer.close()
    }

    private fun parseResult(json: String): String {
        return try {
            val regex = "\"text\":\"(.*?)\"".toRegex()
            val match = regex.find(json)
            val text = match?.groupValues?.get(1) ?: ""
            text
        } catch (e: Exception) {
            ""
        }
    }

    private fun parsePartialResult(json: String): String {
        return try {
            val regex = "\"partial\":\"(.*?)\"".toRegex()
            val match = regex.find(json)
            match?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun cancel() {
        isRecording = false
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }

    fun close() {
        cancel()
        try {
            model?.close()
        } catch (e: Exception) {}
        model = null
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toDir: File) {
        toDir.mkdirs()
        val assets = assetManager.list(fromAssetPath) ?: return
        for (asset in assets) {
            val assetPath = "$fromAssetPath/$asset"
            val file = File(toDir, asset)
            if (assetManager.list(assetPath)?.isNotEmpty() == true) {
                copyAssetFolder(assetManager, assetPath, file)
            } else {
                try {
                    assetManager.open(assetPath).use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: IOException) {
                    Log.e("VoskManager", "Ошибка копирования: $assetPath", e)
                }
            }
        }
    }
}