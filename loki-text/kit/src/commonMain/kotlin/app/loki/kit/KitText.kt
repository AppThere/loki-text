package app.loki.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the kit's own words (OK, Cancel, Close, field labels) reach the interface language: the app sets [translate] to its
 * catalog lookup. The kit has no catalogs of its own; English is the key, and the default shows it as it is.
 */
object KitText {
    var translate: (String) -> String by mutableStateOf({ s: String -> s })
}

/** [en] in the interface language the app chose. */
fun kt(en: String): String = KitText.translate(en)
