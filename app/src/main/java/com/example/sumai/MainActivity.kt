package com.example.sumai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.sumai.search.SearXNGBridge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

class MainActivity : FragmentActivity() {

    private lateinit var tvResult: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnProcess: Button
    private lateinit var btnClear: Button
    private lateinit var btnViewSummary: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressMB: TextView
    private lateinit var tvProgressSpeed: TextView
    private lateinit var tvProgressStatus: TextView
    private lateinit var layoutProgress: LinearLayout

    private var model: Model? = null
    private var isRecording = false
    private var isPaused = false
    private var recordingJob: kotlinx.coroutines.Job? = null
    private var startTime = 0L
    private var pausedDuration = 0L
    private var recognizer: Recognizer? = null
    private var currentText = ""
    private var currentSummaryText = ""
    private var audioRecord: AudioRecord? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    // Новые компоненты
    private lateinit var lucyProcessor: LucyProcessor
    private lateinit var searxngBridge: SearXNGBridge

    companion object {
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 1
        private const val MAX_RECORDING_SECONDS = 3600
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI элементов
        tvResult = findViewById(R.id.tvResult)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        btnRecord = findViewById(R.id.btnRecord)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        btnProcess = findViewById(R.id.btnProcess)
        btnClear = findViewById(R.id.btnClear)
        btnViewSummary = findViewById(R.id.btnViewSummary)
        progressBar = findViewById(R.id.progressBar)
        tvProgressMB = findViewById(R.id.tvProgressMB)
        tvProgressSpeed = findViewById(R.id.tvProgressSpeed)
        tvProgressStatus = findViewById(R.id.tvProgressStatus)
        layoutProgress = findViewById(R.id.layoutProgress)

        // Настройка слушателей кнопок
        btnRecord.setOnClickListener { startRecording() }
        btnPause.setOnClickListener { togglePause() }
        btnStop.setOnClickListener { finishRecording() }
        btnProcess.setOnClickListener { processLecture() }
        btnClear.setOnClickListener { clearText() }
        btnViewSummary.setOnClickListener { openMarkdownViewer() }

        // Изначальное состояние кнопок
        btnProcess.isEnabled = false
        btnPause.isEnabled = false
        btnStop.isEnabled = false
        btnViewSummary.isEnabled = false

        // Инициализация поиска (бесплатный SearXNG)
        searxngBridge = SearXNGBridge()

        // Инициализация Lucy
        lucyProcessor = LucyProcessor(this)

        // Показываем прогресс загрузки модели
        layoutProgress.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        tvProgressStatus.visibility = View.VISIBLE
        tvProgressMB.visibility = View.VISIBLE
        tvProgressSpeed.visibility = View.VISIBLE

        tvProgressStatus.text = "🚀 Запуск загрузки Lucy..."
        tvProgressMB.text = "0 МБ / ? МБ"
        tvProgressSpeed.text = ""

        lifecycleScope.launch {
            lucyProcessor.loadModel(
                onProgress = { progress ->
                    runOnUiThread {
                        progressBar.progress = progress
                        tvProgressStatus.text = "Загрузка Lucy: $progress%"
                    }
                },
                onReady = {
                    runOnUiThread {
                        tvStatus.text = "✅ Lucy готова"
                        btnProcess.isEnabled = true
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        tvStatus.text = "❌ Ошибка: $error"
                    }
                }
            )
        }


        // Загрузка Vosk модели
        if (hasPermission()) {
            loadVoskModel()
        } else {
            requestPermission()
        }
    }

