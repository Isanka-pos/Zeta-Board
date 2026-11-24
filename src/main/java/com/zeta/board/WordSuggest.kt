package com.zeta.board

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Sinhala word suggestions for Singlish typing. Fully offline.
 *
 * The letters typed so far are turned into Sinhala with [Singlish.convert]; words that start with that text are offered.
 * Two sources: the words you finished typing before (learned, most used first) and a small built-in list
 * (assets/si_words.txt). Learned words stay on the phone (a small file in the app's private storage) and can be cleared
 * from the app (Smart typing -> Clear learned words).
 */
object WordSuggest {
    private const val HAL = "\u0DCA"
    private const val FILE = "si_learned.txt"
    private const val MAX_LEARNED = 1500

    private var dict: List<String>? = null
    private var learned: LinkedHashMap<String, Int>? = null
    private val io = Executors.newSingleThreadExecutor()

    /** Loads the word lists in the background (call when the keyboard starts) so the first letter typed never waits for the disk. */
    fun preload(c: Context) {
        val app = c.applicationContext
        io.execute { load(app) }
    }

    @Synchronized
    private fun load(c: Context) {
        if (dict == null) {
            dict = try {
                c.applicationContext.assets.open("si_words.txt").bufferedReader(Charsets.UTF_8).useLines { l ->
                    l.map { it.trim() }.filter { it.isNotEmpty() }.toList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (learned == null) {
            val m = LinkedHashMap<String, Int>()
            try {
                val f = File(c.applicationContext.filesDir, FILE)
                if (f.exists()) {
                    f.forEachLine(Charsets.UTF_8) { line ->
                        val t = line.indexOf('\t')
                        if (t > 0) {
                            val n = line.substring(t + 1).toIntOrNull() ?: 1
                            m[line.substring(0, t)] = n
                        }
                    }
                }
            } catch (e: Exception) {
            }
            learned = m
        }
    }

    private fun isSinhalaWord(w: String): Boolean {
        if (w.length < 2 || w.length > 30) return false
        for (ch in w) if (ch != '\u200D' && ch !in '\u0D80'..'\u0DFF') return false
        return true
    }

    /** Up to [max] words that start like the Singlish letters typed so far ([roman]). Empty when there is nothing useful. */
    fun suggest(c: Context, roman: String, max: Int = 3): List<String> {
        load(c)
        var pre = Singlish.convert(roman)
        if (pre.endsWith(HAL)) pre = pre.dropLast(1)
        if (pre.length < 2) return emptyList()
        val out = ArrayList<String>(max)
        val l = learned
        if (l != null) {
            // best `max` learned words by use count, without sorting the whole list on every key
            val bestW = arrayOfNulls<String>(max)
            val bestC = IntArray(max)
            var cnt = 0
            for (e in l.entries) {
                val k = e.key
                if (k == pre || !k.startsWith(pre)) continue
                val v = e.value
                if (cnt == max && v <= bestC[max - 1]) continue
                var pos = if (cnt < max) cnt else max - 1
                while (pos > 0 && bestC[pos - 1] < v) {
                    bestC[pos] = bestC[pos - 1]
                    bestW[pos] = bestW[pos - 1]
                    pos--
                }
                bestC[pos] = v
                bestW[pos] = k
                if (cnt < max) cnt++
            }
            for (i in 0 until cnt) bestW[i]?.let { out.add(it) }
        }
        val d = dict
        if (d != null) {
            for (w in d) {
                if (out.size >= max) break
                if (w != pre && w.startsWith(pre) && w !in out) out.add(w)
            }
        }
        return out
    }

    /** Remembers a word the user finished typing (only Sinhala words; nothing else is ever stored). */
    fun learn(c: Context, word: String) {
        var w = word
        if (w.endsWith(HAL)) w = w.dropLast(1)
        if (!isSinhalaWord(w)) return
        load(c)
        val m = learned ?: return
        m[w] = (m[w] ?: 0) + 1
        if (m.size > MAX_LEARNED) {
            // forget the least used words
            val drop = m.entries.sortedBy { it.value }.take(m.size - MAX_LEARNED + 100).map { it.key }
            for (k in drop) m.remove(k)
        }
        save(c, m)
    }

    fun learnedCount(c: Context): Int {
        load(c)
        return learned?.size ?: 0
    }

    fun clearLearned(c: Context) {
        load(c)
        learned?.clear()
        val app = c.applicationContext
        io.execute {
            try {
                File(app.filesDir, FILE).delete()
            } catch (e: Exception) {
            }
        }
    }

    private fun save(c: Context, m: Map<String, Int>) {
        val app = c.applicationContext
        val snap = LinkedHashMap(m)
        io.execute {
            try {
                val tmp = File(app.filesDir, "$FILE.tmp")
                tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                    for ((k, v) in snap) w.write(k + "\t" + v + "\n")
                }
                tmp.renameTo(File(app.filesDir, FILE))
            } catch (e: Exception) {
            }
        }
    }
}
