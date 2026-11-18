package com.zeta.board

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class QuickItem(val id: Long, val sc: String, val desc: String, val msg: String, val enabled: Boolean, val ts: Long)

/** One search result: the matching items plus the overall counts (not affected by the search text). */
class QuickList(val items: List<QuickItem>, val total: Int, val enabled: Int)

/** Rules for shortcuts and for finding one right before the cursor. */
object QuickText {
    const val MAX_SC = 32
    const val MAX_DESC = 60
    const val MAX_MSG = 5000

    fun isDelimiter(c: Char): Boolean = c == ' ' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':'

    /** Trims the shortcut and adds the leading dot when the user left it out. */
    fun clean(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return if (t.startsWith(".")) t else ".$t"
    }

    private fun okCp(c: Int): Boolean {
        if (Character.isLetterOrDigit(c)) return true
        if (c == '_'.code || c == '-'.code || c == 0x200D) return true
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() ||
            t == Character.COMBINING_SPACING_MARK.toInt() ||
            t == Character.ENCLOSING_MARK.toInt() ||
            t == Character.OTHER_SYMBOL.toInt() ||
            t == Character.MODIFIER_SYMBOL.toInt()
    }

    /** Returns a message for the user, or null when [cleaned] (the result of [clean]) is a valid shortcut. */
    fun scError(cleaned: String): String? {
        if (cleaned.isEmpty()) return "Shortcut can't be empty"
        if (cleaned.length > MAX_SC) return "Shortcut is too long (max " + (MAX_SC - 1) + " characters)"
        val body = cleaned.substring(1)
        if (body.isEmpty()) return "Add a name after the dot, for example .welcome"
        var i = 0
        while (i < body.length) {
            val cp = body.codePointAt(i)
            if (!okCp(cp)) return "Use letters, numbers, emoji, - or _ only. No spaces."
            i += Character.charCount(cp)
        }
        return null
    }

    /**
     * The word typed right before the cursor, if it could be a shortcut (starts with a dot, no spaces, not too long).
     * Because only the whole word after the last space counts, a part of a longer word never matches.
     */
    fun tokenAtEnd(before: CharSequence): String? {
        val s = before.toString()
        if (s.isEmpty()) return null
        var idx = s.length - 1
        while (idx >= 0 && !Character.isWhitespace(s[idx])) idx--
        val tok = s.substring(idx + 1)
        if (tok.length < 2 || tok.length > MAX_SC) return null
        if (tok[0] != '.') return null
        return tok
    }
}

/** Local-only storage (app-private SQLite). Quick Text never shares a table or file with the clipboard history. */
private class QuickDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "quicktext.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE quick (id INTEGER PRIMARY KEY AUTOINCREMENT, sc TEXT NOT NULL, sckey TEXT NOT NULL UNIQUE, " +
                "descr TEXT NOT NULL DEFAULT '', msg TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
}

object QuickRepo {
    private var helper: QuickDbHelper? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<() -> Unit>()

    /** key (lower-case shortcut) -> message, for enabled shortcuts only. Read on the typing thread, so it is kept in memory. */
    @Volatile
    private var cache: Map<String, String> = emptyMap()

    @Synchronized
    fun init(ctx: Context) {
        if (helper != null) return
        helper = QuickDbHelper(ctx.applicationContext)
        io.execute { rebuildCache() }
    }

    private fun db(): SQLiteDatabase = helper!!.writableDatabase

    fun observe(f: () -> Unit) {
        observers.addIfAbsent(f)
    }

    fun unobserve(f: () -> Unit) {
        observers.remove(f)
    }

    private fun notifyChanged() {
        main.post { for (o in observers) o() }
    }

    private fun rebuildCache() {
        try {
            val m = HashMap<String, String>()
            val c = db().rawQuery("SELECT sckey, msg FROM quick WHERE enabled = 1", null)
            while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
            c.close()
            cache = m
        } catch (e: Exception) {
        }
    }

    fun hasEnabled(): Boolean = cache.isNotEmpty()

    /** The saved message for an enabled shortcut (case-insensitive), or null. */
    fun lookup(token: String): String? = cache[token.lowercase(Locale.ROOT)]

