package com.zeta.board

import android.graphics.Color

class KbTheme(
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val key: Int,
    val special: Int,
    val text: Int,
    val accent: Int,
    val accentText: Int
)

object Themes {
    private fun c(s: String) = Color.parseColor(s)

    val all: List<KbTheme> = listOf(
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

    fun get(i: Int): KbTheme = all[((i % all.size) + all.size) % all.size]
}
