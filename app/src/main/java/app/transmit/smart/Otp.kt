package app.transmit.smart

/**
 * Finds a one-time passcode (verification, login or access code) in a message.
 *
 * Plain string work with no I/O, so it can run on the incoming-message path. A code needs a nearby keyword
 * ("code", "OTP", "verify", "código", "验证码", …) so that ordinary numbers in conversation aren't mistaken for one.
 */
object Otp {
    /** [code] is what gets copied (separators removed); [start] until [end] is where it appears in the text. */
    data class Match(val code: String, val start: Int, val end: Int)

    /**
     * Remembered per text: the message list asks about every message each time it rebuilds, and a rebuild
     * stands between a new message (or translation) and the screen.
     */
    fun find(text: String): Match? {
        if (text.length > MAX_LENGTH || text.none { it.isDigit() }) return null
        synchronized(recent) { recent[text]?.let { return it.match } }
        val match = search(text)
        synchronized(recent) { recent[text] = Found(match) }
        return match
    }

    private class Found(val match: Match?)

    private val recent = object : LinkedHashMap<String, Found>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Found>?) = size > 500
    }

    private fun search(text: String): Match? {
        originBound(text)?.let { return it }
        val keywords = KEYWORD.findAll(text).filter { isKeyword(text, it) }.map { it.range }.toList()
        if (keywords.isEmpty()) return null
        val urls = URL.findAll(text).map { it.range }.toList()
        val candidates = candidates(text).filter { c ->
            urls.none { it.overlaps(c.range) } && keywords.none { it.overlaps(c.range) }
        }
        if (candidates.isEmpty()) return null
        val best = candidates.minWith(compareBy<Candidate>({ score(it, keywords) }, { it.range.first }))
        val gap = distance(best.range, keywords)
        // A year ("since 2019") only counts right next to its keyword ("code: 2019").
        if (best.yearLike && gap > 5) return null
        val near = gap <= MAX_DISTANCE
        val onlyOne = candidates.count { !it.yearLike } == 1 && !best.yearLike && text.length <= 200
        if (!near && !onlyOne) return null
        return Match(best.code, best.range.first, best.range.last + 1)
    }

    private data class Candidate(val code: String, val range: IntRange, val alnum: Boolean) {
        val yearLike get() = code.length == 4 && !alnum && code.toInt() in 1900..2099
    }

    private fun candidates(text: String): List<Candidate> {
        val found = ArrayList<Candidate>()
        fun add(code: String, range: IntRange, alnum: Boolean) {
            if (found.none { it.range.overlaps(range) }) found += Candidate(code, range, alnum)
        }
        // Most specific first: grouped digits ("123 456"), prefixed codes ("G-123456"), plain digits, then letters and digits.
        GROUPED.findAll(text).forEach { m ->
            val digits = m.groupValues[1] + m.groupValues[2]
            if (digits.length in 6..8) add(digits, m.range, alnum = false)
        }
        PREFIXED.findAll(text).forEach { add(it.groupValues[1], it.range, alnum = false) }
        DIGITS.findAll(text).forEach { add(it.value, it.range, alnum = false) }
        ALNUM.findAll(text).forEach { add(it.value, it.range, alnum = true) }
        return found
    }

    private fun score(c: Candidate, keywords: List<IntRange>): Int =
        distance(c.range, keywords) +
            (if (c.alnum) 15 else 0) +
            (if (c.code.length == 6) 0 else 3) +
            (if (c.yearLike) 1000 else 0)

    private fun distance(range: IntRange, keywords: List<IntRange>): Int = keywords.minOf { k ->
        when {
            k.last < range.first -> range.first - k.last - 1
            range.last < k.first -> k.first - range.last - 1
            else -> 0
        }
    }

    /** WebOTP / domain-bound codes end with a line like "@example.com #123456". */
    private fun originBound(text: String): Match? {
        val m = ORIGIN_BOUND.find(text) ?: return null
        val code = m.groupValues[1]
        val earlier = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(code)}(?![\\p{L}\\p{N}])").find(text)
            ?.takeIf { it.range.first < m.range.first }
        val range = earlier?.range ?: m.groups[1]!!.range
        return Match(code, range.first, range.last + 1)
    }

    /** Rejects "zip code", "barcode", "promo code" and the like. */
    private fun isKeyword(text: String, m: MatchResult): Boolean {
        val word = m.value.lowercase()
        if (word in NOT_CODES) return false
        if (!word.endsWith("code") && !word.endsWith("codes")) return true
        val before = text.substring(0, m.range.first).trimEnd().takeLastWhile { it.isLetter() }.lowercase()
        return before !in NOT_CODE_QUALIFIERS
    }

    private fun IntRange.overlaps(other: IntRange) = first <= other.last && other.first <= last

    private const val MAX_LENGTH = 1000
    private const val MAX_DISTANCE = 60

    private val KEYWORD = Regex(
        "(?iu)" + listOf(
            // Word starts in Latin scripts.
            "(?<!\\p{L})(?:verif|authent|autent|passcode|password|passwort|kennwort|wachtwoord|contrase[ñn]a|senha" +
                "|has[łl]o|[şs]ifre|mot de passe|2fa|mfa|two[- ]factor|2[- ]step|log[- ]?in|sign[- ]?in|otp|clave(?!\\p{L})" +
                "|bestätigung|doğrulama|weryfikac|подтвержд|пароль)",
            // Only in capitals: "drop a pin at 1234 Main St" is not a PIN.
            "(?-i:PIN|TAN)(?!\\p{L})",
            // "code" in any language, including compounds such as "Bestätigungscode".
            "\\p{L}*(?:codes?|kode|codice|c[óo]digo|kod\\p{L}{0,2}|код\\p{L}{0,2})(?!\\p{L})",
            // Scripts without spaces between words.
            "验证码|校验码|动态码|确认码|驗證碼|認證碼|密码|密碼|認証コード|確認コード|認証番号|確認番号|ワンタイム|パスワード|暗証番号" +
                "|인증\\s?번호|인증\\s?코드|확인\\s?코드|비밀번호|رمز|كود|קוד|รหัส|कोड|ओटीपी|mã xác|mã otp|mật khẩu|kata sandi",
        ).joinToString("|"),
    )

    private val NOT_CODES = setOf("barcode", "barcodes", "zipcode", "postcode", "decode", "encode", "unicode", "geocode", "qrcode", "opcode")
    private val NOT_CODE_QUALIFIERS = setOf(
        "zip", "postal", "post", "area", "country", "dress", "bar", "qr", "error", "source", "status", "tracking",
        "promo", "promotional", "discount", "coupon", "referral", "gift", "voucher", "product", "reference", "ref", "cheat",
    )

    // Not glued to letters, digits, currency, phone or URL punctuation, and not a decimal, time or date.
    private const val BEFORE = "(?<![\\p{L}\\p{N}_\$€£¥₹#@&+=/\\\\%.,-])(?<!\\d[ .,-])"
    private const val AFTER = "(?![\\p{L}\\p{N}_%/€\$]|[.,:-]\\d|[ -]\\d)"
    private val GROUPED = Regex("$BEFORE(\\d{3,4})[ -](\\d{3,4})$AFTER")
    private val PREFIXED = Regex("(?<![\\p{L}\\p{N}])[A-Z]{1,3}-(\\d{4,8})$AFTER")
    private val DIGITS = Regex("$BEFORE\\d{4,8}$AFTER")
    private val ALNUM = Regex("$BEFORE(?=[A-Z\\d]*\\d)(?=[A-Z\\d]*[A-Z])[A-Z\\d]{4,8}$AFTER")
    private val ORIGIN_BOUND = Regex("(?m)^@[\\w.-]+\\.[\\w-]+ #([A-Za-z0-9]{3,10})\\s*(?:%.*)?$")
    private val URL = Regex("(?i)(https?://|www\\.)\\S+")
}