    private fun esc(q: String): String = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** cb is delivered on the main thread. */
    fun load(query: String, onlyEnabled: Boolean, limit: Int, cb: (QuickList) -> Unit) {
        io.execute {
            val items = ArrayList<QuickItem>()
            var total = 0
            var enabled = 0
            try {
                val d = db()
                val cc = d.rawQuery("SELECT COUNT(*), COALESCE(SUM(enabled), 0) FROM quick", null)
                if (cc.moveToFirst()) {
                    total = cc.getInt(0)
                    enabled = cc.getInt(1)
                }
                cc.close()
                val where = ArrayList<String>()
                val args = ArrayList<String>()
                val q = query.trim()
                if (q.isNotEmpty()) {
                    where.add("(sc LIKE ? ESCAPE '\\' OR descr LIKE ? ESCAPE '\\' OR msg LIKE ? ESCAPE '\\')")
                    val like = "%" + esc(q) + "%"
                    args.add(like)
                    args.add(like)
                    args.add(like)
                }
                if (onlyEnabled) where.add("enabled = 1")
                val sql = "SELECT id, sc, descr, msg, enabled, ts FROM quick" +
                    (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
                    " ORDER BY sckey ASC LIMIT " + limit
                val c = d.rawQuery(sql, args.toTypedArray())
                while (c.moveToNext()) {
                    items.add(QuickItem(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getLong(5)))
                }
                c.close()
            } catch (e: Exception) {
            }
            main.post { cb(QuickList(items, total, enabled)) }
        }
    }

    /**
     * Creates (id <= 0) or updates a shortcut. Inputs are validated and trimmed here, so every caller gets the same rules.
     * cb(null) means saved, otherwise it gets a message to show the user. Delivered on the main thread.
     */
    fun save(id: Long, rawSc: String, rawDesc: String, rawMsg: String, cb: (String?) -> Unit) {
        val sc = QuickText.clean(rawSc)
        val desc = rawDesc.trim()
        val msg = rawMsg.trim()
        val err = QuickText.scError(sc)
            ?: if (msg.isEmpty()) "Message can't be empty"
            else if (msg.length > QuickText.MAX_MSG) "Message is too long (max " + QuickText.MAX_MSG + " characters)"
            else if (desc.length > QuickText.MAX_DESC) "Description is too long (max " + QuickText.MAX_DESC + " characters)"
            else null
        if (err != null) {
            cb(err)
            return
        }
        io.execute {
            var result: String? = null
            try {
                val d = db()
                val key = sc.lowercase(Locale.ROOT)
                val c = d.rawQuery("SELECT id FROM quick WHERE sckey = ? AND id <> ? LIMIT 1", arrayOf(key, id.toString()))
                val dup = c.moveToFirst()
                c.close()
                if (dup) {
                    result = "$sc already exists. Pick a different shortcut."
                } else {
                    val v = ContentValues()
                    v.put("sc", sc)
                    v.put("sckey", key)
                    v.put("descr", desc)
                    v.put("msg", msg)
                    v.put("ts", System.currentTimeMillis())
                    if (id > 0) {
                        if (d.update("quick", v, "id = ?", arrayOf(id.toString())) == 0) result = "This shortcut no longer exists."
                    } else {
                        v.put("enabled", 1)
                        if (d.insert("quick", null, v) < 0) result = "Could not save. Please try again."
                    }
                }
                if (result == null) {
                    rebuildCache()
                    notifyChanged()
                }
            } catch (e: Exception) {
                result = "Could not save. Please try again."
            }
            val r = result
            main.post { cb(r) }
        }
    }

    fun setEnabled(id: Long, on: Boolean) {
        io.execute {
            try {
                val v = ContentValues()
                v.put("enabled", if (on) 1 else 0)
                db().update("quick", v, "id = ?", arrayOf(id.toString()))
                rebuildCache()
                notifyChanged()
            } catch (e: Exception) {
            }
        }
    }

    fun delete(id: Long) {
        io.execute {
            try {
                db().delete("quick", "id = ?", arrayOf(id.toString()))
                rebuildCache()
                notifyChanged()
            } catch (e: Exception) {
            }
        }
    }
}
