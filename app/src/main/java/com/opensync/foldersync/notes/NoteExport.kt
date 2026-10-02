package com.opensync.foldersync.notes

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Turns a note's Markdown into files other apps open: plain text, a web page and a Word document.
 * (PDF needs Android's drawing classes, so it lives in [NotePdf]; [NoteExporter] picks between them.)
 *
 * Everything starts from [blocks], which reads a note the way the in-app viewer (MarkdownView)
 * shows it — headings, lists, checklists, quotes, rules, fenced code, tables, images — so an
 * exported note looks like the note did on screen. Plain Kotlin, unit-tested directly.
 */

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    DOCX("Word document", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    HTML("Web page", "html", "text/html"),
    TXT("Plain text", "txt", "text/plain"),
    MD("Markdown", "md", "text/markdown");
}

/** A stretch of text with one set of inline styles. */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null
)

sealed interface Block {
    data class Heading(val level: Int, val runs: List<Run>) : Block
    data class Para(val runs: List<Run>) : Block
    data class Bullet(val runs: List<Run>) : Block
    data class Numbered(val label: String, val runs: List<Run>) : Block
    data class Check(val checked: Boolean, val runs: List<Run>) : Block
    data class Quote(val runs: List<Run>) : Block
    data class Code(val text: String) : Block
    data class Table(val header: List<List<Run>>, val rows: List<List<List<Run>>>) : Block
    data class Image(val path: String, val alt: String) : Block
    data object Rule : Block
    data object Blank : Block
}

fun List<Run>.plain(): String = joinToString("") { it.text }

object NoteExport {

    private val IMAGE = Regex("^!\\[(.*?)]\\((.+)\\)\\s*$")
    private val NUMBERED = Regex("^\\d{1,3}\\. .*")
    /** Content that the inline parser would otherwise take for a line of its own syntax. */
    private val BLOCKISH = Regex("^(#{1,6} |> |>$|[-*+] )")

    // ---------------------------------------------------------------- reading

