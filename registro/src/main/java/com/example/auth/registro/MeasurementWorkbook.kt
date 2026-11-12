package com.example.auth.registro

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class Measurement(
    val date: String,
    val time: String,
    val prom1: String,
    val max1: String,
    val min1: String,
    val prom2: String,
    val max2: String,
    val min2: String,
    val promGen: String,
    val unidad: String,
) {
    fun cells(): List<String> = listOf(
        date, time, prom1, max1, min1, prom2, max2, min2, promGen, unidad,
    )
}

/**
 * Escribe un .xlsx mínimo (una hoja) que Excel abre sin librerías extra.
 * Cada guardado reescribe el archivo completo con el historial.
 */
object MeasurementWorkbook {
    private const val SHEET = "xl/worksheets/sheet1.xml"
    private val HEADERS = listOf(
        "Fecha", "Hora", "PROM1", "MAX1", "MIN1", "PROM2", "MAX2", "MIN2", "PROM_GEN", "UNIDAD",
    )

    fun parse(date: String, time: String, raw: String): Measurement {
        val fields = mutableMapOf<String, String>()
        raw.split(',').forEach { part ->
            val separator = part.indexOf('=')
            if (separator > 0) {
                fields[part.substring(0, separator).trim()] = part.substring(separator + 1).trim()
            }
        }
        fun field(key: String) = fields[key].orEmpty()
        return Measurement(
            date = date,
            time = time,
            prom1 = field("PROM1"),
            max1 = field("MAX1"),
            min1 = field("MIN1"),
            prom2 = field("PROM2"),
            max2 = field("MAX2"),
            min2 = field("MIN2"),
            promGen = field("PROM_GEN"),
            unidad = field("UNIDAD"),
        )
    }

    fun write(file: File, rows: List<Measurement>) {
        file.parentFile?.mkdirs()
        val sheet = buildSheet(rows)
        file.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.put(CONTENT_TYPES, "[Content_Types].xml")
                zip.put(ROOT_RELS, "_rels/.rels")
                zip.put(WORKBOOK, "xl/workbook.xml")
                zip.put(WORKBOOK_RELS, "xl/_rels/workbook.xml.rels")
                zip.put(sheet, SHEET)
            }
        }
    }

    fun read(file: File): List<Measurement> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(SHEET) ?: return emptyList()
                val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                parseSheet(xml)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ZipOutputStream.put(content: String, name: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun buildSheet(rows: List<Measurement>): String {
        val body = buildString {
            append(row(1, HEADERS))
            rows.forEachIndexed { index, measurement ->
                append(row(index + 2, measurement.cells()))
            }
        }
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>$body</sheetData>
            </worksheet>
        """.trimIndent()
    }

    private fun row(index: Int, cells: List<String>): String {
        val xmlCells = cells.mapIndexed { i, value ->
            val ref = "${columnLetter(i)}$index"
            """<c r="$ref" t="inlineStr"><is><t>${escape(value)}</t></is></c>"""
        }.joinToString("")
        return """<row r="$index">$xmlCells</row>"""
    }

    private fun columnLetter(index: Int): String {
        var n = index + 1
        val letters = StringBuilder()
        while (n > 0) {
            val remainder = (n - 1) % 26
            letters.append('A' + remainder)
            n = (n - 1) / 26
        }
        return letters.reverse().toString()
    }

    private fun parseSheet(xml: String): List<Measurement> {
        val rowPattern = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val textPattern = Regex("<t>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        return rowPattern.findAll(xml).mapNotNull { match ->
            val texts = textPattern.findAll(match.groupValues[1]).map { unescape(it.groupValues[1]) }.toList()
            if (texts.size < 3 || texts[0] == "Fecha") return@mapNotNull null
            if (texts.size >= HEADERS.size) {
                Measurement(
                    date = texts[0],
                    time = texts[1],
                    prom1 = texts[2],
                    max1 = texts[3],
                    min1 = texts[4],
                    prom2 = texts[5],
                    max2 = texts[6],
                    min2 = texts[7],
                    promGen = texts[8],
                    unidad = texts[9],
                )
            } else {
                parse(texts[0], texts[1], texts[2])
            }
        }.toList()
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun unescape(text: String): String =
        text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")

    private val CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        </Types>
    """.trimIndent()

    private val ROOT_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private val WORKBOOK = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="Mediciones" sheetId="1" r:id="rId1"/>
          </sheets>
        </workbook>
    """.trimIndent()

    private val WORKBOOK_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
        </Relationships>
    """.trimIndent()
}
