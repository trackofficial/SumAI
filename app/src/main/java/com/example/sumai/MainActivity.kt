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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.sumai.search.SearXNGBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

class MainActivity : FragmentActivity() {

    private lateinit var tvResult: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnProcess: Button
    private lateinit var btnClear: Button
    private lateinit var btnViewSummary: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressSpeed: TextView
    private lateinit var tvProgressStatus: TextView
    private lateinit var layoutProgress: LinearLayout

    private lateinit var btnModeRu: Button
    private lateinit var btnModeEn: Button
    private lateinit var btnModeBoth: Button

    private var ruModel: Model? = null
    private var enModel: Model? = null
    private var ruRecognizer: Recognizer? = null
    private var enRecognizer: Recognizer? = null

    private var recognitionMode = "ru"

    private var isRecording = false
    private var isPaused = false
    private var recordingJob: kotlinx.coroutines.Job? = null
    private var startTime = 0L
    private var pausedDuration = 0L
    private var audioRecord: AudioRecord? = null
    private var currentText = ""
    private var currentSummaryText = ""
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private lateinit var searxngBridge: SearXNGBridge
    private lateinit var llmEdge: LLMEdge

    companion object {
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 1
        private const val MAX_RECORDING_SECONDS = 3600
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация View
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
        tvProgressSpeed = findViewById(R.id.tvProgressSpeed)
        tvProgressStatus = findViewById(R.id.tvProgressStatus)
        layoutProgress = findViewById(R.id.layoutProgress)

        btnModeRu = findViewById(R.id.btnModeRu)
        btnModeEn = findViewById(R.id.btnModeEn)
        btnModeBoth = findViewById(R.id.btnModeBoth)

        // Настройка кнопок
        btnRecord.setOnClickListener { startRecording() }
        btnPause.setOnClickListener { togglePause() }
        btnStop.setOnClickListener { finishRecording() }
        btnProcess.setOnClickListener { processLecture() }
        btnClear.setOnClickListener { clearText() }
        btnViewSummary.setOnClickListener { openMarkdownViewer() }

        btnModeRu.setOnClickListener { setRecognitionMode("ru") }
        btnModeEn.setOnClickListener { setRecognitionMode("en") }
        btnModeBoth.setOnClickListener { setRecognitionMode("both") }

        // Начальное состояние кнопок
        btnProcess.isEnabled = false
        btnPause.isEnabled = false
        btnStop.isEnabled = false
        btnViewSummary.isEnabled = false
        btnModeRu.isEnabled = false
        btnModeEn.isEnabled = false
        btnModeBoth.isEnabled = false

        // Инициализация мостов
        searxngBridge = SearXNGBridge()
        llmEdge = LLMEdge(this, lifecycleScope)

        // Показываем прогресс загрузки
        layoutProgress.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        tvProgressStatus.visibility = View.VISIBLE
        tvProgressSpeed.visibility = View.VISIBLE

        tvProgressStatus.text = "🚀 Запуск загрузки Lucy..."
        tvProgressSpeed.text = ""

        // Загружаем модель через llmEdge
        llmEdge.loadModel(
            onProgress = { progress ->
                runOnUiThread {
                    layoutProgress.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    tvProgressStatus.visibility = View.VISIBLE
                    progressBar.progress = progress
                    tvProgressStatus.text = "📥 Загрузка модели Lucy: $progress%"

                    if (progress == 100) {
                        tvProgressStatus.text = "✅ Модель готова!"
                        handler.postDelayed({
                            layoutProgress.visibility = View.GONE
                            progressBar.visibility = View.GONE
                            tvProgressStatus.visibility = View.GONE
                        }, 1500)
                    }
                }
            },
            onComplete = {
                runOnUiThread {
                    tvStatus.text = "✅ Lucy готова к работе"
                    btnProcess.isEnabled = true
                }
            },
            onError = { error ->
                runOnUiThread {
                    tvStatus.text = "❌ Ошибка: $error"
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        )

        // Запрос разрешений и загрузка Vosk
        if (hasPermission()) {
            loadBothModels()
        } else {
            requestPermission()
        }
    }

    private fun loadBothModels() {
        tvStatus.text = "Загрузка Vosk моделей..."
        btnRecord.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Русская модель
                val ruModelDir = File(filesDir, "model-ru")
                if (!ruModelDir.exists()) {
                    copyModelFromAssets("model-ru", ruModelDir)
                }
                ruModel = Model(ruModelDir.absolutePath)

                // Английская модель
                val enModelDir = File(filesDir, "model-en")
                if (!enModelDir.exists()) {
                    copyModelFromAssets("model-en", enModelDir)
                }
                enModel = Model(enModelDir.absolutePath)

                withContext(Dispatchers.Main) {
                    tvStatus.text = "✅ Vosk модели загружены"
                    btnRecord.isEnabled = true
                    btnModeRu.isEnabled = true
                    btnModeEn.isEnabled = true
                    btnModeBoth.isEnabled = true
                    setRecognitionMode("ru")
                    Toast.makeText(this@MainActivity, "Vosk загружен (RU/EN)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Ошибка Vosk: ${e.message}"
                }
            }
        }
    }

    private fun setRecognitionMode(mode: String) {
        recognitionMode = mode

        // Закрываем старые распознаватели
        ruRecognizer?.close()
        enRecognizer?.close()
        ruRecognizer = null
        enRecognizer = null

        when(mode) {
            "ru" -> {
                ruRecognizer = ruModel?.let { Recognizer(it, 16000.0f) }
                tvStatus.text = "🎤 Режим: русский"
                btnModeRu.isEnabled = false
                btnModeEn.isEnabled = true
                btnModeBoth.isEnabled = true
            }
            "en" -> {
                enRecognizer = enModel?.let { Recognizer(it, 16000.0f) }
                tvStatus.text = "🎤 Mode: English"
                btnModeRu.isEnabled = true
                btnModeEn.isEnabled = false
                btnModeBoth.isEnabled = true
            }
            "both" -> {
                ruRecognizer = ruModel?.let { Recognizer(it, 16000.0f) }
                enRecognizer = enModel?.let { Recognizer(it, 16000.0f) }
                tvStatus.text = "🌐 Режим: русский + English"
                btnModeRu.isEnabled = true
                btnModeEn.isEnabled = true
                btnModeBoth.isEnabled = false
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
            tvResult.setText(currentText)
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
        tvResult.text.clear()
        btnProcess.isEnabled = false
        btnViewSummary.isEnabled = false
        tvStatus.text = "Текст очищен"
    }

    @Suppress("MissingPermission")
    private fun startRecording() {
        if (ruModel == null && enModel == null) {
            Toast.makeText(this, "Модели не загружены", Toast.LENGTH_SHORT).show()
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
        tvResult.text.clear()
        tvStatus.text = if (recognitionMode == "both") "🌐 Запись (RU+EN)..." else "Запись..."
        startTimer()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
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
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Ошибка микрофона"
                        resetButtons()
                    }
                    return@launch
                }

                audioRecord?.startRecording()
                val buffer = ByteArray(bufferSize)

                while (isRecording) {
                    if (isPaused) {
                        delay(100)
                        continue
                    }

                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (bytesRead > 0) {
                        when (recognitionMode) {
                            "ru" -> processRecognizer(ruRecognizer, buffer, bytesRead)
                            "en" -> processRecognizer(enRecognizer, buffer, bytesRead)
                            "both" -> {
                                val ruText = processRecognizerForResult(ruRecognizer, buffer, bytesRead)
                                val enText = processRecognizerForResult(enRecognizer, buffer, bytesRead)

                                val combined = when {
                                    ruText.isNotEmpty() && enText.isNotEmpty() -> "[RU] $ruText\n[EN] $enText"
                                    ruText.isNotEmpty() -> "[RU] $ruText"
                                    enText.isNotEmpty() -> "[EN] $enText"
                                    else -> ""
                                }

                                if (combined.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        updateResult(combined)
                                    }
                                }
                            }
                        }
                    }
                }

                audioRecord?.stop()

                when (recognitionMode) {
                    "ru" -> getFinalResult(ruRecognizer)
                    "en" -> getFinalResult(enRecognizer)
                    "both" -> {
                        val ruFinal = getFinalResultText(ruRecognizer)
                        val enFinal = getFinalResultText(enRecognizer)
                        val finalCombined = when {
                            ruFinal.isNotEmpty() && enFinal.isNotEmpty() -> "[RU] $ruFinal\n[EN] $enFinal"
                            ruFinal.isNotEmpty() -> "[RU] $ruFinal"
                            enFinal.isNotEmpty() -> "[EN] $enFinal"
                            else -> ""
                        }
                        if (finalCombined.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                updateResult(finalCombined)
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    tvStatus.text = "Запись завершена"
                    resetButtons()
                    stopTimer()
                }

            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Нет разрешения"
                    resetButtons()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Ошибка: ${e.message}"
                    resetButtons()
                }
            } finally {
                audioRecord?.release()
            }
        }
    }

