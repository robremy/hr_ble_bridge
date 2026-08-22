package nl.robremy.hrblebridge

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Genereert (of hergebruikt) een self-signed TLS-certificaat voor
 * HrHttpServer, zodat de PWA over https:// bereikbaar is i.p.v. http://.
 * Reden: Chrome behandelt alleen 127.0.0.1/localhost als "secure context"
 * bij plain http; een LAN-IP (192.168.1.66) niet — waardoor
 * navigator.storage.persist(), de screen wake lock en de service worker
 * daar allemaal stil "niet ondersteund" zijn (bevestigd op een tweede
 * telefoon én relevant voor een Android TV, waar chrome://flags-workarounds
 * met een afstandsbediening niet praktisch zijn). Een TLS-verbinding
 * geldt voor Chrome WEL als secure context zodra de gebruiker één keer de
 * "niet privé/onveilig"-waarschuwing wegklikt — ongeacht of de CA vertrouwd
 * is — en dat geldt dan voor élk toestel, niet alleen 127.0.0.1.
 *
 * Android heeft geen ingebouwde certificate builder (sun.security.x509
 * bestaat niet op ART), vandaar BouncyCastle — puur lokaal gebruikt om het
 * keypair + certificaat te bouwen, geen netwerkcomponent.
 */
object SelfSignedCertManager {
    private const val TAG = "SelfSignedCertManager"
    private const val KEYSTORE_BESTAND = "bridge_tls_keystore.p12"
    private const val ALIAS = "hrbridge"
    private const val GELDIGHEID_JAREN = 10L

    init {
        // Android heeft standaard al een beperkte, ingebouwde provider met
        // dezelfde naam "BC" (voor interne cryptografie) — die mist bv. de
        // SHA256WithRSA-signer die hieronder nodig is. Simpelweg
        // Security.addProvider(BouncyCastleProvider()) voegt de volledige
        // library toe, maar omdat de naam al bestaat, blijft Android's
        // beperktere ingebouwde versie actief (bevestigd via
        // OperatorCreationException: "The BC provider no longer provides
        // an implementation for..."). Daarom eerst expliciet verwijderen en
        // dan de volledige BC-library met voorrang (positie 1) invoegen.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    /**
     * Levert een KeyStore + het bijbehorende wachtwoord, klaar om in
     * KeyManagerFactory te stoppen. Genereert alleen een nieuw certificaat
     * als er nog geen bestaat, OF als het huidige LAN-IP niet meer
     * overeenkomt met de SAN-vermelding in het bestaande certificaat (bv.
     * na een DHCP-adreswissel) — anders zou de certificaatnaam niet meer
     * kloppen met het adres waarop de telefoon nu bereikbaar is.
     */
    fun laadOfGenereer(context: Context): Pair<KeyStore, CharArray> {
        val bestand = File(context.filesDir, KEYSTORE_BESTAND)
        val wachtwoord = wachtwoord(context)
        val huidigIp = lanIp()

        if (bestand.exists()) {
            try {
                val keyStore = KeyStore.getInstance("PKCS12")
                FileInputStream(bestand).use { keyStore.load(it, wachtwoord) }
                val cert = keyStore.getCertificate(ALIAS) as? X509Certificate
                if (cert != null && certBevatIp(cert, huidigIp)) {
                    return keyStore to wachtwoord
                }
                Log.i(TAG, "LAN-IP komt niet meer overeen met certificaat (nu: $huidigIp) — nieuw certificaat genereren")
            } catch (e: Exception) {
                Log.w(TAG, "Bestaande keystore kon niet geladen worden, nieuw certificaat genereren", e)
            }
        }

        val keyStore = genereerEnBewaar(bestand, wachtwoord, huidigIp)
        return keyStore to wachtwoord
    }

    private fun certBevatIp(cert: X509Certificate, ip: String?): Boolean {
        if (ip == null) return true // geen LAN-IP bekend (bv. geen wifi) — huidig cert maar gewoon gebruiken
        val san = cert.subjectAlternativeNames ?: return false
        return san.any { entry -> entry.size >= 2 && entry[1] == ip }
    }

    private fun genereerEnBewaar(bestand: File, wachtwoord: CharArray, lanIp: String?): KeyStore {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        val subject = X500Name("CN=HBmonitor Bridge")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val vanaf = Date()
        val tot = Date(vanaf.time + TimeUnit.DAYS.toMillis(365L * GELDIGHEID_JAREN))

        val namen = mutableListOf<GeneralName>(
            GeneralName(GeneralName.dNSName, "localhost"),
            GeneralName(GeneralName.iPAddress, "127.0.0.1")
        )
        if (lanIp != null) {
            namen.add(GeneralName(GeneralName.iPAddress, lanIp))
        }

        val certBuilder = JcaX509v3CertificateBuilder(
            subject, serial, vanaf, tot, subject, keyPair.public
        ).addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
            false,
            GeneralNames(namen.toTypedArray())
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, wachtwoord, arrayOf(cert))
        FileOutputStream(bestand).use { keyStore.store(it, wachtwoord) }

        Log.i(TAG, "Nieuw self-signed certificaat gegenereerd, geldig tot $tot, SAN's: localhost, 127.0.0.1" + (lanIp?.let { ", $it" } ?: ""))
        return keyStore
    }

    /**
     * Wachtwoord voor de keystore-file: alleen bedoeld om de PKCS12-container
     * consistent te kunnen laden, geen echte beveiligingsgrens (de server
     * draait toch al zonder authenticatie op de endpoints, zie
     * HrHttpServer). Willekeurig gegenereerd bij eerste gebruik en lokaal
     * opgeslagen in SharedPreferences, zodat het niet hardcoded in de APK
     * staat.
     */
    private fun wachtwoord(context: Context): CharArray {
        val prefs = context.getSharedPreferences("hr_bridge_prefs", Context.MODE_PRIVATE)
        var waarde = prefs.getString("tls_keystore_wachtwoord", null)
        if (waarde == null) {
            waarde = (1..32).map { ('a'..'z').random() }.joinToString("")
            prefs.edit().putString("tls_keystore_wachtwoord", waarde).apply()
        }
        return waarde.toCharArray()
    }

    /** Zelfde aanpak als HrHttpServer.lanIp(): eerste niet-loopback IPv4-adres. */
    private fun lanIp(): String? {
        return try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addressen = java.util.Collections.list(iface.inetAddresses)
                for (adres in addressen) {
                    val host = adres.hostAddress ?: continue
                    if (!adres.isLoopbackAddress && !host.contains(":")) {
                        return host
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
