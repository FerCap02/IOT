package cl.filamentbox.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cl.filamentbox.app.security.Crypto

sealed class AuthResult {
    object Ok : AuthResult()
    object BadCredentials : AuthResult()
    data class Locked(val seconds: Long) : AuthResult()
}

/**
 * SQLite local (autenticación sin conexión + historial de sensores).
 *  users:    usuario, sal, hash PBKDF2, contador de intentos fallidos y bloqueo temporal.
 *  readings: lecturas de los 3 DHT11 + UV, por usuario.
 */
class AppDb(context: Context) : SQLiteOpenHelper(context, "filamentbox.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE users(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                salt TEXT NOT NULL,
                hash TEXT NOT NULL,
                iterations INTEGER NOT NULL,
                failed INTEGER NOT NULL DEFAULT 0,
                locked_until INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE readings(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                ts INTEGER NOT NULL,
                ch_t REAL, ch_rh REAL, out_t REAL, out_rh REAL,
                post_t REAL, post_rh REAL, uv REAL, mode TEXT)"""
        )
        db.execSQL("CREATE INDEX idx_readings_user_ts ON readings(username, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------------- Usuarios ----------------

    /** @return false si el usuario ya existe. */
    fun register(username: String, password: String): Boolean {
        val salt = Crypto.newSalt()
        val hash = Crypto.hashPassword(password, salt, Crypto.PBKDF2_ITERATIONS)
        val cv = ContentValues().apply {
            put("username", username)
            put("salt", Crypto.b64(salt))
            put("hash", Crypto.b64(hash))
            put("iterations", Crypto.PBKDF2_ITERATIONS)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("users", null, cv) != -1L
    }

    fun authenticate(username: String, password: String): AuthResult {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        data class Row(val salt: String, val hash: String, val iter: Int, val failed: Int, val lockedUntil: Long)

        val row = db.query(
            "users", arrayOf("salt", "hash", "iterations", "failed", "locked_until"),
            "username=?", arrayOf(username), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) null
            else Row(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getLong(4))
        }

        if (row == null) {
            // Hash ficticio para no revelar por tiempo si el usuario existe.
            Crypto.hashPassword(password, ByteArray(16), Crypto.PBKDF2_ITERATIONS)
            return AuthResult.BadCredentials
        }
        if (row.lockedUntil > now) return AuthResult.Locked((row.lockedUntil - now) / 1000 + 1)

        val calc = Crypto.hashPassword(password, Crypto.unb64(row.salt), row.iter)
        return if (Crypto.constantTimeEquals(calc, Crypto.unb64(row.hash))) {
            db.execSQL("UPDATE users SET failed=0, locked_until=0 WHERE username=?", arrayOf(username))
            AuthResult.Ok
        } else {
            val failed = row.failed + 1
            if (failed >= MAX_ATTEMPTS) {
                db.execSQL(
                    "UPDATE users SET failed=0, locked_until=? WHERE username=?",
                    arrayOf(now + LOCK_MS, username)
                )
                AuthResult.Locked(LOCK_MS / 1000)
            } else {
                db.execSQL("UPDATE users SET failed=? WHERE username=?", arrayOf(failed, username))
                AuthResult.BadCredentials
            }
        }
    }

    // ---------------- Lecturas ----------------

    fun insertReading(username: String, s: DeviceStatus) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("username", username)
            put("ts", System.currentTimeMillis())
            put("ch_t", s.chamber.t); put("ch_rh", s.chamber.rh)
            put("out_t", s.outdoor.t); put("out_rh", s.outdoor.rh)
            put("post_t", s.postSilica.t); put("post_rh", s.postSilica.rh)
            put("uv", s.uv); put("mode", s.mode)
        }
        db.insert("readings", null, cv)
        // Conserva solo las últimas 2000 lecturas por usuario.
        db.execSQL(
            "DELETE FROM readings WHERE username=? AND id NOT IN " +
                "(SELECT id FROM readings WHERE username=? ORDER BY ts DESC LIMIT 2000)",
            arrayOf(username, username)
        )
    }

    /** Últimas [limit] lecturas, de la más antigua a la más reciente. */
    fun lastReadings(username: String, limit: Int): List<Reading> {
        val list = ArrayList<Reading>()
        readableDatabase.query(
            "readings", null, "username=?", arrayOf(username), null, null, "ts DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Reading(
                        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                        chT = c.getDouble(c.getColumnIndexOrThrow("ch_t")),
                        chRh = c.getDouble(c.getColumnIndexOrThrow("ch_rh")),
                        outT = c.getDouble(c.getColumnIndexOrThrow("out_t")),
                        outRh = c.getDouble(c.getColumnIndexOrThrow("out_rh")),
                        postT = c.getDouble(c.getColumnIndexOrThrow("post_t")),
                        postRh = c.getDouble(c.getColumnIndexOrThrow("post_rh")),
                        uv = c.getDouble(c.getColumnIndexOrThrow("uv")),
                        mode = c.getString(c.getColumnIndexOrThrow("mode")) ?: "",
                    )
                )
            }
        }
        return list.reversed()
    }

    fun clearReadings(username: String) {
        writableDatabase.delete("readings", "username=?", arrayOf(username))
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCK_MS = 30_000L
    }
}
