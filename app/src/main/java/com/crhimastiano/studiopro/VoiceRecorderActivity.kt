package com.crhimastiano.studiopro

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/** Real offline microphone capture saved as PCM16 WAV files in the app's private storage. */
class VoiceRecorderActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var recordingsList: LinearLayout

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var recordingFile: File? = null
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var destroying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 11, 14)
        window.navigationBarColor = Color.rgb(10, 11, 14)
        setContentView(buildUi())
        refreshRecordings()
        if (hasMicrophonePermission()) {
            statusText.text = "Microphone prêt. Les prises restent sur cet appareil."
        } else {
            requestMicrophonePermission()
        }
    }

    private fun buildUi(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            setBackgroundColor(Color.rgb(10, 11, 14))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("‹  ACCUEIL", 13f, GOLD, true).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        header.addView(label("ENREGISTREMENT VOCAL", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(View(this), LinearLayout.LayoutParams(dp(18), 1))
        page.addView(header)

        statusText = label("Vérification du microphone…", 12f, Color.rgb(180, 183, 190)).apply {
            setPadding(0, dp(8), 0, dp(12))
        }
        page.addView(statusText)
        recordButton = Button(this).apply {
            text = "●  ENREGISTRER"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(112, 82, 30))
            setOnClickListener { if (recording) stopRecording() else startRecording() }
        }
        page.addView(recordButton, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(20) })
        page.addView(label("PRISES ENREGISTRÉES", 12f, Color.rgb(151, 155, 164), true).apply {
            setPadding(0, 0, 0, dp(8))
        })
        recordingsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(recordingsList)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    private fun startRecording() {
        if (!hasMicrophonePermission()) {
            statusText.text = "L'autorisation du microphone est nécessaire."
            requestMicrophonePermission()
            return
        }
        if (recording || recordingThread?.isAlive == true) return

        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBytes <= 0) {
            statusText.text = "AudioRecord ne peut pas fournir une taille de buffer valide sur cet appareil."
            return
        }
        val bufferBytes = maxOf(minBytes, SAMPLE_RATE / 2) and -2
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferBytes * 2)
        } catch (e: Exception) {
            statusText.text = "Impossible d'ouvrir le microphone : ${e.localizedMessage ?: "erreur inconnue"}"
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            statusText.text = "L'initialisation du microphone a échoué. Vérifie les réglages de l'appareil."
            return
        }

        val directory = File(filesDir, RECORDINGS_DIR)
        if (!directory.exists() && !directory.mkdirs()) {
            recorder.release()
            statusText.text = "Impossible de créer le dossier local des prises."
            return
        }
        val outputFile = File(directory, "prise_${System.currentTimeMillis()}.wav")
        audioRecord = recorder
        recordingFile = outputFile
        recording = true
        recordButton.text = "■  ARRÊTER"
        recordButton.setBackgroundColor(Color.rgb(145, 38, 42))
        statusText.text = "Enregistrement en cours…"

        recordingThread = Thread({ captureToWav(recorder, outputFile, bufferBytes) }, "VoiceRecorder-Capture").also {
            it.priority = Thread.NORM_PRIORITY
            it.start()
        }
    }

    /** AudioRecord.read and file writes happen on this worker, never on the UI or Oboe callback thread. */
    private fun captureToWav(recorder: AudioRecord, file: File, bufferBytes: Int) {
        var successful = false
        var failure: String? = null
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord n'est pas passé en mode enregistrement.")
            }
            BufferedOutputStream(FileOutputStream(file), bufferBytes).use { output ->
                output.write(wavHeader(0))
                val pcm = ByteArray(bufferBytes)
                var totalBytes = 0L
                while (recording) {
                    val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        val alignedCount = count and -2
                        if (alignedCount > 0) {
                            output.write(pcm, 0, alignedCount)
                            totalBytes += alignedCount
                            if (totalBytes > MAX_WAV_DATA_BYTES) throw IllegalStateException("La prise dépasse la taille maximale prise en charge par WAV.")
                        }
                    } else if (count < 0 && recording) {
                        throw IllegalStateException("Erreur AudioRecord : ${audioRecordError(count)}")
                    }
                }
                output.flush()
                successful = totalBytes > 0
            }
            if (successful) {
                RandomAccessFile(file, "rw").use { wav ->
                    wav.seek(0)
                    wav.write(wavHeader(file.length() - WAV_HEADER_BYTES))
                }
            } else {
                throw IllegalStateException("Aucun son n'a été capturé.")
            }
        } catch (e: Exception) {
            failure = e.localizedMessage ?: "Erreur pendant l'enregistrement."
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: Exception) { }
            recorder.release()
            if (!successful) file.delete()
            runOnUiThread {
                if (audioRecord === recorder) audioRecord = null
                recordingThread = null
                recordingFile = null
                recording = false
                if (!destroying) {
                    recordButton.text = "●  ENREGISTRER"
                    recordButton.setBackgroundColor(Color.rgb(112, 82, 30))
                    statusText.text = if (successful) "Prise WAV enregistrée localement." else "Échec de l'enregistrement : ${failure ?: "erreur inconnue"}"
                    refreshRecordings()
                }
            }
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        recordButton.isEnabled = false
        recordButton.text = "Finalisation…"
        statusText.text = "Finalisation du fichier WAV…"
        try {
            // stop() unblocks a blocking AudioRecord.read; file finalization remains on the worker.
            audioRecord?.stop()
        } catch (_: Exception) { }
        recordingThread?.interrupt()
    }

    private fun refreshRecordings() {
        if (!::recordingsList.isInitialized) return
        recordingsList.removeAllViews()
        val directory = File(filesDir, RECORDINGS_DIR)
        val files = directory.listFiles { file -> file.isFile && file.extension.equals("wav", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            recordingsList.addView(label("Aucune prise pour le moment.", 12f, Color.rgb(151, 155, 164)).apply {
                setPadding(0, dp(12), 0, dp(8))
            })
            return
        }
        files.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(label(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.lastModified())), 12f, Color.WHITE, true))
            details.addView(label("${file.length() / 1024} Ko · WAV", 10f, Color.rgb(151, 155, 164)))
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = if (playingFile == file) "■" else "▶"
                contentDescription = if (playingFile == file) "Arrêter la lecture" else "Lire l'enregistrement"
                setOnClickListener { if (playingFile == file) stopPlayback() else playRecording(file) }
            }, LinearLayout.LayoutParams(dp(56), dp(44)))
            row.addView(Button(this).apply {
                text = "Supprimer"
                isAllCaps = false
                setOnClickListener { deleteRecording(file) }
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(6) })
            recordingsList.addView(row)
        }
    }

    private fun playRecording(file: File) {
        if (!file.isFile) {
            statusText.text = "Ce fichier n'existe plus."
            refreshRecordings()
            return
        }
        stopPlayback()
        val newPlayer = MediaPlayer()
        player = newPlayer
        playingFile = file
        newPlayer.setOnPreparedListener { mediaPlayer ->
            if (player === mediaPlayer) {
                mediaPlayer.start()
                statusText.text = "Lecture : ${file.name}"
                refreshRecordings()
            }
        }
        newPlayer.setOnCompletionListener { mediaPlayer ->
            if (player === mediaPlayer) {
                releasePlayer()
                statusText.text = "Lecture terminée."
                refreshRecordings()
            }
        }
        newPlayer.setOnErrorListener { mediaPlayer, _, _ ->
            if (player === mediaPlayer) releasePlayer()
            statusText.text = "Impossible de lire ce fichier WAV."
            refreshRecordings()
            true
        }
        try {
            newPlayer.setDataSource(file.absolutePath)
            newPlayer.prepareAsync()
            statusText.text = "Préparation de la lecture…"
        } catch (e: Exception) {
            releasePlayer()
            statusText.text = "Erreur de lecture : ${e.localizedMessage ?: "fichier invalide"}"
        }
    }

    private fun stopPlayback() {
        releasePlayer()
        if (!destroying && ::statusText.isInitialized && !recording) statusText.text = "Lecture arrêtée."
        if (!destroying) refreshRecordings()
    }

    private fun releasePlayer() {
        val oldPlayer = player
        player = null
        playingFile = null
        try { oldPlayer?.stop() } catch (_: Exception) { }
        try { oldPlayer?.reset() } catch (_: Exception) { }
        try { oldPlayer?.release() } catch (_: Exception) { }
    }

    private fun deleteRecording(file: File) {
        if (playingFile == file) releasePlayer()
        if (!file.delete()) {
            statusText.text = "La suppression de la prise a échoué."
        } else {
            statusText.text = "Prise supprimée."
        }
        refreshRecordings()
    }

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicrophonePermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            statusText.text = "Autorise le microphone dans la demande Android pour enregistrer une prise."
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Microphone autorisé. Tu peux enregistrer une prise."
        } else {
            statusText.text = if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                "Permission refusée : autorise le microphone pour utiliser l'enregistreur."
            } else {
                "Permission microphone refusée. Active-la dans les paramètres de l'application."
            }
        }
    }

    override fun onDestroy() {
        destroying = true
        if (recording) {
            recording = false
            try { audioRecord?.stop() } catch (_: Exception) { }
            recordingThread?.interrupt()
        }
        releasePlayer()
        super.onDestroy()
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((dataBytes + 36).coerceAtMost(0xFFFF_FFFFL).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
        header.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.coerceAtMost(0xFFFF_FFFFL).toInt())
        return header.array()
    }

    private fun audioRecordError(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "opération invalide"
        AudioRecord.ERROR_BAD_VALUE -> "paramètre invalide"
        AudioRecord.ERROR_DEAD_OBJECT -> "microphone indisponible"
        else -> "code $code"
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_MICROPHONE = 201
        private const val RECORDINGS_DIR = "voice_recordings"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_BYTES = 44
        private const val MAX_WAV_DATA_BYTES = 0xFFFF_FFFFL - 36
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val GOLD = Color.rgb(230, 190, 98)
    }
}
