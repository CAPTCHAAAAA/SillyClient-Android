package com.sillyclient.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class CompanionPresetInstallerTest {

    private val realAssetsRoot = File("src/main/assets")

    private fun realAssetReader(relativePath: String): ByteArray {
        val file = File(realAssetsRoot, relativePath)
        require(file.isFile) { "Asset file not found: ${file.absolutePath}" }
        return file.readBytes()
    }

    private fun setupMockServerDir(): File {
        val root = Files.createTempDirectory("sillyclient-preset-test").toFile()
        val defaultContentDir = File(root, "default/content")
        defaultContentDir.mkdirs()
        File(defaultContentDir, "settings.json").writeText(
            JSONObject()
                .put("power_user", JSONObject().put("theme", "Default"))
                .put("background", JSONObject())
                .toString(2)
        )
        return root
    }

    @Test
    fun realAssetsMatchInstallerConstants() {
        val manifestBytes = realAssetReader("companion-presets/sc-bordeaux/manifest.json")
        val manifest = JSONObject(manifestBytes.toString(StandardCharsets.UTF_8))

        assertEquals("sc-bordeaux", manifest.getString("bundleId"))
        assertEquals(CompanionPresetInstaller.REVISION, manifest.getInt("revision"))
        assertEquals(2, CompanionPresetInstaller.REVISION)

        val themeBytes = realAssetReader("companion-presets/sc-bordeaux/themes/SC Bordeaux.json")
        val theme = JSONObject(themeBytes.toString(StandardCharsets.UTF_8))
        assertEquals(14, theme.getInt("blur_strength"))
    }

    @Test
    fun freshInstallAppliesPresetAndCreatesMarker() {
        val serverDir = setupMockServerDir()
        try {
            val tx = CompanionPresetInstaller.installInternal(
                ::realAssetReader,
                serverDir,
                CompanionPresetRequest(bundleId = "sc-bordeaux", revision = 2)
            )
            assertTrue("Fresh install must apply transaction", tx.applied)
            tx.commit()

            val themeFile = File(serverDir, "data/default-user/themes/SC Bordeaux.json")
            val wallpaperFile = File(serverDir, "data/default-user/backgrounds/sillyclient-bg-8k.jpg")
            val markerFile = File(serverDir, ".sillyclient/companion-presets/sc-bordeaux.json")
            val settingsFile = File(serverDir, "data/default-user/settings.json")

            assertTrue(themeFile.isFile)
            assertTrue(wallpaperFile.isFile)
            assertTrue(markerFile.isFile)
            assertTrue(settingsFile.isFile)

            val marker = JSONObject(markerFile.readText())
            assertEquals(2, marker.getInt("revision"))

            val settings = JSONObject(settingsFile.readText())
            val powerUser = settings.getJSONObject("power_user")
            assertEquals("SC Bordeaux", powerUser.getString("theme"))
            assertEquals(14, powerUser.getInt("blur_strength"))
        } finally {
            serverDir.deleteRecursively()
        }
    }

    @Test
    fun subsequentInstallIsNoOpWhenUpToDate() {
        val serverDir = setupMockServerDir()
        try {
            val tx1 = CompanionPresetInstaller.installInternal(
                ::realAssetReader,
                serverDir,
                CompanionPresetRequest(bundleId = "sc-bordeaux", revision = 2)
            )
            assertTrue(tx1.applied)
            tx1.commit()

            val tx2 = CompanionPresetInstaller.installInternal(
                ::realAssetReader,
                serverDir,
                CompanionPresetRequest(bundleId = "sc-bordeaux", revision = 2)
            )
            assertFalse("Second run should be no-op", tx2.applied)
        } finally {
            serverDir.deleteRecursively()
        }
    }

    @Test
    fun upgradeFromOldRevisionWithDefaultThemeUpdatesSettingsAndMarker() {
        val serverDir = setupMockServerDir()
        try {
            val userDir = File(serverDir, "data/default-user")
            userDir.mkdirs()
            val settingsFile = File(userDir, "settings.json")
            settingsFile.writeText(
                JSONObject()
                    .put("power_user", JSONObject().put("theme", "SC Bordeaux").put("blur_strength", 32))
                    .put("background", JSONObject().put("name", "sillyclient-bg-8k.jpg"))
                    .toString(2)
            )

            // Simulate legacy revision 1 marker
            val markerDir = File(serverDir, ".sillyclient/companion-presets")
            markerDir.mkdirs()
            val markerFile = File(markerDir, "sc-bordeaux.json")
            markerFile.writeText(
                JSONObject()
                    .put("schema", "sillyclient.companion-preset-applied")
                    .put("bundleId", "sc-bordeaux")
                    .put("revision", 1)
                    .put("themeSha256", "OLD_HASH")
                    .put("wallpaperSha256", "FA17565F1A3CB8AC6FB4F55E3D8FFE2A8200CC1AC60B9C38DEF2D225938E187E")
                    .toString(2)
            )

            // Legacy client requests with revision 1 or 2
            val tx = CompanionPresetInstaller.installInternal(
                ::realAssetReader,
                serverDir,
                CompanionPresetRequest(bundleId = "sc-bordeaux", revision = 1)
            )
            assertTrue("Upgrade must be applied", tx.applied)
            tx.commit()

            val updatedMarker = JSONObject(markerFile.readText())
            assertEquals(2, updatedMarker.getInt("revision"))

            val updatedSettings = JSONObject(settingsFile.readText())
            val powerUser = updatedSettings.getJSONObject("power_user")
            assertEquals("SC Bordeaux", powerUser.getString("theme"))
            assertEquals(14, powerUser.getInt("blur_strength"))
        } finally {
            serverDir.deleteRecursively()
        }
    }

    @Test
    fun upgradeFromOldRevisionWithCustomThemePreservesCustomTheme() {
        val serverDir = setupMockServerDir()
        try {
            val userDir = File(serverDir, "data/default-user")
            userDir.mkdirs()
            val settingsFile = File(userDir, "settings.json")
            settingsFile.writeText(
                JSONObject()
                    .put("power_user", JSONObject().put("theme", "ObsidianDark").put("blur_strength", 8))
                    .put("background", JSONObject().put("name", "my-custom-wallpaper.png"))
                    .toString(2)
            )

            val markerDir = File(serverDir, ".sillyclient/companion-presets")
            markerDir.mkdirs()
            val markerFile = File(markerDir, "sc-bordeaux.json")
            markerFile.writeText(
                JSONObject()
                    .put("schema", "sillyclient.companion-preset-applied")
                    .put("bundleId", "sc-bordeaux")
                    .put("revision", 1)
                    .toString(2)
            )

            val tx = CompanionPresetInstaller.installInternal(
                ::realAssetReader,
                serverDir,
                CompanionPresetRequest(bundleId = "sc-bordeaux", revision = 1)
            )
            assertTrue("Upgrade must be applied", tx.applied)
            tx.commit()

            val updatedMarker = JSONObject(markerFile.readText())
            assertEquals(2, updatedMarker.getInt("revision"))

            // Verify SC Bordeaux theme file on disk is upgraded to new version
            val themeFile = File(serverDir, "data/default-user/themes/SC Bordeaux.json")
            assertTrue(themeFile.isFile)
            val themeJson = JSONObject(themeFile.readText())
            assertEquals(14, themeJson.getInt("blur_strength"))

            // Verify user's settings.json was NOT hijacked!
            val updatedSettings = JSONObject(settingsFile.readText())
            val powerUser = updatedSettings.getJSONObject("power_user")
            assertEquals("User custom theme must be preserved!", "ObsidianDark", powerUser.getString("theme"))
            assertEquals(8, powerUser.getInt("blur_strength"))

            val background = updatedSettings.getJSONObject("background")
            assertEquals("User custom wallpaper must be preserved!", "my-custom-wallpaper.png", background.getString("name"))
        } finally {
            serverDir.deleteRecursively()
        }
    }
}
