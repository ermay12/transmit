package app.transmit.smart

/**
 * Finds street addresses in message text: US and UK forms ("1600 Amphitheatre Pkwy, Mountain View, CA 94043",
 * "221B Baker Street") and common European ones ("12 rue de Rivoli", "Calle de Alcalá 42", "Hauptstraße 5").
 *
 * A heuristic: it looks for a house number beside a street-type word, so it misses addresses written without one,
 * and it won't take ordinary phrases such as "3 blocks down the road" (a full street word needs a capitalised
 * street name).
 */
object Addresses {
    fun find(text: String): List<IntRange> {
        if (text.length > MAX_TEXT || text.none { it.isDigit() }) return emptyList()
        val found = ArrayList<IntRange>()
        for (re in PATTERNS) {
            for (m in re.findAll(text)) {
                if (m.groups[1]?.value?.split(SPACE)?.any { it.lowercase() in NOT_NAMES } == true) continue
                val range = m.range.first until extend(text, m.range.last + 1)
                if (found.none { it.first <= range.last && range.first <= it.last }) found += range
            }
        }
        return found.sortedBy { it.first }
    }

    /** Adds what follows the street: ", Mountain View", ", CA", " 94043", ", 75001 Paris". */
    private fun extend(text: String, end: Int): Int {
        var e = end
        val city = CITY.matchAt(text, e)?.takeIf { it.groupValues[1].substringBefore(' ') !in NOT_CITIES }
        if (city != null) {
            e = city.range.last + 1
            STATE.matchAt(text, e)?.let { e = it.range.last + 1 }
        }
        POSTAL.matchAt(text, e)?.let { e = it.range.last + 1 }
        // "123 Main St." ends a sentence; the period isn't part of the address.
        while (e > end && text[e - 1] in ",. ") e--
        if (e == end && text[e - 1] == '.') e--
        return e
    }

    private const val MAX_TEXT = 2000
    private val SPACE = Regex("\\s+")

    private const val B = "(?<![\\p{L}\\p{N}#/.,:$-])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private const val NUMBER = "\\d{1,6}[A-Za-z]?(?:-\\d{1,5})?"
    private const val DIRECTION = "(?:(?i:n|s|e|w|ne|nw|se|sw|north|south|east|west)\\.?\\s+)?"
    private const val CAPITAL = "(?:\\d{1,3}(?i:st|nd|rd|th)|[\\p{Lu}][\\p{L}'’.-]*)"
    private const val ANY_WORD = "(?:\\d{1,3}(?i:st|nd|rd|th)|[\\p{L}][\\p{L}'’.-]*)"
    private const val UNIT = "(?:,?\\s*(?i:apt|apartment|suite|ste|unit|fl|floor|#)\\.?\\s*#?\\s*[\\p{L}\\p{N}-]{1,6}$E)?"
    private const val TRAILING_DIRECTION = "(?:\\s+(?i:n|s|e|w|ne|nw|se|sw)\\.?(?![\\p{L}]))?"

    private const val ABBREVIATED = "(?i:st|ave|av|rd|blvd|dr|ln|ct|pl|pkwy|hwy|ter|cir|sq|trl|plz|expy|fwy|tpke)\\.?"
    private const val FULL = "(?i:street|avenue|road|boulevard|drive|lane|way|court|place|parkway|highway|terrace|circle|square" +
        "|trail|plaza|alley|loop|crescent|expressway|freeway|turnpike|mews)"

    private val PATTERNS = listOf(
        // 123 Main Street, 221B Baker Street, 350 5th Avenue
        Regex("$B$NUMBER\\s+$DIRECTION($CAPITAL(?:\\s+$CAPITAL){0,3})\\s+$FULL$E$TRAILING_DIRECTION$UNIT"),
        // 123 main st, 1600 Amphitheatre Pkwy
        Regex("$B$NUMBER\\s+$DIRECTION($CAPITAL(?:\\s+$CAPITAL){0,3}|$ANY_WORD(?:\\s+$ANY_WORD){0,2})\\s+$ABBREVIATED$E$TRAILING_DIRECTION$UNIT"),
        // 1585 Broadway
        Regex("$B$NUMBER\\s+(Broadway)$E$UNIT"),
        // 12 rue de Rivoli, 5 avenue des Champs-Élysées
        Regex(
            "$B\\d{1,5}(?:\\s?(?:bis|ter))?,?\\s+(?i:rue|avenue|av\\.|boulevard|bd|place|quai|allée|allee|impasse|chemin|route|cours)" +
                "\\s+((?:(?:de la|de l'|du|des|de|d'|la|le|les)\\s*)?[\\p{L}'’-]+(?:\\s+[\\p{Lu}][\\p{L}'’-]*){0,3})",
        ),
        // Calle de Alcalá 42, Via Roma 10, Avenida Paulista, 1578
        Regex(
            "(?<![\\p{L}])(?:Calle|C/|Avenida|Avda\\.|Av\\.|Paseo|Plaza|Carrer|Camino|Carretera|Ronda|Rua|Praça|Travessa|Alameda|Estrada" +
                "|Via|Viale|Piazza|Corso|Largo|Vicolo|calle|avenida|rua|viale|piazza)\\s+" +
                "((?:(?:de la|de los|de las|del|de|das|dos|da|do|di|della|delle|dei|degli)\\s+)?[\\p{Lu}][\\p{L}'’-]*" +
                "(?:\\s+(?:(?:de|del|da|di|y|e)\\s+)?[\\p{Lu}][\\p{L}'’-]*){0,3}),?\\s*(?:n[º°o]\\.?\\s*)?\\d{1,5}[a-zA-Z]?$E",
        ),
        // Hauptstraße 5, Friedrichstr. 123, Damstraat 5
        Regex(
            "(?<![\\p{L}])([\\p{Lu}][\\p{L}-]{2,}\\s?(?i:straße|strasse|str\\.|weg|platz|allee|gasse|straat|laan|plein|gracht|kade))" +
                "\\s+\\d{1,4}[a-z]?$E",
        ),
    )

    /** Words that make "3 blocks down the rd" or "2 left in st" ordinary prose. */
    private val NOT_NAMES = setOf(
        "the", "a", "an", "of", "on", "in", "to", "for", "at", "with", "my", "your", "his", "her", "our", "their", "and", "or",
        "is", "are", "was", "more", "min", "mins", "minutes", "hour", "hours", "hrs", "block", "blocks", "mile", "miles", "mi",
        "km", "people", "times", "left", "down", "up",
    )

    private val CITY = Regex(",\\s*([\\p{Lu}][\\p{L}'’.-]*(?:\\s+[\\p{Lu}][\\p{L}'’.-]*){0,2})(?![\\p{L}])")
    private val STATE = Regex(",?\\s+[A-Z]{2}(?![\\p{L}\\p{N}])")
    private val POSTAL = Regex(",?\\s*(?:\\d{5}(?:-\\d{4})?(?:\\s+[\\p{Lu}][\\p{L}-]+)?)(?![\\p{N}])")

    /** Capitalised words after a comma that start a new sentence rather than name a city. */
    private val NOT_CITIES = setOf(
        "I", "I'm", "I'll", "I've", "I'd", "See", "Let's", "Lets", "Meet", "Come", "Text", "Call", "Thanks", "Thank", "Ok", "Okay",
        "OK", "The", "It's", "It", "We", "We're", "You", "He", "She", "They", "And", "But", "So", "Then", "Just", "Can", "Could",
        "Will", "Be", "Bring", "Park", "Parking", "Ring", "Knock", "Door", "Gate", "Apt", "Suite", "Unit", "Floor",
    )
}