    /** A note's Markdown as the viewer lays it out, with [title] as a heading on top unless it's already there. */
    fun blocks(markdown: String, title: String? = null): List<Block> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.startsWith("```") || t.startsWith("~~~")) {
                val fence = t.take(3)
                val body = ArrayList<String>()
                var j = i + 1
                while (j < lines.size && !lines[j].trim().startsWith(fence)) { body.add(lines[j]); j++ }
                out.add(Block.Code(body.joinToString("\n")))
                i = if (j < lines.size) j + 1 else j
                continue
            }
            if (isTableRow(line) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                val header = parseRow(line).map { inline(it) }
                val rows = ArrayList<List<List<Run>>>()
                var j = i + 2
                while (j < lines.size && isTableRow(lines[j])) { rows.add(parseRow(lines[j]).map { inline(it) }); j++ }
                out.add(Block.Table(header, rows))
                i = j
                continue
            }
            val h = headingLevel(line)
            out.add(
                when {
                    t.isEmpty() -> Block.Blank
                    IMAGE.matches(t) -> IMAGE.find(t)!!.let { Block.Image(it.groupValues[2].trim(), it.groupValues[1]) }
                    t.length >= 3 && t.all { it == '-' } -> Block.Rule
                    h > 0 -> Block.Heading(h, inline(line.substring(h + 1)))
                    line.startsWith("- [ ] ") -> Block.Check(false, inline(line.substring(6)))
                    line.startsWith("- [x] ") || line.startsWith("- [X] ") -> Block.Check(true, inline(line.substring(6)))
                    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> Block.Bullet(inline(line.substring(2)))
                    NUMBERED.matches(line) -> line.indexOf(". ").let { Block.Numbered(line.substring(0, it + 1), inline(line.substring(it + 2))) }
                    line.startsWith("> ") -> Block.Quote(inline(line.substring(2)))
                    line == ">" -> Block.Quote(emptyList())
                    else -> Block.Para(inline(line))
                }
            )
            i++
        }
        // Trailing blank lines are just where the cursor was left.
        while (out.lastOrNull() == Block.Blank) out.removeAt(out.size - 1)
        val name = title?.trim().orEmpty()
        val first = out.firstOrNull { it != Block.Blank }
        val hasTitle = first is Block.Heading && first.runs.plain().trim().equals(name, ignoreCase = true)
        if (name.isNotEmpty() && !hasTitle) {
            out.add(0, Block.Heading(1, listOf(Run(name))))
            if (first != null && out.getOrNull(1) != Block.Blank) out.add(1, Block.Blank)
        }
        return out
    }

    /** One line's inline Markdown → styled runs, using the editor's own parser so both agree. */
    fun inline(s: String): List<Run> {
        if (s.isEmpty()) return emptyList()
        val doc = NoteDocument.parse(if (BLOCKISH.containsMatchIn(s)) "\\" + s else s)
        val text = doc.text
        val spans = doc.spans.filter { it.style.inline }
        val cuts = sortedSetOf(0, text.length)
        for (sp in spans) { cuts.add(sp.start); cuts.add(sp.end) }
        val bounds = cuts.toList()
        val runs = ArrayList<Run>()
        for (k in 0 until bounds.size - 1) {
            val a = bounds[k]
            val b = bounds[k + 1]
            if (a >= b) continue
            val on = spans.filter { it.start <= a && it.end >= b }
            runs.add(
                Run(
                    text.substring(a, b),
                    bold = on.any { it.style == Style.BOLD },
                    italic = on.any { it.style == Style.ITALIC },
                    strike = on.any { it.style == Style.STRIKE },
                    code = on.any { it.style == Style.CODE },
                    link = on.firstOrNull { it.style == Style.LINK }?.url
                )
            )
        }
        return runs
    }

    private fun headingLevel(line: String): Int {
        val n = line.takeWhile { it == '#' }.length
        return if (n in 1..6 && line.length > n && line[n] == ' ') n else 0
    }

    private fun isTableRow(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.count { it == '|' } >= 2
    }

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        if (!t.startsWith("|")) return false
        return t.trim('|').split("|").all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.contains('-') && c.all { it == '-' || it == ':' || it == ' ' }
        }
    }

    private fun parseRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split("|").map { it.trim() }
    }

    /** An image path from a note, relative to the note's folder unless it's absolute. */
    fun resolveImage(baseDir: File?, path: String): File? {
        val clean = path.removePrefix("file://").substringBefore(' ')   // `![](a.png "title")`
        val f = File(clean)
        if (f.isAbsolute) return f.takeIf { it.isFile }
        return baseDir?.let { File(it, clean) }?.takeIf { it.isFile }
    }

    fun safeFileName(title: String): String =
        title.trim().ifBlank { "Note" }.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").take(120)

    // ---------------------------------------------------------------- plain text

    fun toPlainText(blocks: List<Block>): String {
        val sb = StringBuilder()
        fun runs(r: List<Run>) = r.joinToString("") { run ->
            val url = run.link
            if (url != null && url.isNotBlank() && url != run.text) "${run.text} ($url)" else run.text
        }
        for (b in blocks) {
            when (b) {
                is Block.Heading -> {
                    val t = runs(b.runs)
                    sb.append(t).append('\n')
                    if (b.level <= 2) sb.append((if (b.level == 1) "=" else "-").repeat(t.length.coerceIn(3, 72))).append('\n')
                }
                is Block.Para -> sb.append(runs(b.runs)).append('\n')
                is Block.Bullet -> sb.append(BULLET).append(runs(b.runs)).append('\n')
                is Block.Numbered -> sb.append(b.label).append(' ').append(runs(b.runs)).append('\n')
                is Block.Check -> sb.append(if (b.checked) DONE else TODO).append(runs(b.runs)).append('\n')
                is Block.Quote -> sb.append("> ").append(runs(b.runs)).append('\n')
                is Block.Code -> b.text.split('\n').forEach { sb.append("    ").append(it).append('\n') }
                is Block.Image -> sb.append("[Image: ").append(b.alt.ifBlank { File(b.path).name }).append("]\n")
                Block.Rule -> sb.append("-".repeat(24)).append('\n')
                Block.Blank -> sb.append('\n')
                is Block.Table -> {
                    val all = listOf(b.header.map { runs(it) }) + b.rows.map { r -> r.map { runs(it) } }
                    val cols = all.maxOf { it.size }
                    val widths = (0 until cols).map { c -> all.maxOf { it.getOrElse(c) { "" }.length } }
                    fun row(cells: List<String>) = (0 until cols)
                        .joinToString(" | ") { c -> cells.getOrElse(c) { "" }.padEnd(widths[c]) }.trimEnd()
                    sb.append(row(all[0])).append('\n')
                    sb.append(widths.joinToString("-+-") { "-".repeat(it.coerceAtLeast(1)) }).append('\n')
                    all.drop(1).forEach { sb.append(row(it)).append('\n') }
                }
            }
        }
        return sb.toString().trimEnd('\n') + "\n"
    }

    // ---------------------------------------------------------------- HTML

    /** A self-contained web page: styles inline, pictures embedded so it still works when sent on. */
    fun toHtml(title: String, blocks: List<Block>, baseDir: File?): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(title.ifBlank { "Note" })).append("</title>\n")
        sb.append(
            """
            <style>
            body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;max-width:46em;margin:2em auto;padding:0 1em;line-height:1.5;color:#1b1b1f;background:#fff}
            h1,h2,h3,h4,h5,h6{margin:.6em 0 .25em;line-height:1.25}
            p{margin:0 0 .3em}
            .gap{height:.6em}
            ul,ol{margin:.2em 0;padding-left:1.6em}
            ul.todo{list-style:none;padding-left:.2em}
            ul.todo li.done{color:#666;text-decoration:line-through}
            blockquote{margin:.3em 0;padding:.4em .8em;border-left:4px solid #c5c5d0;background:#f3f3f7}
            code{font-family:ui-monospace,Consolas,monospace;background:#efeff3;padding:0 .2em;border-radius:3px}
            pre{background:#efeff3;padding:.6em .8em;border-radius:6px;overflow:auto}
            pre code{background:none;padding:0}
            table{border-collapse:collapse;margin:.5em 0}
            th,td{border:1px solid #b9b9c4;padding:.3em .6em;text-align:left;vertical-align:top}
            img{max-width:100%}
            hr{border:0;border-top:1px solid #c5c5d0;margin:.8em 0}
            </style>
            """.trimIndent()
        ).append("\n</head>\n<body>\n")

        var list: String? = null   // the list tag we're inside: ul, ul.todo or ol
        fun openList(kind: String) {
            if (list == kind) return
            closeList(sb, list)
            sb.append(
                when (kind) { "todo" -> "<ul class=\"todo\">\n"; "ol" -> "<ol>\n"; else -> "<ul>\n" }
            )
            list = kind
        }
        for (b in blocks) {
            when (b) {
                is Block.Bullet -> { openList("ul"); sb.append("<li>").append(htmlRuns(b.runs)).append("</li>\n") }
                is Block.Check -> {
                    openList("todo")
                    sb.append(if (b.checked) "<li class=\"done\">" else "<li>")
                        .append(if (b.checked) "☑ " else "☐ ").append(htmlRuns(b.runs)).append("</li>\n")
                }
                is Block.Numbered -> {
                    openList("ol")
                    sb.append("<li value=\"").append(b.label.trimEnd('.')).append("\">").append(htmlRuns(b.runs)).append("</li>\n")
                }
                else -> {
                    closeList(sb, list); list = null
                    when (b) {
                        is Block.Heading -> sb.append("<h${b.level}>").append(htmlRuns(b.runs)).append("</h${b.level}>\n")
                        is Block.Para -> sb.append("<p>").append(htmlRuns(b.runs)).append("</p>\n")
                        is Block.Quote -> sb.append("<blockquote>").append(htmlRuns(b.runs)).append("</blockquote>\n")
                        is Block.Code -> sb.append("<pre><code>").append(esc(b.text)).append("</code></pre>\n")
                        Block.Rule -> sb.append("<hr>\n")
                        Block.Blank -> sb.append("<div class=\"gap\"></div>\n")
                        is Block.Image -> {
                            val f = resolveImage(baseDir, b.path)
                            val src = f?.let { dataUri(it) } ?: b.path
                            sb.append("<p><img src=\"").append(esc(src)).append("\" alt=\"").append(esc(b.alt)).append("\"></p>\n")
                        }
                        is Block.Table -> {
                            val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0)
                            sb.append("<table>\n<tr>")
                            for (c in 0 until cols) sb.append("<th>").append(htmlRuns(b.header.getOrElse(c) { emptyList() })).append("</th>")
                            sb.append("</tr>\n")
                            for (r in b.rows) {
                                sb.append("<tr>")
                                for (c in 0 until cols) sb.append("<td>").append(htmlRuns(r.getOrElse(c) { emptyList() })).append("</td>")
                                sb.append("</tr>\n")
                            }
                            sb.append("</table>\n")
                        }
                        else -> Unit
                    }
                }
            }
        }
        closeList(sb, list)
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    private fun closeList(sb: StringBuilder, kind: String?) {
        when (kind) { null -> Unit; "ol" -> sb.append("</ol>\n"); else -> sb.append("</ul>\n") }
    }

    private fun htmlRuns(runs: List<Run>): String = buildString {
        for (r in runs) {
            var s = esc(r.text)
            if (r.code) s = "<code>$s</code>"
            if (r.strike) s = "<s>$s</s>"
            if (r.italic) s = "<em>$s</em>"
            if (r.bold) s = "<strong>$s</strong>"
            if (r.link != null) s = "<a href=\"${esc(r.link)}\">$s</a>"
            append(s)
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun imageMime(f: File): String? = when (f.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> null
    }

    private const val MAX_EMBED = 12L * 1024 * 1024

    private fun dataUri(f: File): String? {
        val mime = imageMime(f) ?: return null
        if (f.length() > MAX_EMBED) return null
        return runCatching { "data:$mime;base64," + Base64.getEncoder().encodeToString(f.readBytes()) }.getOrNull()
    }

    // ---------------------------------------------------------------- Word (.docx)

    /**
     * A Word document built by hand: the handful of parts Word, Google Docs and LibreOffice need,
     * with real heading styles (so the navigation pane works), tables, links and pictures.
     */
    fun toDocx(blocks: List<Block>, baseDir: File?, letter: Boolean): ByteArray {
        val rels = ArrayList<String>()   // document relationships beyond styles (rId1)
        val media = LinkedHashMap<String, ByteArray>()
        var nextId = 2
        var picId = 1
        val body = StringBuilder()

        fun rPr(r: Run, link: Boolean): String {
            val p = StringBuilder()
            if (r.code) p.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
            if (r.bold) p.append("<w:b/>")
            if (r.italic) p.append("<w:i/>")
            if (r.strike) p.append("<w:strike/>")
            if (link) p.append("<w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/>")
            if (r.code) p.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EFEFF3\"/>")
            return if (p.isEmpty()) "" else "<w:rPr>$p</w:rPr>"
        }
        fun textRun(text: String, props: String) = "<w:r>$props<w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r>"
        fun runs(rs: List<Run>): String = buildString {
            for (r in rs) {
                val url = r.link
                if (url != null && URL_SCHEME.containsMatchIn(url)) {
                    val id = "rId${nextId++}"
                    rels.add("<Relationship Id=\"$id\" Type=\"$REL/hyperlink\" Target=\"${xml(url)}\" TargetMode=\"External\"/>")
                    append("<w:hyperlink r:id=\"$id\" w:history=\"1\">").append(textRun(r.text, rPr(r, true))).append("</w:hyperlink>")
                } else append(textRun(r.text, rPr(r, url != null)))
            }
        }
        fun para(pPr: String, content: String) { body.append("<w:p>").append(if (pPr.isEmpty()) "" else "<w:pPr>$pPr</w:pPr>").append(content).append("</w:p>") }
        val hang = "<w:ind w:left=\"360\" w:hanging=\"360\"/>"
        val maxWidthEmu = 5_486_400L   // 6 inches: fits inside either page's margins

        for (b in blocks) {
            when (b) {
                is Block.Heading -> para("<w:pStyle w:val=\"Heading${b.level}\"/>", runs(b.runs))
                is Block.Para -> para("", runs(b.runs))
                is Block.Bullet -> para(hang, textRun("•", "") + "<w:r><w:tab/></w:r>" + runs(b.runs))
                is Block.Numbered -> para(hang, textRun(b.label, "") + "<w:r><w:tab/></w:r>" + runs(b.runs))
                is Block.Check -> para(
                    hang,
                    textRun(if (b.checked) "☑" else "☐", "") + "<w:r><w:tab/></w:r>" +
                        runs(if (b.checked) b.runs.map { it.copy(strike = true) } else b.runs)
                )
                is Block.Quote -> para(
                    "<w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"C5C5D0\"/></w:pBdr>" +
                        "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F3F3F7\"/><w:ind w:left=\"240\"/>",
                    runs(b.runs)
                )
                is Block.Code -> para(
                    "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EFEFF3\"/><w:spacing w:after=\"120\" w:line=\"240\" w:lineRule=\"auto\"/>",
                    b.text.split('\n').mapIndexed { k, line ->
                        (if (k > 0) "<w:r><w:br/></w:r>" else "") +
                            textRun(line, "<w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:sz w:val=\"19\"/></w:rPr>")
                    }.joinToString("")
                )
                Block.Rule -> para("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"B9B9C4\"/></w:pBdr>", "")
                Block.Blank -> para("<w:spacing w:after=\"0\" w:line=\"120\" w:lineRule=\"exact\"/>", "")
                is Block.Image -> {
                    val f = resolveImage(baseDir, b.path)
                    val bytes = f?.takeIf { it.length() <= MAX_EMBED }?.let { runCatching { it.readBytes() }.getOrNull() }
                    val size = bytes?.let { imageSize(it) }
                    val ext = when (bytes?.let { sniff(it) }) { "png" -> "png"; "jpeg" -> "jpeg"; "gif" -> "gif"; else -> null }
                    if (bytes == null || size == null || ext == null) {
                        para("", textRun("[Image: ${b.alt.ifBlank { File(b.path).name }}]", "<w:rPr><w:i/></w:rPr>"))
                    } else {
                        val name = "image${picId}.$ext"
                        media[name] = bytes
                        val id = "rId${nextId++}"
                        rels.add("<Relationship Id=\"$id\" Type=\"$REL/image\" Target=\"media/$name\"/>")
                        var cx = size.first * 9525L
                        var cy = size.second * 9525L
                        if (cx > maxWidthEmu) { cy = cy * maxWidthEmu / cx; cx = maxWidthEmu }
                        val n = picId++
                        para(
                            "",
                            "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
                                "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Picture $n\" descr=\"${xml(b.alt)}\"/>" +
                                "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                                "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>" +
                                "<pic:blipFill><a:blip r:embed=\"$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
                                "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
                                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
                                "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
                        )
                    }
                }
                is Block.Table -> {
                    val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
                    val w = 9000 / cols
                    body.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
                    for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
                        body.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"B9B9C4\"/>")
                    }
                    body.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
                    repeat(cols) { body.append("<w:gridCol w:w=\"$w\"/>") }
                    body.append("</w:tblGrid>")
                    fun row(cells: List<List<Run>>, header: Boolean) {
                        body.append("<w:tr>").append(if (header) "<w:trPr><w:tblHeader/></w:trPr>" else "")
                        for (c in 0 until cols) {
                            val cell = cells.getOrElse(c) { emptyList() }.let { rs -> if (header) rs.map { it.copy(bold = true) } else rs }
                            body.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>")
                                .append(if (header) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F3F3F7\"/>" else "")
                                .append("</w:tcPr><w:p><w:pPr><w:spacing w:before=\"40\" w:after=\"40\"/></w:pPr>")
                                .append(runs(cell)).append("</w:p></w:tc>")
                        }
                        body.append("</w:tr>")
                    }
                    row(b.header, true)
                    b.rows.forEach { row(it, false) }
                    body.append("</w:tbl>")
                    para("<w:spacing w:after=\"0\" w:line=\"120\" w:lineRule=\"exact\"/>", "")   // Word wants a paragraph between a table and what follows
                }
            }
        }
        val page = if (letter) "<w:pgSz w:w=\"12240\" w:h=\"15840\"/>" else "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
        body.append("<w:sectPr>").append(page)
            .append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")

        val document = XML_HEAD +
            "<w:document xmlns:w=\"$W\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<w:body>$body</w:body></w:document>"
        val docRels = XML_HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL/styles\" Target=\"styles.xml\"/>" + rels.joinToString("") + "</Relationships>"
        val contentTypes = XML_HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Default Extension=\"gif\" ContentType=\"image/gif\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "</Types>"
        val rootRels = XML_HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"word/document.xml\"/></Relationships>"

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("[Content_Types].xml", contentTypes.toByteArray())
            put("_rels/.rels", rootRels.toByteArray())
            put("word/document.xml", document.toByteArray())
            put("word/styles.xml", STYLES.toByteArray())
            put("word/_rels/document.xml.rels", docRels.toByteArray())
            for ((name, bytes) in media) put("word/media/$name", bytes)
        }
        return bos.toByteArray()
    }

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val URL_SCHEME = Regex("^(https?|mailto|ftp|tel):", RegexOption.IGNORE_CASE)

    private val STYLES: String = run {
        val sizes = listOf(36, 30, 26, 24, 22, 22)   // half-points
        val headings = sizes.mapIndexed { i, sz ->
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading${i + 1}\"><w:name w:val=\"heading ${i + 1}\"/>" +
                "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:spacing w:before=\"${if (i == 0) 240 else 180}\" w:after=\"80\"/><w:outlineLvl w:val=\"$i\"/></w:pPr>" +
                "<w:rPr><w:b/><w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr></w:style>"
        }.joinToString("")
        XML_HEAD + "<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"60\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            headings + "</w:styles>"
    }

    /** Text safe inside XML: escaped, and without the control characters XML can't hold at all. */
    private fun xml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\t', '\n', '\r' -> sb.append(c)
                else -> if (c >= ' ' && c != '￾' && c != '￿') sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun sniff(b: ByteArray): String? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpeg"
        b.size > 10 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
        else -> null
    }

    /** Pixel width and height read from a PNG, JPEG or GIF header — no image decoder needed. */
    internal fun imageSize(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
        return when (sniff(b)) {
            "png" -> if (b.size >= 24) be32(16) to be32(20) else null
            "gif" -> (u8(6) or (u8(7) shl 8)) to (u8(8) or (u8(9) shl 8))
            "jpeg" -> {
                var i = 2
                var found: Pair<Int, Int>? = null
                while (i + 9 < b.size) {
                    if (u8(i) != 0xFF) { i++; continue }
                    val marker = u8(i + 1)
                    if (marker == 0xFF) { i++; continue }
                    if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                    val len = be16(i + 2)
                    if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                        found = be16(i + 7) to be16(i + 5)
                        break
                    }
                    i += 2 + len
                }
                found
            }
            else -> null
        }?.takeIf { it.first > 0 && it.second > 0 }
    }
}
