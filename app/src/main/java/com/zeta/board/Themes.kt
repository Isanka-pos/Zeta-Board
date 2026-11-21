package com.zeta.board

import android.content.Context
import android.graphics.Color

class KbTheme(
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val key: Int,
    val special: Int,
    val text: Int,
    val accent: Int,
    val accentText: Int,
    /** Live effect drawn behind the keys: 0 = none, 1 = falling binary code (hacker rain). */
    val fx: Int = 0
)

/** A theme that lives in the Theme Store. [cat] is the store tab it is listed under. */
class StoreTheme(val cat: String, val theme: KbTheme)

object Themes {
    private fun c(s: String) = Color.parseColor(s)

    private fun t(n: String, top: String, bottom: String, key: String, sp: String, tx: String, ac: String, acTx: String, fx: Int = 0) =
        KbTheme(n, c(top), c(bottom), c(key), c(sp), c(tx), c(ac), c(acTx), fx)

    /** The themes every user has from the start. */
    private val builtin: List<KbTheme> = listOf(
        KbTheme("Midnight", c("#1A1033"), c("#0D0820"), c("#2B2050"), c("#3B2D68"), c("#F1EEFF"), c("#8B5CF6"), c("#FFFFFF")),
        KbTheme("Aurora", c("#062A2F"), c("#0B1B3A"), c("#123E47"), c("#1B5560"), c("#E6FFFB"), c("#22D3EE"), c("#04222A")),
        KbTheme("Sakura", c("#FFE4EE"), c("#FFD0E0"), c("#FFFFFF"), c("#FFB8D1"), c("#4A1D33"), c("#EC4899"), c("#FFFFFF")),
        KbTheme("Ocean", c("#0B3D91"), c("#051E4D"), c("#1556B8"), c("#0E3C86"), c("#FFFFFF"), c("#38BDF8"), c("#03213A")),
        KbTheme("Amoled", c("#000000"), c("#000000"), c("#151515"), c("#232323"), c("#FFFFFF"), c("#22C55E"), c("#03200E")),
        KbTheme("Sunset", c("#3B0A45"), c("#1D0A2E"), c("#5B1A5C"), c("#7A2468"), c("#FFE9F3"), c("#FB923C"), c("#2A1200")),
        KbTheme("Clean", c("#F3F4F8"), c("#E5E7EF"), c("#FFFFFF"), c("#D4D8E4"), c("#1B1E2B"), c("#4F46E5"), c("#FFFFFF")),
        KbTheme("Neon", c("#0A0014"), c("#12002B"), c("#1B0A3A"), c("#2A0F57"), c("#E0FFFF"), c("#FF2BD6"), c("#FFFFFF")),
        KbTheme("Forest", c("#0F2B1A"), c("#06150C"), c("#1B4430"), c("#245A3F"), c("#E8FFF1"), c("#A3E635"), c("#0B1A0F"))
    )

    /** Number of themes everybody owns. The saved "theme" number is a position in [all], so never reorder or delete themes: only add new ones at the END of [store]. */
    const val BUILTIN = 9

    private const val KEY_OWNED = "themes_installed"

