package com.example.auth.registro

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class Measurement(
    val date: String,
    val time: String,
    val value: String,
)

/**
 * Escribe un .xlsx mínimo (una hoja) que Excel abre sin librerías extra.
 * Cada guardado reescribe el archivo completo con el historial.
 */
object MeasurementWorkbook {
    private const val SHEET = "xl/worksheets/sheet1.xml"

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
            append(row(1, listOf("Fecha", "Hora", "Medicion")))
            rows.forEachIndexed { index, measurement ->
                append(
                    row(
                        index + 2,
                        listOf(measurement.date, measurement.time, measurement.value),
                    )
                )
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
        val columns = listOf("A", "B", "C")
        val xmlCells = cells.mapIndexed { i, value ->
            val ref = "${columns[i]}$index"
            """<c r="$ref" t="inlineStr"><is><t>${escape(value)}</t></is></c>"""
        }.joinToString("")
        return """<row r="$index">$xmlCells</row>"""
    }

    private fun parseSheet(xml: String): List<Measurement> {
        val rowPattern = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        val textPattern = Regex("<t>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        return rowPattern.findAll(xml).mapNotNull { match ->
            val texts = textPattern.findAll(match.groupValues[1]).map { unescape(it.groupValues[1]) }.toList()
            if (texts.size < 3) return@mapNotNull null
            if (texts[0] == "Fecha") return@mapNotNull null
            Measurement(texts[0], texts[1], texts[2])
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
