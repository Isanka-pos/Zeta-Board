package com.zeta.board

import android.content.Context

/**
 * English word completion and next-word prediction for the keyboard top bar.
 * Everything is learned and stored on this phone only (SharedPreferences), never sent anywhere.
 */
object EnSuggest {

    private const val PREFS = "zeta_en_suggest"
    private const val KEY_WORDS = "words"
    private const val KEY_PAIRS = "pairs"
    private const val MAX_WORDS = 3000
    private const val MAX_PAIRS = 4000

    private val lock = Any()
    private var loaded = false
    private var dirty = false
    private var learnedSinceFlush = 0

    /** words the user typed: lowercase word -> times used */
    private val words = HashMap<String, Int>()

    /** word pairs the user typed: "prev next" -> times used */
    private val pairs = HashMap<String, Int>()

    private val base = (
        "the be to of and a in that have i it for not on with he as you do at this but his by from they we say her she or an " +
            "will my one all would there their what so up out if about who get which go me when make can like time no just him " +
            "know take people into year your good some could them see other than then now look only come its over think also " +
            "back after use two how our work first well way even new want because any these give day most us is are was were " +
            "been has had did does done said went going gone thanks thank please hello hi hey okay yes sorry welcome morning " +
            "night evening today tomorrow yesterday tonight later soon again always never maybe really very much many more " +
            "should would could might must need needs help helps love loved like liked friend friends family brother sister " +
            "mother father home house school office class phone message messages call called calling number video photo " +
            "picture send sent sending receive received online offline free busy ready coming come came where here there " +
            "why what when who whom whose which while until before after during between because though although however " +
            "better best great nice happy sad angry tired hungry thirsty sleep sleeping eat eating drink drinking food water " +
            "money price cost pay paid buy bought sell sold shop market bank card account password login logout update " +
            "download upload install open close start stop begin end finish wait waiting meet meeting remember forget " +
            "think thought understand understood believe feel felt tell told ask asked answer question problem issue " +
            "number name email address place country city town road street bus train car bike walk run running travel " +
            "birthday party wedding holiday weekend monday tuesday wednesday thursday friday saturday sunday january " +
            "february march april may june july august september october november december goodnight " +
            "awesome amazing beautiful cool crazy funny lovely perfect super sure true real right wrong left " +
            "something anything everything nothing someone anyone everyone nobody somewhere anywhere everywhere " +
            "because actually probably definitely already still just maybe together another different important " +
            "congratulations appreciate available absolutely accept account address advice afternoon agree allow almost " +
            "alone along already although among amount anyway around arrive attention available aware beautiful become " +
            "believe below beside beyond birthday bring brother business careful certain change check choose class clean " +
            "clear college company complete continue conversation correct create current decide different difficult " +
            "discuss early easy enough enjoy entire especially example excellent exactly expect experience explain " +
            "fantastic favorite finally follow forward general happen hospital hundred idea include information instead " +
            "interested interesting invite join keep kind language learn leave listen little live long lunch dinner " +
            "breakfast mind minute miss moment month move near next office often order outside parents past person " +
            "plan play point possible prepare present pretty program project promise provide quick quite reach read " +
            "reason receive reply report result return safe same save season second send serious service share short " +
            "show simple since small social someone special speak spend student study success suddenly suggest support " +
            "teacher team test thing thousand through together town try turn usually visit voice wish without woman " +
            "world write written young"
        ).split(' ').filter { it.length > 1 || it == "a" || it == "i" }.distinct()

    /** common ways a sentence continues when there is nothing learned yet */
    private val starters = listOf("the", "i", "you", "to", "and", "is", "it", "a", "of", "in")

    private val basePairs: Map<String, List<String>> = mapOf(
        "i" to listOf("am", "will", "have", "think", "love"),
        "you" to listOf("are", "can", "have", "will", "know"),
        "thank" to listOf("you"),
        "thanks" to listOf("for", "a", "so"),
        "how" to listOf("are", "is", "do", "about", "much"),
        "what" to listOf("is", "are", "do", "time", "about"),
        "where" to listOf("are", "is", "do", "did"),
        "good" to listOf("morning", "night", "evening", "luck", "afternoon"),
        "see" to listOf("you", "me", "the"),
        "i'm" to listOf("not", "going", "sorry", "fine", "so"),
        "let" to listOf("me", "us", "you"),
        "do" to listOf("you", "not", "it", "we"),
        "are" to listOf("you", "we", "they", "there"),
        "is" to listOf("it", "this", "the", "there", "not"),
        "it" to listOf("is", "was", "will", "has"),
        "to" to listOf("the", "be", "do", "go", "see"),
        "in" to listOf("the", "a", "my", "your"),
        "of" to listOf("the", "course", "my", "your"),
        "on" to listOf("the", "my", "your", "a"),
        "can" to listOf("you", "i", "we", "not"),
        "will" to listOf("be", "you", "do", "see"),
        "please" to listOf("send", "tell", "let", "call"),
        "happy" to listOf("birthday", "new", "to"),
        "see" to listOf("you", "me", "the"),
        "call" to listOf("me", "you", "back"),
        "miss" to listOf("you", "u")
    )

