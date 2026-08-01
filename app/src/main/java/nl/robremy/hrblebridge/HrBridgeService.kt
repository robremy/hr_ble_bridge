package nl.robremy.hrblebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Houdt een BLE GATT-verbinding met de hartslagband vast als foreground
 * service (overleeft achtergrond-throttling, in tegenstelling tot een
 * Chrome-tab op de achtergrond). Metingen en verbindingsevents worden
 * append-only weggeschreven naar gedeelde opslag (/HBmonitor/...), zodat
 * een los Termux-script (de "tailer") ze kan uitlezen en in SQLite kan
 * zetten.
 */
class HrBridgeService : Service() {

    companion object {
        private const val TAG = "HrBridgeService"
        const val CHANNEL_ID = "hr_bridge_channel"
        const val NOTIF_ID = 1
        const val EXTRA_MAC = "mac_address"

        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val RECONNECT_DELAY_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }

    private var gatt: BluetoothGatt? = null
    private var macAddress: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stoppedByUser = false
    private var laatsteBpm: Int? = null
    private var verbonden = false
    private var pogingId = 0

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    override fun onCreate() {
        super.onCreate()
        maakNotificatieKanaal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stoppedByUser = false
        macAddress = intent?.getStringExtra(EXTRA_MAC) ?: macAddress

        val notificatie = bouwNotificatie("Verbinden met band...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notificatie, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notificatie)
        }

        macAddress?.let { verbind(it) } ?: run {
            Log.e(TAG, "Geen MAC-adres opgegeven, service stopt")
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stoppedByUser = true
        pogingId++ // maakt elke lopende watchdog inactief
        handler.removeCallbacksAndMessages(null)
        gatt?.close()
        gatt = null
        schrijfEvent("Bridge-service gestopt")
        super.onDestroy()
    }

    // -------------------------------------------------------------------
    // GATT-verbinding
    // -------------------------------------------------------------------

    private fun verbind(mac: String) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth-adapter niet beschikbaar/uit, probeer over ${RECONNECT_DELAY_MS}ms opnieuw")
            plannerHerverbind()
            return
        }

        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Ongeldig MAC-adres: $mac", e)
            stopSelf()
            return
        }

        updateNotificatie("Verbinden met ${device.name ?: mac}...")
        verbonden = false
        pogingId++
        val huidigePoging = pogingId
        gatt = try {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_CONNECT ontbreekt/ingetrokken, probeer over ${RECONNECT_DELAY_MS}ms opnieuw", e)
            schrijfEvent("Bluetooth-permissie ontbreekt, opnieuw proberen")
            plannerHerverbind()
            return
        }

        // Watchdog: connectGatt(autoConnect=false) kan bij een apparaat buiten
        // bereik soms helemaal geen callback geven (bekend Android-gedrag),
        // waardoor de reconnect-keten anders permanent vastloopt. Forceer na
        // CONNECT_TIMEOUT_MS een sluiting + nieuwe poging als er nog geen
        // succesvolle verbinding is.
        val watchdog = Runnable {
            if (!verbonden && huidigePoging == pogingId) {
                Log.w(TAG, "Verbindingspoging $huidigePoging timeout na ${CONNECT_TIMEOUT_MS}ms, forceer retry")
                schrijfEvent("Verbindingspoging timeout, opnieuw proberen")
                gatt?.close()
                gatt = null
                plannerHerverbind()
            }
        }
        handler.postDelayed(watchdog, CONNECT_TIMEOUT_MS)
    }

    private fun plannerHerverbind() {
        if (stoppedByUser) return
        handler.postDelayed({
            macAddress?.let { verbind(it) }
        }, RECONNECT_DELAY_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT verbonden, services ontdekken...")
                    verbonden = true
                    schrijfEvent("BLE verbonden met ${g.device.name ?: g.device.address}")
                    updateNotificatie("Verbonden. Services ontdekken...")
                    g.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT verbroken (status=$status)")
                    verbonden = false
                    schrijfEvent("BLE verbinding verbroken (status=$status)")
                    updateNotificatie("Verbinding verbroken, opnieuw verbinden...")
                    g.close()
                    gatt = null
                    // Kan hier en/of vanuit de watchdog vuren; een dubbele
                    // reconnect-poging is onschuldig (pogingId maakt de oudere
                    // watchdog vanzelf inactief), dus gewoon altijd retryen.
                    plannerHerverbind()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(HR_SERVICE_UUID)
            val char = service?.getCharacteristic(HR_MEASUREMENT_UUID)
            if (char == null) {
                Log.e(TAG, "Heart Rate-service/characteristic niet gevonden")
                schrijfEvent("Heart Rate-characteristic niet gevonden op dit apparaat")
                updateNotificatie("Fout: HR-characteristic niet gevonden")
                return
            }

            g.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }

            updateNotificatie("Verbonden \u00b7 wacht op hartslag...")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val waarde = characteristic.value ?: return
            verwerkHartslag(waarde)
        }
    }

    // -------------------------------------------------------------------
    // Parsing (identiek aan de bestaande parseHeartRate()-logica in de PWA)
    // -------------------------------------------------------------------

    private fun verwerkHartslag(waarde: ByteArray) {
        if (waarde.isEmpty()) return
        val flags = waarde[0].toInt() and 0xFF
        var index = 1
        val is16Bit = (flags and 0x01) != 0

        val bpm: Int = if (is16Bit) {
            if (waarde.size < index + 2) return
            (waarde[index].toInt() and 0xFF) or ((waarde[index + 1].toInt() and 0xFF) shl 8)
        } else {
            if (waarde.size < index + 1) return
            waarde[index].toInt() and 0xFF
        }

        val contactOndersteund = (flags and 0x04) != 0
        val contactGedetecteerd = (flags and 0x02) != 0
        // 1 = contact gedetecteerd of onbekend/niet-ondersteund, 0 = expliciet geen contact
        val contact = if (contactOndersteund && !contactGedetecteerd) 0 else 1

        laatsteBpm = bpm
        val geschreven = schrijfMeting(bpm, contact)
        if (geschreven) {
            updateNotificatie("Hartslag: $bpm bpm")
        }
    }

    // -------------------------------------------------------------------
    // Wegschrijven naar gedeelde opslag (JSON Lines, append-only)
    // -------------------------------------------------------------------

    private fun hbmonitorMap(): File {
        val basis = Environment.getExternalStorageDirectory()
        val map = File(basis, "HBmonitor")
        if (!map.exists()) map.mkdirs()
        return map
    }

    private fun schrijfRegel(bestandsnaam: String, json: String): Boolean {
        return try {
            val bestand = File(hbmonitorMap(), bestandsnaam)
            FileOutputStream(bestand, true).use { out ->
                out.write((json + "\n").toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Schrijven naar $bestandsnaam mislukt", e)
            // Zonder root/logcat-toegang is dit anders onzichtbaar; toon de
            // echte oorzaak direct in de notificatie zodat je 'm kunt lezen.
            // De aanroeper laat de "Hartslag: X bpm"-tekst hierna bewust
            // achterwege zodat deze foutmelding niet meteen overschreven wordt.
            updateNotificatie("FOUT bij schrijven $bestandsnaam: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun nu(): String = isoFormat.format(Date())

    private fun schrijfMeting(bpm: Int, contact: Int): Boolean {
        val ts = nu()
        val json = """{"ts":"$ts","bpm":$bpm,"contact":$contact}"""
        return schrijfRegel("hr_stream.jsonl", json)
    }

    private fun schrijfEvent(bericht: String) {
        val ts = nu()
        val veilig = bericht.replace("\"", "'")
        val json = """{"ts":"$ts","bericht":"$veilig"}"""
        schrijfRegel("hr_events.jsonl", json)
    }

    // -------------------------------------------------------------------
    // Notificatie (verplicht voor een foreground service)
    // -------------------------------------------------------------------

    private fun maakNotificatieKanaal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HR Bridge status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Toont de status van de BLE-verbinding met de hartslagband"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun bouwNotificatie(tekst: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HR Bridge actief")
            .setContentText(tekst)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotificatie(tekst: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, bouwNotificatie(tekst))
    }
}