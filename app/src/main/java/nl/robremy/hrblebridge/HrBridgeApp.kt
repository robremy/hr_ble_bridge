package nl.robremy.hrblebridge

import android.app.Application

/**
 * Vangt élke onverwachte crash op (in de UI-thread én in de service) en
 * schrijft de volledige stacktrace naar bridge_debug.log, vóórdat Android
 * de app alsnog afsluit. Zo hoef je nooit meer te gokken naar de oorzaak
 * van een "app is gestopt".
 */
class HrBridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val standaardHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FileLog.logFout("CRASH", "Onverwachte crash in thread '${thread.name}'", throwable)
            // Geef door aan Android's eigen afhandeling, zodat het normale
            // "app is gestopt"-gedrag intact blijft.
            standaardHandler?.uncaughtException(thread, throwable)
        }

        FileLog.log("App", "Gestart (versie ${BuildConfig.VERSION_NAME})")
    }
}
