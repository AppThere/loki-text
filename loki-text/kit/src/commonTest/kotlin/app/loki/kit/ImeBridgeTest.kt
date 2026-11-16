package app.loki.kit

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImeBridgeTest {
    private val a = IME_ANCHOR
    private fun v(text: String, comp: TextRange? = null, caret: Int = text.length) = TextFieldValue(text, TextRange(caret), comp)

    @Test fun typingIsCommittedAndTheFieldPutBack() {
        val s = imeStep(v(a + "hi"), false)
        assertEquals(listOf<ImeAction>(ImeAction.Commit("hi")), s.actions)
        assertEquals(a, s.next?.text)
    }

    @Test fun takingTheAnchorsCharactersIsBackspaces() {
        assertEquals(listOf<ImeAction>(ImeAction.Backspace(1)), imeStep(v(a.dropLast(1)), false).actions)
        assertEquals(listOf<ImeAction>(ImeAction.Backspace(2)), imeStep(v(""), false).actions)
        assertEquals(a, imeStep(v(""), false).next?.text)
    }

    @Test fun aCompositionIsAPreeditAndTheFieldIsLeftAlone() {
        val s = imeStep(v(a + "にほ", TextRange(a.length, a.length + 2), caret = a.length + 1), false)
        assertEquals(listOf<ImeAction>(ImeAction.Preedit("にほ", 1)), s.actions)
        assertNull(s.next, "the field keeps the composition")
    }

    @Test fun textBeforeTheCompositionIsFinalAndLeavesTheField() {
        val s = imeStep(v(a + "ab" + "cd", TextRange(a.length + 2, a.length + 4)), true)
        assertEquals(listOf(ImeAction.Commit("ab"), ImeAction.Preedit("cd", 2)), s.actions)
        assertEquals(a + "cd", s.next?.text)
        assertEquals(TextRange(a.length, a.length + 2), s.next?.composition)
    }

    @Test fun endingACompositionCommitsAndCancellingItIsHeard() {
        assertEquals(listOf<ImeAction>(ImeAction.Commit("日本")), imeStep(v(a + "日本"), true).actions)
        assertEquals(listOf<ImeAction>(ImeAction.Preedit("", null)), imeStep(v(a), true).actions)
        assertEquals(emptyList(), imeStep(v(a), false).actions)
        assertNull(imeStep(v(a), false).next)
    }

    @Test fun aFieldThatLostItsAnchorIsStillRead() {
        assertEquals(listOf<ImeAction>(ImeAction.Commit("x")), imeStep(v("x" + "​​"), false).actions)
    }
}