    val store: List<StoreTheme> = listOf(
        // ---- Dark ----
        StoreTheme("Dark", t("Carbon", "#1C1C1E", "#101012", "#2C2C2E", "#3A3A3C", "#FFFFFF", "#0A84FF", "#FFFFFF")),
        StoreTheme("Dark", t("Graphite", "#2B2F36", "#1A1D22", "#3A3F48", "#4B515C", "#EDF0F5", "#F59E0B", "#1F1300")),
        StoreTheme("Dark", t("Obsidian", "#0F0F14", "#07070A", "#1A1A22", "#262631", "#F4F4F8", "#EF4444", "#FFFFFF")),
        StoreTheme("Dark", t("Deep Space", "#0B1026", "#040616", "#151C3D", "#212A55", "#E8ECFF", "#6366F1", "#FFFFFF")),
        StoreTheme("Dark", t("Mocha", "#2A1F1A", "#17100D", "#3B2C25", "#4D3A31", "#FBEFE6", "#D9A066", "#24160B")),
        StoreTheme("Dark", t("Slate", "#1E293B", "#0F172A", "#334155", "#475569", "#F1F5F9", "#38BDF8", "#03212F")),
        StoreTheme("Dark", t("Crimson Night", "#2B0A12", "#14050A", "#45111F", "#5E1A2D", "#FFE8EC", "#F43F5E", "#FFFFFF")),
        StoreTheme("Dark", t("Dracula", "#282A36", "#1E1F29", "#44475A", "#565A72", "#F8F8F2", "#BD93F9", "#1E1F29")),
        StoreTheme("Dark", t("Nord", "#2E3440", "#242933", "#3B4252", "#4C566A", "#ECEFF4", "#88C0D0", "#1C2530")),
        // ---- Light ----
        StoreTheme("Light", t("Snow", "#F7F8FA", "#E9ECF1", "#FFFFFF", "#D9DEE7", "#1A1D26", "#2563EB", "#FFFFFF")),
        StoreTheme("Light", t("Cream", "#FBF4E6", "#F1E6CE", "#FFFDF7", "#E6D6B4", "#3A2E1A", "#B45F0B", "#FFFFFF")),
        StoreTheme("Light", t("Mint Fresh", "#E8FBF3", "#D2F3E4", "#FFFFFF", "#B6E6CF", "#12382A", "#10B981", "#042F22")),
        StoreTheme("Light", t("Lavender Mist", "#F1ECFF", "#E1D8FB", "#FFFFFF", "#CFC2F5", "#2B1B57", "#7C3AED", "#FFFFFF")),
        StoreTheme("Light", t("Sky", "#E6F4FF", "#CDE8FB", "#FFFFFF", "#B5D8F3", "#0F2A44", "#0284C7", "#FFFFFF")),
        StoreTheme("Light", t("Peach", "#FFEDE3", "#FFD9C7", "#FFFFFF", "#FFC2A8", "#4A2113", "#F97316", "#2B1100")),
        // ---- Colorful ----
        StoreTheme("Colorful", t("Cotton Candy", "#FFB6D9", "#C3B1FF", "#FFFFFF", "#F4D3FF", "#3B1A4A", "#D6338F", "#FFFFFF")),
        StoreTheme("Colorful", t("Tropical", "#00B4A6", "#0072B5", "#0E8196", "#0A6489", "#FFFFFF", "#FFD23F", "#3A2A00")),
        StoreTheme("Colorful", t("Lava", "#FF5F2E", "#8E1B00", "#C7360F", "#8F2208", "#FFF3EC", "#FFD166", "#3B1D00")),
        StoreTheme("Colorful", t("Galaxy", "#5B2A86", "#1B1464", "#6A38A6", "#4A2C8A", "#FFFFFF", "#00E5FF", "#002B33")),
        StoreTheme("Colorful", t("Bubblegum", "#FF5FA2", "#7B2FF7", "#C42F82", "#8E2DB0", "#FFFFFF", "#FFE066", "#3A2C00")),
        StoreTheme("Colorful", t("Citrus", "#FFE259", "#FFA751", "#FFFFFF", "#FFD08A", "#4A2A00", "#C2410C", "#FFFFFF")),
        StoreTheme("Colorful", t("Northern Lights", "#0F4C5C", "#2B1B5A", "#1A6577", "#2B4C8C", "#E9FFFB", "#7CFFB2", "#04281A")),
        // ---- Neon ----
        StoreTheme("Neon", t("Cyber", "#05050F", "#0B0B24", "#12122E", "#1B1B45", "#E6FDFF", "#00F5FF", "#00262B")),
        StoreTheme("Neon", t("Toxic", "#07120A", "#030905", "#0F2414", "#17361E", "#E9FFE0", "#39FF14", "#062400")),
        StoreTheme("Neon", t("Hot Pink", "#14000C", "#2B0019", "#3A0A27", "#52103A", "#FFE6F4", "#FF2E93", "#FFFFFF")),
        StoreTheme("Neon", t("Electric Blue", "#00041A", "#00103F", "#061A5C", "#0B2680", "#E6F0FF", "#2D6BFF", "#FFFFFF")),
        StoreTheme("Neon", t("Synthwave", "#1B0B3B", "#4A1D6B", "#2A1457", "#43206F", "#FFE8FB", "#FF6EC7", "#2B0020")),
        // ---- Nature ----
        StoreTheme("Nature", t("Desert", "#EDD3A0", "#D2A864", "#F8EBCB", "#D7B27A", "#3B2A12", "#B4531A", "#FFFFFF")),
        StoreTheme("Nature", t("Rainforest", "#0B3D2E", "#041F17", "#13513D", "#1C6A50", "#E6FFF3", "#34D399", "#03231A")),
        StoreTheme("Nature", t("Glacier", "#DFF3FB", "#B8DDF0", "#FFFFFF", "#A5CFE6", "#0B2E45", "#0891B2", "#FFFFFF")),
        StoreTheme("Nature", t("Autumn", "#5A2A0E", "#2B1206", "#7A3B14", "#5A2C10", "#FFEFD9", "#F59E0B", "#2B1700")),
        StoreTheme("Nature", t("Lagoon", "#064E5A", "#032B33", "#0B6877", "#0E8196", "#E8FEFF", "#5EEAD4", "#022B27")),
        StoreTheme("Nature", t("Moss", "#2F3B22", "#1B2412", "#43552F", "#566C3C", "#F1F7E6", "#BEF264", "#1D2A05")),
        // ---- Pastel ----
        StoreTheme("Pastel", t("Marshmallow", "#FFF0F5", "#FDE1EC", "#FFFFFF", "#F8C8DC", "#5A2A40", "#F472B6", "#3F0A24")),
        StoreTheme("Pastel", t("Baby Blue", "#EAF4FF", "#D6E8FF", "#FFFFFF", "#BFD8FF", "#1D3557", "#60A5FA", "#0B2447")),
        StoreTheme("Pastel", t("Pistachio", "#F0F9E8", "#DDEFC9", "#FFFFFF", "#C6E3A8", "#26401A", "#84CC16", "#1A2E05")),
        StoreTheme("Pastel", t("Lilac", "#F5EDFF", "#E8D9FF", "#FFFFFF", "#D8C2FA", "#3B2370", "#A78BFA", "#1F0F4D")),
        StoreTheme("Pastel", t("Butter", "#FFFBE0", "#FFF2B3", "#FFFFFF", "#FFE680", "#4A3C00", "#F5B301", "#3A2C00")),
        // ---- Amoled ----
        StoreTheme("Amoled", t("Amoled Blue", "#000000", "#000000", "#0E1420", "#18202E", "#FFFFFF", "#3B82F6", "#FFFFFF")),
        StoreTheme("Amoled", t("Amoled Red", "#000000", "#000000", "#1A0B0B", "#2B1212", "#FFFFFF", "#EF4444", "#FFFFFF")),
        StoreTheme("Amoled", t("Amoled Purple", "#000000", "#000000", "#130B1F", "#1F1233", "#FFFFFF", "#A855F7", "#FFFFFF")),
        StoreTheme("Amoled", t("Amoled Gold", "#000000", "#000000", "#17130A", "#261F0F", "#FFFFFF", "#FACC15", "#2B2000")),
        // ---- Hacker (animated binary rain + typing banner) ----
        StoreTheme("Hacker", t("Matrix", "#020C05", "#000000", "#0A1F0F", "#12331B", "#C8FFD6", "#00FF41", "#001A06", 1)),
        StoreTheme("Hacker", t("Cyber Rain", "#00090E", "#000000", "#06171F", "#0B2A38", "#D6FBFF", "#00E5FF", "#00222A", 1)),
        StoreTheme("Hacker", t("Blood Code", "#0E0204", "#000000", "#1A0508", "#2E0B10", "#FFD9DE", "#FF1744", "#FFFFFF", 1)),
        StoreTheme("Hacker", t("Violet Code", "#09030F", "#000000", "#140824", "#230F3F", "#F1E6FF", "#C084FC", "#1A0833", 1)),
        StoreTheme("Hacker", t("Amber Terminal", "#0C0800", "#000000", "#181000", "#2B1D00", "#FFE9B8", "#FFB000", "#2A1A00", 1)),
        StoreTheme("Hacker", t("Neo Pink", "#0D0108", "#000000", "#1B0614", "#2E0A22", "#FFE0F6", "#FF2BD6", "#FFFFFF", 1))
    )

