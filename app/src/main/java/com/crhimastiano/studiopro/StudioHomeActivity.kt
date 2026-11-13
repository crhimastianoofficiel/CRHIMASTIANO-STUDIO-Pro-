package com.crhimastiano.studiopro

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Native studio dashboard. Only implemented modules are actionable. */
class StudioHomeActivity : Activity() {
    private val background = Color.rgb(10, 11, 14)
    private val surface = Color.rgb(23, 25, 30)
    private val muted = Color.rgb(151, 155, 164)
    private val gold = Color.rgb(230, 190, 98)

    private data class Module(val title: String, val detail: String, val icon: String, val available: Boolean = false)

    private val modules = listOf(
        Module("Piano", "Clavier tactile · audio local", "♫", true),
        Module("Beat Maker", "Séquenceur rythmique · 16 pas", "▦", true),
        Module("Piano Roll / MIDI", "Édition et séquençage MIDI", "⌁"),
        Module("Enregistrement vocal", "Capture microphone", "●"),
        Module("Multipiste", "Pistes audio et MIDI", "▤"),
        Module("Chœurs et Duo", "Voix et arrangement", "♫"),
        Module("Voix IA", "Fournisseur spécialisé à intégrer", "✧"),
        Module("Mixage", "Volume, panoramique et effets", "◒"),
        Module("Mastering", "Traitement final", "◈"),
        Module("Exportation", "WAV, MP3 et MIDI selon support", "⇧")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = 0
        setContentView(buildDashboard())
    }

    private fun buildDashboard(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(this@StudioHomeActivity.background)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }

        page.addView(label("CRHIMASTIANO  /  STUDIO PRO", 12f, gold, true).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(8), 0, dp(18))
        })
        page.addView(label("Votre studio.\nVotre prochaine idée.", 29f, Color.WHITE, true).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        page.addView(label("Choisissez un module pour commencer.", 14f, muted).apply {
            setPadding(0, dp(10), 0, dp(20))
        })

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Color.rgb(38, 34, 25), dp(18), Color.rgb(86, 69, 38))
        }
        hero.addView(label("PRÊT À CRÉER", 11f, gold, true).apply { letterSpacing = 0.1f })
        hero.addView(label("Ouvrez le piano ou le Beat Maker.", 15f, Color.WHITE).apply {
            setPadding(0, dp(7), 0, 0)
        })
        page.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        page.addView(label("MODULES DU STUDIO", 12f, muted, true).apply {
            letterSpacing = 0.1f
            setPadding(0, 0, 0, dp(12))
        })

        modules.chunked(2).forEach { rowModules ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowModules.forEachIndexed { index, module ->
                val tile = buildModuleTile(module)
                row.addView(tile, LinearLayout.LayoutParams(0, dp(124), 1f).apply {
                    if (index == 0) marginEnd = dp(8) else marginStart = dp(8)
                    bottomMargin = dp(12)
                })
            }
            if (rowModules.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(124), 1f).apply { marginStart = dp(8) })
            page.addView(row)
        }

        page.addView(label("Les modules indiqués « En préparation » ne sont pas encore disponibles.", 12f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(4))
        })
        return scroll
    }

    private fun buildModuleTile(module: Module): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = rounded(surface, dp(16), if (module.available) Color.rgb(112, 88, 43) else Color.rgb(43, 45, 51))
            isClickable = module.available
            isFocusable = module.available
            if (module.available) setOnClickListener {
                val destination = when (module.title) {
                    "Piano" -> MainActivity::class.java
                    "Beat Maker" -> BeatMakerActivity::class.java
                    else -> return@setOnClickListener
                }
                startActivity(Intent(this@StudioHomeActivity, destination))
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = module.icon
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(gold)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.rgb(43, 39, 30), dp(10), Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        if (module.available) {
            top.addView(label("DISPONIBLE", 9f, gold, true).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(dp(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        tile.addView(top)
        tile.addView(label(module.title, 14f, Color.WHITE, true).apply {
            maxLines = 1
            setPadding(0, dp(10), 0, dp(2))
        })
        tile.addView(label(if (module.available) module.detail else "En préparation", 11f, if (module.available) muted else gold).apply {
            maxLines = 2
        })
        return tile
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
