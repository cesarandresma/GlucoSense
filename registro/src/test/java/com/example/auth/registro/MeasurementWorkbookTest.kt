package com.example.auth.registro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementWorkbookTest {
    @Test
    fun writesAndReadsMeasurements() {
        val file = File.createTempFile("mediciones", ".xlsx")
        val rows = listOf(
            Measurement("12/11/2026", "10:29:01", "125.4"),
            Measurement("12/11/2026", "10:30:15", "a<b & c"),
        )

        MeasurementWorkbook.write(file, rows)

        assertEquals(rows, MeasurementWorkbook.read(file))
        file.delete()
    }
}
