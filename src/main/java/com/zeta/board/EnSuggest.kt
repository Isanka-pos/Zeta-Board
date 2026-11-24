package com.zeta.board

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * English word suggestions for the toolbar: completions while a word is typed, and next-word predictions after it.
 * Fully offline. Two sources: a built-in list (assets/en_words.txt, most common words first, assets/en_next.txt for
 * "what comes after this word") and what the user types often (kept in a small file in the app's private storage).
 *
 * Everything is loaded on a background thread. Until it is ready every call simply returns an empty list, so the
 * keyboard never waits for the disk. Learned data is only touched from the main thread.
 */
object EnSuggest {
    private const val FILE = "en_learned.txt"
    private const val MAX_UNI = 3000
    private const val MAX_PREV = 1200
    private const val MAX_NEXT = 8
    private val FALLBACK = arrayOf("the", "I", "to", "and", "a")

    @Volatile private var ready = false
    @Volatile private var loading = false

    private var words: Array<String> = emptyArray()
    private var lowerSorted: Array<String> = emptyArray()
    private var rankOfSorted: IntArray = IntArray(0)
    private var nextMap: HashMap<String, List<String>> = HashMap()

    private val uni = HashMap<String, Int>()
    private val bi = HashMap<String, HashMap<String, Int>>()
    private var dirty = 0
    private val io = Executors.newSingleThreadExecutor()

    /** Starts loading the lists in the background (safe to call many times). */
    fun preload(c: Context) {
        if (ready || loading) return
        loading = true
        val app = c.applicationContext
        io.execute {
            try {
                loadNow(app)
            } catch (e: Throwable) {
                ready = false
            } finally {
                loading = false
            }
        }
    }

    private fun loadNow(app: Context) {
        val list = ArrayList<String>(70000)
        app.assets.open("en_words.txt").bufferedReader(Charsets.UTF_8).useLines { l ->
            for (x in l) {
                val w = x.trim()
                if (w.isNotEmpty()) list.add(w)
            }
        }
        val arr = list.toTypedArray()
        val n = arr.size
        val lower = Array(n) { arr[it].lowercase() }
        val idx = (0 until n).sortedBy { lower[it] }
        val ls = Array(n) { lower[idx[it]] }
        val rs = IntArray(n) { idx[it] }

        val nm = HashMap<String, List<String>>()
        try {
            app.assets.open("en_next.txt").bufferedReader(Charsets.UTF_8).useLines { l ->
                for (line in l) {
                    val c = line.indexOf(':')
                    if (c <= 0) continue
                    val key = line.substring(0, c).trim().lowercase()
                    val vals = line.substring(c + 1).trim().split(' ').filter { it.isNotEmpty() }
                    if (vals.isNotEmpty()) nm[key] = vals
                }
            }
        } catch (e: Exception) {
        }

        uni.clear()
        bi.clear()
        try {
            val f = File(app.filesDir, FILE)
            if (f.exists()) {
                f.forEachLine(Charsets.UTF_8) { line ->
                    val p = line.split('\t')
                    try {
                        if (p.size == 3 && p[0] == "U") uni[p[1]] = p[2].toInt()
                        else if (p.size == 4 && p[0] == "B") bi.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toInt()
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
        }

        words = arr
        lowerSorted = ls
        rankOfSorted = rs
        nextMap = nm
        ready = true
    }

    private fun lowerBound(p: String): Int {
        var lo = 0
        var hi = lowerSorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lowerSorted[mid] < p) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun adapt(typed: String, w: String): String {
        if (typed.isEmpty()) return w
        var letters = 0
        var lowers = 0
        for (ch in typed) {
            if (ch.isLetter()) letters++
            if (ch.isLowerCase()) lowers++
        }
        if (letters >= 2 && lowers == 0) return w.uppercase()
        if (typed[0].isUpperCase()) return w.replaceFirstChar { it.uppercaseChar() }
        return w
    }

    private fun hasIgnoreCase(list: List<String>, w: String): Boolean {
        for (x in list) if (x.equals(w, true)) return true
        return false
    }

    /** Up to [max] words that start with [typed] (longer than it). Empty while the lists are still loading. */
    fun complete(typed: String, max: Int = 5): List<String> {
        if (!ready || typed.isEmpty() || typed.length > 24 || max <= 0) return emptyList()
        val p = typed.lowercase()
        val out = ArrayList<String>(max)

        // words the user types often (used at least twice), most used first: at most 2
        val lw = arrayOfNulls<String>(2)
        val lc = IntArray(2)
        var lcnt = 0
        for (e in uni.entries) {
            val v = e.value
            val k = e.key
            if (v < 2 || k.length <= p.length || !k.startsWith(p)) continue
            if (lcnt == 2 && v <= lc[1]) continue
            var pos = if (lcnt < 2) lcnt else 1
            while (pos > 0 && lc[pos - 1] < v) {
                lc[pos] = lc[pos - 1]
                lw[pos] = lw[pos - 1]
                pos--
            }
            lc[pos] = v
            lw[pos] = k
            if (lcnt < 2) lcnt++
        }
        for (i in 0 until lcnt) lw[i]?.let { out.add(adapt(typed, it)) }

        // the built-in list: the best-ranked (most common) words with this start
        val rb = IntArray(max)
        val wb = arrayOfNulls<String>(max)
        var cnt = 0
        var i = lowerBound(p)
        val n = lowerSorted.size
        while (i < n) {
            val s = lowerSorted[i]
            if (!s.startsWith(p)) break
            if (s.length > p.length) {
                val r = rankOfSorted[i]
                if (cnt < max || r < rb[max - 1]) {
                    var pos = if (cnt < max) cnt else max - 1
                    while (pos > 0 && rb[pos - 1] > r) {
                        rb[pos] = rb[pos - 1]
                        wb[pos] = wb[pos - 1]
                        pos--
                    }
                    rb[pos] = r
                    wb[pos] = words[r]
                    if (cnt < max) cnt++
                }
            }
            i++
        }
        for (j in 0 until cnt) {
            if (out.size >= max) break
            val w = adapt(typed, wb[j] ?: continue)
            if (!hasIgnoreCase(out, w)) out.add(w)
        }
        return out
    }

    /** Likely words after [prev] (a finished word). Always returns [max] words once loaded. */
    fun next(prev: String, max: Int = 5): List<String> {
        if (!ready || max <= 0) return emptyList()
        val key = prev.lowercase()
        val out = ArrayList<String>(max)

        val m = bi[key]
        if (m != null) {
            val top = m.entries.filter { it.value >= 2 }.sortedByDescending { it.value }
            for (e in top) {
                if (out.size >= 3) break
                out.add(e.key)
            }
        }
        val st = nextMap[key]
        if (st != null) for (w in st) {
            if (out.size >= max) break
            if (!hasIgnoreCase(out, w)) out.add(w)
        }
        for (w in FALLBACK) {
            if (out.size >= max) break
            if (!hasIgnoreCase(out, w)) out.add(w)
        }
        return out
    }

    /** Remembers a finished word and the word before it. Only letters; nothing is ever sent anywhere. */
    fun learn(word: String, prev: String?) {
        if (!ready) return
        val w = word.trimEnd('\'').lowercase()
        if (w.length < 2 || w.length > 24) return
        for (ch in w) if (!(ch.isLetter() || ch == '\'')) return
        uni[w] = (uni[w] ?: 0) + 1
        if (uni.size > MAX_UNI) {
            val iter = uni.entries.iterator()
            var drop = 300
            while (iter.hasNext() && drop > 0) {
                if (iter.next().value <= 1) {
                    iter.remove()
                    drop--
                }
            }
        }
        if (prev != null && prev.isNotEmpty()) {
            val m = bi.getOrPut(prev) { HashMap() }
            m[w] = (m[w] ?: 0) + 1
            if (m.size > MAX_NEXT) {
                var minK: String? = null
                var minV = Int.MAX_VALUE
                for (e in m.entries) if (e.value < minV && e.key != w) {
                    minV = e.value
                    minK = e.key
                }
                if (minK != null) m.remove(minK)
            }
            if (bi.size > MAX_PREV) bi.keys.firstOrNull { it != prev }?.let { bi.remove(it) }
        }
        dirty++
    }

    fun learnedCount(): Int = uni.size

    /** Writes the learned words to disk when something changed (the keyboard calls this when a new field starts). */
    fun flush(c: Context) {
        if (!ready || dirty == 0) return
        dirty = 0
        val sb = StringBuilder()
        for ((k, v) in uni) sb.append("U\t").append(k).append('\t').append(v).append('\n')
        for ((p, m) in bi) for ((k, v) in m) sb.append("B\t").append(p).append('\t').append(k).append('\t').append(v).append('\n')
        val text = sb.toString()
        val app = c.applicationContext
        io.execute {
            try {
                val tmp = File(app.filesDir, "$FILE.tmp")
                tmp.writeText(text, Charsets.UTF_8)
                tmp.renameTo(File(app.filesDir, FILE))
            } catch (e: Exception) {
            }
        }
    }

    fun clearLearned(c: Context) {
        if (ready) {
            uni.clear()
            bi.clear()
        }
        dirty = 0
        val app = c.applicationContext
        io.execute {
            try {
                File(app.filesDir, FILE).delete()
            } catch (e: Exception) {
            }
        }
    }
}
