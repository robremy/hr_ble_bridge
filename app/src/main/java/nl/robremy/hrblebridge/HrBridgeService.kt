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
import android.database.sqlite.SQLiteDatabase
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.sin

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

        // Kanaal-ID v3: het vorige kanaal (v2) had geluid+trillen op
        // kanaalniveau aanstaan; dat is na aanmaken onveranderlijk. Nu het
        // kanaal zelf stil moet zijn (geluid/trillen worden volledig
        // programmatisch geregeld), is een nieuwe ID nodig om dat op
        // toestellen die v2 al hadden gehad alsnog door te voeren.
        const val ALARM_CHANNEL_ID = "hr_bridge_alarm_channel_v3"
        const val ALARM_NOTIF_ID = 2
        private const val INSTELLINGEN_HERLAAD_MS = 15_000L
    }

    private var gatt: BluetoothGatt? = null
    private var macAddress: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stoppedByUser = false
    private var laatsteBpm: Int? = null
    private var verbonden = false
    private var pogingId = 0

    // Alarminstellingen — standaardwaarden gelijk aan de PWA; worden
    // overschreven zodra de instellingen-tabel in de gedeelde SQLite-db
    // gelezen kan worden (die de PWA vult via hr_sync_server.py).
    private var alarmLimit = 76
    private var alarmSecondsHigh = 10
    private var alarmCooldownSec = 30
    private var alarmMode = "vibrate"
    private var aboveSinceMs: Long? = null
    private var laatsteAlarmMs = 0L
    // Laatst bekende "stop alarm"-tijdstip (epoch ms, wall-clock — vergeleken
    // met het epoch-tijdstip waarop het huidige alarm begon) uit de
    // instellingen-tabel. Alleen relevant zolang een alarm actief aan het
    // afspelen is; zie leesStopAlarmSignaal() en wachtOfGestopt().
    private var stopAlarmSignaalMs = 0L
    private var laatsteInstellingenCheckMs = 0L

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
        checkAlarm(bpm)
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
            val nm = getSystemService(NotificationManager::class.java)

            val statusChannel = NotificationChannel(
                CHANNEL_ID,
                "HR Bridge status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Toont de status van de BLE-verbinding met de hartslagband"
            }
            nm.createNotificationChannel(statusChannel)

            // Apart, hoog-prioriteit kanaal voor de hartslagalarmen zelf,
            // zichtbaar als heads-up, los van de stille doorlopende
            // statusmelding hierboven. Het kanaal zelf blijft bewust stil
            // (geen geluid/trillen) — dat wordt volledig door
            // triggerAlarm() zelf geregeld via speelAlarmGeluid()/
            // trilAlarm(), zodat er niet zowel het kanaal-standaardgeluid
            // als onze eigen toon/trilpatroon tegelijk afgaan.
            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "HR Bridge alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Waarschuwing als de hartslag boven de ingestelde grens blijft"
                enableVibration(false)
                setSound(null, null)
                setBypassDnd(false)
            }
            nm.createNotificationChannel(alarmChannel)
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

    // -------------------------------------------------------------------
    // Alarm (drempelwaarde ingesteld vanuit de PWA, alarm zelf getriggerd
    // door deze altijd-actieve service — werkt dus ook als er geen
    // browser-tab open staat)
    // -------------------------------------------------------------------

    private fun herlaadInstellingenIndienNodig() {
        val nu = SystemClock.elapsedRealtime()
        if (nu - laatsteInstellingenCheckMs < INSTELLINGEN_HERLAAD_MS) return
        laatsteInstellingenCheckMs = nu

        try {
            val dbBestand = File(hbmonitorMap(), "hbmonitor.db")
            if (!dbBestand.exists()) return
            // Alleen-lezen openen: de tailer en hr_sync_server.py schrijven
            // er ook naartoe (WAL-modus), dus dit mag gelijktijdig.
            val db = SQLiteDatabase.openDatabase(
                dbBestand.path, null, SQLiteDatabase.OPEN_READONLY
            )
            db.use {
                val cursor = it.rawQuery("SELECT key, value FROM instellingen", null)
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val key = c.getString(0)
                        val waarde = c.getString(1)
                        when (key) {
                            "limit" -> waarde.toIntOrNull()?.let { v -> alarmLimit = v }
                            "secondsHigh" -> waarde.toIntOrNull()?.let { v -> alarmSecondsHigh = v }
                            "cooldownSec" -> waarde.toIntOrNull()?.let { v -> alarmCooldownSec = v }
                            "mode" -> if (waarde == "audio" || waarde == "vibrate") alarmMode = waarde
                            "stopAlarmMs" -> waarde.toLongOrNull()?.let { v -> stopAlarmSignaalMs = v }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Tabel bestaat mogelijk nog niet (PWA heeft nog nooit
            // instellingen gestuurd) — dan gewoon de standaardwaarden
            // aanhouden, dit is geen fout om de service voor te laten
            // crashen.
            Log.w(TAG, "Alarminstellingen lezen mislukt, standaardwaarden blijven gelden", e)
        }
    }

    // Snelle, ongethrottelde lezing van alleen de stopAlarmMs-waarde. Wordt
    // uitsluitend aangeroepen terwijl een alarm actief aan het afspelen is
    // (zie wachtOfGestopt()) — vandaar geen 15s-drempel zoals bij de
    // reguliere instellingen, anders komt "Stop alarm" te laat aan om nog
    // iets uit te maken.
    private fun leesStopAlarmSignaal(): Long {
        return try {
            val dbBestand = File(hbmonitorMap(), "hbmonitor.db")
            if (!dbBestand.exists()) return stopAlarmSignaalMs
            val db = SQLiteDatabase.openDatabase(
                dbBestand.path, null, SQLiteDatabase.OPEN_READONLY
            )
            db.use {
                val cursor = it.rawQuery(
                    "SELECT value FROM instellingen WHERE key = ?", arrayOf("stopAlarmMs")
                )
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.toLongOrNull()?.let { v -> stopAlarmSignaalMs = v }
                    }
                }
            }
            stopAlarmSignaalMs
        } catch (e: Exception) {
            stopAlarmSignaalMs
        }
    }

    // Speelt een alarm-actie (trillen of geluid) af in korte stapjes, en
    // controleert tussendoor of er een nieuwer stop-signaal is binnengekomen
    // dan het moment waarop dit alarm begon. Zo ja: stopActie() aanroepen en
    // meteen stoppen, in plaats van de volledige duur uit te zitten.
    private fun wachtOfGestopt(totaalMs: Long, alarmStartMs: Long, stopActie: () -> Unit) {
        val stapMs = 250L
        var verstrekenMs = 0L
        while (verstrekenMs < totaalMs) {
            if (leesStopAlarmSignaal() > alarmStartMs) {
                stopActie()
                schrijfEvent("Alarm gestopt door gebruiker (Stop alarm)")
                return
            }
            val slaap = minOf(stapMs, totaalMs - verstrekenMs)
            Thread.sleep(slaap)
            verstrekenMs += slaap
        }
    }

    private fun checkAlarm(bpm: Int) {
        herlaadInstellingenIndienNodig()
        val nu = SystemClock.elapsedRealtime()

        if (bpm > alarmLimit) {
            if (aboveSinceMs == null) aboveSinceMs = nu
            val hoogVoorMs = nu - (aboveSinceMs ?: nu)

            if (
                hoogVoorMs >= alarmSecondsHigh * 1000L &&
                nu - laatsteAlarmMs >= alarmCooldownSec * 1000L
            ) {
                laatsteAlarmMs = nu
                triggerAlarm(bpm)
            }
        } else {
            aboveSinceMs = null
        }
    }

    private fun triggerAlarm(bpm: Int) {
        schrijfEvent("ALARM: hartslag $bpm bpm boven grens $alarmLimit ($alarmMode)")
        val alarmStartMs = System.currentTimeMillis()
        // Zelfde als de PWA: audio-modus en trilmodus zijn elkaars
        // alternatief, niet allebei tegelijk (zie triggerAlarm() in
        // index.html).
        if (alarmMode == "audio") {
            speelAlarmGeluid(alarmStartMs)
        } else {
            trilAlarm(alarmStartMs)
        }
        toonAlarmNotificatie(bpm)
    }

    private fun speelAlarmGeluid(alarmStartMs: Long) {
        // Draait op een eigen thread: het genereren en synchroon afspelen
        // van ~3,75 s PCM-audio, plus het tussendoor pollen op een
        // stop-signaal, hoort niet op de Handler/main-looper thuis.
        Thread {
            try {
                speelSynthetischAlarmToon(alarmStartMs)
            } catch (e: Exception) {
                Log.e(TAG, "Alarmgeluid afspelen mislukt", e)
                // Als geluid afspelen om wat voor reden dan ook mislukt, toch
                // trillen als terugval zodat de gebruiker hoe dan ook
                // gewaarschuwd wordt.
                handler.post { trilAlarm(alarmStartMs) }
            }
        }.start()
    }

    // Zelfde alarmtoon als de PWA (beepOnce() in index.html): een 950 Hz
    // toon, 500 ms aan / 250 ms stil, 5 keer herhaald met 0,35 gain, zodat
    // de bridge-app en de browser-PWA identiek klinken in plaats van het
    // ene een systeem-ringtone en het andere een gesynthetiseerde piep.
    private fun speelSynthetischAlarmToon(alarmStartMs: Long) {
        val sampleRate = 44100
        val frequentieHz = 950.0
        val toonDuurMs = 500
        val stilteDuurMs = 250
        val herhalingen = 5
        val amplitude = 0.35 * Short.MAX_VALUE
        val fadeDuurSamples = sampleRate / 100 // 10 ms in/uit om tik-geluid te voorkomen

        val toonSamples = sampleRate * toonDuurMs / 1000
        val stilteSamples = sampleRate * stilteDuurMs / 1000
        val buffer = ShortArray(herhalingen * (toonSamples + stilteSamples))

        var offset = 0
        repeat(herhalingen) {
            for (i in 0 until toonSamples) {
                val fade = when {
                    i < fadeDuurSamples -> i.toDouble() / fadeDuurSamples
                    i > toonSamples - fadeDuurSamples -> (toonSamples - i).toDouble() / fadeDuurSamples
                    else -> 1.0
                }
                val waarde = amplitude * fade * sin(2.0 * Math.PI * frequentieHz * i / sampleRate)
                buffer[offset + i] = waarde.toInt().toShort()
            }
            offset += toonSamples + stilteSamples // stiltesamples blijven 0 (ShortArray-default)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val bufferBytes = buffer.size * 2
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack(
            audioAttributes,
            audioFormat,
            maxOf(minBufferSize, bufferBytes),
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        try {
            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.play()
            // In plaats van de volledige duur blind uit te zitten: elke
            // 250ms pollen op een stop-signaal, zodat "Stop alarm" in de
            // PWA de toon binnen ~250ms daadwerkelijk afbreekt.
            wachtOfGestopt(
                (herhalingen * (toonDuurMs + stilteDuurMs)).toLong(),
                alarmStartMs
            ) {
                try {
                    audioTrack.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack vroegtijdig stoppen mislukt", e)
                }
                annuleerAlarmNotificatie()
            }
        } finally {
            try {
                audioTrack.stop()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack stoppen mislukt", e)
            }
            audioTrack.release()
        }
    }

    private fun trilAlarm(alarmStartMs: Long) {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            // Zelfde patroon als het trilalarm in de PWA.
            val patroon = longArrayOf(0, 900, 250, 900, 250, 1400, 300, 900, 250, 1800)
            val totaalMs = patroon.sum()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(patroon, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(patroon, -1)
            }
            // vibrate() keert meteen terug (het patroon loopt systeem-kant
            // door); op een eigen thread pollen op een stop-signaal zodat
            // "Stop alarm" in de PWA het trillen binnen ~250ms afbreekt via
            // vibrator.cancel(), in plaats van de volle ~7s uit te zitten.
            Thread {
                wachtOfGestopt(totaalMs, alarmStartMs) {
                    try {
                        vibrator.cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "Trillen vroegtijdig stoppen mislukt", e)
                    }
                    annuleerAlarmNotificatie()
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Trillen mislukt", e)
        }
    }

    private fun annuleerAlarmNotificatie() {
        try {
            getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIF_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Alarmnotificatie annuleren mislukt", e)
        }
    }

    private fun toonAlarmNotificatie(bpm: Int) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notificatie = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
                .setContentTitle("⚠ Hartslag boven grens")
                .setContentText("$bpm bpm (grens $alarmLimit)")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(ALARM_NOTIF_ID, notificatie)
        } catch (e: Exception) {
            Log.e(TAG, "Alarmnotificatie tonen mislukt", e)
        }
    }
}