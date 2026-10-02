package com.opensync.foldersync.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class NoteExportTest {

    private val sample = """
        # Groceries

        Buy **milk** and _eggs_, see [list](https://example.com/a?b=1&c=2).
        - [ ] bread
        - [x] coffee
        - apples
        1. first
        2. second
        > keep it cheap
        ---
        ```
        code <here> & there
        ```
        | Item | Price |
        | --- | --- |
        | Tea | 3 |
    """.trimIndent()

    @Test fun `blocks follow the viewer's reading of the note`() {
        val b = NoteExport.blocks(sample)
        assertEquals(Block.Heading(1, listOf(Run("Groceries"))), b[0])
        assertEquals(Block.Blank, b[1])
        val para = b[2] as Block.Para
        assertEquals("Buy milk and eggs, see list.", para.runs.plain())
        assertTrue(para.runs.single { it.text == "milk" }.bold)
        assertTrue(para.runs.single { it.text == "eggs" }.italic)
        assertEquals("https://example.com/a?b=1&c=2", para.runs.single { it.text == "list" }.link)
        assertEquals(Block.Check(false, listOf(Run("bread"))), b[3])
        assertEquals(Block.Check(true, listOf(Run("coffee"))), b[4])
        assertEquals(Block.Bullet(listOf(Run("apples"))), b[5])
        assertEquals(Block.Numbered("2.", listOf(Run("second"))), b[7])
        assertEquals(Block.Quote(listOf(Run("keep it cheap"))), b[8])
        assertEquals(Block.Rule, b[9])
        assertEquals(Block.Code("code <here> & there"), b[10])
        val table = b[11] as Block.Table
        assertEquals(listOf("Item", "Price"), table.header.map { it.plain() })
        assertEquals(listOf(listOf("Tea", "3")), table.rows.map { r -> r.map { it.plain() } })
    }

    @Test fun `the title goes on top unless the note already starts with it`() {
        assertEquals(Block.Heading(1, listOf(Run("Groceries"))), NoteExport.blocks(sample, "groceries").first())
        assertEquals(1, NoteExport.blocks(sample, "groceries").count { it is Block.Heading })
        val withTitle = NoteExport.blocks("just text", "My note")
        assertEquals(listOf(Block.Heading(1, listOf(Run("My note"))), Block.Blank, Block.Para(listOf(Run("just text")))), withTitle)
    }

    @Test fun `text that looks like syntax after its marker stays text`() {
        assertEquals(Block.Bullet(listOf(Run("# not a heading"))), NoteExport.blocks("- # not a heading")[0])
        assertEquals(Block.Quote(listOf(Run("- not a bullet"))), NoteExport.blocks("> - not a bullet")[0])
    }

    @Test fun `plain text keeps the shape without the markup`() {
        val t = NoteExport.toPlainText(NoteExport.blocks(sample))
        assertTrue(t.startsWith("Groceries\n=========\n"))
        assertTrue(t.contains("Buy milk and eggs, see list (https://example.com/a?b=1&c=2)."))
        assertTrue(t.contains("☐ bread\n☑ coffee\n• apples\n1. first\n"))
        assertTrue(t.contains("    code <here> & there\n"))
        assertTrue(t.contains("Item | Price\n"))
        assertFalse(t.contains("**"))
    }

    @Test fun `html escapes text and groups list items`() {
        val h = NoteExport.toHtml("A & B", NoteExport.blocks(sample), null)
        assertTrue(h.contains("<title>A &amp; B</title>"))
        assertTrue(h.contains("<strong>milk</strong>"))
        assertTrue(h.contains("<a href=\"https://example.com/a?b=1&amp;c=2\">list</a>"))
        assertTrue(h.contains("<pre><code>code &lt;here&gt; &amp; there</code></pre>"))
        assertEquals(1, Regex("<ol>").findAll(h).count())
        assertTrue(h.contains("<li value=\"2\">second</li>"))
    }

    @Test fun `docx is a well-formed package with styles, links and pictures`() {
        val dir = Files.createTempDirectory("export").toFile()
        val png = File(dir, "pic.png").apply { writeBytes(tinyPng(40, 30)) }
        val md = "$sample\n![shot](${png.name})\n\u0001odd control char"
        val bytes = NoteExport.toDocx(NoteExport.blocks(md, "Groceries"), dir, letter = true)

        val parts = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; parts[e.name] = z.readBytes() }
        }
        assertTrue(parts.keys.containsAll(listOf(
            "[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml",
            "word/_rels/document.xml.rels", "word/media/image1.png"
        )))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for (name in parts.keys.filter { it.endsWith(".xml") || it.endsWith(".rels") }) {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(parts[name]))   // throws if malformed
        }
        val doc = String(parts["word/document.xml"]!!)
        assertTrue(doc.contains("<w:pStyle w:val=\"Heading1\"/>"))
        assertTrue(doc.contains("<w:hyperlink r:id="))
        assertTrue(doc.contains("<wp:extent cx=\"${40 * 9525}\" cy=\"${30 * 9525}\"/>"))
        assertTrue(doc.contains("w:w=\"12240\""))
        assertFalse(doc.contains("\u0001"))
        assertTrue(String(parts["word/_rels/document.xml.rels"]!!).contains("https://example.com/a?b=1&amp;c=2"))
        dir.deleteRecursively()
    }

    @Test fun `a missing picture becomes a note of what was there`() {
        val t = NoteExport.toPlainText(NoteExport.blocks("![holiday](nope.jpg)"))
        assertEquals("[Image: holiday]\n", t)
    }

    @Test fun `image sizes come from the file header`() {
        assertEquals(40 to 30, NoteExport.imageSize(tinyPng(40, 30)))
        // Smallest JPEG shape the reader cares about: SOI, an APP0 segment, then SOF0 with 7×5.
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
            0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 5, 0, 7, 1, 1, 0x11, 0
        )
        assertEquals(7 to 5, NoteExport.imageSize(jpeg))
    }

    /** A PNG header with the given size — enough for the size reader and for Word's package. */
    private fun tinyPng(w: Int, h: Int): ByteArray {
        val b = ByteArray(33)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10).copyInto(b)
        b[11] = 13; b[12] = 'I'.code.toByte(); b[13] = 'H'.code.toByte(); b[14] = 'D'.code.toByte(); b[15] = 'R'.code.toByte()
        fun put32(at: Int, v: Int) { for (k in 0..3) b[at + k] = (v shr (24 - 8 * k)).toByte() }
        put32(16, w); put32(20, h)
        return b
    }
}
