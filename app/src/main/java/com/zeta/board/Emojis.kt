package com.zeta.board

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface

/** Emoji data + helpers: curated categories, recents, skin tones and whole-emoji deletion. */
object Emojis {

    class Cat(val name: String, val icon: String, private val raw: String) {
        val all: List<String> by lazy { raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
    }

    const val RECENT = 0
    private const val RECENT_KEY = "emoji_recent"
    private const val RECENT_MAX = 32

    val cats: List<Cat> = listOf(
        Cat("FREQUENTLY USED", "\uD83D\uDD58", ""),
        Cat("SMILEYS & EMOTION", "\uD83D\uDE00", """
            😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 ☺️ 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🫡
            🤐 🤨 😐 😑 😶 🫥 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 😵‍💫 🤯 🤠 🥳
            🥸 😎 🤓 🧐 😕 🫤 😟 🙁 ☹️ 😮 😯 😲 😳 🥺 🥹 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡
            😠 🤬 😈 👿 💀 ☠️ 💩 🤡 👹 👺 👻 👽 👾 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊
            💋 💌 💘 💝 💖 💗 💓 💞 💕 💟 ❣️ 💔 ❤️‍🔥 ❤️‍🩹 ❤️ 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💯 💢 💥 💫 💦 💨 🕳️ 💬 💭 💤
        """),
        Cat("PEOPLE & BODY", "\uD83D\uDC4B", """
            👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏
            ✍️ 💅 🤳 💪 🦾 🦵 🦶 👂 👃 🧠 🫀 🫁 🦷 🦴 👀 👁️ 👅 👄
            👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 💂 🥷 👷 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🤱 👼
            🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🏃 💃 🕺 👯 🧘 👭 👫 👬 💏 💑 👪
            👣 👤 👥 🫂
        """),
        Cat("ANIMALS & NATURE", "\uD83D\uDC36", """
            🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🐒 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄
            🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🪳 🦟 🦗 🕷️ 🕸️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🪼 🐡 🐠 🐟 🐬 🐳 🐋 🦈
            🐊 🐅 🐆 🦓 🦍 🦧 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🦮 🐈 🐈‍⬛ 🪶 🐓 🦃 🦚
            🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁 🐀 🐿️ 🦔 🐾 🐉 🐲
            🌵 🎄 🌲 🌳 🌴 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🍄 🐚 🪸 🌾 💐 🌷 🌹 🥀 🪻 🌺 🌸 🌼 🌻
            🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 ⭐ 🌟 ✨ ⚡ ☄️ 🔥 🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️ ☁️
            🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💧 ☔ ☂️ 🌊 🌫️
        """),
        Cat("FOOD & DRINK", "\uD83C\uDF54", """
            🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🫒 🧄 🧅 🥔 🍠
            🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆 🌮 🌯 🫔 🥗 🥘 🫕 🥫 🍝 🍜
            🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯
            🥛 🍼 ☕ 🫖 🍵 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊 🥄 🍴 🍽️ 🥣 🥡 🥢 🧂
        """),
        Cat("ACTIVITY", "\u26BD", """
            ⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 ⛷️
            🏂 🪂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🏄 🏊 🤽 🚣 🧗 🚴 🚵 🎖️ 🏆 🏅 🥇 🥈 🥉 🎫 🎟️ 🎪 🤹 🎭 🎨 🎬 🎤 🎧
            🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩 🎈 🎉 🎊 🎁 🎀
        """),
        Cat("TRAVEL & PLACES", "\u2708\uFE0F", """
            🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🏍️ 🛵 🚲 🛴 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈
            🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ 🚧 ⛽ 🚏 🚦 🚥 🗺️ 🗿 🗽 🗼
            🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨
            🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🛕 🕍 ⛩️ 🕋 🌁 🌃 🏙️ 🌄 🌅 🌆 🌇 🌉 🎆 🎇 🌠
        """),
        Cat("OBJECTS", "\uD83D\uDCA1", """
            ⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 💽 💾 💿 📀 📷 📸 📹 🎥 📞 ☎️ 📟 📠 📺 📻 🎙️ ⏰ ⏱️ ⏲️ 🕰️ ⌛ ⏳ 📡 🔋 🪫
            🔌 💡 🔦 🕯️ 🧯 💸 💵 💴 💶 💷 🪙 💰 💳 💎 ⚖️ 🪜 🧰 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ 🧱 ⛓️ 🧲 💣 🧨 🪓 🔪 🗡️
            ⚔️ 🛡️ 🚬 ⚰️ 🏺 🔮 📿 🧿 💈 ⚗️ 🔭 🔬 🩹 🩺 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🧺 🧻 🚽 🚰 🚿 🛁 🧼 🪥 🧽
            🪣 🧴 🔑 🗝️ 🚪 🪑 🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒 🎏 🎎 🏮 🎐 ✉️ 📩 📨 📧 📦 🏷️ 📪 📫 📬 📭 📮 📜 📃 📄 📑
            🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️ 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷
            🔗 📎 🖇️ 📐 📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓
        """),
        Cat("SYMBOLS", "\u2764\uFE0F", """
            ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎
            ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ ☢️ ☣️ 📴 📳 🆚 💮 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 💯 💢 ♨️ 🚷 🚯 🚳
            🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔ ‼️ ⁉️ 🔅 🔆 〽️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 💹 ❇️ ✳️ ❎ 🌐 💠 Ⓜ️ 🌀 💤 🏧 🚾 ♿
            🅿️ 🛗 🛂 🛃 🛄 🛅 🚹 🚺 🚼 🚻 🚮 🎦 📶 🔣 ℹ️ 🔤 🔡 🔠 🆖 🆗 🆙 🆒 🆕 🆓
            0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔢 #️⃣ *️⃣ ⏏️ ▶️ ⏸️ ⏯️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ ⏫ ⏬ ◀️ 🔼 🔽
            ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ ↪️ ↩️ ⤴️ ⤵️ 🔀 🔁 🔂 🔄 🔃 🎵 🎶 ➕ ➖ ➗ ✖️ ♾️ 💲 💱 ™️ ©️ ®️
            🔚 🔙 🔛 🔝 🔜 ✔️ ☑️ 🔘 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ▪️ ▫️ ◾ ◽ ◼️ ◻️
            🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ 🟫 🔈 🔇 🔉 🔊 🔔 🔕 📣 📢 ♠️ ♣️ ♥️ ♦️ 🃏 🎴 🀄
            🕐 🕑 🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛
        """),
        Cat("FLAGS", "\uD83C\uDFC1", """
            🏁 🚩 🎌 🏴 🏳️ 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇱🇰 🇮🇳 🇵🇰 🇧🇩 🇳🇵 🇧🇹 🇲🇻 🇦🇫 🇺🇸 🇬🇧 🇨🇦 🇦🇺 🇳🇿 🇮🇪 🇫🇷 🇩🇪 🇮🇹 🇪🇸
            🇵🇹 🇳🇱 🇧🇪 🇨🇭 🇦🇹 🇸🇪 🇳🇴 🇩🇰 🇫🇮 🇮🇸 🇵🇱 🇨🇿 🇬🇷 🇹🇷 🇷🇺 🇺🇦 🇨🇳 🇯🇵 🇰🇷 🇰🇵 🇹🇼 🇭🇰 🇸🇬 🇲🇾
            🇹🇭 🇻🇳 🇮🇩 🇵🇭 🇦🇪 🇸🇦 🇶🇦 🇰🇼 🇴🇲 🇧🇭 🇮🇷 🇮🇶 🇮🇱 🇵🇸 🇱🇧 🇯🇴 🇪🇬 🇿🇦 🇳🇬 🇰🇪 🇪🇹 🇬🇭 🇲🇦 🇧🇷
            🇦🇷 🇲🇽 🇨🇴 🇨🇱 🇵🇪 🇨🇺 🇯🇲 🇺🇳 🇪🇺
        """)
    )

