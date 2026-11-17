package app.transmit.data

import java.text.Normalizer

/** On-device help with romanization: which text needs the AI to read it, which is written in pinyin or romaji, and pinyin tone numbers. */
object Scripts {
    /** True when [text] has Han characters, kana or bopomofo, whose readings only the AI can give. */
    fun needsReading(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA, Character.UnicodeScript.BOPOMOFO,
                -> return true
                else -> Unit
            }
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * [text]'s romanization when the device can tell it: text with nothing to read is already romanized,
     * though pinyin typed with tone numbers gets tone marks. Null when it takes the AI.
     */
    fun local(text: String, r: Romanization): String? = when {
        needsReading(text) -> null
        r == Romanization.PINYIN -> toneMarks(text)
        else -> text
    }

    /**
     * True when [text] reads as [r] rather than characters: it has none to read, and most of its letters are in
     * words that split into [r] syllables ("wo3 xiang3 qu4 Běijīng", "ok hao3 de", "Tōkyō ni ikimasu").
     */
    fun isRomanized(text: String, r: Romanization): Boolean {
        if (needsReading(text)) return false
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(toneDiacritics, "")
            .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
            .replace(colonU, "ü")
        val words = word.findAll(plain).map { it.value.lowercase() }.filter { w -> w.any(Char::isLetter) }.toList()
        val letters = words.sumOf { w -> w.count(Char::isLetter) }
        if (letters == 0) return false
        val fitting = words.filter { if (r == Romanization.PINYIN) pinyinWord(it.replace('v', 'ü')) else romajiWord(it) }
            .sumOf { w -> w.count(Char::isLetter) }
        return fitting >= letters * 0.7
    }

    /** Tone marks and macrons, but not the diaeresis of ü. */
    private val toneDiacritics = Regex("[\\u0300\\u0301\\u0304\\u030C]")
    private val colonU = Regex("(?<=[lnLN])[uU]:")
    private val word = Regex("[\\p{L}0-9]+")

    /** Syllables, each with an optional erhua r and tone number: "bei3jing", "xie4xie5", "nar3", "lü4". */
    private fun pinyinWord(w: String): Boolean = splits(w) { i ->
        (1..6).filter { i + it <= w.length && w.substring(i, i + it) in pinyin }.flatMap { len ->
            val end = i + len
            val ends = if (w.getOrNull(end) == 'r') listOf(end, end + 1) else listOf(end)
            ends.flatMap { e -> if (w.getOrNull(e) in '1'..'5') listOf(e, e + 1) else listOf(e) }
        }
    }

    /** Hepburn kana syllables, a doubled consonant for a small tsu, and n: "kitte", "konnichiwa", "shimbun". */
    private fun romajiWord(w: String): Boolean = splits(w) { i ->
        val next = (1..3).filter { i + it <= w.length && w.substring(i, i + it) in romaji }.map { i + it }
        val c = w[i]
        val smallTsu = c in "kstpgzdbfjcv" && (w.getOrNull(i + 1) == c || (c == 't' && w.startsWith("ch", i + 1)))
        val m = c == 'm' && w.getOrNull(i + 1).let { it != null && it in "bpm" }
        if (smallTsu || m) next + (i + 1) else next
    }

    /** Whether all of [w] splits into pieces, given where a piece starting at each index can end. */
    private inline fun splits(w: String, ends: (Int) -> List<Int>): Boolean {
        val reach = BooleanArray(w.length + 1).also { it[0] = true }
        for (i in w.indices) if (reach[i]) ends(i).forEach { reach[it] = true }
        return reach[w.length]
    }

    private val pinyin: Set<String> = (
        "a ai an ang ao e ei en eng er o ou " +
            "ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu " +
            "pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu " +
            "ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu " +
            "fa fan fang fei fen feng fiao fo fou fu " +
            "da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo " +
            "ta tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo " +
            "na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nuan nun nuo nü nüe nue " +
            "la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lü lüe lue " +
            "ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo " +
            "ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo " +
            "ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo " +
            "ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun " +
            "qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun " +
            "xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
            "zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo " +
            "cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo " +
            "sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo " +
            "ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo " +
            "za zai zan zang zao ze zei zen zeng zi zong zou zu zuan zui zun zuo " +
            "ca cai can cang cao ce cen ceng ci cong cou cu cuan cui cun cuo " +
            "sa sai san sang sao se sen seng si song sou su suan sui sun suo " +
            "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun " +
            "wa wai wan wang wei wen weng wo wu"
        ).split(' ').toHashSet()

    private val romaji: Set<String> = (
        "a i u e o n ka ki ku ke ko sa si su se so ta ti tu te to na ni nu ne no ha hi hu he ho ma mi mu me mo " +
            "ya yu yo ye ra ri ru re ro wa wi we wo ga gi gu ge go za zi zu ze zo da di du de do ba bi bu be bo " +
            "pa pi pu pe po va vi vu ve vo fa fi fu fe fo shi sha shu she sho chi cha chu che cho tsu ja ji ju je jo dzu " +
            "kya kyu kyo sya syu syo tya tyu tyo nya nyu nyo hya hyu hyo mya myu myo rya ryu ryo " +
            "gya gyu gyo zya zyu zyo jya jyu jyo dya dyu dyo bya byu byo pya pyu pyo"
        ).split(' ').toHashSet()

    /** A pinyin syllable typed with a tone number, as in "dui4", "lv4" or "nu:3". It may follow another one ("ni3hao3"). */
    private val numbered = Regex(
        "(?<![A-Za-zÜü:])((?:zh|ch|sh|[bpmfdtnlgkhjqxrzcsyw])?" +
            "(?:iang|iong|uang|ang|eng|ing|ong|ian|iao|uai|uan|van|üan|ai|ei|ao|ou|an|en|er|in|ia|ie|iu|ua|uo|ui|un|vn|ün|ve|üe|ue|u:e|u:|a|o|e|i|u|v|ü))" +
            "(r?)([1-5])(?![0-9])",
        RegexOption.IGNORE_CASE,
    )

    private val marks = mapOf(
        'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì", 'o' to "ōóǒò", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
        'A' to "ĀÁǍÀ", 'E' to "ĒÉĚÈ", 'I' to "ĪÍǏÌ", 'O' to "ŌÓǑÒ", 'U' to "ŪÚǓÙ", 'Ü' to "ǕǗǙǛ",
    )

    /** Pinyin tone numbers to tone marks: "Wo3 xiang3 qu4" → "Wǒ xiǎng qù", "xie4xie5" → "xièxie". Other text is unchanged. */
    fun toneMarks(text: String): String = numbered.replace(text) { m ->
        val syllable = m.groupValues[1]
            .replace("u:", "ü").replace("U:", "Ü")
            .replace('v', 'ü').replace('V', 'Ü')
        val tone = m.groupValues[3].toInt()
        val marked = if (tone == 5) syllable else mark(syllable, tone)
        marked + m.groupValues[2]
    }

    /** The tone goes on a or e, else on the o of "ou", else on the last vowel. */
    private fun mark(syllable: String, tone: Int): String {
        val lower = syllable.lowercase()
        val at = when {
            'a' in lower -> lower.indexOf('a')
            'e' in lower -> lower.indexOf('e')
            "ou" in lower -> lower.indexOf("ou")
            else -> lower.indexOfLast { it in "iouü" }
        }
        if (at < 0) return syllable
        val accents = marks[syllable[at]] ?: return syllable
        return syllable.substring(0, at) + accents[tone - 1] + syllable.substring(at + 1)
    }
}