    /** Every theme: the built-in ones first, then the store ones. The saved "theme" number is a position in this list. */
    val all: List<KbTheme> = builtin + store.map { it.theme }

    /** Store tabs, in display order. */
    val categories: List<String> = listOf("Classic") + store.map { it.cat }.distinct()

    fun get(i: Int): KbTheme = all[((i % all.size) + all.size) % all.size]

    fun isBuiltin(i: Int): Boolean = i in 0 until BUILTIN

    fun category(i: Int): String = if (i in BUILTIN until all.size) store[i - BUILTIN].cat else "Classic"

    private fun ownedNames(c: Context): Set<String> =
        Prefs.getS(c, KEY_OWNED, "").split("|").filter { it.isNotEmpty() }.toSet()

    fun isInstalled(c: Context, i: Int): Boolean {
        if (i !in 0 until all.size) return false
        return i < BUILTIN || ownedNames(c).contains(all[i].name)
    }

    /** Positions (in [all]) of the themes this user can use: the built-in ones and the ones taken from the store. */
    fun installed(c: Context): List<Int> {
        val own = ownedNames(c)
        val out = ArrayList<Int>()
        for (i in all.indices) if (i < BUILTIN || own.contains(all[i].name)) out.add(i)
        return out
    }

    fun install(c: Context, i: Int) {
        if (i < BUILTIN || i >= all.size) return
        val own = LinkedHashSet(ownedNames(c))
        own.add(all[i].name)
        Prefs.setS(c, KEY_OWNED, own.joinToString("|"))
    }

    /** Removes a store theme. If it was the one in use the keyboard goes back to the first theme. */
    fun uninstall(c: Context, i: Int) {
        if (i < BUILTIN || i >= all.size) return
        val own = LinkedHashSet(ownedNames(c))
        own.remove(all[i].name)
        Prefs.setS(c, KEY_OWNED, own.joinToString("|"))
        if (Prefs.getI(c, "theme", 0) == i) Prefs.setI(c, "theme", 0)
    }

    /** The theme the keyboard's Theme button switches to next (it only cycles through installed themes). */
    fun next(c: Context): Int {
        val list = installed(c)
        val cur = Prefs.getI(c, "theme", 0)
        val pos = list.indexOf(cur)
        return list[(pos + 1) % list.size]
    }
}
