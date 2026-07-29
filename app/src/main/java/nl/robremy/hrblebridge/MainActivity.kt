package nl.robremy.hrblebridge

import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import nl.robremy.hrblebridge.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var gekozenMac: String? = null

    // adres -> label, voor zowel de gekoppelde-lijst als de scanresultaten
    private val getoondeApparaten = LinkedHashMap<String, String>()
    private var scanBezig = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val SCAN_DUUR_MS = 12_000L

    private val permissieLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultaten ->
        if (resultaten.values.all { it }) {
            toonGekoppeldeApparaten()
        } else {
            binding.statusText.text = "Permissies geweigerd \u2014 kan geen apparaten tonen"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("hr_bridge_prefs", Context.MODE_PRIVATE)
        gekozenMac = prefs.getString("mac_address", null)

        binding.knopPermissies.setOnClickListener { vraagPermissiesAan() }
        binding.knopAlleBestanden.setOnClickListener { vraagAlleBestandenToegangAan() }
        binding.knopVernieuwen.setOnClickListener { toonGekoppeldeApparaten() }
        binding.knopScan.setOnClickListener { startScan() }
        binding.knopToonLog.setOnClickListener { toonLogbestand() }

        binding.knopStart.setOnClickListener {
            val mac = gekozenMac
            if (mac == null) {
                binding.statusText.text = "Kies eerst een apparaat uit de lijst"
            } else {
                FileLog.log("MainActivity", "Start bridge getikt voor $mac")
                val intent = Intent(this, HrBridgeService::class.java)
                intent.putExtra(HrBridgeService.EXTRA_MAC, mac)
                ContextCompat.startForegroundService(this, intent)
                binding.statusText.text = "Service gestart voor $mac"
            }
        }

        binding.knopStop.setOnClickListener {
            stopService(Intent(this, HrBridgeService::class.java))
            binding.statusText.text = "Service gestopt"
        }

        binding.apparatenLijst.setOnItemClickListener { parent, _, positie, _ ->
            val label = parent.getItemAtPosition(positie) as String
            val mac = label.substringAfter("(").substringBefore(")")
            gekozenMac = mac
            prefs.edit().putString("mac_address", mac).apply()
            binding.statusText.text = "Gekozen: $label"
        }

        toonGekoppeldeApparaten()
    }

    override fun onDestroy() {
        stopScanIntern()
        super.onDestroy()
    }

    private fun benodigdePermissies(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun heeftPermissies(): Boolean =
        benodigdePermissies().all {
            ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun vraagPermissiesAan() {
        permissieLauncher.launch(benodigdePermissies())
    }

    private fun vraagAlleBestandenToegangAan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } else {
            binding.statusText.text = "Bestandstoegang al verleend"
        }
    }

    @Suppress("MissingPermission")
    private fun toonGekoppeldeApparaten() {
        if (!heeftPermissies()) {
            binding.statusText.text = "Tik op 'Permissies aanvragen'"
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val gebonden = adapter?.bondedDevices ?: emptySet()

        getoondeApparaten.clear()
        gebonden.forEach { d ->
            getoondeApparaten[d.address] = "${d.name ?: "(naamloos)"} (${d.address}) \u00b7 gekoppeld"
        }
        verversLijst()

        binding.statusText.text = if (gebonden.isEmpty()) {
            "Geen gekoppelde apparaten. Probeer 'Scan naar band (BLE)' hieronder."
        } else {
            "${gebonden.size} gekoppeld apparaat/apparaten. Zie je de band niet? Probeer de BLE-scan."
        }
    }

    // ---------------------------------------------------------------------
    // Directe BLE-scan (vindt de band ook als hij niet als 'gekoppeld' geldt)
    // ---------------------------------------------------------------------

    @Suppress("MissingPermission")
    private fun startScan() {
        if (!heeftPermissies()) {
            binding.statusText.text = "Tik eerst op 'Permissies aanvragen'"
            return
        }
        if (scanBezig) return

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            binding.statusText.text = "Bluetooth staat uit of niet beschikbaar"
            return
        }

        getoondeApparaten.clear()
        verversLijst()
        scanBezig = true
        binding.statusText.text =
            "Scannen... (zorg dat locatievoorziening/GPS aanstaat en de band dichtbij is)"

        val instellingen = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, instellingen, scanCallback)
        scanHandler.postDelayed({ stopScanIntern() }, SCAN_DUUR_MS)
    }

    @Suppress("MissingPermission")
    private fun stopScanIntern() {
        if (!scanBezig) return
        scanBezig = false
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // adapter kan intussen uit staan; niets aan te doen
        }
        binding.statusText.text =
            if (getoondeApparaten.isEmpty())
                "Scan klaar, niets gevonden. Band dichterbij houden en opnieuw proberen."
            else
                "Scan klaar \u2014 kies de band uit de lijst"
    }

    @Suppress("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val naam = result.scanRecord?.deviceName ?: device.name ?: "(naamloos)"
            val label = "$naam (${device.address}) \u00b7 ${result.rssi} dBm"
            getoondeApparaten[device.address] = label
            verversLijst()
        }

        override fun onScanFailed(errorCode: Int) {
            scanBezig = false
            binding.statusText.text = "Scan mislukt (foutcode $errorCode)"
        }
    }

    private fun verversLijst() {
        binding.apparatenLijst.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, getoondeApparaten.values.toList()
        )
    }

    private fun toonLogbestand() {
        val bestand = File(Environment.getExternalStorageDirectory(), "HBmonitor/bridge_debug.log")
        val inhoud = if (bestand.exists()) {
            val regels = bestand.readLines()
            regels.takeLast(200).joinToString("\n")
        } else {
            "Nog geen logbestand gevonden op ${bestand.absolutePath}\n\n" +
                "(Verleen eerst bestandstoegang bij stap 2, en start/gebruik de app/bridge minstens één keer.)"
        }

        val tekstView = TextView(this).apply {
            text = inhoud
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(tekstView) }

        AlertDialog.Builder(this)
            .setTitle("Logbestand (laatste 200 regels)")
            .setView(scroll)
            .setPositiveButton("Sluiten", null)
            .show()
    }
}

