package com.yagay.supjav.player

/** No invented quality options: the chooser uses manifest/Media3 track heights. */
object VideoQualityPolicy {
    const val AUTO = "auto"
    const val HIGHEST = "highest"
    const val DEFAULT = HIGHEST
    private val resolution = Regex("p([1-9][0-9]{2,3})")

    fun normalize(value: String?): String {
        if (value == AUTO || value == HIGHEST) return value
        val height = value?.let { resolution.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        return if (height != null && height in 144..4320) "p$height" else DEFAULT
    }

    fun label(value: String): String = when (normalize(value)) {
        AUTO -> "自动"
        HIGHEST -> "最高可用"
        else -> normalize(value).removePrefix("p") + "p"
    }

    fun targetHeight(heights: Collection<Int>, preference: String): Int? {
        val choices = heights.filter { it in 144..4320 }.distinct()
        if (choices.isEmpty()) return null
        val normalized = normalize(preference)
        if (normalized == AUTO) return null
        if (normalized == HIGHEST) return choices.maxOrNull()
        val requested = normalized.removePrefix("p").toIntOrNull() ?: return choices.maxOrNull()
        // Prefer the highest rendition not exceeding the requested cap.
        // If even the lowest rendition exceeds it, retain the lowest one.
        return choices.filter { it <= requested }.maxOrNull() ?: choices.minOrNull()
    }

    fun choices(heights: Collection<Int>): List<String> =
        listOf(AUTO, HIGHEST) +
            heights.filter { it in 144..4320 }.distinct().sortedDescending().map { "p$it" }
}