    private suspend fun processRecognizer(
        recognizer: Recognizer?,
        buffer: ByteArray,
        bytesRead: Int
    ) {
        if (recognizer == null) return

        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
            val text = parseResult(recognizer.result)
            if (text.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    updateResult(text)
                }
            }
        } else {
            val partial = recognizer.partialResult
            val text = parsePartialResult(partial)
            if (text.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    updateResult(text)
                }
            }
        }
    }

    private suspend fun processRecognizerForResult(
        recognizer: Recognizer?,
        buffer: ByteArray,
        bytesRead: Int
    ): String {
        if (recognizer == null) return ""

        return if (recognizer.acceptWaveForm(buffer, bytesRead)) {
            parseResult(recognizer.result)
        } else {
            parsePartialResult(recognizer.partialResult)
        }
    }

    private suspend fun getFinalResult(recognizer: Recognizer?) {
        if (recognizer == null) return

        val finalResult = recognizer.result
        val text = parseResult(finalResult)
        if (text.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                updateResult(text)
            }
        }
        recognizer.close()
    }

    private fun getFinalResultText(recognizer: Recognizer?): String {
        if (recognizer == null) return ""
        val result = recognizer.result
        recognizer.close()
        return parseResult(result)
    }

    private fun processLecture() {
        val textToProcess = tvResult.text.toString()
        if (textToProcess.isEmpty()) {
            Toast.makeText(this, "Нет текста для обработки", Toast.LENGTH_SHORT).show()
            return
        }

        if (!llmEdge.isReady()) {
            Toast.makeText(this, "Lucy загружается. Пожалуйста, подождите.", Toast.LENGTH_SHORT).show()
            return
        }

        btnProcess.isEnabled = false
        layoutProgress.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        tvProgressStatus.visibility = View.VISIBLE
        progressBar.progress = 0

        lifecycleScope.launch {
            try {
                val result = llmEdge.createLectureSummary(
                    rawText = textToProcess,
                    internetSearch = { query ->
                        searxngBridge.search(query)
                    },
                    onStatus = { status ->
                        runOnUiThread {
                            tvProgressStatus.text = status
                            when {
                                status.contains("Анализ", true) -> progressBar.progress = 20
                                status.contains("темы", true) -> progressBar.progress = 40
                                status.contains("Поиск", true) -> progressBar.progress = 50
                                status.contains("Интернет", true) -> progressBar.progress = 60
                                status.contains("Противоречия", true) -> progressBar.progress = 70
                                status.contains("Конспект", true) || status.contains("генерация", true) -> progressBar.progress = 85
                                status.contains("Готов", true) -> progressBar.progress = 100
                            }
                        }
                    }
                )

                runOnUiThread {
                    progressBar.progress = 100
                    tvProgressStatus.text = "✅ Конспект готов!"
                    currentSummaryText = result
                    tvResult.setText(result)
                    btnViewSummary.isEnabled = true
                    tvStatus.text = "✅ Конспект успешно создан!"
                }

                saveToFile(result)

            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "❌ Ошибка: ${e.message}"
                    Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    btnProcess.isEnabled = true
                    handler.postDelayed({
                        layoutProgress.visibility = View.GONE
                        progressBar.visibility = View.GONE
                        tvProgressStatus.visibility = View.GONE
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
                loadBothModels()
            } else {
                tvStatus.text = "Нет разрешения на запись"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
        recordingJob?.cancel()
        ruRecognizer?.close()
        enRecognizer?.close()
        ruModel?.close()
        enModel?.close()
        llmEdge.close()
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