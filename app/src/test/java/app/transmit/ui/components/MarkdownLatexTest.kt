package app.transmit.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tutor answers from gemma-4-31b-it carry LaTeX arrows despite the prompt (seen in Explain on beta10). */
class MarkdownLatexTest {
    @Test
    fun inlineMathArrowsBecomeArrows() {
        assertEquals("- **Hola** → Hello", withoutLatex("- **Hola** \$\\rightarrow\$ Hello"))
        assertEquals("tener → to have", withoutLatex("tener \$\\to\$ to have"))
        assertEquals("ir ⇒ fue", withoutLatex("ir \$\\Rightarrow\$ fue"))
    }

    @Test
    fun bareCommandsAndTextBecomePlain() {
        assertEquals("go → went", withoutLatex("go \\rightarrow went"))
        assertEquals("hola → hello", withoutLatex("\$\\text{hola} \\rightarrow \\text{hello}\$"))
        assertEquals("3 × 2", withoutLatex("\$3 \\times 2\$"))
    }

    @Test
    fun pricesAndPlainTextStay() {
        assertEquals("It costs \$5 and \$10.", withoutLatex("It costs \$5 and \$10."))
        assertEquals("¿Qué tal? → fine", withoutLatex("¿Qué tal? → fine"))
        assertEquals("a \\alpha b", withoutLatex("a \\alpha b"))
    }
}
