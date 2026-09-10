package io.github.qwqgong.androidcyaml

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.w3c.dom.Element

/** Guard the manifest boundary that separates removable tasks from the core. */
class VpnProcessIsolationTest {
    private val document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
    private val application = document.getElementsByTagName("application").item(0) as Element

    private fun Element.android(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun elements(tag: String): List<Element> {
        val nodes = application.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun process(element: Element): String =
        element.android("process").ifBlank { application.android("process") }

    @Test
    fun coreAndControlShareDedicatedProcessThatHostsNoActivities() {
        val services = elements("service")
        val vpn = services.single { it.android("name") == ".AndroidVpnService" }
        val control = services.single { it.android("name") == ".AppControlService" }
        assertEquals(":vpn", process(vpn))
        assertEquals(process(vpn), process(control))
        elements("activity").forEach {
            assertNotEquals(it.android("name"), process(vpn), process(it))
        }
        assertEquals("false", vpn.android("stopWithTask"))
        assertEquals("false", control.android("stopWithTask"))
        assertEquals("false", control.android("exported"))
        assertEquals("android.permission.BIND_VPN_SERVICE", vpn.android("permission"))
    }
}
