package com.example.auth.registro

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasurementWorkbookTest {
    @Test
    fun splitsEsp32FieldsAndRoundTripsTheWorkbook() {
        val payload = "PROM1=1753.93,MAX1=1800.10,MIN1=1600.00," +
            "PROM2=900.50,MAX2=950.00,MIN2=800.25," +
            "PROM_GEN=1327.22,UNIDAD=mV"
        val rows = listOf(
            MeasurementWorkbook.parse("12/11/2026", "11:43:29", payload),
            MeasurementWorkbook.parse("12/11/2026", "11:44:24", "PROM1=a<b & c,UNIDAD=mV"),
        )

        assertEquals("1753.93", rows[0].prom1)
        assertEquals("1800.10", rows[0].max1)
        assertEquals("1600.00", rows[0].min1)
        assertEquals("900.50", rows[0].prom2)
        assertEquals("950.00", rows[0].max2)
        assertEquals("800.25", rows[0].min2)
        assertEquals("1327.22", rows[0].promGen)
        assertEquals("mV", rows[0].unidad)

        val file = File.createTempFile("mediciones", ".xlsx")
        MeasurementWorkbook.write(file, rows)
        assertEquals(rows, MeasurementWorkbook.read(file))
        file.delete()
    }
}
