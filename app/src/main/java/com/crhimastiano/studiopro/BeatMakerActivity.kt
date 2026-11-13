package com.crhimastiano.studiopro

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/** Offline 16-step drum sequencer backed by the shared Oboe output stream. */
class BeatMakerActivity : Activity() {
    private external fun nativeSetBeatPattern(instrument: Int, mask: Int)
    private external fun nativeSetBeatTempo(bpm: Int)
    private external fun nativeBeatPlay(): Boolean
    private external fun nativeBeatStop()
    private external fun nativeBeatReset()
    private external fun nativeBeatCurrentStep(): Int

    companion object {
        private const val STEP_COUNT = 16
        private const val INSTRUMENT_COUNT = 4
        private const val DEFAULT_BPM = 120
        private const val PREFS = "beat_maker_pattern"
        private const val KEY_BPM = "tempo"
        private val ROW_NAMES = arrayOf("KICK", "SNARE", "HI-HAT", "PERC")
        private val ROW_COLORS = intArrayOf(
            Color.rgb(235, 169, 79), Color.rgb(221, 100, 91),
            Color.rgb(104, 190, 166), Color.rgb(147, 137, 226)
        )

        init { System.loadLibrary("studio_audio") }
    }

    private val pattern = IntArray(INSTRUMENT_COUNT)
    private var bpm = DEFAULT_BPM
    private lateinit var grid: StepGridView
    private lateinit var tempoLabel: TextView
    private lateinit var statusLabel: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val pollPlayhead = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                grid.setPlayhead(nativeBeatCurrentStep())
                handler.postDelayed(this, 30L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 11, 14)
        window.navigationBarColor = Color.rgb(10, 11, 14)
        loadPattern()
        setContentView(buildUi())
        syncPatternToEngine()
        nativeSetBeatTempo(bpm)
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
        header.addView(text("‹  ACCUEIL", 13f, Color.rgb(230, 190, 98), true).apply {
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        header.addView(text("BEAT MAKER", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(View(this), LinearLayout.LayoutParams(dp(72), 1))
        page.addView(header)
        page.addView(text("Séquenceur 16 pas · audio local Oboe", 12f, Color.rgb(151, 155, 164)).apply {
            setPadding(0, dp(2), 0, dp(14))
        })

        val tempoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(5))
            setBackgroundColor(Color.rgb(23, 25, 30))
        }
        tempoLabel = text("$bpm BPM", 14f, Color.rgb(230, 190, 98), true)
        tempoRow.addView(tempoLabel, LinearLayout.LayoutParams(dp(76), dp(48)))
        val tempoSlider = SeekBar(this).apply {
            max = 180
            progress = bpm - 40
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(230, 190, 98))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    bpm = (progress + 40).coerceIn(40, 220)
                    tempoLabel.text = "$bpm BPM"
                    nativeSetBeatTempo(bpm)
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_BPM, bpm).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        tempoRow.addView(tempoSlider, LinearLayout.LayoutParams(0, dp(48), 1f))
        page.addView(tempoRow)

        statusLabel = text("Touchez les cases pour créer un pattern.", 12f, Color.rgb(180, 183, 190)).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), 0, dp(8))
        }
        page.addView(statusLabel)

        grid = StepGridView(this, pattern) { instrument, step, enabled ->
            val bit = 1 shl step
            pattern[instrument] = if (enabled) pattern[instrument] or bit else pattern[instrument] and bit.inv()
            nativeSetBeatPattern(instrument, pattern[instrument])
            savePattern()
        }
        page.addView(grid, LinearLayout.LayoutParams(-1, dp(244)))

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        transport.addView(actionButton("▶  PLAY", Color.rgb(112, 82, 30)) {
            if (nativeBeatPlay()) statusLabel.text = "Lecture en cours · $bpm BPM"
            else statusLabel.text = "Impossible de démarrer le flux audio Oboe."
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5) })
        transport.addView(actionButton("■  STOP", Color.rgb(48, 49, 55)) {
            nativeBeatStop()
            statusLabel.text = "Lecture arrêtée."
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(5)
            marginEnd = dp(5)
        })
        transport.addView(actionButton("RESET", Color.rgb(48, 49, 55)) {
            nativeBeatReset()
            pattern.fill(0)
            syncPatternToEngine()
            savePattern()
            grid.invalidate()
            statusLabel.text = "Pattern effacé. Touchez les cases pour recommencer."
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5) })
        page.addView(transport)

        page.addView(text("Kick, Snare, Hi-Hat et Percussion sont synthétisés localement. Les patterns sont conservés sur cet appareil.", 11f, Color.rgb(135, 139, 148)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(14), dp(4), dp(2))
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }
    }

    private fun loadPattern() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        bpm = prefs.getInt(KEY_BPM, DEFAULT_BPM).coerceIn(40, 220)
        for (row in 0 until INSTRUMENT_COUNT) {
            val defaultMask = when (row) {
                0 -> (1 shl 0) or (1 shl 4) or (1 shl 8) or (1 shl 12)
                1 -> (1 shl 4) or (1 shl 12)
                2 -> (1 shl 0) or (1 shl 2) or (1 shl 4) or (1 shl 6) or
                    (1 shl 8) or (1 shl 10) or (1 shl 12) or (1 shl 14)
                else -> 0
            }
            pattern[row] = prefs.getInt("row_$row", defaultMask) and 0xFFFF
        }
    }

    private fun savePattern() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            putInt(KEY_BPM, bpm)
            for (row in 0 until INSTRUMENT_COUNT) putInt("row_$row", pattern[row])
        }.apply()
    }

    private fun syncPatternToEngine() {
        for (row in 0 until INSTRUMENT_COUNT) nativeSetBeatPattern(row, pattern[row])
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollPlayhead)
        handler.post(pollPlayhead)
    }

    override fun onPause() {
        handler.removeCallbacks(pollPlayhead)
        nativeBeatStop()
        super.onPause()
    }

    private fun actionButton(title: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = title
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private class StepGridView(
        context: android.content.Context,
        private val masks: IntArray,
        private val onStepChanged: (row: Int, step: Int, enabled: Boolean) -> Unit
    ) : View(context) {
        private val labelWidth get() = dp(72).toFloat()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var playhead = -1
        private val rowNames = arrayOf("KICK", "SNARE", "HI-HAT", "PERC")
        private val colors = intArrayOf(
            Color.rgb(235, 169, 79), Color.rgb(221, 100, 91),
            Color.rgb(104, 190, 166), Color.rgb(147, 137, 226)
        )

        fun setPlayhead(step: Int) {
            if (playhead != step) {
                playhead = step
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rowHeight = height / 4f
            val cellWidth = max(1f, (width - labelWidth) / 16f)
            for (row in 0..3) {
                val top = row * rowHeight
                paint.color = Color.rgb(18, 20, 24)
                canvas.drawRoundRect(RectF(0f, top + dp(3), width.toFloat(), top + rowHeight - dp(3)), dp(8).toFloat(), dp(8).toFloat(), paint)
                paint.color = colors[row]
                paint.textSize = dp(10).toFloat()
                paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                canvas.drawText(rowNames[row], dp(8).toFloat(), top + rowHeight * 0.56f, paint)
                for (step in 0 until 16) {
                    val left = labelWidth + step * cellWidth + dp(2)
                    val right = labelWidth + (step + 1) * cellWidth - dp(2)
                    val cellTop = top + dp(8)
                    val cellBottom = top + rowHeight - dp(8)
                    val active = masks[row] and (1 shl step) != 0
                    paint.color = when {
                        step == playhead && active -> Color.WHITE
                        active -> colors[row]
                        step == playhead -> Color.rgb(63, 66, 75)
                        else -> Color.rgb(43, 46, 53)
                    }
                    canvas.drawRoundRect(RectF(left, cellTop, right, cellBottom), dp(4).toFloat(), dp(4).toFloat(), paint)
                    if (row == 0 && step % 4 == 0) {
                        paint.color = Color.rgb(145, 149, 158)
                        paint.textSize = dp(8).toFloat()
                        paint.typeface = android.graphics.Typeface.DEFAULT
                        canvas.drawText((step + 1).toString(), left, top + dp(7), paint)
                    }
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_UP) return true
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val row = (event.y / (height / 4f)).toInt().coerceIn(0, 3)
                val cellWidth = max(1f, (width - labelWidth) / 16f)
                val step = ((event.x - labelWidth) / cellWidth).toInt()
                if (event.x >= labelWidth && step in 0..15) {
                    val enabled = masks[row] and (1 shl step) == 0
                    onStepChanged(row, step, enabled)
                    invalidate()
                }
            }
            return true
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
