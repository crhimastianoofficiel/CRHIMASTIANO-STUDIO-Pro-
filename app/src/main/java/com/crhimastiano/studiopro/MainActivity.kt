package com.crhimastiano.studiopro

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.pow

class MainActivity : Activity() {

    private external fun nativeNoteOn(voiceIndex: Int, frequencyHz: Float): Boolean
    private external fun nativeNoteOff(voiceIndex: Int)
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeSetSustain(enabled: Boolean)
    private external fun nativeStop()

    private var statusView: TextView? = null
    private var sustainEnabled = false
    private val noteVoices = mutableMapOf<Int, Int>()
    private val pointerNotes = mutableMapOf<Int, Int>()
    private val sustainedNotes = mutableSetOf<Int>()

    companion object {
        private const val FIRST_MIDI_NOTE = 48 // C3
        private const val LAST_MIDI_NOTE = 83 // B5: three complete octaves
        private const val MAX_VOICES = 16

        init {
            System.loadLibrary("studio_audio")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(10, 10, 12)
        window.navigationBarColor = Color.rgb(10, 10, 12)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            setBackgroundColor(Color.rgb(10, 10, 12))
        }

        val heading = TextView(this).apply {
            text = "CRHIMASTIANO  /  STUDIO PRO"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(224, 184, 92))
            gravity = Gravity.CENTER
        }
        content.addView(heading, matchWrap())

        val title = TextView(this).apply {
            text = "PIANO"
            textSize = 27f
            letterSpacing = 0.16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
        }
        content.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "36 TOUCHES  •  16 VOIX  •  AUDIO LOCAL"
            textSize = 11f
            letterSpacing = 0.06f
            setTextColor(Color.rgb(155, 155, 160))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        content.addView(subtitle, matchWrap())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(25, 25, 29))
        }
        val volumeLabel = TextView(this).apply {
            text = "VOLUME 80%"
            textSize = 11f
            setTextColor(Color.rgb(232, 198, 120))
        }
        controls.addView(volumeLabel, LinearLayout.LayoutParams(dp(96), dp(48)))

        val volumeSlider = SeekBar(this).apply {
            max = 100
            progress = 80
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(220, 179, 83))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(244, 211, 135))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    nativeSetVolume(progress / 100f)
                    volumeLabel.text = "VOLUME $progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        controls.addView(volumeSlider, LinearLayout.LayoutParams(0, dp(48), 1f))

        val sustainButton = Button(this).apply {
            text = "SUSTAIN  OFF"
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.rgb(240, 218, 168))
            setBackgroundColor(Color.rgb(56, 48, 32))
            setOnClickListener {
                sustainEnabled = !sustainEnabled
                nativeSetSustain(sustainEnabled)
                text = if (sustainEnabled) "SUSTAIN  ON" else "SUSTAIN  OFF"
                setBackgroundColor(if (sustainEnabled) Color.rgb(112, 82, 30) else Color.rgb(56, 48, 32))
                if (!sustainEnabled) releaseSustainedNotes()
            }
        }
        controls.addView(sustainButton, LinearLayout.LayoutParams(dp(124), dp(48)).apply {
            marginStart = dp(8)
        })
        content.addView(controls, matchWrap())

        statusView = TextView(this).apply {
            text = "Prêt — touchez les touches pour jouer. Faites défiler le clavier horizontalement."
            textSize = 12f
            setTextColor(Color.rgb(190, 190, 195))
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        content.addView(statusView, matchWrap())

        val piano = PianoKeyboardView(this) { pointerId, midiNote ->
            handlePianoTouch(pointerId, midiNote)
        }
        val pianoScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            setBackgroundColor(Color.rgb(16, 16, 19))
            // ViewGroup.LayoutParams is sufficient here and avoids coupling the keyboard
            // to a specific scroll-container LayoutParams implementation.
            addView(piano, android.view.ViewGroup.LayoutParams(piano.keyboardWidthPx, dp(240)))
        }
        content.addView(pianoScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(240)
        ))

        val info = TextView(this).apply {
            text = "C3 — B5     •     Jusqu’à 16 notes simultanées\nLe son actuel est un oscillateur de test, pas encore un échantillon de piano."
            textSize = 11f
            setTextColor(Color.rgb(145, 145, 150))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        content.addView(info, matchWrap())

        val verticalScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        setContentView(verticalScroll)
        nativeSetVolume(0.8f)
    }

    private fun handlePianoTouch(pointerId: Int, midiNote: Int?) {
        val previous = pointerNotes[pointerId]
        if (previous == midiNote) return
        if (previous != null) releasePointer(pointerId, previous)
        if (midiNote != null) pressPointer(pointerId, midiNote)
    }

    private fun pressPointer(pointerId: Int, midiNote: Int) {
        if (midiNote !in FIRST_MIDI_NOTE..LAST_MIDI_NOTE) return
        var voiceIndex = noteVoices[midiNote]
        if (voiceIndex == null) {
            voiceIndex = (0 until MAX_VOICES).firstOrNull { it !in noteVoices.values }
            if (voiceIndex == null) {
                statusView?.text = "Limite de polyphonie atteinte : 16 notes."
                return
            }
            val frequency = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
            if (!nativeNoteOn(voiceIndex, frequency.toFloat())) {
                statusView?.text = "Le moteur audio n'a pas pu démarrer."
                return
            }
            noteVoices[midiNote] = voiceIndex
        } else {
            val frequency = 440.0 * 2.0.pow((midiNote - 69) / 12.0)
            if (!nativeNoteOn(voiceIndex, frequency.toFloat())) return
        }
        sustainedNotes.remove(midiNote)
        pointerNotes[pointerId] = midiNote
        statusView?.text = "Note ${noteName(midiNote)} — ${noteVoices.size}/$MAX_VOICES voix"
    }

    private fun releasePointer(pointerId: Int, midiNote: Int) {
        pointerNotes.remove(pointerId)
        if (pointerNotes.values.contains(midiNote)) return
        val voiceIndex = noteVoices[midiNote] ?: return
        nativeNoteOff(voiceIndex)
        if (sustainEnabled) {
            sustainedNotes.add(midiNote)
        } else {
            noteVoices.remove(midiNote)
            statusView?.text = "Prêt — touchez les touches pour jouer."
        }
    }

    private fun releaseSustainedNotes() {
        sustainedNotes.toList().forEach { midiNote ->
            if (!pointerNotes.values.contains(midiNote)) noteVoices.remove(midiNote)
        }
        sustainedNotes.clear()
        statusView?.text = "Sustain désactivé — prêt à jouer."
    }

    override fun onDestroy() {
        nativeStop()
        super.onDestroy()
    }

    private fun noteName(midi: Int): String {
        val names = arrayOf("Do", "Do♯", "Ré", "Ré♯", "Mi", "Fa", "Fa♯", "Sol", "Sol♯", "La", "La♯", "Si")
        return "${names[midi % 12]}${midi / 12 - 1}"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}

