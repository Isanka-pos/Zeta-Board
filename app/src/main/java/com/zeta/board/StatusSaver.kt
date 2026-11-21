package com.zeta.board

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream

/** One photo or video found in a status folder. */
class StatusItem(val uri: Uri, val name: String, val mime: String, val modified: Long, val size: Long) {
    val video: Boolean get() = mime.startsWith("video/")
    val key: String get() = uri.toString()
}

/** Small flag shared between the keyboard and the setup screen: open the Status Saver again when the keyboard comes back. */
object StatusHub {
    @Volatile
    var pendingShow = false
}

/**
 * Finds the WhatsApp status folders the user allowed (Android only lets an app read them after the user picks the
 * folder once) and lists the photos and videos inside.
 */
object StatusRepo {
    private const val AUTH = "com.android.externalstorage.documents"
    private const val KEY_FOLDERS = "status_folders"
    private const val KEY_SAVED = "status_saved"
    private const val MAX_SAVED = 400
    private const val MAX_ITEMS = 150

    /** Folder names worth descending into while looking for ".Statuses" below something the user picked. */
    private val route = setOf("android", "media", "com.whatsapp", "com.whatsapp.w4b", "whatsapp", "whatsapp business")

    class Folder(val tree: Uri, val docId: String)
    class Listing(val items: List<StatusItem>, val folders: Int, val broken: Int)

    private class Child(val id: String, val name: String, val mime: String, val modified: Long, val size: Long) {
        val dir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    // ---------- the folders the user added ----------

    fun folders(c: Context): List<Folder> {
        val out = ArrayList<Folder>()
        for (line in Prefs.getS(c, KEY_FOLDERS, "").split("\n")) {
            val p = line.split("\t")
            if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) out.add(Folder(Uri.parse(p[0]), p[1]))
        }
        return out
    }

    private fun store(c: Context, list: List<Folder>) {
        Prefs.setS(c, KEY_FOLDERS, list.joinToString("\n") { it.tree.toString() + "\t" + it.docId })
    }