    /** Loads the saved words. Safe to call many times. Run when the keyboard starts so the first suggestion is instant. */
    fun preload(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                sp.getString(KEY_WORDS, null)?.split('\n')?.forEach { line ->
                    val t = line.indexOf('\t')
                    if (t > 0) {
                        val n = line.substring(t + 1).toIntOrNull() ?: return@forEach
                        words[line.substring(0, t)] = n
                    }
                }
                sp.getString(KEY_PAIRS, null)?.split('\n')?.forEach { line ->
                    val t = line.lastIndexOf('\t')
                    if (t > 0) {
                        val n = line.substring(t + 1).toIntOrNull() ?: return@forEach
                        pairs[line.substring(0, t)] = n
                    }
                }
            } catch (e: Exception) {
                words.clear()
                pairs.clear()
            }
        }
    }

    /** Up to [max] words that start with [prefix] (what the user uses most first). */
    fun complete(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val low = prefix.lowercase()
        val upperFirst = prefix[0].isUpperCase()
        val allCaps = prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() }
        val found = ArrayList<Pair<String, Int>>()
        synchronized(lock) {
            for ((w, n) in words) {
                if (w.length > low.length && w.startsWith(low)) found.add(w to (n * 10 + 1000))
            }
            for ((i, w) in base.withIndex()) {
                if (w.length > low.length && w.startsWith(low) && !words.containsKey(w)) {
                    found.add(w to (500 - minOf(i, 499)))
                }
            }
        }
        found.sortWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length })
        val out = ArrayList<String>()
        for ((w, _) in found) {
            val shown = when {
                allCaps -> w.uppercase()
                upperFirst -> w.replaceFirstChar { it.uppercaseChar() }
                else -> w
            }
            if (!out.contains(shown)) out.add(shown)
            if (out.size >= max) break
        }
        return out
    }

    /** Up to [max] words that usually come after [prev]. */
    fun next(prev: String, max: Int): List<String> {
        val p = prev.lowercase()
        val found = ArrayList<Pair<String, Int>>()
        synchronized(lock) {
            val prefix = "$p "
            for ((k, n) in pairs) {
                if (k.startsWith(prefix)) found.add(k.substring(prefix.length) to (n * 10 + 1000))
            }
        }
        basePairs[p]?.forEachIndexed { i, w ->
            if (found.none { it.first == w }) found.add(w to (500 - i))
        }
        if (found.isEmpty()) return emptyList()
        found.sortByDescending { it.second }
        val out = ArrayList<String>()
        for ((w, _) in found) {
            if (!out.contains(w)) out.add(w)
            if (out.size >= max) break
        }
        if (out.size < max && out.size < 3) {
            for (s in starters) {
                if (!out.contains(s)) out.add(s)
                if (out.size >= 3) break
            }
        }
        return out
    }

    /** Remembers a finished word, and which word came before it. */
    fun learn(word: String, prev: String?) {
        val w = word.lowercase().trim('\'')
        if (w.length < 2 || w.length > 30) return
        if (!w.all { it.isLetter() || it == '\'' }) return
        synchronized(lock) {
            words[w] = (words[w] ?: 0) + 1
            if (prev != null && prev.isNotEmpty()) {
                val k = prev.lowercase() + " " + w
                pairs[k] = (pairs[k] ?: 0) + 1
            }
            dirty = true
            learnedSinceFlush++
            if (words.size > MAX_WORDS + 200) trim(words, MAX_WORDS)
            if (pairs.size > MAX_PAIRS + 200) trim(pairs, MAX_PAIRS)
        }
    }

    private fun trim(map: HashMap<String, Int>, keep: Int) {
        val sorted = map.entries.sortedByDescending { it.value }
        map.clear()
        for (e in sorted.take(keep)) map[e.key] = e.value
    }

    /** How many words are remembered on this phone. */
    fun learnedCount(): Int = synchronized(lock) { words.size }

    /** Saves what was learned. Called when a text field closes. */
    fun flush(context: Context) {
        val wordsText: String
        val pairsText: String
        synchronized(lock) {
            if (!dirty) return
            dirty = false
            learnedSinceFlush = 0
            wordsText = words.entries.joinToString("\n") { it.key + "\t" + it.value }
            pairsText = pairs.entries.joinToString("\n") { it.key + "\t" + it.value }
        }
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_WORDS, wordsText)
                .putString(KEY_PAIRS, pairsText)
                .apply()
        } catch (e: Exception) {
        }
    }

    /** Forgets every learned word (Smart typing -> Clear learned words). */
    fun clearLearned(context: Context) {
        synchronized(lock) {
            words.clear()
            pairs.clear()
            dirty = false
            loaded = true
        }
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (e: Exception) {
        }
    }
}
