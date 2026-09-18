package com.vitalyart.bydkeyless.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ApplicationManifestTest {
    private val main = sequenceOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test fun doesNotRequestAudioCapturePermissions() {
        val permissions = elements("AndroidManifest.xml", "uses-permission").map { it.android("name") }
        assertFalse(permissions.contains("android.permission.RECORD_AUDIO"))
        assertFalse(permissions.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(permissions.contains("android.permission.BLUETOOTH_CONNECT"))
    }

    @Test fun keylessServiceOnlyUsesConnectedDeviceForegroundType() {
        val service = elements("AndroidManifest.xml", "service")
            .single { it.android("name") == ".service.KeylessService" }
        assertEquals("connectedDevice", service.android("foregroundServiceType"))
        assertEquals("false", service.android("exported"))
        assertEquals("false", service.android("stopWithTask"))
    }

    @Test fun launcherShortcutsAndWidgetRemainAvailable() {
        val shortcuts = elements("res/xml/shortcuts.xml", "shortcut")
        assertEquals(setOf("quick_unlock", "quick_lock", "quick_trunk"), shortcuts.map { it.android("shortcutId") }.toSet())
        val intents = elements("res/xml/shortcuts.xml", "intent")
        assertEquals(
            setOf("com.vitalyart.bydkeyless.quick.UNLOCK", "com.vitalyart.bydkeyless.quick.LOCK", "com.vitalyart.bydkeyless.quick.OPEN_TRUNK"),
            intents.map { it.android("action") }.toSet(),
        )
        assertTrue(intents.all { it.android("targetClass") == "com.vitalyart.bydkeyless.quick.QuickCommandActivity" })
        assertTrue(elements("AndroidManifest.xml", "receiver").any { it.android("name") == ".widget.QuickControlWidget" })
    }

    @Test fun updaterUsesPrivateFileProviderAndInstallerPermission() {
        val permissions = elements("AndroidManifest.xml", "uses-permission").map { it.android("name") }
        assertTrue(permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        val provider = elements("AndroidManifest.xml", "provider")
            .single { it.android("name") == "androidx.core.content.FileProvider" }
        assertEquals("false", provider.android("exported"))
        assertEquals("true", provider.android("grantUriPermissions"))
        assertEquals("${'$'}{applicationId}.updates", provider.android("authorities"))
    }

    private fun elements(file: String, tag: String): List<Element> {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(main, file))
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(name: String): String = getAttributeNS(androidNamespace, name)
}