    private fun loadVoskModel() {
        tvStatus.text = "Загрузка Vosk модели..."
        btnRecord.isEnabled = false

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val modelDir = File(filesDir, "model-ru")
                if (!modelDir.exists()) {
                    copyModelFromAssets("model-ru", modelDir)
                }
                model = Model(modelDir.absolutePath)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tvStatus.text = "✅ Vosk модель готова"
                    btnRecord.isEnabled = true
                    Toast.makeText(this@MainActivity, "Vosk загружен", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tvStatus.text = "Ошибка Vosk: ${e.message}"
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
                if (!isPaused && isRecording) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val minutes = elapsed / 60
                    val seconds = elapsed % 60
                    tvTimer.text = String.format("%02d:%02d", minutes, seconds)
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
            if (currentText.isNotEmpty()) {
                btnProcess.isEnabled = true
            }
            val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollView)
            scrollView?.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun clearText() {
        currentText = ""
        currentSummaryText = ""
        tvResult.text = ""
        btnProcess.isEnabled = false
        btnViewSummary.isEnabled = false
        tvStatus.text = "Текст очищен"
    }

    @Suppress("MissingPermission")
    private fun startRecording() {
        if (model == null) {
            Toast.makeText(this, "Модель не загружена", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        isPaused = false
        pausedDuration = 0L
        btnRecord.isEnabled = false
        btnPause.isEnabled = true
        btnStop.isEnabled = true
        btnProcess.isEnabled = false
        btnViewSummary.isEnabled = false
        currentText = ""
        currentSummaryText = ""
        tvResult.text = ""
        tvStatus.text = "Запись..."
        startTimer()

        recognizer = Recognizer(model, 16000.0f)

        recordingJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        tvStatus.text = "Ошибка микрофона"
                        resetButtons()
                    }
                    return@launch
                }

                audioRecord?.startRecording()
                val buffer = ByteArray(bufferSize)

                while (isRecording) {
                    if (isPaused) {
                        kotlinx.coroutines.delay(100)
                        continue
                    }

                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (bytesRead > 0) {
                        if (recognizer?.acceptWaveForm(buffer, bytesRead) == true) {
                            val result = recognizer?.result
                            result?.let {
                                val text = parseResult(it)
                                if (text.isNotEmpty()) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        updateResult(text)
                                    }
                                }
                            }
                        } else {
                            val partial = recognizer?.partialResult
                            partial?.let {
                                val text = parsePartialResult(it)
                                if (text.isNotEmpty()) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        updateResult(text)
                                    }
                                }
                            }
                        }
                    }
                }

                audioRecord?.stop()

                val finalResult = recognizer?.result
                finalResult?.let {
                    val text = parseResult(it)
                    if (text.isNotEmpty()) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            updateResult(text)
                        }
                    }
                }

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tvStatus.text = "Запись завершена"
                    resetButtons()
                    stopTimer()
                }

            } catch (e: SecurityException) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tvStatus.text = "Нет разрешения"
                    resetButtons()
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tvStatus.text = "Ошибка: ${e.message}"
                    resetButtons()
                }
            } finally {
                audioRecord?.release()
                recognizer?.close()
                recognizer = null
            }
        }
    }


    private fun processLecture() {
        if (currentText.isEmpty()) return
        if (!lucyProcessor.isReady()) {
            Toast.makeText(this, "Lucy загружается", Toast.LENGTH_SHORT).show()
            return
        }

        btnProcess.isEnabled = false
        layoutProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val result = lucyProcessor.processLecture(
                    rawText = currentText,
                    onStatus = { status ->
                        runOnUiThread {
                            tvProgressStatus.text = status
                            when {
                                status.contains("Анализ") -> progressBar.progress = 25
                                status.contains("Поиск") -> progressBar.progress = 50
                                status.contains("Конспект") -> progressBar.progress = 75
                            }
                        }
                    },
                    onSearchRequest = { query ->
                        searxngBridge.search(query)
                    }
                )

                runOnUiThread {
                    progressBar.progress = 100
                    tvProgressStatus.text = "✅ Готово!"
                    currentSummaryText = result
                    tvResult.text = result
                    btnViewSummary.isEnabled = true
                    tvStatus.text = "✅ Конспект готов!"
                }

                saveToFile(result)

            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ Ошибка: ${e.message}"
                }
            } finally {
                runOnUiThread {
                    btnProcess.isEnabled = true
                    handler.postDelayed({
                        layoutProgress.visibility = View.GONE
                    }, 2000)
                }
            }
        }
    }

    private fun openMarkdownViewer() {
        if (currentSummaryText.isEmpty()) {
            Toast.makeText(this, "Нет конспекта для просмотра", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MarkdownViewerActivity::class.java)
        intent.putExtra("markdown", currentSummaryText)
        startActivity(intent)
    }

    private fun saveToFile(content: String) {
        try {
            val fileName = "summary_${System.currentTimeMillis()}.md"
            val file = File(getExternalFilesDir(null), fileName)
            file.writeText(content)
            tvStatus.text = "📁 Сохранено: $fileName"
        } catch (e: Exception) {
            tvStatus.text = "Ошибка сохранения: ${e.message}"
        }
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
                loadVoskModel()
            } else {
                tvStatus.text = "Нет разрешения на запись"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        recordingJob?.cancel()
        recognizer?.close()
        model?.close()
        lucyProcessor.close()
    }

    private fun togglePause() {
        if (!isRecording) return

        isPaused = !isPaused
        if (isPaused) {
            btnPause.text = "Продолжить"
            tvStatus.text = "На паузе"
        } else {
            btnPause.text = "Пауза"
            tvStatus.text = "Запись"
            val pauseTime = System.currentTimeMillis() - (startTime + pausedDuration)
            pausedDuration += pauseTime
        }
    }

    private fun finishRecording() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        tvStatus.text = "Запись остановлена"
        btnStop.isEnabled = false
        btnPause.isEnabled = false
        btnRecord.isEnabled = true
        btnPause.text = "Пауза"
    }

    private fun resetButtons() {
        isRecording = false
        isPaused = false
        btnRecord.isEnabled = true
        btnPause.isEnabled = false
        btnStop.isEnabled = false
        btnPause.text = "Пауза"
    }
}