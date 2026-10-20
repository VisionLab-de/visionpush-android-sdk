package de.visionlab.visionpush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * The shipped backup-exclusion resources must cover exactly the SDK's identity files
 * (and nothing the user would want restored), and must NOT be wired into the library
 * manifest — see README "Backup & Restore" for why (single-attribute merge conflict).
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {

    private val identityFiles = setOf(
        "${DeviceRegistrar.PREFS_FILE_NAME}.xml",
        "${DeviceRegistrar.FALLBACK_PREFS_FILE_NAME}.xml",
    )

    /** Files that must SURVIVE a restore and therefore must not be excluded. */
    private val keepFiles = setOf("visionpush_consent.xml", "${DeviceRegistrar.STATE_PREFS_FILE_NAME}.xml")

    /** Collects `<exclude path=…>` values, grouped by the enclosing section tag. */
    private fun excludesOf(resId: Int): Map<String, Set<String>> {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(resId)
        val result = mutableMapOf<String, MutableSet<String>>()
        val stack = ArrayDeque<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "exclude") {
                        val path = requireNotNull(parser.getAttributeValue(null, "path"))
                        assertEquals("sharedpref", parser.getAttributeValue(null, "domain"))
                        result.getOrPut(stack.last()) { mutableSetOf() }.add(path)
                    } else {
                        stack.addLast(parser.name)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name != "exclude") stack.removeLast()
            }
            event = parser.next()
        }
        return result
    }

    @Test
    fun fullBackupContentExcludesIdentityFilesOnly() {
        val excludes = excludesOf(R.xml.visionpush_backup_rules).getValue("full-backup-content")
        assertTrue("$excludes", excludes.containsAll(identityFiles))
        assertTrue(keepFiles.none { it in excludes })
    }

    @Test
    fun dataExtractionRulesExcludeIdentityFilesInBothSections() {
        val excludes = excludesOf(R.xml.visionpush_data_extraction_rules)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val paths = excludes.getValue(section)
            assertTrue("$section: $paths", paths.containsAll(identityFiles))
            assertTrue(keepFiles.none { it in paths })
        }
    }

    @Test
    fun libraryManifestDoesNotForceBackupAttributesOntoHosts() {
        // Deliberate: these are single attributes on <application>; the manifest merger
        // fails the host build when it declares its own rules (the Studio templates do).
        val manifest = File(System.getProperty("user.dir"), "src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.isFile)
        val text = manifest.readText()
        assertFalse(text.contains("fullBackupContent"))
        assertFalse(text.contains("dataExtractionRules"))
        assertFalse(text.contains("allowBackup"))
    }
}
