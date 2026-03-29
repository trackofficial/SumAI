package com.example.sumai

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class TestVoskActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnTest: Button
    private var model: Model? = null
    private var isRecording = false

    companion object {
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_vosk)

        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        btnTest = findViewById(R.id.btnTest)

        btnTest.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        if (hasRecordAudioPermission()) {
            loadModel()
        } else {
            requestRecordAudioPermission()
        }
    }

    private fun loadModel() {
        tvStatus.text = "Загрузка модели..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val modelDir = File(filesDir, "model-en")
                if (!modelDir.exists()) {
                    copyAssetsToDir("model-en", modelDir)
                }
                model = Model(modelDir.absolutePath)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Модель загружена. Можно начинать"
                    btnTest.isEnabled = true
                    Toast.makeText(this@TestVoskActivity, "Модель готова", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Ошибка: ${e.message}"
                    Toast.makeText(this@TestVoskActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyAssetsToDir(assetPath: String, destinationDir: File) {
        try {
            destinationDir.mkdirs()
            val assetList = assets.list(assetPath)
            if (assetList == null || assetList.isEmpty()) return

            for (assetName in assetList) {
                val assetFullPath = "$assetPath/$assetName"
                val destFile = File(destinationDir, assetName)

                // Проверяем, является ли это папкой
                val subAssets = assets.list(assetFullPath)
                if (subAssets != null && subAssets.isNotEmpty()) {
                    copyAssetsToDir(assetFullPath, destFile)
                } else {
                    assets.open(assetFullPath).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun startRecording() {
        if (model == null) {
            Toast.makeText(this, "Модель не загружена", Toast.LENGTH_SHORT).show()
            return
        }

        // Проверяем разрешение перед записью
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            return
        }

        isRecording = true
        btnTest.text = "Остановить"
        tvResult.text = "Запись..."
        tvStatus.text = "Говорите..."

        CoroutineScope(Dispatchers.IO).launch {
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
                        tvStatus.text = "Ошибка инициализации микрофона"
                        btnTest.text = "Тест"
                        isRecording = false
                    }
                    return@launch
                }

                val tempFile = File(cacheDir, "test_audio.pcm")
                val fos = FileOutputStream(tempFile)

                audioRecord.startRecording()
                val buffer = ByteArray(bufferSize)

                val startTime = System.currentTimeMillis()
                while (isRecording && System.currentTimeMillis() - startTime < 5000) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }

                fos.close()
                audioRecord.stop()
                audioRecord.release()

                withContext(Dispatchers.Main) {
                    tvStatus.text = "Обработка..."
                }

                val recognizer = Recognizer(model, sampleRate.toFloat())
                val audioData = tempFile.readBytes()
                tempFile.delete()

                if (recognizer.acceptWaveForm(audioData, audioData.size)) {
                    val result = recognizer.result
                    val text = parseResult(result)
                    withContext(Dispatchers.Main) {
                        tvResult.text = if (text.isEmpty()) "Ничего не распознано" else text
                        tvStatus.text = "Готово"
                        btnTest.text = "Тест"
                        isRecording = false
                    }
                } else {
                    val partial = recognizer.partialResult
                    val text = parsePartialResult(partial)
                    withContext(Dispatchers.Main) {
                        tvResult.text = if (text.isEmpty()) "Ничего не распознано" else text
                        tvStatus.text = "Готово"
                        btnTest.text = "Тест"
                        isRecording = false
                    }
                }

                recognizer.close()

            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Нет разрешения на запись"
                    btnTest.text = "Тест"
                    isRecording = false
                    Toast.makeText(this@TestVoskActivity, "Нет разрешения на запись звука", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Ошибка: ${e.message}"
                    btnTest.text = "Тест"
                    isRecording = false
                }
            } finally {
                audioRecord?.release()
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        btnTest.text = "Остановка..."
        tvStatus.text = "Останавливаю..."
    }

    private fun parseResult(json: String): String {
        return try {
            val regex = "\"text\":\"(.*?)\"".toRegex()
            regex.find(json)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun parsePartialResult(json: String): String {
        return try {
            val regex = "\"partial\":\"(.*?)\"".toRegex()
            regex.find(json)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadModel()
            } else {
                Toast.makeText(this, "Разрешение необходимо", Toast.LENGTH_LONG).show()
                tvStatus.text = "Нет разрешения"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        model?.close()
    }
}