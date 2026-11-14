package com.crhimastiano.studiopro

import android.app.Application
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persists uncaught Kotlin/Java exceptions before delegating to Android's normal crash handler. */
class CrashReportApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashReport(thread, throwable)
            } catch (_: Exception) {
                // Preserve normal crash handling even if private storage is unavailable.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashReport(thread: Thread, throwable: Throwable) {
        val reportDirectory = File(filesDir, REPORT_DIRECTORY)
        if (!reportDirectory.exists() && !reportDirectory.mkdirs()) return

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss.SSS", Locale.US).format(Date())
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            printWriter.println("CRHIMASTIANO STUDIO PRO — rapport de crash")
            printWriter.println("Date: $timestamp")
            printWriter.println("Thread: ${thread.name} (${thread.id})")
            printWriter.println("Application: $packageName")
            printWriter.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            printWriter.println("Appareil: ${Build.MANUFACTURER} ${Build.MODEL}")
            printWriter.println()
            printWriter.println("Trace complète (y compris les causes et exceptions supprimées):")
            throwable.printStackTrace(printWriter)
        }

        val reportBytes = writer.toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(File(reportDirectory, LATEST_REPORT), false).use { output ->
            output.write(reportBytes)
            output.fd.sync()
        }
        File(reportDirectory, "crash-$timestamp.txt").writeBytes(reportBytes)
    }

    companion object {
        const val REPORT_DIRECTORY = "crash-reports"
        const val LATEST_REPORT = "latest-crash.txt"
    }
}
