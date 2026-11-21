package com.zeta.board

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.Executors

/** A language the user can translate into. [native] is how the language writes its own name. */
class Lang(val code: String, val name: String, val native: String)

object Langs {
    val all: List<Lang> = listOf(
        Lang("en", "English", "English"),
        Lang("si", "Sinhala", "සිංහල"),
        Lang("ta", "Tamil", "தமிழ்"),
        Lang("hi", "Hindi", "हिन्दी"),
        Lang("bn", "Bengali", "বাংলা"),
        Lang("ml", "Malayalam", "മലയാളം"),
        Lang("te", "Telugu", "తెలుగు"),
        Lang("kn", "Kannada", "ಕನ್ನಡ"),
        Lang("mr", "Marathi", "मराठी"),
        Lang("gu", "Gujarati", "ગુજરાતી"),
        Lang("pa", "Punjabi", "ਪੰਜਾਬੀ"),
        Lang("ur", "Urdu", "اردو"),
        Lang("ne", "Nepali", "नेपाली"),
        Lang("ar", "Arabic", "العربية"),
        Lang("fa", "Persian", "فارسی"),
        Lang("tr", "Turkish", "Türkçe"),
        Lang("ru", "Russian", "Русский"),
        Lang("uk", "Ukrainian", "Українська"),
        Lang("pl", "Polish", "Polski"),
        Lang("cs", "Czech", "Čeština"),
        Lang("ro", "Romanian", "Română"),
        Lang("hu", "Hungarian", "Magyar"),
        Lang("el", "Greek", "Ελληνικά"),
        Lang("bg", "Bulgarian", "Български"),
        Lang("sv", "Swedish", "Svenska"),
        Lang("da", "Danish", "Dansk"),
        Lang("fi", "Finnish", "Suomi"),
        Lang("nl", "Dutch", "Nederlands"),
        Lang("de", "German", "Deutsch"),
        Lang("fr", "French", "Français"),
        Lang("es", "Spanish", "Español"),
        Lang("pt", "Portuguese", "Português"),
        Lang("it", "Italian", "Italiano"),
        Lang("ja", "Japanese", "日本語"),
        Lang("ko", "Korean", "한국어"),
        Lang("zh-CN", "Chinese (Simplified)", "中文 (简体)"),
        Lang("zh-TW", "Chinese (Traditional)", "中文 (繁體)"),
        Lang("th", "Thai", "ไทย"),
        Lang("vi", "Vietnamese", "Tiếng Việt"),
        Lang("id", "Indonesian", "Bahasa Indonesia"),
        Lang("ms", "Malay", "Bahasa Melayu")
    )

    fun byCode(c: String): Lang? = all.firstOrNull { it.code == c }

    /** The language chosen in the app (Zeta Translate settings). English until the user picks another. */
    fun target(ctx: Context): Lang {
        val raw = Prefs.getS(ctx, "tr_to", "en")
        return byCode(if (raw == "zh") "zh-CN" else raw) ?: all[0]
    }

    fun setTarget(ctx: Context, l: Lang) = Prefs.setS(ctx, "tr_to", l.code)

    /** True when two language codes mean the same language (the detector says "zh", the setting says "zh-CN"). */
    fun same(a: String?, b: String): Boolean {
        if (a == null) return false
        val x = a.lowercase()
        val y = b.lowercase()
        if (x == y) return true
        if (x.startsWith("zh") || y.startsWith("zh")) return x == "zh" && y.startsWith("zh")
        return x.substringBefore('-') == y.substringBefore('-')
    }
}

class TResult(val text: String, val detected: String?)

/** Failure with a message that is safe to show to the user (never contains the translated text). */
class TError(val kind: Kind, msg: String) : Exception(msg) {
    enum class Kind { EMPTY, NETWORK, TIMEOUT, RATE, UNSUPPORTED, BAD_RESPONSE, SERVER, CANCELLED }
}

