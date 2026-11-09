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

    /**
     * The sequence a Chromebook keyboard sent for "teh" + space (logged from a device): delete the three
     * letters, commit "the", commit the space, each edit built on the text the one before it left. Read
     * against the field it built on, it is one deletion of the typo and two plain inserts. Read against a
     * field reset to filler between edits (the earlier code), the 2nd and 3rd edits each also "removed"
     * three filler characters, backspacing the document three times more before the word.
     */
    @Test fun anAutocorrectBatchIsReadAgainstTheFieldItBuiltOn() {
        val steps = listOf(IME_FILLER + "of teh", IME_FILLER + "of ", IME_FILLER + "of the", IME_FILLER + "of the ")
        val commands = steps.zipWithNext { prev, next -> ids(prev, next) }
        assertEquals(
            listOf(listOf("text.backspace", "text.backspace", "text.backspace"), listOf("insert:the"), listOf("insert: ")),
            commands,
        )
        // The reset-field reading that deleted text: same second edit against filler alone.
        val wrong = ids(IME_FILLER, IME_FILLER.dropLast(3) + "the")
        assertEquals(listOf("text.backspace", "text.backspace", "text.backspace", "insert:the"), wrong)
    }

    @Test fun repeatedLettersAreNotDropped() {
        // Two l's typed in one frame, before the field is reset: the second arrives on the field with the first.
        assertEquals(listOf("insert:l"), ids(IME_FILLER + "l", IME_FILLER + "ll"))
        assertEquals(listOf("insert:l"), ids(IME_FILLER + "hel", IME_FILLER + "hell"))
    }
}
