package nl.robremy.hrblebridge

import android.content.Context
import android.database.Cursor
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

/**
 * Vervangt hr_sync_server.py: dezelfde endpoints, dezelfde JSON-vorm, zodat
 * de PWA (index.html/features.js, BRIDGE_URL blijft http://<ip>:8787)
 * ongewijzigd kan blijven. Draait embedded in HrBridgeService i.p.v. als
 * los Termux-proces — leest/schrijft rechtstreeks via HbmonitorDb, geen
 * los `python hr_sync_server.py` meer nodig.
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

        return when (path) {
            "/api/health" -> jsonResponse(
                Response.Status.OK,
                JSONObject().put("ok", true).put("ip", lanIp())
            )
            "/api/metingen" -> lijstResponse("metingen", "ts, bpm, contact", datum) { c ->
                JSONObject()
                    .put("ts", c.getString(0))
                    .put("bpm", c.getInt(1))
                    .put("contact", c.getInt(2))
            }
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
            else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not found"))
        }
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
        val label = if (data.isNull("label")) null else data.optString("label", null)
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
