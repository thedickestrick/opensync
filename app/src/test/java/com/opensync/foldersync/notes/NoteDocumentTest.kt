package com.opensync.foldersync.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NoteDocumentTest {

    private fun doc(md: String) = NoteDocument.parse(md)
    private fun styles(d: NoteDocument, from: Int, to: Int) =
        d.spans.filter { it.start <= from && it.end >= to }.map { it.style }.toSet()

    // ------------------------------------------------------------ parsing

    @Test fun `bold italic strike code and links become spans and lose their markers`() {
        val d = doc("a **bold** _it_ ~~gone~~ `code` [docs](http://x) z")
        assertEquals("a bold it gone code docs z", d.text)
        assertEquals(setOf(Style.BOLD), styles(d, 2, 6))
        assertEquals(setOf(Style.ITALIC), styles(d, 7, 9))
        assertEquals(setOf(Style.STRIKE), styles(d, 10, 14))
        assertEquals(setOf(Style.CODE), styles(d, 15, 19))
        assertEquals("http://x", d.spans.first { it.style == Style.LINK }.url)
    }

    @Test fun `headings quotes bullets and checklists`() {
        val d = doc("# Title\n> quoted\n- milk\n- [ ] todo\n- [x] done\n* star")
        assertEquals("Title\nquoted\n${BULLET}milk\n${TODO}todo\n${DONE}done\n${BULLET}star", d.text)
        assertEquals(Style.H1, d.lineStyle(0)?.style)
        assertEquals(Style.QUOTE, d.lineStyle(6)?.style)
    }

    @Test fun `nested and adjacent emphasis`() {
        val d = doc("**bold *both* bold** and *a***b**")
        assertEquals("bold both bold and ab", d.text)
        assertEquals(setOf(Style.BOLD, Style.ITALIC), styles(d, 5, 9))
        assertEquals(setOf(Style.BOLD), styles(d, 0, 4))
        assertEquals(setOf(Style.ITALIC), styles(d, 19, 20))
        assertEquals(setOf(Style.BOLD), styles(d, 20, 21))
    }

    @Test fun `underscores inside words are not italics`() {
        val d = doc("snake_case_name and 2 * 3 * 4")
        assertEquals("snake_case_name and 2 * 3 * 4", d.text)
        assertTrue(d.spans.isEmpty())
    }

    @Test fun `unmatched markers are just text`() {
        assertEquals("**not closed", doc("**not closed").text)
        assertEquals("a * b", doc("a * b").text)
        assertEquals("*", doc("*").text)
        assertEquals("**", doc("**").text)
        assertEquals("****", doc("****").text)
    }

    @Test fun `escapes read as literal characters`() {
        val d = doc("\\*not italic\\* and \\# not heading")
        assertEquals("*not italic* and # not heading", d.text)
        assertTrue(d.spans.isEmpty())
    }

    @Test fun `code fences tables and images pass through untouched`() {
        val md = "```kotlin\nval x = **not bold**\n```\n| a | b |\n| --- | --- |\n![pic](p.png) tail"
        val d = doc(md)
        assertEquals(md, d.text)
        assertTrue(d.spans.isEmpty())
        assertEquals(md, d.toMarkdown())
    }

    // ------------------------------------------------------------ round trips

    private val canonical = listOf(
        "", "plain", "a **bold** b", "**bold**", "*it*", "~~s~~", "`c`", "[t](u)",
        "# H1\n## H2\n### H3\n#### H4", "> q", "- a\n- b", "- [ ] t\n- [x] d",
        "**a *b* c**", "*a **b** c*", "[**bold link**](u)", "**x [l](u) y**",
        "line1\n\nline3", "- **bold item**", "# **bold heading**", "> *quiet*",
        "a\n", "\n\na", "- \n- ", "# ", "\\*literal\\*", "\\# not a heading", "\\- not a bullet",
        "2 \\*3", "a \\_b c", "**a**\n**b**", "`a` `b`", "tail **bold**", "**bold** head",
        "![pic](p.png) tail", "a\\[b](c)", "[a\\]b](u)", "a\\`b",
    )

    @Test fun `canonical markdown survives a round trip exactly`() {
        for (md in canonical) assertEquals("round trip of $md", md, doc(md).toMarkdown())
    }

    @Test fun `any markdown is stable after one round trip`() {
        val samples = canonical + listOf(
            "a **b", "*a **b*", "**  padded  **", "`**x**`", "[a](b) [c](d)", "***x***", "___x___",
            "a __b__ c", "snake_case", "* * *", "---", "1. one\n2. two", "|a|b|", "x\\y", "C:\\Users",
            "**a****b**", "*a**b*", "**bo**ld", "a*b*c", "_a_b", "a_b_", "`unclosed", "[unclosed](",
            "![i](p) and [l](u)", "> # not heading", "- - -", "-  two spaces", "#  two spaces",
        )
        for (md in samples) {
            val once = doc(md)
            val again = doc(once.toMarkdown())
            assertEquals("text after round trip of $md", once.text, again.text)
            assertEquals("spans after round trip of $md", once.spans, again.spans)
            assertEquals("markdown stable for $md", once.toMarkdown(), again.toMarkdown())
        }
    }

    @Test fun `random documents survive a round trip`() {
        val rnd = Random(42)
        val alphabet = "ab *_~`[]()#-\\!x\n"
        val inline = listOf(Style.BOLD, Style.ITALIC, Style.STRIKE, Style.CODE, Style.LINK)
        repeat(3000) {
            val text = String(CharArray(rnd.nextInt(0, 24)) { alphabet[rnd.nextInt(alphabet.length)] })
            val spans = ArrayList<Span>()
            repeat(rnd.nextInt(0, 4)) {
                if (text.isEmpty()) return@repeat
                val a = rnd.nextInt(text.length + 1)
                val b = rnd.nextInt(text.length + 1)
                val st = inline[rnd.nextInt(inline.size)]
                spans.add(Span(minOf(a, b), maxOf(a, b), st, if (st == Style.LINK) "u" else null))
            }
            if (rnd.nextBoolean() && text.isNotEmpty()) {
                spans.add(Span(rnd.nextInt(text.length), 0, if (rnd.nextBoolean()) Style.QUOTE else Style.heading(rnd.nextInt(1, 4))))
            }
            val d = NoteDocument(text, spans)
            val md = d.toMarkdown()
            val back = doc(md)
            assertEquals("text of ${d.text} spans=${d.spans} | md=$md | back spans=${back.spans}", d.text, back.text)
            // emphasis on whitespace or under code can't be written down; everything else must come back
            val again = doc(back.toMarkdown())
            assertEquals("stable text for $md", back.text, again.text)
            assertEquals("stable spans for $md", back.spans, again.spans)
        }
    }

    // ------------------------------------------------------------ editing

    @Test fun `typing at the end of bold continues it and typing in front does not`() {
        val d = doc("a **bold** b")
        val (after, _) = d.typed(Edit(6, 6, "X"))
        assertEquals("a boldX b", after.text)
        assertEquals(setOf(Style.BOLD), styles(after, 2, 7))
        val (front, _) = d.typed(Edit(2, 2, "X"))
        assertEquals("a Xbold b", front.text)
        assertEquals(setOf(Style.BOLD), styles(front, 3, 7))
        assertTrue(styles(front, 2, 3).isEmpty())
    }

    @Test fun `pending style starts bold with nothing selected and can stop it`() {
        val d = doc("plain")
        val (on, _) = d.typed(Edit(5, 5, "B"), mapOf(Style.BOLD to true))
        assertEquals(setOf(Style.BOLD), styles(on, 5, 6))
        val (more, _) = on.typed(Edit(6, 6, "C"))
        assertEquals(setOf(Style.BOLD), styles(more, 5, 7))
        val (off, _) = more.typed(Edit(7, 7, "D"), mapOf(Style.BOLD to false))
        assertEquals("plainBCD", off.text)
        assertTrue(styles(off, 7, 8).isEmpty())
        assertEquals(setOf(Style.BOLD), styles(off, 5, 7))
    }

    @Test fun `deleting the whole of a bold word drops the span and selecting across keeps the rest`() {
        val d = doc("a **bold** b")
        val (gone, _) = d.typed(Edit(2, 6, ""))
        assertEquals("a  b", gone.text)
        assertTrue(gone.spans.isEmpty())
        assertEquals("a  b", gone.toMarkdown())
        val (part, _) = d.typed(Edit(1, 4, ""))
        assertEquals("ald b", part.text)
        assertEquals(setOf(Style.BOLD), styles(part, 1, 3))
        assertEquals("a**ld** b", part.toMarkdown())
    }

    @Test fun `enter continues a list and enter on an empty item ends it`() {
        val d = doc("- milk")
        val (next, caret) = d.typed(Edit(6, 6, "\n"))
        assertEquals("${BULLET}milk\n$BULLET", next.text)
        assertEquals(next.text.length, caret)
        val (ended, caret2) = next.typed(Edit(caret, caret, "\n"))
        assertEquals("${BULLET}milk\n", ended.text)
        assertEquals(ended.text.length, caret2)
        val (todo, _) = doc("- [x] done").typed(Edit(6, 6, "\n"))
        assertEquals("${DONE}done\n$TODO", todo.text)
    }

    @Test fun `backspace into a list glyph removes the glyph whole`() {
        val d = doc("- milk")
        val (un, caret) = d.typed(Edit(1, 2, ""))
        assertEquals("milk", un.text)
        assertEquals(0, caret)
        assertEquals("milk", un.toMarkdown())
    }

    @Test fun `enter in a heading keeps the heading on the first half`() {
        val d = doc("# Title")
        val (split, _) = d.typed(Edit(3, 3, "\n"))
        assertEquals("Tit\nle", split.text)
        assertEquals(Style.H1, split.lineStyle(0)?.style)
        assertEquals(null, split.lineStyle(4))
        assertEquals("# Tit\nle", split.toMarkdown())
        val (pushed, _) = d.typed(Edit(0, 0, "\n"))
        assertEquals("\n# Title", pushed.toMarkdown())
    }

    @Test fun `toggling styles and line styles`() {
        var d = doc("hello world")
        d = d.toggleInline(0, 5, Style.BOLD)
        assertEquals("**hello** world", d.toMarkdown())
        d = d.toggleInline(0, 5, Style.BOLD)
        assertEquals("hello world", d.toMarkdown())
        d = d.toggleInline(0, 11, Style.ITALIC).toggleInline(6, 11, Style.BOLD)
        assertEquals("*hello **world***", d.toMarkdown())
        d = d.setLineStyle(0, 0, Style.H2)
        assertEquals("## *hello **world***", d.toMarkdown())
        d = d.setLineStyle(0, 0, null)
        val (bul, c) = d.toggleGlyph(0, 0, BULLET, 3)
        assertEquals("- *hello **world***", bul.toMarkdown())
        assertEquals(5, c)
        val (unbul, c2) = bul.toggleGlyph(0, 0, BULLET, 5)
        assertEquals("*hello **world***", unbul.toMarkdown())
        assertEquals(3, c2)
    }

    @Test fun `checkbox toggling by line index`() {
        val d = doc("x\n- [ ] a\n- [x] b")
        assertEquals("x\n- [x] a\n- [x] b", d.toggleCheckbox(1).toMarkdown())
        assertEquals("x\n- [ ] a\n- [ ] b", d.toggleCheckbox(2).toMarkdown())
        assertEquals(d, d.toggleCheckbox(0))
    }

    @Test fun `random edits never corrupt spans`() {
        val rnd = Random(7)
        val alphabet = "ab *_\n"
        repeat(2000) {
            var d = doc(listOf("a **bold** b", "# H\n- a\n- b", "> q *i* z", "[l](u) x", "").random(rnd))
            repeat(12) {
                val len = d.text.length
                val a = rnd.nextInt(len + 1)
                val b = if (rnd.nextInt(3) == 0) rnd.nextInt(a, len + 1) else a
                val ins = if (b > a && rnd.nextBoolean()) "" else String(CharArray(rnd.nextInt(0, 3)) { alphabet[rnd.nextInt(alphabet.length)] })
                val (next, caret) = d.typed(Edit(a, b, ins), if (rnd.nextInt(4) == 0) mapOf(Style.BOLD to true) else emptyMap())
                assertTrue("caret in range", caret in 0..next.text.length)
                for (s in next.spans) {
                    assertTrue("span in range $s of ${next.text.length}", s.start in 0..next.text.length && s.end in s.start..next.text.length)
                    if (!s.style.inline) {
                        assertEquals("line span starts a line $s", s.start, next.lineStart(s.start))
                        assertEquals("line span ends its line $s", s.end, next.lineEnd(s.start))
                    } else assertTrue("inline span non-empty $s", s.end > s.start)
                }
                // what we write down must read back as the same document
                val back = doc(next.toMarkdown())
                assertEquals("text survives save/load: spans=${next.spans} | md=${next.toMarkdown()} | back spans=${back.spans}", next.text, back.text)
                d = next
            }
        }
    }
}
