package rida.pour.les.pros.xlsx

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** Onglet lu : valeurs brutes en texte (les dates peuvent être des numéros de série). */
data class ReadSheet(val name: String, val rows: List<List<String>>) {
    fun cell(row: Int, col: Int): String = rows.getOrNull(row)?.getOrNull(col).orEmpty()
}

class XlsxFormatException(message: String) : Exception(message)

/** Lecture xlsx sans dépendance (SAX, disponible sur Android et sur la JVM). */
object XlsxReader {
    private const val MAX_ENTRY_BYTES = 50L * 1024 * 1024

    fun read(input: InputStream): List<ReadSheet> {
        val files = unzip(input)
        val workbook = files["xl/workbook.xml"] ?: throw XlsxFormatException("fichier xlsx invalide (workbook absent)")
        val sheets = parseWorkbook(workbook)
        val rels = files["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) } ?: emptyMap()
        val shared = files["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        return sheets.mapIndexed { i, (name, rid) ->
            val target = rels[rid]?.let { normalizeTarget(it) } ?: "xl/worksheets/sheet${i + 1}.xml"
            val data = files[target] ?: throw XlsxFormatException("onglet « $name » introuvable ($target)")
            ReadSheet(name, parseSheet(data, shared))
        }
    }

    private fun normalizeTarget(t: String): String = when {
        t.startsWith("/") -> t.removePrefix("/")
        t.startsWith("xl/") -> t
        else -> "xl/" + t.removePrefix("./")
    }

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && (e.name.endsWith(".xml") || e.name.endsWith(".rels"))) {
                    val bytes = zip.readBytes()
                    if (bytes.size > MAX_ENTRY_BYTES) throw XlsxFormatException("fichier trop volumineux")
                    map[e.name.removePrefix("/")] = bytes
                }
            }
        }
        if (map.isEmpty()) throw XlsxFormatException("ce fichier n'est pas un classeur xlsx")
        return map
    }

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }

    private fun Attributes.byLocal(name: String): String? {
        for (i in 0 until length) if (getLocalName(i) == name || getQName(i) == name) return getValue(i)
        return null
    }

    /** Liste (nom, r:id) dans l'ordre du classeur. */
    private fun parseWorkbook(bytes: ByteArray): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                if (localName == "sheet") result += (a.byLocal("name").orEmpty() to a.byLocal("id").orEmpty())
            }
        })
        return result
    }

    private fun parseRels(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        parse(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                if (localName == "Relationship") {
                    val id = a.byLocal("Id")
                    val target = a.byLocal("Target")
                    if (id != null && target != null) result[id] = target
                }
            }
        })
        return result
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        parse(bytes, object : DefaultHandler() {
            val sb = StringBuilder()
            var inT = false
            var inPhonetic = false
            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                when (localName) {
                    "si" -> sb.setLength(0)
                    "rPh" -> inPhonetic = true
                    "t" -> inT = !inPhonetic
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName) {
                    "t" -> inT = false
                    "rPh" -> inPhonetic = false
                    "si" -> result += sb.toString()
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inT) sb.append(ch, start, length)
            }
        })
        return result
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        parse(bytes, object : DefaultHandler() {
            var rowIndex = -1
            var colIndex = -1
            var nextCol = 0
            var type: String? = null
            val value = StringBuilder()
            var capture = false
            var inIs = false

            override fun startElement(uri: String?, localName: String?, qName: String?, a: Attributes) {
                when (localName) {
                    "row" -> {
                        rowIndex = a.byLocal("r")?.toIntOrNull()?.minus(1) ?: (rowIndex + 1)
                        nextCol = 0
                    }
                    "c" -> {
                        colIndex = a.byLocal("r")?.let { colFromRef(it) } ?: nextCol
                        nextCol = colIndex + 1
                        type = a.byLocal("t")
                        value.setLength(0)
                    }
                    "is" -> inIs = true
                    "v" -> capture = true
                    "t" -> capture = inIs
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName) {
                    "v", "t" -> capture = false
                    "is" -> inIs = false
                    "c" -> {
                        val raw = value.toString()
                        val v = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            else -> raw
                        }
                        if (v.isNotEmpty() && rowIndex >= 0) rows.getOrPut(rowIndex) { mutableMapOf() }[colIndex] = v
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) value.append(ch, start, length)
            }
        })
        if (rows.isEmpty()) return emptyList()
        val lastRow = rows.lastKey()
        return (0..lastRow).map { r ->
            val cells = rows[r] ?: return@map emptyList()
            val maxCol = cells.keys.maxOrNull() ?: -1
            (0..maxCol).map { c -> cells[c].orEmpty() }
        }
    }

    fun colFromRef(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z' && ch !in 'a'..'z') break
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return n - 1
    }
}
