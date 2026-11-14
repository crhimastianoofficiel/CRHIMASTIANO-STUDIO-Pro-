package com.crhimastiano.studiopro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

/** Standalone launcher screen for reading and sharing the most recent crash report. */
class CrashReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val reportFile = File(filesDir, "${CrashReportApplication.REPORT_DIRECTORY}/${CrashReportApplication.LATEST_REPORT}")
        val reportText = if (reportFile.isFile) {
            try {
                reportFile.readText(Charsets.UTF_8)
            } catch (error: Exception) {
                "Impossible de lire le rapport : ${error.localizedMessage ?: error.javaClass.simpleName}"
            }
        } else {
            "Aucun rapport de crash n'a encore été enregistré. Si l'application plante au démarrage, relance-la puis ouvre l'icône Diagnostic."
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Diagnostic des crashs"
            textSize = 22f
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(Button(this).apply {
            text = "Partager le rapport"
            isEnabled = reportFile.isFile
            setOnClickListener { shareReport(reportFile) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(TextView(this@CrashReportActivity).apply {
                text = reportText
                textSize = 12f
                setTextIsSelectable(true)
                setPadding(0, dp(12), 0, dp(12))
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.gravity = Gravity.TOP
        setContentView(root)
    }

    private fun shareReport(file: File) {
        if (!file.isFile) return
        val reportUri = FileProvider.getUriForFile(this, "$packageName.crashfiles", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Rapport de crash — CRHIMASTIANO STUDIO PRO")
            putExtra(Intent.EXTRA_TEXT, "Rapport de crash en pièce jointe.")
            putExtra(Intent.EXTRA_STREAM, reportUri)
            clipData = android.content.ClipData.newUri(contentResolver, "Rapport de crash", reportUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Partager le rapport de crash"))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
