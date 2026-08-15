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
        private const val RECONNECT_MAX_DELAY_MS = 120_000L // cap op 2 min
        private const val CONNECT_TIMEOUT_MS = 12_000L

        const val ALARM_CHANNEL_ID = "hr_bridge_alarm_channel"
        const val ALARM_NOTIF_ID = 2
        private const val INSTELLINGEN_HERLAAD_MS = 15_000L

        // Bekende GATT-statuscodes (Android BluetoothGatt), voor leesbare
        // logging bij een disconnect. Onvolledig — onbekende codes worden
        // gewoon als kaal nummer gelogd.
        private val GATT_STATUS_NAMEN = mapOf(
            0 to "GATT_SUCCESS (nette disconnect, evt. Android/MIUI-init)",
            8 to "GATT_CONN_TIMEOUT (radio-timeout)",
            19 to "GATT_CONN_TERMINATE_PEER_USER (band verbrak zelf de verbinding)",
            22 to "GATT_CONN_TERMINATE_LOCAL_HOST (telefoon verbrak zelf de verbinding)",
            34 to "GATT_CONN_LMP_TIMEOUT",
            62 to "GATT_CONN_FAIL_ESTABLISH",
            133 to "GATT_ERROR (generieke/onbekende fout, vaak stack-intern)",
            257 to "GATT_ERROR (Android-interne foutcode)"
        )

        private fun gattStatusNaam(status: Int): String =
            GATT_STATUS_NAMEN[status] ?: "onbekende status"
    }

    private var gatt: BluetoothGatt? = null
    private var macAddress: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stoppedByUser = false
    private var laatsteBpm: Int? = null
    private var verbonden = false
    private var pogingId = 0

    // Tijdstip (elapsedRealtime, overleeft geen reboot maar wel scherm-uit/
    // Doze) waarop de huidige/laatste geslaagde GATT-verbinding tot stand
    // kwam. Gebruikt om bij een disconnect te loggen hoe lang de verbinding
    // heeft standgehouden — nodig om patronen zoals "valt na 3-4 uur weg"
    // te onderscheiden van willekeurige drops.
    private var verbindingTotStandMs: Long? = null

    // Aantal opeenvolgende mislukte (her)verbindpogingen sinds de laatste
    // geslaagde STATE_CONNECTED. Gebruikt voor exponentiële backoff, zodat
    // een band die lang buiten bereik is (bv. 's nachts elders aan het
    // opladen) niet elke 5s de radio blijft belasten.
    private var opeenvolgendeMislukkingen = 0
    private var maxBackoffGelogd = false

    // Alarminstellingen — standaardwaarden gelijk aan de PWA; worden
    // overschreven zodra de instellingen-tabel in de gedeelde SQLite-db
    // gelezen kan worden (die de PWA vult via hr_sync_server.py).
    private var alarmLimit = 76
    private var alarmSecondsHigh = 10
    private var alarmCooldownSec = 30
    // "vibrate" of "audio" — zelfde waarden als alarmMode in de PWA (zie
    // toggleAlarmMode() in index.html). Werd voorheen niet gelezen, dus
    // deze service trilde altijd, ongeacht wat de PWA-knop toonde.
    private var alarmMode = "vibrate"
    private var aboveSinceMs: Long? = null
    private var laatsteAlarmMs = 0L
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

        // Exponentiële backoff: 5s, 10s, 20s, 40s, 80s, daarna gecapt op
        // RECONNECT_MAX_DELAY_MS. Voorkomt dat de radio elke 5s belast blijft
        // worden terwijl de band voor langere tijd buiten bereik is (bv.
        // 's nachts elders aan het opladen). Teller wordt gereset zodra een
        // verbinding weer lukt (STATE_CONNECTED).
        val factor = 1L shl opeenvolgendeMislukkingen.coerceAtMost(10) // voorkom overflow
        val delay = (RECONNECT_DELAY_MS * factor).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        opeenvolgendeMislukkingen++

        // Alleen loggen op het moment dat de cap voor het eerst bereikt
        // wordt, niet bij elke losse poging erna — anders vervuilt dit de
        // events-log net zo hard als de situatie die het moest voorkomen.
        if (delay >= RECONNECT_MAX_DELAY_MS && !maxBackoffGelogd) {
            maxBackoffGelogd = true
            schrijfEvent("Herverbinden bereikt max. interval (${delay / 1000}s), band mogelijk lang buiten bereik")
        }

        Log.i(TAG, "Volgende herverbindpoging over ${delay}ms (mislukking #$opeenvolgendeMislukkingen)")
        handler.postDelayed({
            macAddress?.let { verbind(it) }
        }, delay)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT verbonden (status=$status), services ontdekken...")
                    verbonden = true
                    verbindingTotStandMs = SystemClock.elapsedRealtime()
                    opeenvolgendeMislukkingen = 0
                    maxBackoffGelogd = false
                    schrijfEvent("BLE verbonden met ${g.device.name ?: g.device.address}")
                    updateNotificatie("Verbonden. Services ontdekken...")
                    g.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val duurMs = verbindingTotStandMs?.let { SystemClock.elapsedRealtime() - it }
                    val duurTekst = duurMs?.let { formatteerDuur(it) } ?: "onbekend (nooit STATE_CONNECTED bereikt)"
                    val statusNaam = gattStatusNaam(status)
                    Log.w(TAG, "GATT verbroken: status=$status ($statusNaam), verbonden geweest voor $duurTekst")
                    verbonden = false
                    verbindingTotStandMs = null
                    schrijfVerbroken(status, statusNaam, duurMs)
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

    /**
     * Structured disconnect-event met losse velden voor status/duur, zodat
     * dit later machinaal te analyseren is (bv. "valt de verbinding steeds
     * na ~3-4 uur weg, en met welke statuscode?") zonder tekst te moeten
     * parsen. Blijft ook via schrijfEvent() zichtbaar in de bestaande
     * events-tijdlijn, met dezelfde informatie in leesbare vorm.
     */
    private fun schrijfVerbroken(status: Int, statusNaam: String, duurMs: Long?) {
        val ts = nu()
        val statusNaamVeilig = statusNaam.replace("\"", "'")
        val json = """{"ts":"$ts","type":"disconnect","status":$status,"statusNaam":"$statusNaamVeilig","verbondenDuurMs":${duurMs ?: "null"}}"""
        schrijfRegel("hr_events.jsonl", json)

        val duurTekst = duurMs?.let { formatteerDuur(it) } ?: "onbekend"
        schrijfEvent("BLE verbinding verbroken na $duurTekst (status=$status: $statusNaam)")
    }

    private fun formatteerDuur(ms: Long): String {
        val totaalSec = ms / 1000
        val u = totaalSec / 3600
        val m = (totaalSec % 3600) / 60
        val s = totaalSec % 60
        return if (u > 0) "${u}u ${m}m ${s}s" else if (m > 0) "${m}m ${s}s" else "${s}s"
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

            // Apart, hoog-prioriteit kanaal voor de hartslagalarmen zelf:
            // geluid + trillen, en zichtbaar als heads-up, los van de
            // stille doorlopende statusmelding hierboven.
            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "HR Bridge alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Waarschuwing als de hartslag boven de ingestelde grens blijft"
                enableVibration(true)
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
        // Wall-clock tijdstip (Date.now()-compatibel), niet elapsedRealtime:
        // stopAlarmMs komt van de PWA als Date.now() en moet hiermee
        // vergeleken worden, niet met de elapsedRealtime-klok die verder in
        // deze service voor interne timing gebruikt wordt.
        val alarmTriggeredWallMs = System.currentTimeMillis()
        schrijfEvent("ALARM: hartslag $bpm bpm boven grens $alarmLimit (modus: $alarmMode)")
        if (alarmMode == "audio") {
            geluidAlarm(alarmTriggeredWallMs)
        } else {
            trilAlarm(alarmTriggeredWallMs)
        }
        toonAlarmNotificatie(bpm)
    }

    // Snelle, ongethrottelde losse lezing van het stopsignaal (i.t.t.
    // herlaadInstellingenIndienNodig(), die door INSTELLINGEN_HERLAAD_MS
    // gethrottled wordt) — nodig omdat "Stop alarm" in de PWA binnen
    // ~250ms effect moet hebben op een lopend alarm.
    private fun leesStopAlarmSignaal(): Long {
        return try {
            val dbBestand = File(hbmonitorMap(), "hbmonitor.db")
            if (!dbBestand.exists()) return 0L
            val db = SQLiteDatabase.openDatabase(
                dbBestand.path, null, SQLiteDatabase.OPEN_READONLY
            )
            db.use {
                val cursor = it.rawQuery(
                    "SELECT value FROM instellingen WHERE key = ?",
                    arrayOf("stopAlarmMs")
                )
                cursor.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.toLongOrNull() ?: 0L else 0L
                }
            }
        } catch (e: Exception) {
            0L
        }
    }

    // Wacht in stapjes van stapMs tot totaalMs is verstreken, of tot er een
    // vers stopsignaal binnenkomt (stopAlarmMs nieuwer dan het moment
    // waarop dit specifieke alarm afging — zo negeren we een oud
    // stopsignaal van een vorig alarm). Retourneert true bij vroegtijdig
    // stoppen, zodat de aanroeper de afspeel-/trilcyclus kan afbreken.
    private fun wachtOfGestopt(
        alarmTriggeredWallMs: Long,
        totaalMs: Long,
        stapMs: Long = 250L
    ): Boolean {
        var verstrekenMs = 0L
        while (verstrekenMs < totaalMs) {
            val stap = minOf(stapMs, totaalMs - verstrekenMs)
            Thread.sleep(stap)
            verstrekenMs += stap
            if (leesStopAlarmSignaal() > alarmTriggeredWallMs) return true
        }
        return false
    }

    // Zelfde 950 Hz-toon en herhaalpatroon (5x 500ms, 250ms stilte) als
    // triggerAudioAlarm()/beepOnce() in de PWA, maar dan met AudioTrack
    // i.p.v. Web Audio, want deze service draait ook zonder open browser-
    // tab. Genereert de sinusgolf zelf als 16-bit PCM — geen los geluid-
    // bestand nodig.
    private fun geluidAlarm(alarmTriggeredWallMs: Long) {
        Thread {
            try {
                val sampleRate = 44100
                val frequentieHz = 950.0
                val duurMs = 500L
                val stilteMs = 250L
                val aantalHerhalingen = 5

                val aantalSamples = (sampleRate * duurMs / 1000).toInt()
                val buffer = ShortArray(aantalSamples)
                for (i in buffer.indices) {
                    val t = i.toDouble() / sampleRate
                    // Kleine fade-in/fade-out (eerste/laatste ~5ms) om
                    // een hoorbare "klik" bij start/stop van elke beep
                    // te voorkomen.
                    val fadeSamples = sampleRate / 200
                    val fade = when {
                        i < fadeSamples -> i.toDouble() / fadeSamples
                        i > aantalSamples - fadeSamples -> (aantalSamples - i).toDouble() / fadeSamples
                        else -> 1.0
                    }
                    val waarde = Math.sin(2.0 * Math.PI * frequentieHz * t) * fade
                    buffer[i] = (waarde * Short.MAX_VALUE * 0.7).toInt().toShort()
                }

                val minBufSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val audioTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    maxOf(minBufSize, buffer.size * 2),
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                try {
                    audioTrack.write(buffer, 0, buffer.size)
                    for (herhaling in 0 until aantalHerhalingen) {
                        audioTrack.play()
                        val gestopt = wachtOfGestopt(alarmTriggeredWallMs, duurMs)
                        audioTrack.stop()
                        if (gestopt) break
                        audioTrack.reloadStaticData()
                        if (herhaling < aantalHerhalingen - 1) {
                            if (wachtOfGestopt(alarmTriggeredWallMs, stilteMs)) break
                        }
                    }
                } finally {
                    audioTrack.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio alarm mislukt", e)
            }
        }.start()
    }

    private fun trilAlarm(alarmTriggeredWallMs: Long) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(patroon, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(patroon, -1)
            }

            // Los wachtthreadje dat het stopsignaal in de gaten houdt zolang
            // het patroon nog loopt, en de trilling desnoods vroegtijdig
            // afbreekt — vibrator.vibrate() zelf is fire-and-forget en kan
            // niet "van buitenaf" onderbroken worden zonder cancel().
            Thread {
                if (wachtOfGestopt(alarmTriggeredWallMs, patroon.sum())) {
                    try {
                        vibrator.cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "Trillen annuleren mislukt", e)
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Trillen mislukt", e)
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