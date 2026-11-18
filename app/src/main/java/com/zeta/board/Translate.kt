package com.zeta.board

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.Executors

/** A language the provider can translate. [code] "auto" means detect the source language. */
class Lang(val code: String, val name: String, val native: String)

object Langs {
    val AUTO = Lang("auto", "Auto Detect", "Auto")
    val all: List<Lang> = listOf(
        Lang("si", "Sinhala", "\u0DC3\u0DD2\u0D82\u0DC4\u0DBD"),
        Lang("en", "English", "English"),
        Lang("ta", "Tamil", "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD"),
        Lang("hi", "Hindi", "\u0939\u093F\u0928\u094D\u0926\u0940"),
        Lang("ja", "Japanese", "\u65E5\u672C\u8A9E"),
        Lang("ko", "Korean", "\uD55C\uAD6D\uC5B4"),
        Lang("zh", "Chinese", "\u4E2D\u6587"),
        Lang("fr", "French", "Fran\u00E7ais"),
        Lang("de", "German", "Deutsch"),
        Lang("es", "Spanish", "Espa\u00F1ol"),
        Lang("ar", "Arabic", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"),
        Lang("ru", "Russian", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"),
        Lang("it", "Italian", "Italiano"),
        Lang("pt", "Portuguese", "Portugu\u00EAs")
    )

    fun byCode(c: String): Lang? = if (c == "auto") AUTO else all.firstOrNull { it.code == c }
}

class TResult(val text: String, val detected: String?)

/** Failure with a message that is safe to show to the user (never contains the translated text). */
class TError(val kind: Kind, msg: String) : Exception(msg) {
    enum class Kind { SETUP, EMPTY, NETWORK, TIMEOUT, RATE, UNSUPPORTED, BAD_RESPONSE, SERVER }
}

interface TranslateProvider {
    val id: String
    val label: String

    /** Languages this provider really supports (codes). Others are never shown. */
    fun supports(code: String): Boolean

    /** Blocking call. Run it off the main thread. Throws [TError]. */
    fun translate(text: String, from: String, to: String, conn: (HttpURLConnection) -> Unit): TResult
}

private fun readAll(c: HttpURLConnection, ok: Boolean): String {
    val s = if (ok) c.inputStream else (c.errorStream ?: return "")
    return s.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

private fun mapIo(e: Exception): TError = when (e) {
    is TError -> e
    is SocketTimeoutException -> TError(TError.Kind.TIMEOUT, "The translation server took too long. Try again.")
    is UnknownHostException -> TError(TError.Kind.NETWORK, "No internet connection.")
    is IOException -> TError(TError.Kind.NETWORK, "Network problem. Check your connection.")
    else -> TError(TError.Kind.BAD_RESPONSE, "Unexpected response from the translation server.")
}

private fun open(url: String, post: Boolean): HttpURLConnection {
    if (!url.startsWith("https://", true)) throw TError(TError.Kind.SETUP, "Translation endpoint must use HTTPS.")
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 15000
    c.requestMethod = if (post) "POST" else "GET"
    c.setRequestProperty("Accept", "application/json")
    c.setRequestProperty("User-Agent", "ZetaBoard")
    return c
}

private fun checkCode(c: HttpURLConnection) {
    when (val code = c.responseCode) {
        200 -> {}
        429 -> throw TError(TError.Kind.RATE, "Rate limit reached. Wait a moment, or set your own endpoint in Zeta Board settings.")
        400, 422 -> throw TError(TError.Kind.UNSUPPORTED, "This language pair is not supported by the provider.")
        401, 403 -> throw TError(TError.Kind.SETUP, "The translation server rejected the API key. Check Translate settings.")
        else -> throw TError(TError.Kind.SERVER, "Translation server error ($code).")
    }
}

/** Free public MyMemory API. No key needed (about 5,000 characters/day per IP; an optional email raises it). */
class MyMemoryProvider(private val ctx: Context) : TranslateProvider {
    override val id = "mymemory"
    override val label = "MyMemory (free, no key)"
    override fun supports(code: String) = code == "auto" || Langs.byCode(code) != null

    private fun mm(code: String) = if (code == "zh") "zh-CN" else code

    override fun translate(text: String, from: String, to: String, conn: (HttpURLConnection) -> Unit): TResult {
        if (text.length > 450) throw TError(TError.Kind.UNSUPPORTED, "Free provider limit is 450 characters per request. Shorten the text or set your own endpoint.")
        val src = if (from == "auto") "Autodetect" else mm(from)
        val email = Prefs.getS(ctx, "tr_email", "").trim()
        var u = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text, "UTF-8") +
            "&langpair=" + URLEncoder.encode("$src|${mm(to)}", "UTF-8")
        if (email.isNotEmpty()) u += "&de=" + URLEncoder.encode(email, "UTF-8")
        var c: HttpURLConnection? = null
        try {
            c = open(u, false)
            conn(c)
            val ok = c.responseCode == 200
            val body = readAll(c, ok)
            if (!ok) checkCode(c)
            val j = JSONObject(body)
            val status = j.opt("responseStatus").toString().toIntOrNull() ?: 200
            if (status == 429) throw TError(TError.Kind.RATE, "Daily free limit reached. Try later or set your own endpoint.")
            if (status != 200) {
                val det = j.optString("responseDetails")
                if (det.contains("SUPPORTED", true) || det.contains("INVALID", true))
                    throw TError(TError.Kind.UNSUPPORTED, "This language pair is not supported by the provider.")
                throw TError(TError.Kind.SERVER, "Translation failed ($status).")
            }
            val out = j.getJSONObject("responseData").optString("translatedText")
            if (out.isBlank()) throw TError(TError.Kind.BAD_RESPONSE, "The server returned an empty translation.")
            if (out.startsWith("PLEASE SELECT TWO DISTINCT LANGUAGES", true))
                throw TError(TError.Kind.UNSUPPORTED, "Source and target languages are the same.")
            if (out.contains("MYMEMORY WARNING", true))
                throw TError(TError.Kind.RATE, "Daily free limit reached. Try later or set your own endpoint.")
            val det = j.optJSONObject("responseData")?.optString("detectedLanguage")?.takeIf { it.isNotBlank() && it != "null" }
            return TResult(out, det)
        } catch (e: Exception) {
            throw mapIo(e)
        } finally {
            c?.disconnect()
        }
    }
}

/** Any LibreTranslate-compatible server (self-hosted is free). Endpoint and optional key come from settings. */
class LibreProvider(private val ctx: Context) : TranslateProvider {
    override val id = "libre"
    override val label = "LibreTranslate-compatible (custom)"
    override fun supports(code: String) = code == "auto" || Langs.byCode(code) != null

    override fun translate(text: String, from: String, to: String, conn: (HttpURLConnection) -> Unit): TResult {
        val base = Prefs.getS(ctx, "tr_url", "").trim().trimEnd('/')
        if (base.isEmpty()) throw TError(TError.Kind.SETUP, "Set your translation endpoint in Zeta Board \u2192 Translate settings.")
        val key = Prefs.getS(ctx, "tr_key", "").trim()
        var c: HttpURLConnection? = null
        try {
            c = open("$base/translate", true)
            conn(c)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("q", text).put("source", from).put("target", to).put("format", "text")
            if (key.isNotEmpty()) body.put("api_key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val ok = c.responseCode == 200
            val resp = readAll(c, ok)
            if (!ok) checkCode(c)
            val j = JSONObject(resp)
            val out = j.optString("translatedText")
            if (out.isBlank()) throw TError(TError.Kind.BAD_RESPONSE, "The server returned an empty translation.")
            val det = j.optJSONObject("detectedLanguage")?.optString("language")?.takeIf { it.isNotBlank() }
            return TResult(out, det)
        } catch (e: Exception) {
            throw mapIo(e)
        } finally {
            c?.disconnect()
        }
    }
}

/** Runs translations on one background thread and drops outdated requests. */
object Translator {
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "zeta-translate").also { it.isDaemon = true } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var gen = 0
    @Volatile private var active: HttpURLConnection? = null

    fun provider(ctx: Context): TranslateProvider =
        if (Prefs.getS(ctx, "tr_provider", "mymemory") == "libre") LibreProvider(ctx.applicationContext) else MyMemoryProvider(ctx.applicationContext)

    /** Languages that the active provider supports. */
    fun languages(ctx: Context): List<Lang> { val p = provider(ctx); return Langs.all.filter { p.supports(it.code) } }

    fun cancel() { gen++; try { active?.disconnect() } catch (e: Exception) {} }

    fun run(ctx: Context, text: String, from: String, to: String, done: (TResult?, TError?) -> Unit) {
        val clean = text.trim()
        if (clean.isEmpty()) { done(null, TError(TError.Kind.EMPTY, "Type or paste some text first.")); return }
        if (from != "auto" && from == to) { done(null, TError(TError.Kind.UNSUPPORTED, "Source and target languages are the same.")); return }
        cancel()
        val my = gen
        val p = provider(ctx)
        pool.execute {
            var res: TResult? = null
            var err: TError? = null
            try {
                res = p.translate(clean, from, to) { active = it }
            } catch (e: TError) {
                err = e
            } catch (e: Exception) {
                err = TError(TError.Kind.BAD_RESPONSE, "Unexpected error.")
            }
            active = null
            if (my == gen) main.post { if (my == gen) done(res, err) }
        }
    }
}

class THistItem(val id: Long, val src: String, val dst: String, val from: String, val to: String)

/** Local-only history (SharedPreferences JSON). Off when the "tr_hist" setting is off. */
object TrHistory {
    private const val KEY = "tr_history"
    private const val MAX = 100
    fun enabled(c: Context) = Prefs.getB(c, "tr_hist", true)

    fun load(c: Context): MutableList<THistItem> {
        val out = ArrayList<THistItem>()
        try {
            val a = JSONArray(Prefs.getS(c, KEY, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(THistItem(o.getLong("id"), o.getString("s"), o.getString("d"), o.getString("f"), o.getString("t")))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun save(c: Context, l: List<THistItem>) {
        val a = JSONArray()
        for (h in l) a.put(JSONObject().put("id", h.id).put("s", h.src).put("d", h.dst).put("f", h.from).put("t", h.to))
        Prefs.setS(c, KEY, a.toString())
    }

    fun add(c: Context, h: THistItem) {
        if (!enabled(c)) return
        val l = load(c)
        l.removeAll { it.src == h.src && it.dst == h.dst && it.to == h.to }
        l.add(0, h)
        while (l.size > MAX) l.removeAt(l.size - 1)
        save(c, l)
    }

    fun delete(c: Context, id: Long) { val l = load(c); l.removeAll { it.id == id }; save(c, l) }
    fun clear(c: Context) = Prefs.setS(c, KEY, "[]")
}
