package rida.pour.les.pros.xlsx

import java.io.OutputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class CellKind { TEXT, NUMBER, DATE }

/** Cellule à écrire. [value] : texte, nombre (texte décimal) ou date ISO/JJ-MM-AAAA déjà convertie. */
data class XlsxCell(
    val value: String?,
    val kind: CellKind = CellKind.TEXT,
    val bold: Boolean = false,
    /** Couleur de fond "#RRGGBB". */
    val fillHex: String? = null,
    val wrap: Boolean = false,
    val date: LocalDate? = null,
) {
    companion object {
        fun text(v: String?, bold: Boolean = false, fillHex: String? = null, wrap: Boolean = false) =
            XlsxCell(v, CellKind.TEXT, bold, fillHex, wrap)

        fun number(v: Number) = XlsxCell(v.toString(), CellKind.NUMBER)

        fun date(d: LocalDate?, fillHex: String? = null) =
            if (d == null) XlsxCell(null, CellKind.TEXT, fillHex = fillHex) else XlsxCell(null, CellKind.DATE, fillHex = fillHex, date = d)
    }
}

data class XlsxSheet(
    val name: String,
    val rows: List<List<XlsxCell>>,
    /** Largeurs de colonnes (en caractères). */
    val columnWidths: List<Double> = emptyList(),
    val freezeFirstRow: Boolean = true,
)

/** Écriture xlsx minimale et sans dépendance (chaînes en ligne, styles générés à la volée). */
object XlsxWriter {
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private val FORBIDDEN = Regex("[:\\\\/?*\\[\\]]")

    fun write(sheets: List<XlsxSheet>, out: OutputStream) {
        require(sheets.isNotEmpty()) { "au moins un onglet" }
        val names = uniqueNames(sheets.map { it.name })
        val styles = StyleRegistry()
        val sheetXml = sheets.map { sheetXml(it, styles) }
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(sheets.size))
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("xl/workbook.xml", workbook(names))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            zip.put("xl/styles.xml", styles.xml())
            sheetXml.forEachIndexed { i, xml -> zip.put("xl/worksheets/sheet${i + 1}.xml", xml) }
        }
    }

    /** Nom d'onglet valide : 31 caractères max, sans : \ / ? * [ ], unique. */
    fun sanitizeSheetName(raw: String): String {
        val s = raw.replace(FORBIDDEN, " ").trim().trim('\'').ifEmpty { "Feuille" }
        return if (s.length > 31) s.substring(0, 31) else s
    }

    private fun uniqueNames(raw: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return raw.map { r ->
            val base = sanitizeSheetName(r)
            var name = base
            var i = 2
            while (!used.add(name.lowercase())) {
                val suffix = " ($i)"
                name = base.take(31 - suffix.length) + suffix
                i++
            }
            name
        }
    }

    private fun ZipOutputStream.put(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheetXml(sheet: XlsxSheet, styles: StyleRegistry): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (sheet.freezeFirstRow) {
            append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        }
        if (sheet.columnWidths.isNotEmpty()) {
            append("<cols>")
            sheet.columnWidths.forEachIndexed { i, w ->
                append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            append("""<row r="${r + 1}">""")
            row.forEachIndexed { c, cell -> appendCell(this, ref(c, r), cell, styles) }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun appendCell(sb: StringBuilder, ref: String, cell: XlsxCell, styles: StyleRegistry) {
        val style = styles.idFor(cell)
        val s = if (style > 0) """ s="$style"""" else ""
        when {
            cell.kind == CellKind.DATE && cell.date != null -> {
                val serial = ChronoUnit.DAYS.between(EXCEL_EPOCH, cell.date)
                sb.append("""<c r="$ref"$s><v>$serial</v></c>""")
            }
            cell.kind == CellKind.NUMBER && cell.value?.toDoubleOrNull() != null ->
                sb.append("""<c r="$ref"$s><v>${cell.value}</v></c>""")
            cell.value.isNullOrEmpty() -> if (style > 0) sb.append("""<c r="$ref"$s/>""")
            else -> sb.append("""<c r="$ref" t="inlineStr"$s><is><t xml:space="preserve">${escape(cell.value)}</t></is></c>""")
        }
    }

    fun ref(col: Int, row: Int): String = colName(col) + (row + 1)

    fun colName(col: Int): String {
        var n = col + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, 'A' + rem)
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    fun escape(s: String): String = buildString(s.length) {
        for (ch in s) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\n' || ch == '\t' || ch == '\r' -> append(ch)
                ch < ' ' -> Unit // caractères de contrôle interdits en XML
                else -> append(ch)
            }
        }
    }

    private fun contentTypes(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..n) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private fun workbook(names: List<String>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        names.forEachIndexed { i, n ->
            append("""<sheet name="${escape(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..n) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    /** Génère styles.xml : une combinaison (gras, fond, retour à la ligne, date) = un style. */
    private class StyleRegistry {
        private data class Key(val bold: Boolean, val fill: String?, val wrap: Boolean, val date: Boolean)
        private val keys = mutableListOf(Key(false, null, false, false))
        private val fills = mutableListOf<String>()

        fun idFor(cell: XlsxCell): Int {
            val fill = cell.fillHex?.removePrefix("#")?.uppercase()?.takeIf { it.matches(Regex("[0-9A-F]{6}")) }
            val key = Key(cell.bold, fill, cell.wrap, cell.kind == CellKind.DATE)
            if (fill != null && fill !in fills) fills += fill
            val idx = keys.indexOf(key)
            if (idx >= 0) return idx
            keys += key
            return keys.lastIndex
        }

        fun xml(): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            append("""<numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/></numFmts>""")
            append("""<fonts count="2"><font><sz val="10"/><name val="Arial"/></font><font><b/><sz val="10"/><name val="Arial"/></font></fonts>""")
            append("""<fills count="${2 + fills.size}"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""")
            fills.forEach { append("""<fill><patternFill patternType="solid"><fgColor rgb="FF$it"/><bgColor indexed="64"/></patternFill></fill>""") }
            append("</fills>")
            append("""<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border>""")
            append("""<border><left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom><diagonal/></border></borders>""")
            append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")
            append("""<cellXfs count="${keys.size}">""")
            keys.forEachIndexed { i, k ->
                if (i == 0) {
                    append("""<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""")
                    return@forEachIndexed
                }
                val numFmt = if (k.date) 164 else 0
                val font = if (k.bold) 1 else 0
                val fillId = k.fill?.let { 2 + fills.indexOf(it) } ?: 0
                append("""<xf numFmtId="$numFmt" fontId="$font" fillId="$fillId" borderId="1" xfId="0"""")
                if (k.date) append(""" applyNumberFormat="1"""")
                if (k.bold) append(""" applyFont="1"""")
                if (k.fill != null) append(""" applyFill="1"""")
                append(""" applyBorder="1"""")
                if (k.wrap) append(""" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>""") else append("/>")
            }
            append("</cellXfs>")
            append("""<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""")
            append("</styleSheet>")
        }
    }
}
