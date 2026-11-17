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

    /**
     * The language tag text boxes hint to the keyboard, so it switches to this language (when the user
     * has it enabled). Keyboards list Chinese by region rather than script.
     */
    val keyboardLocale: String
        get() = when (code) {
            "zh-Hans" -> "zh-CN"
            "zh-Hant" -> "zh-TW"
            else -> code
        }

    /**
     * The Latin-alphabet spelling learners can read this language in, shown under or instead of its own
     * script (see [ScriptDisplay]). Null for languages written in Latin letters, and for those Transmit
     * doesn't romanize.
     */
    val romanization: Romanization?
        get() = when (code.substringBefore('-')) {
            "zh" -> Romanization.PINYIN
            "ja" -> Romanization.ROMAJI
            else -> null
        }

    /** What the language's own writing is called in the script display setting. */
    val scriptName: String
        get() = when (code) {
            "zh-Hans" -> "汉字"
            "zh-Hant" -> "漢字"
            else -> nativeName
        }

    /** Written in Latin letters, so its keyboard can type pinyin or romaji. */
    val latinScript: Boolean get() = code.substringBefore('-') !in NON_LATIN

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

/** A way to write a language in Latin letters; [style] tells the AI how. */
enum class Romanization(val label: String, val style: String) {
    PINYIN(
        "Pinyin",
        "Hanyu Pinyin with tone marks (nǐ hǎo), each word's syllables written together as in standard pinyin orthography " +
            "(Běijīng, xiànzài), and sentences starting with a capital",
    ),
    ROMAJI(
        "Romaji",
        "Hepburn romaji with spaces between words, long vowels marked with macrons (Tōkyō, arigatō), particles written as " +
            "pronounced (wa, e, o), and sentences starting with a capital",
    ),
}

/** How text in a language with a [Romanization] is shown. */
enum class ScriptDisplay {
    /** The language's own script; tapping a message adds its romanization. */
    CHARACTERS,
    /** The script with its romanization underneath. */
    BOTH,
    /** Only the romanization. The user also writes in it, and corrections stay in it. */
    ROMANIZED,
}

private val NON_LATIN = setOf("ru", "uk", "el", "ja", "ko", "zh", "th", "hi")

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

    /** How many recently used languages pickers show above the full list. */
    const val RECENT_SHOWN = 5
    private const val RECENT_KEPT = 8

    /**
     * The picker's two sections: the [recent] codes (most recent first, at most [RECENT_SHOWN]) and
     * every other language in the usual order. [exclude] appears in neither.
     */
    fun sections(recent: List<String>, exclude: String? = null): Pair<List<Language>, List<Language>> {
        val top = recent.asSequence().distinct().mapNotNull { byCode[it] }.filter { it.code != exclude }.take(RECENT_SHOWN).toList()
        val codes = top.mapTo(HashSet()) { it.code }
        return top to all.filter { it.code != exclude && it.code !in codes }
    }

    /** [recent] after using [code]: it moves to the front. */
    fun used(recent: List<String>, code: String): List<String> =
        (listOf(code) + recent.filter { it != code }).take(RECENT_KEPT)

    fun deviceDefault(): Language {
        val locale = Locale.getDefault()
        if (locale.language == "zh") return get(if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans")
        return byCode[locale.language] ?: get("en")
    }
}
