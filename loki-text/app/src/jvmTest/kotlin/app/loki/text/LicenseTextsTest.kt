package app.loki.text

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LicenseTextsTest {
    // The repository root is two levels above the Gradle module (<root>/loki-text/app).
    private fun root(name: String) = File("../../$name").readText().trim('\n')

    @Test
    fun mitTextMatchesTheLicenseFile() = assertEquals(root("LICENSE-MIT"), MIT_LICENSE_TEXT)

    @Test
    fun apacheTextMatchesTheLicenseFile() = assertEquals(root("LICENSE-APACHE"), APACHE_LICENSE_TEXT)

    @Test
    fun textsAreWhatTheyClaimToBe() {
        assertTrue(MIT_LICENSE_TEXT.startsWith("MIT License"))
        assertTrue(APACHE_LICENSE_TEXT.contains("Apache License") && APACHE_LICENSE_TEXT.contains("Version 2.0"))
    }
}
