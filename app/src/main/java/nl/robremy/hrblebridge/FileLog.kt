package nl.robremy.hrblebridge

import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schrijft app-logregels weg naar /HBmonitor/bridge_debug.log in gedeelde
 * opslag, zodat je ze met `cat` in Termux kunt lezen zonder logcat/root
 * nodig te hebben:
 *
 *   cat /storage/emulated/0/HBmonitor/bridge_debug.log
 *
 * of vanuit Termux via de shared-storage-koppeling:
 *
 *   cat ~/storage/shared/HBmonitor/bridge_debug.log
 */
object FileLog {

    private val formaat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    private fun logBestand(): File? {
        return try {
            val basis = Environment.getExternalStorageDirectory()
            val map = File(basis, "HBmonitor")
            if (!map.exists()) map.mkdirs()
            File(map, "bridge_debug.log")
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun log(tag: String, bericht: String) {
        val regel = "${formaat.format(Date())} [$tag] $bericht\n"
        try {
            logBestand()?.let { bestand ->
                FileOutputStream(bestand, true).use { it.write(regel.toByteArray(Charsets.UTF_8)) }
            }
        } catch (e: Exception) {
            // Kan niets doen als wegschrijven zelf faalt (bv. nog geen
            // bestandstoegang verleend); dan is er sowieso nog geen log.
        }
    }

    @Synchronized
    fun logFout(tag: String, bericht: String, throwable: Throwable) {
        val stacktrace = throwable.stackTraceToString()
        log(tag, "FOUT: $bericht\n$stacktrace")
    }
}
