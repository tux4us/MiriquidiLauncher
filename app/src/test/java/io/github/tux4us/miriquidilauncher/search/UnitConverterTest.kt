// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitConverterTest {
    @Test fun lengthWithSeparator() {
        val c = UnitConverter.convert("5 km in mi")!!
        assertEquals(3.10686, c.result, 1e-4)
        assertEquals("km", c.fromLabel)
        assertEquals("mi", c.toLabel)
    }

    @Test fun withoutSeparatorAndWithoutSpaceBeforeUnit() {
        assertEquals(1000.0, UnitConverter.convert("1km m")!!.result, 1e-9)
        assertEquals(1000.0, UnitConverter.convert("1 km nach m")!!.result, 1e-9)
    }

    @Test fun inchAsTargetAndSource() {
        assertEquals(2540.0, UnitConverter.convert("100 in mm")!!.result, 1e-9)
        assertEquals(2.54, UnitConverter.convert("1 in cm")!!.result, 1e-9)
        assertEquals(2.54, UnitConverter.convert("1 in in cm")!!.result, 1e-9)
        assertEquals(39.3700787, UnitConverter.convert("1 m in")!!.result, 1e-6)
        assertEquals(2.54, UnitConverter.convert("1 inch cm")!!.result, 1e-9)
        assertEquals(0.3937, UnitConverter.convert("1 cm inch")!!.result, 1e-3)
    }

    @Test fun massAndVolume() {
        assertEquals(1.36078, UnitConverter.convert("3 lb kg")!!.result, 1e-4)
        assertEquals(500.0, UnitConverter.convert("0,5 l ml")!!.result, 1e-9)
    }

    @Test fun temperature() {
        assertEquals(68.0, UnitConverter.convert("20 c nach f")!!.result, 1e-9)
        assertEquals(0.0, UnitConverter.convert("32 °F in °C")!!.result, 1e-9)
        assertEquals(273.15, UnitConverter.convert("0 c k")!!.result, 1e-9)
        assertEquals(-40.0, UnitConverter.convert("-40 c f")!!.result, 1e-9)
    }

    @Test fun speedAreaTime() {
        assertEquals(36.0, UnitConverter.convert("10 m/s km/h")!!.result, 1e-9)
        assertEquals(10000.0, UnitConverter.convert("1 ha m2")!!.result, 1e-9)
        assertEquals(90.0, UnitConverter.convert("1,5 h min")!!.result, 1e-9)
    }

    @Test fun differentCategoriesAreRejected() {
        assertNull(UnitConverter.convert("5 km kg"))
        assertNull(UnitConverter.convert("5 foo bar"))
        assertNull(UnitConverter.convert("km mi"))
        assertNull(UnitConverter.convert("5 km"))
    }
}