    fun remove(c: Context, f: Folder) {
        val left = folders(c).filter { !(it.tree == f.tree && it.docId == f.docId) }
        store(c, left)
        // give the permission back only when no other entry still uses the same picked folder
        if (left.none { it.tree == f.tree }) {
            try {
                c.contentResolver.releasePersistableUriPermission(f.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
        }
    }

    /** Where the folder picker should start, so the user only has to press "Use this folder". */
    fun initialUri(kind: String): Uri {
        val business = kind == "w4b"
        val pkg = if (business) "com.whatsapp.w4b" else "com.whatsapp"
        val app = if (business) "WhatsApp Business" else "WhatsApp"
        val path = if (Build.VERSION.SDK_INT >= 30) "Android/media/$pkg/$app/Media/.Statuses" else "$app/Media/.Statuses"
        return DocumentsContract.buildDocumentUri(AUTH, "primary:$path")
    }

    /**
     * The user picked [tree]. If it is (or contains) a ".Statuses" folder, that one is used, otherwise the picked
     * folder itself is listed. Returns how many photos/videos are in there right now.
     */
    fun add(c: Context, tree: Uri): Int {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            return -1
        }
        val target = if (rootId.endsWith(".Statuses", ignoreCase = true)) rootId else (findStatuses(c, tree, rootId) ?: rootId)
        val f = Folder(tree, target)
        val list = folders(c).filter { !(it.tree == tree && it.docId == target) }.toMutableList()
        list.add(f)
        store(c, list)
        val ch = children(c, tree, target) ?: return -1
        return ch.count { mediaMime(it) != null }
    }

    private fun findStatuses(c: Context, tree: Uri, rootId: String): String? {
        var level = listOf(rootId)
        repeat(6) {
            val next = ArrayList<String>()
            for (id in level) {
                for (ch in children(c, tree, id) ?: continue) {
                    if (!ch.dir) continue
                    val n = ch.name.lowercase()
                    if (n == ".statuses") return ch.id
                    if (n in route) next.add(ch.id)
                }
            }
            if (next.isEmpty()) return null
            level = next
        }
        return null
    }

    // ---------- listing ----------

    /** Every photo and video in the added folders, newest first. Run this off the main thread. */
    fun list(c: Context): Listing {
        val fs = folders(c)
        val seen = HashSet<String>()
        val items = ArrayList<StatusItem>()
        var broken = 0
        for (f in fs) {
            val ch = children(c, f.tree, f.docId)
            if (ch == null) {
                broken++
                continue
            }
            for (x in ch) {
                val mime = mediaMime(x) ?: continue
                if (x.size <= 0L) continue
                if (!seen.add(x.name)) continue
                items.add(StatusItem(DocumentsContract.buildDocumentUriUsingTree(f.tree, x.id), x.name, mime, x.modified, x.size))
            }
        }
        items.sortByDescending { it.modified }
        return Listing(if (items.size > MAX_ITEMS) items.subList(0, MAX_ITEMS).toList() else items, fs.size, broken)
    }

    /** null = the folder can't be read (permission gone, folder deleted). */
    private fun children(c: Context, tree: Uri, id: String): List<Child>? {
        var cur: Cursor? = null
        return try {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            cur = c.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            ) ?: return null
            val out = ArrayList<Child>()
            while (cur.moveToNext()) {
                val docId = cur.getString(0) ?: continue
                out.add(Child(docId, cur.getString(1) ?: "", cur.getString(2) ?: "", cur.getLong(3), cur.getLong(4)))
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            try {
                cur?.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun mediaMime(x: Child): String? {
        val m = x.mime.lowercase()
        if (m.startsWith("image/") || m.startsWith("video/")) return m
        val n = x.name.lowercase()
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".gif") -> "image/gif"
            n.endsWith(".mp4") -> "video/mp4"
            n.endsWith(".3gp") -> "video/3gpp"
            n.endsWith(".mkv") -> "video/x-matroska"
            else -> null
        }
    }

    // ---------- "already saved" marks ----------

    fun savedNames(c: Context): Set<String> = Prefs.getS(c, KEY_SAVED, "").split("\n").filter { it.isNotEmpty() }.toSet()

    fun markSaved(c: Context, name: String) {
        val all = Prefs.getS(c, KEY_SAVED, "").split("\n").filter { it.isNotEmpty() && it != name }.toMutableList()
        all.add(name)
        while (all.size > MAX_SAVED) all.removeAt(0)
        Prefs.setS(c, KEY_SAVED, all.joinToString("\n"))
    }
}

/** Reading, copying and saving the files. Everything here blocks, so call it from a background thread. */
object StatusIO {
    enum class Saved { OK, EXISTS, FAIL, NEED_PERMISSION }

    private val thumbs = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 12).toInt().coerceAtLeast(4096)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun cachedThumb(item: StatusItem): Bitmap? = thumbs.get(item.key)

    fun thumb(c: Context, item: StatusItem, px: Int): Bitmap? {
        thumbs.get(item.key)?.let { return it }
        val b = try {
            loadThumb(c, item, px)
        } catch (e: Throwable) {
            null
        }
        if (b != null) thumbs.put(item.key, b)
        return b
    }

    private fun loadThumb(c: Context, item: StatusItem, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return c.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
            } catch (e: Throwable) {
                // the folder's provider has no thumbnail for this file: decode it ourselves below
            }
        }
        return if (item.video) videoFrame(c, item.uri, px) else decode(c, item.uri, px)
    }

    private fun decode(c: Context, uri: Uri, px: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    private fun videoFrame(c: Context, uri: Uri, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            val f = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val big = maxOf(f.width, f.height)
            if (big <= px * 2) return f
            val k = (px * 2).toFloat() / big
            return Bitmap.createScaledBitmap(f, (f.width * k).toInt().coerceAtLeast(1), (f.height * k).toInt().coerceAtLeast(1), true)
        } finally {
            try {
                r.release()
            } catch (e: Throwable) {
            }
        }
    }

    private fun safeName(n: String): String = n.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** A copy the target app is allowed to read (through our FileProvider). Old copies are cleaned up first. */
    fun toCache(c: Context, item: StatusItem): File? {
        return try {
            val dir = File(c.cacheDir, "statusshare")
            dir.mkdirs()
            cleanCache(dir)
            val f = File(dir, safeName(item.name))
            if (f.exists() && f.length() == item.size) return f
            val ok = c.contentResolver.openInputStream(item.uri)?.use { i ->
                FileOutputStream(f).use { o -> i.copyTo(o) }
                true
            } ?: false
            if (ok) f else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun cleanCache(dir: File) {
        val limit = System.currentTimeMillis() - 6L * 60 * 60 * 1000
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    /** Copies the file into the gallery (Pictures/Zeta Board or Movies/Zeta Board). The same file is never saved twice. */
    fun save(c: Context, item: StatusItem): Saved {
        return try {
            if (Build.VERSION.SDK_INT >= 29) saveQ(c, item) else saveLegacy(c, item)
        } catch (e: Throwable) {
            Saved.FAIL
        }
    }

    @RequiresApi(29)
    private fun saveQ(c: Context, item: StatusItem): Saved {
        val base = if (item.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val rel = (if (item.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Zeta Board/"
        c.contentResolver.query(
            base,
            arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
            arrayOf(item.name, rel),
            null
        )?.use { if (it.moveToFirst()) return Saved.EXISTS }

        val v = ContentValues()
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
        v.put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        v.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val out = c.contentResolver.insert(base, v) ?: return Saved.FAIL
        try {
            val ok = c.contentResolver.openOutputStream(out)?.use { o ->
                c.contentResolver.openInputStream(item.uri)?.use { i ->
                    i.copyTo(o)
                    true
                }
            } ?: false
            if (!ok) {
                c.contentResolver.delete(out, null, null)
                return Saved.FAIL
            }
            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            c.contentResolver.update(out, done, null, null)
            return Saved.OK
        } catch (e: Throwable) {
            try {
                c.contentResolver.delete(out, null, null)
            } catch (e2: Throwable) {
            }
            return Saved.FAIL
        }
    }

    /** Android 9 and older: plain files in the public folders (needs the storage permission). */
    private fun saveLegacy(c: Context, item: StatusItem): Saved {
        if (c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return Saved.NEED_PERMISSION
        val root = Environment.getExternalStoragePublicDirectory(if (item.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
        val dir = File(root, "Zeta Board")
        if (!dir.isDirectory && !dir.mkdirs()) return Saved.FAIL
        val f = File(dir, safeName(item.name))
        if (f.exists() && f.length() == item.size) return Saved.EXISTS
        val ok = c.contentResolver.openInputStream(item.uri)?.use { i ->
            FileOutputStream(f).use { o -> i.copyTo(o) }
            true
        } ?: false
        if (!ok) return Saved.FAIL
        MediaScannerConnection.scanFile(c, arrayOf(f.path), arrayOf(item.mime), null)
        return Saved.OK
    }
}

/**
 * Invisible screen that a keyboard cannot show itself: lets the user pick a status folder (kind "wa", "w4b" or "any")
 * or allow storage access on old Android versions (kind "perm").
 */
class StatusSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("kind") ?: "wa"
        if (kind == "perm") {
            if (Build.VERSION.SDK_INT < 29) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 51)
            } else {
                finish()
            }
            return
        }
        if (savedInstanceState != null) return // the picker is already open, its result will arrive
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, StatusRepo.initialUri(kind))
            i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            startActivityForResult(i, 50)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker found on this phone", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != 50 || resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        val app = applicationContext
        Thread {
            val n = StatusRepo.add(app, uri)
            runOnUiThread {
                val msg = when {
                    n < 0 -> "Couldn't open that folder. Try again."
                    n == 0 -> "Folder added. No statuses yet: view some in WhatsApp first."
                    else -> "Folder added: $n statuses found"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
