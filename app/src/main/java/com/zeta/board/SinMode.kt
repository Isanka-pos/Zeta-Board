package com.zeta.board

import android.content.Context

/**
 * Which Sinhala typing is on: none (English keys), phonetic (Singlish: type mama, get මම) or Wijesekara
 * (the standard Sinhala typewriter layout, Sinhala letters on the keys). Only one of them is ever on.
 */
object SinMode {
    const val OFF = 0
    const val PHONETIC = 1
    const val WIJESEKARA = 2

    /** Wijesekara wins if both old switches are somehow on, so the keys and the typing always agree. */
    fun current(c: Context): Int = when {
        Prefs.getB(c, "wije", false) -> WIJESEKARA
        Prefs.getB(c, "singlish", false) -> PHONETIC
        else -> OFF
    }

    /** Switches to [mode] and remembers the layout (phonetic / Wijesekara) for the quick on/off key. */
    fun set(c: Context, mode: Int) {
        val m = if (mode == PHONETIC || mode == WIJESEKARA) mode else OFF
        Prefs.setB(c, "singlish", m == PHONETIC)
        Prefs.setB(c, "wije", m == WIJESEKARA)
        if (m != OFF) Prefs.setI(c, "sin_layout", m)
    }

    /** The layout the quick on/off key switches on: the last one used, phonetic at first. */
    fun lastLayout(c: Context): Int = if (Prefs.getI(c, "sin_layout", PHONETIC) == WIJESEKARA) WIJESEKARA else PHONETIC

    /** English -> phonetic -> Wijesekara -> English. */
    fun next(mode: Int): Int = when (mode) {
        OFF -> PHONETIC
        PHONETIC -> WIJESEKARA
        else -> OFF
    }
}

/**
 * The Wijesekara layout on a phone keyboard, written the way Unicode stores Sinhala: the consonant first, then its
 * vowel sign (kombuva too). Taken from the standard Sinhala keyboard (SLS 1134) positions of the letter keys.
 */
object Wijesekara {
    /** One key: the letter, the Shift letter and the extra letters shown when the key is held. */
    class Letter(val n: String, val s: String, val extra: List<String> = emptyList())

    private fun k(n: String, s: String, vararg extra: String) = Letter(n, s, extra.toList())

    /** Keys q w e r t y u i o p */
    val top: List<Letter> = listOf(
        k("\u0DD4", "\u0DD6"),
        k("\u0D85", "\u0D8B", "\u0D86", "\u0D87", "\u0D88", "\u0D8C"),
        k("\u0DD0", "\u0DD1"),
        k("\u0DBB", "\u0D8D", "\u0DCA\u200D\u0DBB", "\u0DBB\u0DCA\u200D", "\u0D8E", "\u0DF2"),
        k("\u0D91", "\u0D94", "\u0D92", "\u0D93", "\u0D95", "\u0D96"),
        k("\u0DC4", "\u0DC1"),
        k("\u0DB8", "\u0DB9"),
        k("\u0DC3", "\u0DC2"),
        k("\u0DAF", "\u0DB0", "\u0DB3"),
        k("\u0DA0", "\u0DA1", "\u0DA4", "\u0DA5")
    )

    /** Keys a s d f g h j k l ; */
    val home: List<Letter> = listOf(
        k("\u0DCA", "\u0DDF", "\u0DF3"),
        k("\u0DD2", "\u0DD3"),
        k("\u0DCF", "\u0DD8"),
        k("\u0DD9", "\u0DC6", "\u0DDA", "\u0DDB", "\u0DDC", "\u0DDD", "\u0DDE"),
        k("\u0DA7", "\u0DA8"),
        k("\u0DBA", "\u0DCA\u200D\u0DBA"),
        k("\u0DC0", "\u0DC5\u0DD4"),
        k("\u0DB1", "\u0DAB"),
        k("\u0D9A", "\u0D9B"),
        k("\u0DAD", "\u0DAE")
    )

    /** Keys x c v b n m , . */
    val bottom: List<Letter> = listOf(
        k("\u0D82", "\u0D9E", "\u0D83"),
        k("\u0DA2", "\u0DA3", "\u0DA6"),
        k("\u0DA9", "\u0DAA", "\u0DAC"),
        k("\u0D89", "\u0D8A"),
        k("\u0DB6", "\u0DB7"),
        k("\u0DB4", "\u0DB5"),
        k("\u0DBD", "\u0DC5", "\u0D8F", "\u0D90"),
        k("\u0D9C", "\u0D9D", "\u0D9F")
    )

    /**
     * Kombuva signs that join into one sign when typed one after the other (kombuva + kombuva = ෛ, kombuva + aela-pilla = ො,
     * kombuva + al-lakuna = ේ, kombuva + gayanukitta = ෞ, ො + al-lakuna = ෝ). Returns null when [prev] and [c] do not join.
     */
    fun combine(prev: Char, c: Char): Char? = when {
        prev == '\u0DD9' && c == '\u0DD9' -> '\u0DDB'
        prev == '\u0DD9' && c == '\u0DCF' -> '\u0DDC'
        prev == '\u0DD9' && c == '\u0DCA' -> '\u0DDA'
        prev == '\u0DD9' && c == '\u0DDF' -> '\u0DDE'
        prev == '\u0DDC' && c == '\u0DCA' -> '\u0DDD'
        else -> null
    }
}
