package nl.robremy.hrblebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Herstart de bridge-service automatisch na een reboot, als er eerder al
 * een apparaat gekozen was. Zonder dit moet je de app na elke herstart
 * handmatig openen en op "Start" tikken.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("hr_bridge_prefs", Context.MODE_PRIVATE)
        val mac = prefs.getString("mac_address", null) ?: return

        val serviceIntent = Intent(context, HrBridgeService::class.java)
        serviceIntent.putExtra(HrBridgeService.EXTRA_MAC, mac)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
