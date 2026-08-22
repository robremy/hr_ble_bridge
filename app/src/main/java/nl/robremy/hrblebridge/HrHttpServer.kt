package nl.robremy.hrblebridge

import android.content.Context
import android.database.Cursor
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.KeyManagerFactory

/**
 * Vervangt hr_sync_server.py: dezelfde endpoints, dezelfde JSON-vorm, zodat
 * de PWA (index.html/features.js, BRIDGE_URL is https://<ip>:8787) verder
 * ongewijzigd kan blijven. Draait embedded in HrBridgeService i.p.v. als
 * los Termux-proces — leest/schrijft rechtstreeks via HbmonitorDb, geen
 * los `python hr_sync_server.py` meer nodig.
 *
 * Draait over HTTPS (self-signed, zie SelfSignedCertManager) i.p.v. plain
 * HTTP: Chrome behandelt alleen 127.0.0.1/localhost als "secure context"
 * bij http, een LAN-IP niet — waardoor navigator.storage.persist(), de
 * screen wake lock en de service worker daar stil "niet ondersteund" waren.
 * Een TLS-verbinding telt voor Chrome WEL als secure context zodra de
 * gebruiker eenmalig de certificaatwaarschuwing wegklikt, op elk toestel
 * (ook een Android TV, waar chrome://flags-workarounds met een
 * afstandsbediening niet praktisch zijn).
 *
 * Bewust nog zonder authenticatie, net als de Python-versie: bereikbaar
 * voor elk apparaat op hetzelfde LAN (0.0.0.0), geen verificatie op de
 * endpoints. Zelfde afweging als voorheen — een los stuk werk voor later.
 */
