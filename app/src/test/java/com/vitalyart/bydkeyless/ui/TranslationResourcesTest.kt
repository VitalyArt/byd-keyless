package com.vitalyart.bydkeyless.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TranslationResourcesTest {
    @Test fun russianAndUzbekResourcesCoverEveryLocalizedKey() {
        val res = sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
        val english = keys(File(res, "values/strings.xml"))
        val intentionallyUniversal = setOf(
            "app_name", "brand_name", "language_english", "language_russian", "language_uzbek",
        )
        listOf("values-ru", "values-uz").forEach { qualifier ->
            val translated = keys(File(res, "$qualifier/strings.xml"))
            assertEquals("Missing translations in $qualifier", emptySet<String>(), english - translated - intentionallyUniversal)
        }
    }

    private fun keys(file: File): Set<String> = Regex("<string name=\"([^\"]+)\"")
        .findAll(file.readText()).map { it.groupValues[1] }.toSet()
}
