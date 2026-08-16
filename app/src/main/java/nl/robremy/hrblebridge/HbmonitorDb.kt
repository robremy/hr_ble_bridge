package nl.robremy.hrblebridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Enige SQLite-toegangspunt voor de app. Vervangt hr_tail.py (dat losse
 * JSONL-bestanden tailde en in SQLite zette) en het schema-gedeelte van
 * hr_sync_server.py — beide liepen als apart Termux-proces omdat een
 * losse Kotlin-service en een los Python-script niet zomaar dezelfde
 * in-memory toegang konden delen. Nu draait alles (BLE-service +
 * embedded HTTP-server) in hetzelfde proces, dus kan één gedeelde
 * SQLiteOpenHelper-instantie de rol van "tailer" overbodig maken: de
 * BLE-callback schrijft direct een rij weg in plaats van een JSON-regel
 * die later ingelezen moet worden.
 *
 * Blijft bewust op gedeelde opslag (net als voorheen), zodat de database
 * ook zonder deze app leesbaar blijft vanuit Termux voor analyse:
 *   /storage/emulated/0/HBmonitor/hbmonitor.db
 */
object HbmonitorDb {
    private const val TAG = "HbmonitorDb"
    private const val DB_NAAM = "hbmonitor.db"
    private const val DB_VERSIE = 1

    private var helper: Helper? = null
    private val lock = Any()

    /** Map op gedeelde opslag waar de db (en voorheen de JSONL-bestanden) leven. */
    fun hbmonitorMap(): File {
        val basis = Environment.getExternalStorageDirectory()
        val map = File(basis, "HBmonitor")
        if (!map.exists()) map.mkdirs()
        return map
    }

    fun dbBestand(): File = File(hbmonitorMap(), DB_NAAM)

    /**
     * Eén schrijfbare verbinding voor de hele app-levensduur. Android's
     * SQLiteDatabase serialiseert schrijfacties op een enkele instantie
     * intern, dus BLE-callbacks (kunnen op een binder-thread komen) en
     * NanoHTTPD-requests (elk op een eigen thread) mogen hier gelijktijdig
     * op schrijven; het `lock`-object hieronder maakt dat bovendien
     * expliciet i.p.v. stilzwijgend op Android's interne locking te
     * vertrouwen.
     */
    fun open(context: Context): SQLiteDatabase {
        synchronized(lock) {
            var h = helper
            if (h == null) {
                h = Helper(context.applicationContext)
                helper = h
            }
            return h.writableDatabase
        }
    }

    fun sluit() {
        synchronized(lock) {
            helper?.close()
            helper = null
        }
    }

    // -------------------------------------------------------------------
    // Schrijfacties (voorheen: BLE-service -> JSONL, hr_tail.py -> SQLite)
    // -------------------------------------------------------------------

    fun voegMetingToe(context: Context, ts: String, bpm: Int, contact: Int) {
        synchronized(lock) {
            try {
                val db = open(context)
                val values = ContentValues().apply {
                    put("ts", ts)
                    put("bpm", bpm)
                    put("contact", contact)
                }
                // OR IGNORE: zelfde gedrag als hr_tail.py's "INSERT OR IGNORE",
                // ts is PRIMARY KEY dus een dubbele timestamp wordt stil genegeerd.
                db.insertWithOnConflict("metingen", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            } catch (e: Exception) {
                Log.e(TAG, "Wegschrijven meting mislukt", e)
            }
        }
    }

    fun voegEventToe(context: Context, ts: String, bericht: String) {
        synchronized(lock) {
            try {
                val db = open(context)
                val values = ContentValues().apply {
                    put("ts", ts)
                    put("bericht", bericht)
                }
                db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            } catch (e: Exception) {
                Log.e(TAG, "Wegschrijven event mislukt", e)
            }
        }
    }

    // -------------------------------------------------------------------
    // Gelezen/geschreven vanuit HrHttpServer (voorheen hr_sync_server.py)
    // -------------------------------------------------------------------

    fun zetAnnotatie(context: Context, ts: String, type: String, label: String?, bpm: Int?) {
        synchronized(lock) {
            val db = open(context)
            val values = ContentValues().apply {
                put("ts", ts)
                put("type", type)
                put("label", label)
                if (bpm != null) put("bpm", bpm) else putNull("bpm")
            }
            db.insertWithOnConflict("annotaties", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun zetInstelling(context: Context, key: String, value: String) {
        synchronized(lock) {
            val db = open(context)
            val values = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            db.insertWithOnConflict("instellingen", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, HbmonitorDb.dbBestand().absolutePath, null, DB_VERSIE) {

        override fun onCreate(db: SQLiteDatabase) {
            maakTabellenAan(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Nog geen schemawijzigingen geweest; toekomstige versies voegen
            // hier ALTER TABLE-stappen toe i.p.v. destructief te droppen,
            // want dit is de enige kopie van Rob's gemeten data.
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            // Rob's bestaande hbmonitor.db (aangemaakt door hr_sync_server.py/
            // hr_tail.py, niet door SQLiteOpenHelper) bestaat al op schijf, dus
            // onCreate() vuurt daarvoor NIET — die wordt alleen aangeroepen
            // voor een gloednieuw dbbestand. Zonder deze regel hier zou een
            // db van vóór "instellingen" bestond (zie hr_sync_server.py's
            // eigen kanttekening daarover) die tabel dus nooit alsnog krijgen.
            // CREATE TABLE IF NOT EXISTS is idempotent, dus dit bij elke open
            // herhalen is goedkoop en veilig.
            maakTabellenAan(db)
            // WAL i.p.v. de standaard rollback-journal: laat de HTTP-server
            // (leesthread) en de BLE-service (schrijft metingen) gelijktijdig
            // toegang hebben zonder elkaar te blokkeren op elke losse write.
            db.enableWriteAheadLogging()
        }

        private fun maakTabellenAan(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS metingen (
                    ts      TEXT PRIMARY KEY,
                    bpm     INTEGER NOT NULL,
                    contact INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS events (
                    id      INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts      TEXT NOT NULL,
                    bericht TEXT NOT NULL,
                    UNIQUE (ts, bericht)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS annotaties (
                    ts    TEXT NOT NULL,
                    type  TEXT NOT NULL,
                    label TEXT,
                    bpm   INTEGER,
                    PRIMARY KEY (ts, type)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS instellingen (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
    }
}