/**
 * Zeta Translate engine. Sends the text to Google Translate's public web endpoint and, if that is rate limited
 * or broken, to MyMemory. Nothing is stored anywhere: no history, no keys.
 */
object Translator {
    /** Longest text one translate press handles. */
    const val MAX_CHARS = 4000

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "zeta-translate").also { it.isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0
    @Volatile private var active: HttpURLConnection? = null

    /** Drops the running translation (and its result) if there is one. */
    fun cancel() {
        gen++
        try {
            active?.disconnect()
        } catch (e: Exception) {
        }
    }

    /** Translates [text] into [to]. [done] runs on the main thread, exactly one of its arguments is not null, and it never runs for a cancelled request. */
    fun run(text: String, to: String, done: (TResult?, TError?) -> Unit) {
        cancel()
        val my = gen
        pool.execute {
            var res: TResult? = null
            var err: TError? = null
            try {
                res = translateAll(text, to, my)
            } catch (e: TError) {
                err = e
            } catch (e: Exception) {
                err = TError(TError.Kind.BAD_RESPONSE, "Something went wrong while translating.")
            }
            active = null
            if (my == gen && err?.kind != TError.Kind.CANCELLED) {
                main.post { if (my == gen) done(res, err) }
            }
        }
    }

    // ---------- work (background thread) ----------

    private fun alive(my: Int) {
        if (my != gen) throw TError(TError.Kind.CANCELLED, "Cancelled")
    }

    private fun translateAll(text: String, to: String, my: Int): TResult {
        val core = text.trim()
        if (core.isEmpty()) throw TError(TError.Kind.EMPTY, "Nothing to translate.")
        val lead = text.substring(0, text.indexOf(core))
        val trail = text.substring(lead.length + core.length)
        val r = try {
            runChunks(core, my, 0) { s -> google(s, to, my) }
        } catch (e: TError) {
            if (e.kind == TError.Kind.RATE || e.kind == TError.Kind.SERVER || e.kind == TError.Kind.BAD_RESPONSE) {
                runChunks(core, my, 1) { s -> myMemory(s, to, my) }
            } else {
                throw e
            }
        }
        return TResult(lead + r.text + trail, r.detected)
    }

    /** Splits [core] into pieces the provider accepts, translates them one after another and joins them back with the original spacing. */
    private fun runChunks(core: String, my: Int, provider: Int, one: (String) -> TResult): TResult {
        val wide = core.any { it.code > 127 }
        val max = if (provider == 1) 400 else if (wide) 600 else 1800
        val sb = StringBuilder()
        var detected: String? = null
        for (piece in chunk(core, max)) {
            alive(my)
            val l = piece.takeWhile { it.isWhitespace() }
            val t = piece.takeLastWhile { it.isWhitespace() }
            if (l.length == piece.length) {
                sb.append(piece)
                continue
            }
            val body = piece.substring(l.length, piece.length - t.length)
            val r = one(body)
            if (detected == null) detected = r.detected
            sb.append(l).append(r.text).append(t)
        }
        alive(my)
        return TResult(sb.toString(), detected)
    }

    /** Cuts [text] into pieces of at most [max] characters, at a line break, a sentence end or a space when possible. */
    internal fun chunk(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            var end = minOf(n, i + max)
            if (end < n) {
                val window = text.substring(i, end)
                var cut = window.lastIndexOf('\n')
                if (cut < max / 3) cut = sentenceEnd(window)
                if (cut < max / 3) cut = window.lastIndexOf(' ')
                if (cut > 0) {
                    end = i + cut + 1
                } else if (Character.isHighSurrogate(text[end - 1])) {
                    end--
                }
            }
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }

    private fun sentenceEnd(w: String): Int {
        for (k in w.length - 1 downTo 0) {
            val ch = w[k]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\u0964' || ch == '\u3002') return k
        }
        return -1
    }

    // ---------- providers ----------

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) ZetaBoard")
        return c
    }

    private fun mapIo(e: Exception): TError = when (e) {
        is TError -> e
        is SocketTimeoutException -> TError(TError.Kind.TIMEOUT, "The translation server took too long. Try again.")
        is UnknownHostException -> TError(TError.Kind.NETWORK, "No internet connection.")
        is IOException -> TError(TError.Kind.NETWORK, "Network problem. Check your connection.")
        else -> TError(TError.Kind.BAD_RESPONSE, "Unexpected response from the translation server.")
    }

    private fun readAll(c: HttpURLConnection, ok: Boolean): String {
        val s = if (ok) c.inputStream else (c.errorStream ?: return "")
        return s.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun google(text: String, to: String, my: Int): TResult {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + enc(to) +
            "&dt=t&ie=UTF-8&oe=UTF-8&q=" + enc(text)
        var c: HttpURLConnection? = null
        try {
            alive(my)
            c = open(url)
            active = c
            val code = c.responseCode
            if (code == 429) throw TError(TError.Kind.RATE, "Too many requests. Wait a moment and try again.")
            if (code == 400) throw TError(TError.Kind.UNSUPPORTED, "This language is not supported.")
            if (code != 200) throw TError(TError.Kind.SERVER, "Translation server error ($code).")
            val body = readAll(c, true).trim()
            if (!body.startsWith("[")) throw TError(TError.Kind.BAD_RESPONSE, "Unexpected response from the translation server.")
            val root = JSONArray(body)
            val segs = root.optJSONArray(0) ?: throw TError(TError.Kind.BAD_RESPONSE, "The server returned an empty translation.")
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                if (seg.isNull(0)) continue
                sb.append(seg.getString(0))
            }
            val out = sb.toString()
            if (out.isBlank()) throw TError(TError.Kind.BAD_RESPONSE, "The server returned an empty translation.")
            val det = if (root.length() > 2 && !root.isNull(2)) root.optString(2).takeIf { it.isNotBlank() } else null
            return TResult(out, det)
        } catch (e: Exception) {
            if (my != gen) throw TError(TError.Kind.CANCELLED, "Cancelled")
            throw mapIo(e)
        } finally {
            c?.disconnect()
        }
    }

    private fun myMemory(text: String, to: String, my: Int): TResult {
        val url = "https://api.mymemory.translated.net/get?q=" + enc(text) + "&langpair=" + enc("Autodetect|$to")
        var c: HttpURLConnection? = null
        try {
            alive(my)
            c = open(url)
            active = c
            val code = c.responseCode
            if (code == 429) throw TError(TError.Kind.RATE, "Daily free limit reached. Try again later.")
            if (code != 200) throw TError(TError.Kind.SERVER, "Translation server error ($code).")
            val j = JSONObject(readAll(c, true))
            val status = j.opt("responseStatus").toString().toIntOrNull() ?: 200
            if (status == 429) throw TError(TError.Kind.RATE, "Daily free limit reached. Try again later.")
            if (status != 200) throw TError(TError.Kind.SERVER, "Translation failed ($status).")
            val data = j.optJSONObject("responseData") ?: throw TError(TError.Kind.BAD_RESPONSE, "The server returned an empty translation.")
            val out = data.optString("translatedText")
            if (out.isBlank()) throw TError(TError.Kind.BAD_RESPONSE, "The server returned an empty translation.")
            if (out.contains("MYMEMORY WARNING", true)) throw TError(TError.Kind.RATE, "Daily free limit reached. Try again later.")
            if (out.startsWith("PLEASE SELECT TWO DISTINCT LANGUAGES", true)) throw TError(TError.Kind.UNSUPPORTED, "The text is already in that language.")
            val det = data.optString("detectedLanguage").takeIf { it.isNotBlank() && it != "null" }
            return TResult(out, det)
        } catch (e: Exception) {
            if (my != gen) throw TError(TError.Kind.CANCELLED, "Cancelled")
            throw mapIo(e)
        } finally {
            c?.disconnect()
        }
    }
}
