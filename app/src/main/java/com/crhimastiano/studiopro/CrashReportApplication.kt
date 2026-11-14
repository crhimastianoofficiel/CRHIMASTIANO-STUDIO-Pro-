package com.crhimastiano.studiopro

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persists uncaught Kotlin/Java exceptions from the main app process only. */
class CrashReportApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Android creates this Application in every process. Keep diagnostic-process startup
        // deliberately minimal: no crash handler, studio initialization, or audio loading there.
        if (!isMainApplicationProcess()) return

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashReport(thread, throwable)
            } catch (_: Throwable) {
                // Always delegate the original crash, even if report persistence fails.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun isMainApplicationProcess(): Boolean {
        return try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val currentPid = Process.myPid()
            val currentProcessName = activityManager.runningAppProcesses
                ?.firstOrNull { it.pid == currentPid }
                ?.processName
            // Fail closed if Android cannot identify the process; this avoids running app-only
            // initialization in the isolated diagnostic process.
            currentProcessName == packageName
        } catch (_: RuntimeException) {
            false
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
        // Write and sync the timestamped copy first so a crash during latest-report replacement
        // still leaves a recoverable report in the shared app-private files directory.
        FileOutputStream(File(reportDirectory, "crash-$timestamp.txt"), false).use { output ->
            output.write(reportBytes)
            output.fd.sync()
        }
        FileOutputStream(File(reportDirectory, LATEST_REPORT), false).use { output ->
            output.write(reportBytes)
            output.fd.sync()
        }
    }

    companion object {
        const val REPORT_DIRECTORY = "crash-reports"
        const val LATEST_REPORT = "latest-crash.txt"
    }
}
