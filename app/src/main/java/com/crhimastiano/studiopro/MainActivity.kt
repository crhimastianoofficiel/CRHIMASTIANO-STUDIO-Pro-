package com.crhimastiano.studiopro

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private external fun nativeNoteOn(voiceIndex: Int, frequencyHz: Float): Boolean
    private external fun nativeNoteOff(voiceIndex: Int)
    private external fun nativeStop()

    private var statusView: TextView? = null

    companion object {
        init {
            System.loadLibrary("studio_audio")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 14, 20)
        window.navigationBarColor = Color.rgb(12, 14, 20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 34, 20, 24)
            setBackgroundColor(Color.rgb(12, 14, 20))
        }

        val title = TextView(this).apply {
            text = "CRHIMASTIANO STUDIO PRO"
            textSize = 22f
            setTextColor(Color.rgb(245, 190, 70))
            gravity = Gravity.CENTER
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "AUDIO ENGINE • OBOE • OFFLINE TEST"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 22)
        }
        root.addView(subtitle, matchWrap())

        statusView = TextView(this).apply {
            text = "Prêt — touche une ou plusieurs notes."
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 18, 8, 26)
        }
        root.addView(statusView, matchWrap())

        val notes = listOf(
            "DO" to 261.63f,
            "RÉ" to 293.66f,
            "MI" to 329.63f,
            "FA" to 349.23f,
            "SOL" to 392.00f,
            "LA" to 440.00f,
            "SI" to 493.88f,
            "DO+" to 523.25f
        )

        notes.chunked(4).forEachIndexed { rowIndex, rowNotes ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowNotes.forEachIndexed { columnIndex, (label, frequency) ->
                val voiceIndex = rowIndex * 4 + columnIndex
                val key = Button(this).apply {
                    text = label
                    textSize = 14f
                    isAllCaps = false
                    setTextColor(Color.BLACK)
                    setBackgroundColor(Color.rgb(238, 190, 83))
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                                val started = nativeNoteOn(voiceIndex, frequency)
                                statusView?.text = if (started) {
                                    "Lecture : $label — ${frequency.toInt()} Hz"
                                } else {
                                    "Impossible d'ouvrir le moteur audio."
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                nativeNoteOff(voiceIndex)
                                statusView?.text = "Prêt — moteur audio local."
                                true
                            }
                            else -> true
                        }
                    }
                }
                val params = LinearLayout.LayoutParams(0, 92, 1f).apply {
                    setMargins(4, 5, 4, 5)
                }
                row.addView(key, params)
            }
            root.addView(row, matchWrap())
        }

        val note = TextView(this).apply {
            text = "Prototype initial : son synthétisé localement.\nLes instruments échantillonnés et les fonctions studio seront ajoutés ensuite."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 28, 8, 0)
        }
        root.addView(note, matchWrap())

        setContentView(root)
    }

    override fun onDestroy() {
        nativeStop()
        super.onDestroy()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
}
