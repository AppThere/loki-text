package com.appthere.loki.text

import com.appthere.loki.kit.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The defaults table names commands and fields the engine has: a renamed parameter shows up here, not as a blank field. */
class FormDefaultsFixtureTest {
    private val commands = parseCommands(Json.parseToJsonElement(File("src/jvmTest/resources/engine-commands.json").readText())).associateBy { it.id }

    @Test fun everyEntryNamesRealCommandsAndFields() {
        for ((id, source) in FORM_DEFAULTS) {
            val c = commands[id]
            assertTrue(c != null, "$id is not an engine command")
            assertTrue(!commands.getValue(source.reader).mutates, "${source.reader} changes the document; a form may only read with a reader")
            val fields = parseParamForm(c!!.params).fields.map { it.name }.toSet()
            for (f in source.fields.keys) assertTrue(f in fields, "$id has no field '$f' (it has $fields)")
        }
    }
}
