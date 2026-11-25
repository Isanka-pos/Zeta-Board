package com.zeta.board

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

/**
 * Protect: turns a message into text that looks empty and back.
 *
 * The message is turned into bytes (UTF-8), scrambled with a small per-message key, given a check code and then written
 * with four invisible characters (two bits each). The result starts with a blank Braille cell so chat apps do not
 * throw the message away as "empty". Anyone without Zeta Board just sees a blank message.
 *
 * This hides a message, it is not strong encryption.
 *
 * Nothing here ever throws: [decode] answers null for anything that is not a valid protected message.
 */
object Protect {

    /** The four invisible characters (zero width space, non-joiner, joiner, word joiner). */
    private val SYM = charArrayOf('​', '‌', '‍', '⁠')

    /** A blank-looking but real character: keeps the message from being treated as empty. */
    private const val COVER = '⠀'

    private const val MAGIC_A: Int = 0x5A
    private const val MAGIC_B: Int = 0x42

    /** Longest message that can be protected, in bytes of UTF-8 (about 650 Sinhala letters or 2000 English letters). */
    const val MAX_BYTES = 2000

    /** The most invisible characters a protected message can hold (anything longer is not read). */
    private const val MAX_RUN = (MAX_BYTES + 8) * 4

    sealed class Result {
        class Ok(val text: String) : Result()
        class Fail(val reason: String) : Result()
    }

    private fun symIndex(c: Char): Int = when (c) {
        '​' -> 0
        '‌' -> 1
        '‍' -> 2
        '⁠' -> 3
        else -> -1
    }

    /** True when [s] contains a run of invisible symbols long enough to be a protected message (cheap check, no decoding). */
    fun has(s: String?): Boolean {
        if (s == null || s.length < 20) return false
        var run = 0
        for (ch in s) {
            if (symIndex(ch) >= 0) {
                run++
                if (run >= 20) return true
            } else {
                run = 0
            }
        }
        return false
    }

    /** Scrambles / unscrambles [data] in place with a key stream made from [salt]. */
    private fun scramble(data: ByteArray, salt: Int) {
        var x = (salt * 0x9E3779B1.toInt()) xor 0x5A17C3D1
        for (i in data.indices) {
            x = x * 1664525 + 1013904223
            data[i] = (data[i].toInt() xor (x ushr 24)).toByte()
        }
    }

    private fun check(plain: ByteArray): Int {
        val crc = CRC32()
        crc.update(plain, 0, plain.size)
        return (crc.value and 0xFFFFL).toInt()
    }

    /** Makes the protected (invisible) form of [message]. */
    fun encode(message: String): Result {
        if (message.isEmpty()) return Result.Fail("Type a message first")
        val plain = try {
            message.toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            return Result.Fail("Could not protect this text")
        }
        if (plain.size > MAX_BYTES) return Result.Fail("Too long to protect")
        val salt = (System.nanoTime() xor (System.nanoTime() ushr 7)).toInt() and 0xFF
        val body = plain.copyOf()
        scramble(body, salt)
        val sum = check(plain)

        // layout: magic(2) salt(1) length(2) scrambled text check(2)
        val total = 2 + 1 + 2 + body.size + 2
        val bytes = ByteArray(total)
        bytes[0] = MAGIC_A.toByte()
        bytes[1] = MAGIC_B.toByte()
        bytes[2] = salt.toByte()
        bytes[3] = ((body.size ushr 8) and 0xFF).toByte()
        bytes[4] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, bytes, 5, body.size)
        bytes[5 + body.size] = ((sum ushr 8) and 0xFF).toByte()
        bytes[6 + body.size] = (sum and 0xFF).toByte()

        val sb = StringBuilder(1 + total * 4)
        sb.append(COVER)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(SYM[(v ushr 6) and 3])
            sb.append(SYM[(v ushr 4) and 3])
            sb.append(SYM[(v ushr 2) and 3])
            sb.append(SYM[v and 3])
        }
        return Result.Ok(sb.toString())
    }

    /**
     * The hidden message inside [s], or null when there is none (or it is damaged). Other text around the protected part is
     * ignored, and if several protected parts are there the first valid one wins.
     */
    fun decode(s: String?): String? {
        if (!has(s)) return null
        val str = s ?: return null
        try {
            var i = 0
            val n = str.length
            while (i < n) {
                if (symIndex(str[i]) < 0) {
                    i++
                    continue
                }
                var j = i
                while (j < n && symIndex(str[j]) >= 0) j++
                val runLen = j - i
                if (runLen in 20..MAX_RUN) {
                    val r = decodeRun(str, i, runLen)
                    if (r != null) return r
                }
                i = j
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    private fun decodeRun(s: String, start: Int, len: Int): String? {
        val count = len / 4
        if (count < 7) return null
        val bytes = ByteArray(count)
        for (k in 0 until count) {
            val o = start + k * 4
            val v = (symIndex(s[o]) shl 6) or (symIndex(s[o + 1]) shl 4) or (symIndex(s[o + 2]) shl 2) or symIndex(s[o + 3])
            bytes[k] = v.toByte()
        }
        if ((bytes[0].toInt() and 0xFF) != MAGIC_A || (bytes[1].toInt() and 0xFF) != MAGIC_B) return null
        val salt = bytes[2].toInt() and 0xFF
        val size = ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        if (size <= 0 || size > MAX_BYTES || 5 + size + 2 > count) return null
        val body = ByteArray(size)
        System.arraycopy(bytes, 5, body, 0, size)
        scramble(body, salt)
        val sum = ((bytes[5 + size].toInt() and 0xFF) shl 8) or (bytes[6 + size].toInt() and 0xFF)
        if (sum != check(body)) return null
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = dec.decode(ByteBuffer.wrap(body)).toString()
            if (text.isBlank()) null else text
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
