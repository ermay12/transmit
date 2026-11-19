package app.transmit

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The English strings, read from `res/values/strings.xml`, so unit tests (which have no Android resources) can check
 * what [UiText] says. Dates show as "<date>".
 */
object TestStrings {
    private val texts: Map<String, String> by lazy {
        // Unit tests run in the module's directory.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to unescape(e.textContent)
        }
    }

    private val names: Map<Int, String> by lazy { R.string::class.java.fields.associate { it.getInt(null) to it.name } }

    fun text(id: Int): String = texts.getValue(names.getValue(id))

    fun resolve(t: UiText): String = when (t) {
        is UiText.Res -> text(t.id).let { s -> if (t.args.isEmpty()) s else s.format(*t.args.map { if (it is UiText) resolve(it) else it }.toTypedArray()) }
        is UiText.Date -> "<date>"
        is UiText.Plain -> t.text
    }

    /** Every string resource, by name. */
    val all: Map<String, String> get() = texts

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
}