class HrHttpServer(
    private val context: Context,
    port: Int = STANDAARD_POORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "HrHttpServer"
        const val STANDAARD_POORT = 8787

        private fun mimeVoorAsset(naam: String): String = when {
            naam.endsWith(".html") -> "text/html"
            naam.endsWith(".js") -> "application/javascript"
            naam.endsWith(".css") -> "text/css"
            naam.endsWith(".webmanifest") || naam.endsWith(".json") -> "application/manifest+json"
            naam.endsWith(".png") -> "image/png"
            naam.endsWith(".svg") -> "image/svg+xml"
            naam.endsWith(".jpg") || naam.endsWith(".jpeg") -> "image/jpeg"
            naam.endsWith(".ico") -> "image/x-icon"
            naam.endsWith(".woff2") -> "font/woff2"
            naam.endsWith(".woff") -> "font/woff"
            else -> "application/octet-stream"
        }
    }

    /**
     * Schakelt TLS in met een (automatisch gegenereerd/hergebruikt)
     * self-signed certificaat. MOET vóór start() aangeroepen worden —
     * NanoHTTPD kan HTTPS niet meer inschakelen nadat de server al luistert.
     */
    fun activeerHttps() {
        val (keyStore, wachtwoord) = SelfSignedCertManager.laadOfGenereer(context)
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, wachtwoord)
        val sslSocketFactory = makeSSLSocketFactory(keyStore, keyManagerFactory.keyManagers)
        makeSecure(sslSocketFactory, null)
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.method) {
                Method.OPTIONS -> metCors(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, ""))
                Method.GET -> handleGet(session)
                Method.POST -> handlePost(session)
                else -> jsonResponse(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("ok", false).put("error", "method not allowed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Onverwachte fout bij afhandelen request", e)
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", e.message ?: "onbekende fout"))
        }
    }

    // -------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------

    private fun handleGet(session: IHTTPSession): Response {
        val path = session.uri
        val datum = session.parameters["date"]?.firstOrNull()
        val sinds = session.parameters["since"]?.firstOrNull()

        return when (path) {
            "/api/health" -> jsonResponse(
                Response.Status.OK,
                JSONObject().put("ok", true).put("ip", lanIp())
            )
            "/api/metingen" -> metingenResponse(datum, sinds)
            "/api/events" -> lijstResponse("events", "ts, bericht", datum) { c ->
                JSONObject()
                    .put("ts", c.getString(0))
                    .put("bericht", c.getString(1))
            }
            "/api/annotaties" -> lijstResponse("annotaties", "ts, type, label, bpm", datum) { c ->
                JSONObject()
                    .put("ts", c.getString(0))
                    .put("type", c.getString(1))
                    .put("label", if (c.isNull(2)) JSONObject.NULL else c.getString(2))
                    .put("bpm", if (c.isNull(3)) JSONObject.NULL else c.getInt(3))
            }
            "/api/instellingen" -> {
                val db = HbmonitorDb.open(context)
                val resultaat = JSONObject()
                db.rawQuery("SELECT key, value FROM instellingen", null).use { c ->
                    while (c.moveToNext()) {
                        resultaat.put(c.getString(0), c.getString(1))
                    }
                }
                jsonResponse(Response.Status.OK, resultaat)
            }
            else -> {
                // Alleen de "withPwa"-flavor bundelt assets/www/ en zet
                // BUNDLE_PWA op true (zie app/build.gradle.kts); de gewone
                // "standard"-build valt hier altijd door naar de bestaande
                // 404-JSON-respons, exact het huidige gedrag.
                if (BuildConfig.BUNDLE_PWA) {
                    serveAsset(path)
                } else {
                    jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not found"))
                }
            }
        }
    }

    /**
     * Serveert willekeurige bestanden vanaf assets/www/ zodat de PWA op
     * hetzelfde private-netwerk-origin draait als de bridge zelf
     * (https://<bridge-ip>:8787), i.p.v. vanaf de publieke GitHub Pages-
     * origin. Dat omzeilt Chrome's Local Network Access-permissieprompt
     * volledig — die is alleen relevant bij een publiek->privaat verzoek,
     * en dat is hier niet langer het geval.
     *
     * Generiek i.p.v. een hardcoded lijst (voorheen STATIC_ASSETS): als
     * index.html later bv. style.css of een extra script aantrekt, hoeft
     * deze Kotlin-code niet aangepast te worden zolang het bestand in
     * assets/www/ terechtkomt. "/" wordt naar index.html gemapt; elk ander
     * pad wordt direct als relatief bestandspad onder www/ opgevat.
     *
     * Padvalidatie: alleen relatieve paden zonder ".."-segmenten worden
     * toegelaten, om path traversal buiten assets/www/ uit te sluiten
     * (AssetManager normaliseert "../" zelf niet weg).
     */
    private fun serveAsset(path: String): Response {
        val relatiefPad = if (path == "/") "index.html" else path.removePrefix("/")
        if (relatiefPad.isEmpty() || relatiefPad.split("/").any { it == ".." || it.isEmpty() }) {
            Log.w(TAG, "Geweigerd (ongeldig pad): $path")
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "invalid path"))
        }
        return try {
            val input = context.assets.open("www/$relatiefPad")
            val mime = mimeVoorAsset(relatiefPad)
            metCors(newFixedLengthResponse(Response.Status.OK, mime, input, input.available().toLong()))
        } catch (e: IOException) {
            // assets/www/ leeg of bestand ontbreekt (bv. een withPwa-build
            // waarbij de HBmonitor-checkout in build-apk.yml is mislukt) —
            // geen crash, gewoon een nette 404 zodat het probleem zichtbaar
            // is in de browser i.p.v. de service te laten stuklopen.
            Log.w(TAG, "Static asset niet gevonden: www/$relatiefPad", e)
            jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "asset not found: $relatiefPad"))
        }
    }

    /**
     * /api/metingen krijgt een eigen functie i.p.v. de generieke
     * lijstResponse(), omdat dit verreweg de grootste tabel is (tienduizenden
     * rijen op een drukke dag) en tot nu toe bij ELKE sync-cyclus (elke
     * paar seconden, via de PWA's bridgeAutoSyncTimer) de HELE dag opnieuw
     * opbouwde en verstuurde — ook als er sinds de vorige cyclus maar een
     * handvol nieuwe metingen bijkwamen. Naarmate de dag vordert en het
     * aantal metingen oploopt (10.000+, soms 20.000+), werd het opnieuw
     * opbouwen van die hele JSONArray + het versturen ervan elke paar
     * seconden zwaar genoeg om verbindingen te laten wegvallen — precies
     * het "TypeError: Failed to fetch"-patroon dat pas optrad nadat de dag
     * al een tijd bezig was, niet vanaf het begin.
     *
     * Met een optionele "since"-parameter (een ts-string, zelfde formaat
     * als de ts-kolom) hoeft alleen het nieuwe stuk opgehaald te worden.
     * De PWA kent haar eigen watermark al (bridgeLaatstVerwerkteTs) en
     * kan die nu meesturen i.p.v.'m alleen client-side te gebruiken om de
     * volledige respons achteraf te filteren.
     */
    private fun metingenResponse(datum: String?, sinds: String?): Response {
        val db = HbmonitorDb.open(context)
        val resultaat = JSONArray()
        val cursor: Cursor = when {
            datum != null && sinds != null ->
                db.rawQuery(
                    "SELECT ts, bpm, contact FROM metingen WHERE ts LIKE ? AND ts > ? ORDER BY ts",
                    arrayOf("$datum%", sinds)
                )
            datum != null ->
                db.rawQuery(
                    "SELECT ts, bpm, contact FROM metingen WHERE ts LIKE ? ORDER BY ts",
                    arrayOf("$datum%")
                )
            sinds != null ->
                db.rawQuery(
                    "SELECT ts, bpm, contact FROM metingen WHERE ts > ? ORDER BY ts",
                    arrayOf(sinds)
                )
            else ->
                db.rawQuery("SELECT ts, bpm, contact FROM metingen ORDER BY ts", null)
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                resultaat.put(
                    JSONObject()
                        .put("ts", c.getString(0))
                        .put("bpm", c.getInt(1))
                        .put("contact", c.getInt(2))
                )
            }
        }
        return jsonResponse(Response.Status.OK, resultaat)
    }

    private fun lijstResponse(
        tabel: String,
        kolommen: String,
        datum: String?,
        naarJson: (Cursor) -> JSONObject
    ): Response {
        val db = HbmonitorDb.open(context)
        val resultaat = JSONArray()
        // ts is opgeslagen als "YYYY-MM-DDTHH:mm:ss" (naive lokale tijd),
        // dus een simpele prefix-match selecteert die hele dag — zelfde
        // aanpak als in hr_sync_server.py.
        val cursor: Cursor = if (datum != null) {
            db.rawQuery("SELECT $kolommen FROM $tabel WHERE ts LIKE ? ORDER BY ts", arrayOf("$datum%"))
        } else {
            db.rawQuery("SELECT $kolommen FROM $tabel ORDER BY ts", null)
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                resultaat.put(naarJson(c))
            }
        }
        return jsonResponse(Response.Status.OK, resultaat)
    }

    // -------------------------------------------------------------------
    // POST
    // -------------------------------------------------------------------

    private fun handlePost(session: IHTTPSession): Response {
        val body = leesBody(session)
        val data = try {
            JSONObject(body)
        } catch (e: Exception) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "invalid JSON"))
        }

        return when (session.uri) {
            "/api/annotaties" -> handlePostAnnotatie(data)
            "/api/instellingen" -> handlePostInstellingen(data)
            else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not found"))
        }
    }

    private fun leesBody(session: IHTTPSession): String {
        val lengte = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (lengte == 0) return ""
        val map = HashMap<String, String>()
        session.parseBody(map)
        // NanoHTTPD legt de ruwe POST-body in map["postData"] voor
        // niet-multipart/form-urlencoded content zoals JSON.
        return map["postData"] ?: ""
    }

    private fun handlePostAnnotatie(data: JSONObject): Response {
        val ts = data.optString("ts", "")
        val type = data.optString("type", "")
        if (ts.isEmpty() || type.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "ts and type are required"))
        }
        // JSONObject.optString(String, String) staat sinds compileSdk 34 met
        // strengere null-annotaties in de SDK-stubs, waardoor Kotlin "null"
        // als fallback-argument niet meer toestaat ("Type mismatch:
        // inferred type is Nothing? but String was expected"). Vandaar deze
        // expliciete if/else i.p.v. een null-fallback doorgeven.
        val label: String? = if (!data.has("label") || data.isNull("label")) {
            null
        } else {
            data.optString("label", "")
        }
        val bpm = if (data.has("bpm") && !data.isNull("bpm")) data.optInt("bpm") else null

        return try {
            HbmonitorDb.zetAnnotatie(context, ts, type, label, bpm)
            jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", e.message ?: "db-fout"))
        }
    }

    private fun handlePostInstellingen(data: JSONObject): Response {
        if (data.length() == 0) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "expected a non-empty JSON object"))
        }
        return try {
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                HbmonitorDb.zetInstelling(context, key, data.get(key).toString())
            }
            jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", e.message ?: "db-fout"))
        }
    }

    // -------------------------------------------------------------------
    // Hulpfuncties
    // -------------------------------------------------------------------

    private fun jsonResponse(status: Response.Status, payload: Any): Response {
        val body = payload.toString()
        val response = newFixedLengthResponse(status, "application/json", body)
        return metCors(response)
    }

    private fun metCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        // Chrome (fully enforced since v130/142) sends a CORS preflight
        // ahead of ANY request — including plain GETs — when a page on a
        // public origin (https://robremy.github.io) targets a private-
        // network address (127.0.0.1/192.168.x.x). Without this header on
        // the preflight response, Chrome silently kills the fetch with
        // "TypeError: Failed to fetch", which is indistinguishable from a
        // dead server on the client side. Harmless to send on every
        // response, not just OPTIONS. See:
        // https://developer.chrome.com/blog/private-network-access-preflight
        response.addHeader("Access-Control-Allow-Private-Network", "true")
        return response
    }

    /**
     * Java/Android-equivalent van de UDP-connect()-truc in
     * hr_sync_server.py's get_lan_ip(): loop over netwerkinterfaces op
     * zoek naar een niet-loopback IPv4-adres. Geen socket-connect nodig
     * zoals in Python, want NetworkInterface geeft de toegewezen adressen
     * direct.
     */
    private fun lanIp(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addressen = Collections.list(iface.inetAddresses)
                for (adres in addressen) {
                    val host = adres.hostAddress ?: continue
                    // IPv4 zonder ':' (sluit IPv6 uit), niet-loopback.
                    if (!adres.isLoopbackAddress && !host.contains(":")) {
                        return host
                    }
                }
            }
            "onbekend"
        } catch (e: Exception) {
            "onbekend"
        }
    }
}
