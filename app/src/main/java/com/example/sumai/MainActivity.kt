package com.example.sumai

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

class MainActivity : FragmentActivity() {

    private lateinit var tvResult: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnRecord: Button
    private var model: Model? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var startTime = 0L
    private var recognizer: Recognizer? = null
    private var currentText = ""
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    companion object {
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 1
        private const val MAX_RECORDING_SECONDS = 3600 // 1 час
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvResult = findViewById(R.id.tvResult)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        btnRecord = findViewById(R.id.btnRecord)

        btnRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        if (hasPermission()) {
            loadModel()
        } else {
            requestPermission()
        }
    }

    private fun loadModel() {
        tvStatus.text = "Загрузка модели..."
        btnRecord.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelDir = File(filesDir, "model-ru")
                if (!modelDir.exists()) {
                    copyModelFromAssets("model-ru", modelDir)
                }
                model = Model(modelDir.absolutePath)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Модель готова"
                    btnRecord.isEnabled = true
                    Toast.makeText(this@MainActivity, "Модель загружена", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Ошибка: ${e.message}"
                }
            }
        }
    }

    private fun copyModelFromAssets(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val files = assets.list(assetPath) ?: return
        for (file in files) {
            val assetFullPath = "$assetPath/$file"
            val destFile = File(destDir, file)

            if (assets.list(assetFullPath)?.isNotEmpty() == true) {
                copyModelFromAssets(assetFullPath, destFile)
            } else {
                assets.open(assetFullPath).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun startTimer() {
        startTime = System.currentTimeMillis()
        timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                val minutes = elapsed / 60
                val seconds = elapsed % 60
                tvTimer.text = String.format("%02d:%02d", minutes, seconds)

                if (elapsed >= MAX_RECORDING_SECONDS - 10 && elapsed < MAX_RECORDING_SECONDS) {
                    tvTimer.setTextColor(resources.getColor(android.R.color.holo_orange_dark))
                    tvStatus.text = "Заканчивается время записи"
                } else if (elapsed >= MAX_RECORDING_SECONDS) {
                    tvTimer.setTextColor(resources.getColor(android.R.color.holo_red_dark))
                    tvStatus.text = "Время записи истекло"
                    stopRecording()
                } else {
                    tvTimer.setTextColor(resources.getColor(android.R.color.white))
                }

                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun updateResult(text: String) {
        if (text.isNotEmpty() && text != currentText) {
            currentText = text
            tvResult.text = currentText
            val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
            scrollView?.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    @Suppress("MissingPermission")
    private fun startRecording() {
        if (model == null) {
            Toast.makeText(this, "Модель не загружена", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        btnRecord.text = "Остановить"
        currentText = ""
        tvResult.text = ""
        tvStatus.text = "Запись"
        tvTimer.text = "00:00"
        startTimer()

        // Создаём распознаватель для реального времени
        recognizer = Recognizer(model, 16000.0f)

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Ошибка микрофона"
                        btnRecord.text = "Запись"
                        isRecording = false
                        stopTimer()
                    }
                    return@launch
                }

                audioRecord.startRecording()
                val buffer = ByteArray(bufferSize)

                while (isRecording && (System.currentTimeMillis() - startTime) / 1000 < MAX_RECORDING_SECONDS) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        // Отправляем в распознаватель в реальном времени
                        if (recognizer?.acceptWaveForm(buffer, bytesRead) == true) {
                            // Фраза закончена - получаем финальный результат
                            val result = recognizer?.result
                            result?.let {
                                val text = parseResult(it)
                                if (text.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        updateResult(text)
                                    }
                                }
                            }
                        } else {
                            // Промежуточный результат
                            val partial = recognizer?.partialResult
                            partial?.let {
                                val text = parsePartialResult(it)
                                if (text.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        updateResult(text)
                                    }
                                }
                            }
                        }
                    }
                }

                audioRecord.stop()

                // Получаем финальный результат после остановки
                val finalResult = recognizer?.result
                finalResult?.let {
                    val text = parseResult(it)
                    if (text.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            updateResult(text)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    tvStatus.text = "Запись завершена"
                    btnRecord.text = "Запись"
                    isRecording = false
                    stopTimer()
                }

            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Нет разрешения"
                    btnRecord.text = "Запись"
                    isRecording = false
                    stopTimer()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Ошибка: ${e.message}"
                    btnRecord.text = "Запись"
                    isRecording = false
                    stopTimer()
                    e.printStackTrace()
                }
            } finally {
                audioRecord?.release()
                recognizer?.close()
                recognizer = null
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        btnRecord.text = "Остановка..."
        tvStatus.text = "Останавливаю..."
        stopTimer()
    }

    private fun parseResult(json: String): String {
        return try {
            val textPattern = "\"text\"\\s*:\\s*\"(.*?)\"".toRegex()
            val match = textPattern.find(json)
            match?.groupValues?.get(1)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parsePartialResult(json: String): String {
        return try {
            val textPattern = "\"partial\"\\s*:\\s*\"(.*?)\"".toRegex()
            val match = textPattern.find(json)
            match?.groupValues?.get(1)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadModel()
            } else {
                tvStatus.text = "Нет разрешения"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        recordingJob?.cancel()
        recognizer?.close()
        model?.close()
    }
}