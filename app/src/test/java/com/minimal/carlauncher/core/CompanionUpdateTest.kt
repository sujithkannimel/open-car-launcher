package com.minimal.carlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionUpdateTest {

    private fun release(tag: String, vararg assets: String, draft: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"assets":[""" +
            assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://github.com/x/releases/download/$tag/$it"}""" } +
            "]}"

    @Test fun versionFromAssetName() {
        assertEquals("1.2", CompanionUpdate.versionOf("car-companion-1.2.apk"))
        assertEquals("0.26", CompanionUpdate.versionOf("car-companion-v0.26.apk"))
        assertNull(CompanionUpdate.versionOf("car-launcher-v0.27-debug.apk"))
    }

    @Test fun comparesNumerically() {
        assertTrue(CompanionUpdate.compare("1.10", "1.9") > 0)
        assertEquals(0, CompanionUpdate.compare("1.2", "1.2.0"))
        assertTrue(CompanionUpdate.compare("1.1", "1.2") < 0)
    }

    @Test fun findsNewestAndIgnoresLauncherAndOldNames() {
        val json = "[" + listOf(
            release("v0.28", "car-launcher-v0.28-debug.apk", "car-companion-1.3.apk"),
            release("v0.27", "car-launcher-v0.27-debug.apk", "car-companion-1.2.apk"),
            release("v0.26", "car-launcher-v0.26-debug.apk", "car-companion-v0.26.apk"),
        ).joinToString(",") + "]"
        val u = CompanionUpdate.newest(json, "1.2")!!
        assertEquals("1.3", u.version)
        assertEquals("v0.28", u.tag)
        assertTrue(u.url.endsWith("car-companion-1.3.apk"))
    }

    @Test fun nothingWhenUpToDateOrDraft() {
        val json = "[" + release("v0.29", "car-companion-1.4.apk", draft = true) + "," +
            release("v0.27", "car-companion-1.2.apk") + "]"
        assertNull(CompanionUpdate.newest(json, "1.2"))
        assertNull(CompanionUpdate.newest("not json", "1.0"))
    }
}
