package app.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val APP_NAME = "AppThere Loki Text"

/** Keep in step with `versionName` in app/build.gradle.kts. */
const val APP_VERSION = "0.1.0"

/** The engine's credit line, as in the WordCraft NOTICE. */
internal const val ENGINE_CREDIT = "Copyright (c) 2026 ArtCraft Team and the WordCraft contributors."

internal const val ENGINE_URL = "https://getartcraft.com/apps/wordcraft"

/** Material this app depends on, with the license each is used under. The engine's own bundled material is in its NOTICE. */
internal val THIRD_PARTY = listOf(
    "Lucide icons (https://lucide.dev)" to "ISC License; some icons derive from Feather, MIT License",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization, Compose Multiplatform, AndroidX" to "Apache License 2.0",
    "JNA (Java Native Access)" to "used under the Apache License 2.0 (dual-licensed with LGPL 2.1 or later)",
)

private enum class LicenseTab(val label: String, val text: String) {
    Mit("MIT License", MIT_LICENSE_TEXT),
    Apache("Apache License 2.0", APACHE_LICENSE_TEXT),
}

/** The app's name and version, who made the engine, and the full license texts. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(LicenseTab.Mit) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Column {
                Text(APP_NAME, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Version $APP_VERSION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Heading("Editing engine")
                        Text("$APP_NAME is built on WordCraft, the open-source word processing engine from the ArtCraft team.", style = MaterialTheme.typography.bodyMedium)
                        Text(ENGINE_CREDIT, style = MaterialTheme.typography.bodySmall)
                        Text(ENGINE_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

                        Heading("License")
                        Text(
                            "WordCraft and this app are offered under the MIT License or the Apache License, Version 2.0, at your option. The full texts are below.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in LicenseTab.entries) FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                }
                SelectionContainer {
                    Text(tab.text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Heading("Third-party software")
                        for ((name, license) in THIRD_PARTY) {
                            Column {
                                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(license, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(
                            "Fonts, hyphenation data and Rust libraries bundled with the WordCraft engine carry their own licenses; they are listed in the NOTICE and ATTRIBUTION.md files of the WordCraft source.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
