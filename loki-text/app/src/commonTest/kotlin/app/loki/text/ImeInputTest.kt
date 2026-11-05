package app.loki.text

import kotlin.test.Test
import kotlin.test.assertEquals

class ImeInputTest {
    private fun ids(prev: String, next: String) = imeCommands(prev, next).map {
        if (it.command == "text.insert") "insert:" + it.params["text"].toString().trim('"') else it.command
    }

    @Test fun typingInsertsAtTheCaret() = assertEquals(listOf("insert:a"), ids(IME_FILLER, IME_FILLER + "a"))

    @Test fun deletingFillerBackspaces() = assertEquals(listOf("text.backspace"), ids(IME_FILLER, IME_FILLER.dropLast(1)))

    @Test fun autocorrectReplacesTheComposingWord() {
        // "teh" composing → "the": two characters change, so two backspaces and the new tail.
        assertEquals(listOf("text.backspace", "text.backspace", "insert:he"), ids(IME_FILLER + "teh", IME_FILLER + "the"))
    }

    @Test fun suggestionExtendsTheWord() = assertEquals(listOf("insert:lo "), ids(IME_FILLER + "hel", IME_FILLER + "hello "))

    @Test fun enterStartsAParagraph() = assertEquals(listOf("insert:a", "text.newParagraph"), ids(IME_FILLER, IME_FILLER + "a\n"))

    @Test fun emojiDeletesAsOneCharacter() = assertEquals(listOf("text.backspace"), ids(IME_FILLER + "😀", IME_FILLER))

    @Test fun noChangeNoCommands() = assertEquals(emptyList(), ids(IME_FILLER, IME_FILLER))
}
