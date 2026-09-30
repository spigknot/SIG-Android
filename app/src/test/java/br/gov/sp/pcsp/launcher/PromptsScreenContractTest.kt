package br.gov.sp.pcsp.launcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural vaccine for the two simultaneous, independent section editors. */
class PromptsScreenContractTest {
    private fun source(path: String): File = listOf(File("src/main/$path"), File("app/src/main/$path"))
        .first { it.isFile }

    @Test
    fun historyThenStatementAreRealTabsWithSeparateSectionContainers() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("res/layout/activity_prompts_settings.xml"))
        assertEquals(1, doc.getElementsByTagName("TabHost").length)
        val xml = source("res/layout/activity_prompts_settings.xml").readText()
        assertTrue(xml.indexOf("prompt_history_sections") < xml.indexOf("prompt_statement_sections"))
        assertTrue(xml.contains("prompt_history_sections"))
        assertTrue(xml.contains("prompt_statement_sections"))
        assertFalse(xml.contains("button_save_as_prompt"))
        assertFalse(xml.contains("prompt_type_kind"))
    }

    @Test
    fun plusIsInSectionHeaderAndReusesTranscriptionButtonAppearance() {
        val candidates = listOf(File("src/main/res/layout/view_prompt_section_header.xml"),
            File("app/src/main/res/layout/view_prompt_section_header.xml"))
        assertTrue("Each section needs a header-owned plus, outside the radios", candidates.any { it.isFile })
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val header = builder.parse(candidates.first { it.isFile })
        val original = builder.parse(source("res/layout/activity_remote_stt_transcription.xml"))
        val plus = header.getElementsByTagName("ImageButton").item(0) as org.w3c.dom.Element
        val buttons = original.getElementsByTagName("ImageButton")
        val select = (0 until buttons.length).map { buttons.item(it) as org.w3c.dom.Element }
            .first { it.getAttribute("android:id") == "@+id/button_select_media" }
        for (attr in listOf("layout_width", "background", "foreground", "padding", "scaleType", "src")) {
            assertEquals(attr, select.getAttribute("android:$attr"), plus.getAttribute("android:$attr"))
        }
        val title = header.getElementsByTagName("TextView").item(0) as org.w3c.dom.Element
        assertEquals("1", title.getAttribute("android:layout_weight"))
        assertEquals("36dp", plus.getAttribute("android:layout_height"))
    }

    @Test
    fun refreshIsOneAccessibleIconAtGlobalHeaderEndOnly() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("res/layout/activity_prompts_settings.xml"))
        val buttons = doc.getElementsByTagName("ImageButton")
        val updates = (0 until buttons.length).map { buttons.item(it) as org.w3c.dom.Element }
            .filter { it.getAttribute("android:id") == "@+id/button_update_prompts" }
        assertEquals(1, updates.size)
        val update = updates.single()
        assertEquals("end|center_vertical", update.getAttribute("android:layout_gravity"))
        assertEquals("Atualizar prompts", update.getAttribute("android:contentDescription"))
        assertEquals("@drawable/ic_prompts_refresh", update.getAttribute("android:src"))
        assertEquals("48dp", update.getAttribute("android:layout_width"))
        assertEquals("", update.getAttribute("android:text"))
        val back = (0 until buttons.length).map { buttons.item(it) as org.w3c.dom.Element }
            .first { it.getAttribute("android:id") == "@+id/btnBack" }
        assertEquals(back.parentNode, update.parentNode)
        val activity = source("java/br/gov/sp/pcsp/launcher/PromptsSettingsActivity.kt").readText()
        assertFalse(activity.contains("actionButton(\"Atualizar\")"))
        assertTrue(activity.contains("listOf(save, import, delete)"))
        assertTrue(activity.contains("findViewById<ImageButton>(R.id.button_update_prompts).setOnClickListener { updateFromR2() }"))
        val vector = source("res/drawable/ic_prompts_refresh.xml").readText()
        assertTrue(vector.contains("android:strokeColor=\"#FF7CD98A\""))
        assertTrue(vector.contains("android:fillColor=\"@android:color/transparent\""))
    }

    @Test
    fun systemGrows50PercentAndUserHalvesFromCurrentHeightWithoutChangingCreationDialog() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("res/values/prompts_dimensions.xml"))
        val dimensions = doc.getElementsByTagName("dimen")
        assertEquals(2, dimensions.length)
        val values = (0 until dimensions.length).associate {
            val dimension = dimensions.item(it) as org.w3c.dom.Element
            dimension.getAttribute("name") to dimension.textContent.removeSuffix("dp").toBigDecimal()
        }
        for ((name, factor) in mapOf("prompt_tab_system_editor_height" to "1.5", "prompt_tab_user_editor_height" to "0.5")) {
            assertEquals(name, 0, java.math.BigDecimal("239.4").multiply(java.math.BigDecimal(factor)).compareTo(values.getValue(name)))
        }
        val activity = source("java/br/gov/sp/pcsp/launcher/PromptsSettingsActivity.kt").readText()
        assertTrue(activity.contains("if (slot.isSystem) R.dimen.prompt_tab_system_editor_height else R.dimen.prompt_tab_user_editor_height"))
        assertEquals(1, Regex("resources.getDimensionPixelSize\\(editorHeight\\)").findAll(activity).count())
        assertEquals(4, Regex("buildSection\\(PromptSlot\\.").findAll(activity).count())
        val creation = activity.substringAfter("private fun createPrompt").substringBefore("private fun confirmDelete")
        assertFalse(creation.contains("prompt_tab_"))
        assertFalse(creation.contains("editorHeight"))
        assertTrue(creation.contains("ViewGroup.LayoutParams.WRAP_CONTENT"))
    }

    @Test
    fun footerStatusIsReservedScrollableAndCannotShiftTabs() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("res/layout/activity_prompts_settings.xml"))
        val nodes = doc.getElementsByTagName("*")
        val elements = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        val status = elements.single { it.getAttribute("android:id") == "@+id/prompt_status_area" }
        val message = elements.single { it.getAttribute("android:id") == "@+id/prompt_message" }
        val tabs = elements.single { it.getAttribute("android:id") == "@+id/prompt_tabs" }
        assertEquals(doc.documentElement, status.parentNode)
        assertEquals("ScrollView", status.tagName)
        assertEquals("56dp", status.getAttribute("android:layout_height"))
        assertEquals("64dp", status.getAttribute("android:layout_marginBottom"))
        assertEquals("bottom", status.getAttribute("android:layout_gravity"))
        assertEquals("", status.getAttribute("android:visibility"))
        assertEquals(status, message.parentNode)
        assertEquals("polite", message.getAttribute("android:accessibilityLiveRegion"))
        assertEquals("120dp", (tabs.parentNode as org.w3c.dom.Element).getAttribute("android:paddingBottom"))
        assertFalse(tabs.parentNode == message.parentNode)
    }

    @Test
    fun tabsKeepRequestedMixedCaseLabels() {
        val activity = source("java/br/gov/sp/pcsp/launcher/PromptsSettingsActivity.kt").readText()
        assertTrue(activity.contains("title.isAllCaps = false"))
    }

    @Test
    fun defaultEditorsRemainEditableButCannotSaveDeleteOrRestoreTemporaryDrafts() {
        val activity = source("java/br/gov/sp/pcsp/launcher/PromptsSettingsActivity.kt").readText()
        assertTrue(activity.contains("section.editor.isEnabled = true"))
        assertFalse(activity.contains("section.editor.isEnabled = !active.isDefault"))
        assertFalse(activity.contains("keyListener = null"))
        assertFalse(activity.contains("isFocusable = false"))
        assertTrue(activity.contains("InputType.TYPE_TEXT_FLAG_MULTI_LINE"))
        assertTrue(activity.contains("section.save.isEnabled = !active.isDefault"))
        assertTrue(activity.contains("section.delete.isEnabled = !active.isDefault"))
        assertEquals(2, Regex("if \\(entry.isDefault\\) return").findAll(activity).count())
        assertTrue(activity.contains("if (section.current?.isDefault == false)"))
        assertTrue(activity.contains("sections.values.filter { it.current?.isDefault == false }"))
    }
}
