package com.zeta.board

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class QuickText(val id: Long, val shortcut: String, val message: String, val category: String, val favorite: Boolean, val usedAt: Long, val useCount: Int)

object QuickTextRepo {
    private lateinit var helper: Helper
    fun init(c: Context) { if (!::helper.isInitialized) helper = Helper(c.applicationContext) }
    private fun db(): SQLiteDatabase { check(::helper.isInitialized) { "QuickTextRepo not initialized" }; return helper.writableDatabase }
    fun all(query: String = ""): List<QuickText> {
        val out = mutableListOf<QuickText>(); val q = query.trim()
        val order = " ORDER BY favorite DESC, use_count DESC, used_at DESC, shortcut COLLATE NOCASE"
        val sql = if (q.isEmpty()) "SELECT * FROM quick_text$order" else "SELECT * FROM quick_text WHERE shortcut LIKE ? OR message LIKE ? OR category LIKE ?$order"
        val args = if (q.isEmpty()) null else arrayOf("%$q%", "%$q%", "%$q%")
        db().rawQuery(sql, args).use { c -> while (c.moveToNext()) out += QuickText(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) != 0, c.getLong(5), c.getInt(6)) }
        return out
    }
    fun save(id: Long = 0, shortcut: String, message: String, category: String, favorite: Boolean): Boolean {
        val key = shortcut.trim().removePrefix(".").lowercase()
        if (key.isBlank() || message.isBlank()) return false
        val values = ContentValues().apply { put("shortcut", key); put("message", message); put("category", category.trim().ifBlank { "General" }); put("favorite", if (favorite) 1 else 0) }
        return try {
            if (id == 0L) db().insertWithOnConflict("quick_text", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            else db().updateWithOnConflict("quick_text", values, "_id=?", arrayOf(id.toString()), SQLiteDatabase.CONFLICT_IGNORE) > 0
        } catch (_: Exception) { false }
    }
    fun delete(id: Long) { db().delete("quick_text", "_id=?", arrayOf(id.toString())) }
    fun toggleFavorite(id: Long, value: Boolean) { db().execSQL("UPDATE quick_text SET favorite=? WHERE _id=?", arrayOf(if (value) 1 else 0, id)) }
    fun used(id: Long) { db().execSQL("UPDATE quick_text SET used_at=?, use_count=use_count+1 WHERE _id=?", arrayOf(System.currentTimeMillis(), id)) }
    fun categories(): List<String> = all().map { it.category }.distinct().sorted()
    fun exportJson(): String { val a = JSONArray(); all().forEach { a.put(JSONObject().put("shortcut", it.shortcut).put("message", it.message).put("category", it.category).put("favorite", it.favorite)) }; return JSONObject().put("format", "zeta-quick-text").put("version", 1).put("shortcuts", a).toString(2) }
    fun importJson(json: String): Int { val root = JSONObject(json); require(root.optString("format") == "zeta-quick-text") { "Not a ZETA Quick Text file" }; val a = root.getJSONArray("shortcuts"); var n = 0; for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (save(shortcut=o.optString("shortcut"), message=o.optString("message"), category=o.optString("category", "General"), favorite=o.optBoolean("favorite"))) n++ }; return n }
    private class Helper(c: Context) : SQLiteOpenHelper(c, "zeta_quick_text.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE quick_text (_id INTEGER PRIMARY KEY AUTOINCREMENT, shortcut TEXT NOT NULL COLLATE NOCASE UNIQUE, message TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'General', favorite INTEGER NOT NULL DEFAULT 0, used_at INTEGER NOT NULL DEFAULT 0, use_count INTEGER NOT NULL DEFAULT 0)") }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) db.execSQL("ALTER TABLE quick_text ADD COLUMN use_count INTEGER NOT NULL DEFAULT 0") }
    }
}