    // ---------- recents ----------

    fun recents(c: Context): List<String> =
        Prefs.getS(c, RECENT_KEY, "").split(' ').filter { it.isNotEmpty() }

    fun addRecent(c: Context, e: String) {
        val l = ArrayList<String>(recents(c))
        l.remove(e)
        l.add(0, e)
        while (l.size > RECENT_MAX) l.removeAt(l.size - 1)
        Prefs.setS(c, RECENT_KEY, l.joinToString(" "))
    }

    // ---------- skin tones ----------

    private val tones = intArrayOf(0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF)

    private fun stripVs(e: String) = e.replace("\uFE0F", "")

    /** Skin-tone versions of [e] that this device can really draw as ONE glyph. Empty when [e] has no tones. */
    fun toneVariants(e: String, paint: Paint): List<String> {
        if (e.length > 4 || e.contains('\u200D')) return emptyList() // only simple one-person emoji
        val base = stripVs(e)
        val first = base + String(Character.toChars(tones[0]))
        if (!paint.hasGlyph(first)) return emptyList()
        val out = ArrayList<String>()
        out.add(e)
        for (t in tones) {
            val v = base + String(Character.toChars(t))
            if (paint.hasGlyph(v)) out.add(v)
        }
        return if (out.size > 1) out else emptyList()
    }

