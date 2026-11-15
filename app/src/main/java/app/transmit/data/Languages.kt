package app.transmit.data

import java.util.Locale

data class Language(
    val code: String,
    val name: String,
    val nativeName: String,
    val flag: String,
) {
    /** Two-letter badge shown next to input boxes and bubbles. */
    val badge: String get() = code.substringBefore('-').uppercase(Locale.ROOT)

    /** Pronunciation aid the tutor adds for non-Latin scripts. */
    val readingAid: String?
        get() = when (code.substringBefore('-')) {
            "ja" -> "furigana (hiragana readings) and romaji"
            "zh" -> "pinyin with tone marks"
            "ko" -> "Revised Romanization"
            "ru", "uk" -> "transliteration"
            "el" -> "transliteration"
            "th" -> "RTGS romanization"
            "hi" -> "IAST transliteration"
            else -> null
        }
}

enum class Level(val label: String, val promptDescription: String) {
    BEGINNER("Beginner", "beginner (A1–A2)"),
    INTERMEDIATE("Intermediate", "intermediate (B1–B2)"),
    ADVANCED("Advanced", "advanced (C1–C2)"),
}

/** Left-to-right languages supported as native or target languages. */
object Languages {
    val all = listOf(
        Language("en", "English", "English", "🇺🇸"),
        Language("es", "Spanish", "Español", "🇪🇸"),
        Language("fr", "French", "Français", "🇫🇷"),
        Language("de", "German", "Deutsch", "🇩🇪"),
        Language("it", "Italian", "Italiano", "🇮🇹"),
        Language("pt", "Portuguese", "Português", "🇧🇷"),
        Language("nl", "Dutch", "Nederlands", "🇳🇱"),
        Language("sv", "Swedish", "Svenska", "🇸🇪"),
        Language("pl", "Polish", "Polski", "🇵🇱"),
        Language("ru", "Russian", "Русский", "🇷🇺"),
        Language("uk", "Ukrainian", "Українська", "🇺🇦"),
        Language("el", "Greek", "Ελληνικά", "🇬🇷"),
        Language("tr", "Turkish", "Türkçe", "🇹🇷"),
        Language("ja", "Japanese", "日本語", "🇯🇵"),
        Language("ko", "Korean", "한국어", "🇰🇷"),
        Language("zh-Hans", "Chinese (Simplified)", "简体中文", "🇨🇳"),
        Language("zh-Hant", "Chinese (Traditional)", "繁體中文", "🇹🇼"),
        Language("vi", "Vietnamese", "Tiếng Việt", "🇻🇳"),
        Language("th", "Thai", "ไทย", "🇹🇭"),
        Language("id", "Indonesian", "Bahasa Indonesia", "🇮🇩"),
        Language("hi", "Hindi", "हिन्दी", "🇮🇳"),
        Language("tl", "Tagalog", "Tagalog", "🇵🇭"),
    )

    private val byCode = all.associateBy { it.code }

    fun get(code: String?): Language = byCode[code] ?: all.first()

    fun deviceDefault(): Language {
        val locale = Locale.getDefault()
        if (locale.language == "zh") return get(if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans")
        return byCode[locale.language] ?: get("en")
    }
}