/** Canvas-drawn 3-octave keyboard; keys are sized in dp and support independent touch pointers. */
private class PianoKeyboardView(
    context: android.content.Context,
    private val onNoteChanged: (pointerId: Int, midiNote: Int?) -> Unit
) : View(context) {
    private val firstMidi = 48
    private val lastMidi = 83
    private val whiteKeyDp = 48f
    private val blackKeyDp = 29f
    private val keyHeightDp = 240f
    private val whitePitches = setOf(0, 2, 4, 5, 7, 9, 11)
    private val whiteNotes = (firstMidi..lastMidi).filter { it % 12 in whitePitches }
    private val activePointers = mutableMapOf<Int, Int>()
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(160, 126, 57)
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(75, 68, 54)
        textSize = dp(10).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val blackLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(234, 205, 143)
        textSize = dp(8).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val whiteWidth = dp(whiteKeyDp.toInt()).toFloat()
    private val blackWidth = dp(blackKeyDp.toInt()).toFloat()
    private val keyHeight = dp(keyHeightDp.toInt()).toFloat()
    private val blackHeight = keyHeight * 0.62f
    val keyboardWidthPx: Int = (whiteNotes.size * whiteWidth).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(keyboardWidthPx, keyHeight.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 12, 14))
        whiteNotes.forEachIndexed { index, midi ->
            val left = index * whiteWidth
            val rect = RectF(left, 0f, left + whiteWidth, keyHeight)
            whitePaint.color = if (midi in activePointers.values) Color.rgb(235, 198, 112) else Color.rgb(244, 242, 235)
            whitePaint.style = Paint.Style.FILL
            canvas.drawRect(rect, whitePaint)
            canvas.drawRect(rect, edgePaint)
            if (midi % 12 == 0) {
                canvas.drawText("C${midi / 12 - 1}", rect.centerX(), keyHeight - dp(12), labelPaint)
            }
        }

        var whitesBefore = 0
        for (midi in firstMidi..lastMidi) {
            val pitch = midi % 12
            if (pitch in whitePitches) {
                whitesBefore++
                continue
            }
            val left = whitesBefore * whiteWidth - blackWidth / 2f
            val rect = RectF(left, 0f, left + blackWidth, blackHeight)
            blackPaint.color = if (midi in activePointers.values) Color.rgb(196, 145, 54) else Color.rgb(30, 30, 34)
            blackPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, dp(3).toFloat(), dp(3).toFloat(), blackPaint)
            canvas.drawRoundRect(rect, dp(3).toFloat(), dp(3).toFloat(), edgePaint)
            canvas.drawText("♯", rect.centerX(), blackHeight - dp(9), blackLabelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                updatePointer(event.getPointerId(index), event.xForPointer(index), event.yForPointer(index))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    updatePointer(event.getPointerId(index), event.xForPointer(index), event.yForPointer(index))
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                activePointers.remove(pointerId)
                onNoteChanged(pointerId, null)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointers.keys.toList().forEach { pointerId -> onNoteChanged(pointerId, null) }
                activePointers.clear()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun updatePointer(pointerId: Int, x: Float, y: Float) {
        val note = noteAt(x, y)
        if (activePointers[pointerId] == note) return
        if (note == null) activePointers.remove(pointerId) else activePointers[pointerId] = note
        onNoteChanged(pointerId, note)
        invalidate()
    }

    private fun noteAt(x: Float, y: Float): Int? {
        if (x < 0f || x >= keyboardWidthPx || y < 0f || y >= keyHeight) return null
        // Black keys take priority in the overlap above the white keys.
        var whitesBefore = 0
        for (midi in firstMidi..lastMidi) {
            val pitch = midi % 12
            if (pitch in whitePitches) {
                whitesBefore++
            } else {
                val left = whitesBefore * whiteWidth - blackWidth / 2f
                if (x >= left && x <= left + blackWidth && y <= blackHeight) return midi
            }
        }
        val whiteIndex = (x / whiteWidth).toInt().coerceIn(0, whiteNotes.lastIndex)
        return whiteNotes[whiteIndex]
    }

    private fun MotionEvent.xForPointer(index: Int) = getX(index)
    private fun MotionEvent.yForPointer(index: Int) = getY(index)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
