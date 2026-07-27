package nl.robremy.hrblebridge

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import nl.robremy.hrblebridge.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var gekozenMac: String? = null

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

        binding.knopStart.setOnClickListener {
            val mac = gekozenMac
            if (mac == null) {
                binding.statusText.text = "Kies eerst een apparaat uit de lijst"
            } else {
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
            val mac = label.substringAfterLast("(").removeSuffix(")")
            gekozenMac = mac
            prefs.edit().putString("mac_address", mac).apply()
            binding.statusText.text = "Gekozen: $label"
        }

        toonGekoppeldeApparaten()
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
        val gebonden = adapter?.bondedDevices?.map { "${it.name} (${it.address})" } ?: emptyList()

        binding.apparatenLijst.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, gebonden
        )

        if (gebonden.isEmpty()) {
            binding.statusText.text =
                "Geen gekoppelde apparaten. Koppel de band eerst via Android-Bluetooth-instellingen."
        } else {
            binding.statusText.text = "Kies de hartslagband uit de lijst"
        }
    }
}
