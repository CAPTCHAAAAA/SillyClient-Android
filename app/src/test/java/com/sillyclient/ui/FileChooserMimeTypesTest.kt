package com.sillyclient.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChooserMimeTypesTest {

    private fun resolve(vararg acceptTypes: String): FileChooserMimeTypes.Filter =
        FileChooserMimeTypes.resolve(arrayOf(*acceptTypes))

    @Test
    fun worldBookAcceptLeavesPickerUnfiltered() {
        // 世界书导入的真实 accept 值，扩展名无法通过 SAF 过滤。
        val filter = resolve(".json", ".lorebook", ".png")

        assertEquals("*/*", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun singleJsonExtensionLeavesPickerUnfiltered() {
        // 厂商把 JSON 上报成 octet-stream 时，按 application/json 过滤会置灰。
        val filter = resolve(".json")

        assertEquals("*/*", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun multipleKnownExtensionsLeavePickerUnfiltered() {
        val filter = resolve(".json", ".png")

        assertEquals("*/*", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun bareExtensionTokenLeavesPickerUnfiltered() {
        val filter = resolve("json")

        assertEquals("*/*", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun mimeWildcardIsPreserved() {
        val filter = resolve("image/*")

        assertEquals("image/*", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun pureMimeTokensKeepFiltering() {
        val filter = resolve("image/png", "image/jpeg")

        assertEquals("*/*", filter.type)
        assertEquals(listOf("image/png", "image/jpeg"), filter.extraMimeTypes)
    }

    @Test
    fun pureMimeTokensAreNormalizedAndDeduplicated() {
        val filter = resolve(" application/JSON ; charset=utf-8 ", "image/PNG", "application/json")

        assertEquals("*/*", filter.type)
        assertEquals(listOf("application/json", "image/png"), filter.extraMimeTypes)
    }

    @Test
    fun extensionTokenMixedWithMimeLeavesPickerUnfiltered() {
        val filter = resolve(" .JSON , application/json ", "image/PNG")

        assertEquals("*/*", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun emptyAcceptLeavesPickerUnfiltered() {
        val filter = FileChooserMimeTypes.resolve(emptyArray<String>())

        assertEquals("*/*", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun explicitWildcardLeavesPickerUnfiltered() {
        val filter = resolve("application/json", "*/*")

        assertEquals("*/*", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun invalidMimeTokenLeavesPickerUnfiltered() {
        val filter = resolve("movie/format/unsupported")

        assertEquals("*/*", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun mimeTypeParametersAreDropped() {
        val filter = resolve("application/json; charset=utf-8")

        assertEquals("application/json", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }

    @Test
    fun singleMimeTokenUsesConcreteType() {
        val filter = resolve("image/png")

        assertEquals("image/png", filter.type)
        assertTrue(filter.extraMimeTypes.isEmpty())
    }
}
