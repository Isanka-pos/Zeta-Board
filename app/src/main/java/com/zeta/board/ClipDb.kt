package com.zeta.board

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class ClipItem(val id: Long, val text: String, val pinned: Boolean, val ts: Long)

/** Local-only storage (app-private SQLite). Nothing here ever leaves the device. */
private class ClipDbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "clips.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clips (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, ts INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_clips_ts ON clips(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
}

object ClipRepo {
    const val MAX_TEXT = 20000

    private var helper: ClipDbHelper? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun init(ctx: Context) {
        if (helper == null) helper = ClipDbHelper(ctx.applicationContext)
    }

    private fun db(): SQLiteDatabase = helper!!.writableDatabase

    /** Opens the database in the background when the keyboard starts, so the first tap on Clipboard does not wait for it. */
    fun warm() {
        io.execute {
            try {
                db().rawQuery("SELECT 1", null).close()
            } catch (e: Exception) {
            }
        }
    }

    fun observe(f: () -> Unit) {
        observers.add(f)
    }

    fun unobserve(f: () -> Unit) {
        observers.remove(f)
    }

    private fun notifyChanged() {
        main.post { for (o in observers) o() }
    }

    /** Saves a copied text. Duplicates are moved to the top (pinned state is kept). Old unpinned entries beyond [max] are removed. */
    fun add(text: String, max: Int) {
        if (text.isBlank() || text.length > MAX_TEXT) return
        io.execute {
            try {
                val d = db()
                val now = System.currentTimeMillis()
                val c = d.rawQuery("SELECT id FROM clips WHERE text = ? LIMIT 1", arrayOf(text))
                var id = -1L
                if (c.moveToFirst()) id = c.getLong(0)
                c.close()
                if (id >= 0) {
                    val v = ContentValues()
                    v.put("ts", now)
                    d.update("clips", v, "id = ?", arrayOf(id.toString()))
                } else {
                    val v = ContentValues()
                    v.put("text", text)
                    v.put("pinned", 0)
                    v.put("ts", now)
                    d.insert("clips", null, v)
                }
                d.execSQL(
                    "DELETE FROM clips WHERE pinned = 0 AND id NOT IN (SELECT id FROM clips WHERE pinned = 0 ORDER BY ts DESC LIMIT ?)",
                    arrayOf<Any>(max)
                )
                notifyChanged()
            } catch (e: Exception) {
            }
        }
    }

    private fun esc(q: String): String = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** cb(pinned, recent) is delivered on the main thread. */
    fun load(query: String, cb: (List<ClipItem>, List<ClipItem>) -> Unit) {
        io.execute {
            val pinned = ArrayList<ClipItem>()
            val recent = ArrayList<ClipItem>()
            try {
                val q = query.trim()
                val where = if (q.isEmpty()) "" else "WHERE text LIKE ? ESCAPE '\\' "
                val args: Array<String> = if (q.isEmpty()) emptyArray() else arrayOf("%" + esc(q) + "%")
                val c = db().rawQuery("SELECT id, text, pinned, ts FROM clips " + where + "ORDER BY ts DESC", args)
                while (c.moveToNext()) {
                    val it = ClipItem(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getLong(3))
                    if (it.pinned) pinned.add(it) else if (recent.size < 200) recent.add(it)
                }
                c.close()
            } catch (e: Exception) {
            }
            main.post { cb(pinned, recent) }
        }
    }

    fun setPinned(id: Long, pinned: Boolean) {
        io.execute {
            try {
                val v = ContentValues()
                v.put("pinned", if (pinned) 1 else 0)
                db().update("clips", v, "id = ?", arrayOf(id.toString()))
                notifyChanged()
            } catch (e: Exception) {
            }
        }
    }

    fun updateText(id: Long, text: String) {
        if (text.isBlank() || text.length > MAX_TEXT) return
        io.execute {
            try {
                val v = ContentValues()
                v.put("text", text)
                db().update("clips", v, "id = ?", arrayOf(id.toString()))
                notifyChanged()
            } catch (e: Exception) {
            }
        }
    }

    fun delete(id: Long) {
        io.execute {
            try {
                db().delete("clips", "id = ?", arrayOf(id.toString()))
                notifyChanged()
            } catch (e: Exception) {
            }
        }
    }

    /** Removes every unpinned entry. Pinned items are never touched. */
    fun clearRecent() {
        io.execute {
            try {
                db().delete("clips", "pinned = 0", null)
                notifyChanged()
            } catch (e: Exception) {
            }
        }
    }

    /** Wipes everything including pinned items (only used from app settings). */
    fun clearAll() {
        io.execute {
            try {
                db().delete("clips", null, null)
                notifyChanged()
            } catch (e: Exception) {
            }
        }
    }
}

/** UI state holder for the clipboard panel (no AndroidX needed inside an IME). */
class ClipViewModel {
    var query = ""
        private set
    var pinned: List<ClipItem> = emptyList()
        private set
    var recent: List<ClipItem> = emptyList()
        private set
    var loaded = false
        private set
    var onChanged: (() -> Unit)? = null

    private val obs: () -> Unit = { refresh() }

    fun start() {
        ClipRepo.observe(obs)
        refresh()
    }

    fun stop() {
        ClipRepo.unobserve(obs)
    }

    fun refresh() {
        val q = query
        ClipRepo.load(q) { p, r ->
            if (q == query) {
                pinned = p
                recent = r
                loaded = true
                onChanged?.invoke()
            }
        }
    }

    fun search(q: String) {
        query = q
        refresh()
    }

    fun togglePin(item: ClipItem) = ClipRepo.setPinned(item.id, !item.pinned)
    fun delete(item: ClipItem) = ClipRepo.delete(item.id)
    fun edit(item: ClipItem, text: String) = ClipRepo.updateText(item.id, text)
    fun clearRecent() = ClipRepo.clearRecent()
}