    // ---------- deleting one whole emoji ----------

    private fun looksEmoji(s: CharSequence): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            if (cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 || cp in 0x1F000..0x1FAFF ||
                cp in 0x2190..0x2BFF || cp in 0x2300..0x23FF || cp == 0x00A9 || cp == 0x00AE ||
                cp == 0x2122 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
            ) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * How many UTF-16 chars to delete so a whole emoji (flag, family, skin tone, keycap, heart + variation
     * selector...) disappears in ONE tap. Normal letters (including Sinhala) still delete one character.
     */
    fun lastClusterLength(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val text = before.toString()
        val bi = android.icu.text.BreakIterator.getCharacterInstance()
        bi.setText(text)
        val end = text.length
        val start = bi.preceding(end)
        if (start == android.icu.text.BreakIterator.DONE || start < 0) return Character.charCount(text.codePointBefore(end))
        val cluster = text.substring(start, end)
        return if (looksEmoji(cluster)) cluster.length else Character.charCount(text.codePointBefore(end))
    }
}

/** Key font presets (how the keys look). Switch in the app or from the text-style panel on the keyboard. */
object KbFonts {
    val names = listOf("Default", "Clean", "Light", "Condensed", "Serif", "Mono", "Casual", "Cursive", "Black")

    private val families = listOf(
        "sans-serif-medium", "sans-serif", "sans-serif-light", "sans-serif-condensed",
        "serif", "monospace", "casual", "cursive", "sans-serif-black"
    )

    fun selected(c: Context): Int {
        var v = Prefs.getI(c, "keyfont", 0)
        return if (v in names.indices) v else 0
    }

    fun preset(i: Int): Typeface {
        return Typeface.create(families.getOrNull(i) ?: families[0], Typeface.NORMAL)
    }

    fun keyTypeface(c: Context): Typeface = preset(selected(c))
}

/** Fancy Unicode text styles: what you type is converted while typing (Latin letters only). */
object TextStyles {
    val names = listOf(
        "Normal", "Bold", "Bold Italic", "Sans Italic", "Sans Bold Italic", "Script",
        "Monospace", "Circled", "Squared", "Small Caps", "Canadian", "Flags"
    )

    private const val SMALL = "\u1D00\u0299\u1D04\u1D05\u1D07\uA730\u0262\u029C\u026A\u1D0A\u1D0B\u029F\u1D0D\u0274\u1D0F\u1D18\u01EB\u0280s\u1D1B\u1D1C\u1D20\u1D21x\u028F\u1D22"
    private const val CANADIAN = "\u15E9\u15F7\u1455\u15EAE\u1596G\u157CI\u148DK\u146A\u15F0\u144E" +
        "O\u146D\u146B\u1587\u054FT\u144C\u1431\u15EF\u166DY\u1614"

    fun sample(style: Int): String = convert(style, "Hy")

    /** True when [p] can draw this style's letters (checked per letter, so no empty boxes). */
    fun supported(style: Int, p: android.graphics.Paint): Boolean {
        if (style == 0) return true
        return listOf("H", "y").all { p.hasGlyph(convert(style, it).replace("\u200C", "")) }
    }

    fun convert(style: Int, s: String): String {
        if (style <= 0 || style >= names.size) return s
        val sb = StringBuilder(s.length * 2)
        for (ch in s) sb.append(map(style, ch))
        return sb.toString()
    }

    private fun map(style: Int, ch: Char): String {
        val up = ch in 'A'..'Z'
        val lo = ch in 'a'..'z'
        if (!up && !lo) return ch.toString()
        val i = if (up) ch - 'A' else ch - 'a'
        fun cp(base: Int) = String(Character.toChars(base + i))
        return when (style) {
            1 -> cp(if (up) 0x1D400 else 0x1D41A)
            2 -> cp(if (up) 0x1D468 else 0x1D482)
            3 -> cp(if (up) 0x1D608 else 0x1D622)
            4 -> cp(if (up) 0x1D63C else 0x1D656)
            5 -> cp(if (up) 0x1D4D0 else 0x1D4EA)
            6 -> cp(if (up) 0x1D670 else 0x1D68A)
            7 -> cp(if (up) 0x24B6 else 0x24D0)
            8 -> cp(0x1F130)
            9 -> if (up) ch.toString() else SMALL[i].toString()
            10 -> CANADIAN[i].toString()
            11 -> cp(0x1F1E6) + "\u200C" // zero-width non-joiner stops two letters becoming a flag
            else -> ch.toString()
        }
    }
}
