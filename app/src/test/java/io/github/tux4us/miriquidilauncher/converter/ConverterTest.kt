// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.converter

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConverterTest {
    private fun cat(id: String) = requireNotNull(ConverterCatalog.byId(id))
    private fun conv(category: String, unit: String, text: String) = cat(category).convert(unit, text, '.')

    @Test
    fun everyCategoryHasUniqueUnitIdsAndIsListed() {
        assertEquals(20, ConverterCatalog.categories.size)
        assertEquals(ConverterCatalog.ids.size, ConverterCatalog.ids.toSet().size)
        ConverterCatalog.categories.forEach { c ->
            assertEquals(c.id, c.units.size, c.units.map { it.id }.toSet().size)
            assertTrue(c.id, c.units.size >= 4)
        }
    }

    @Test
    fun everyUnitConvertsToAllOthersForTheValueOne() {
        // Eine „1“ in jedem Feld füllt alle übrigen Felder (Tabellen- und Römisch-Felder haben eigene Werte).
        ConverterCatalog.categories.filter { it is NumericCategory }.forEach { c ->
            c.units.forEach { u ->
                val out = c.convert(u.id, "1", '.')
                assertEquals("${c.id}/${u.id}", c.units.size - 1, out.size)
                assertTrue("${c.id}/${u.id} $out", out.values.all { it.isNotEmpty() })
            }
        }
    }

    @Test
    fun roundTripThroughEveryUnitKeepsTheValue() {
        ConverterCatalog.categories.filter { it is NumericCategory && it.id != ConverterCatalog.SHOE }.forEach { c ->
            val first = c.units.first()
            val value = 12.5
            val out = c.convert(first.id, "12.5", '.')
            c.units.drop(1).forEach { u ->
                val back = c.convert(u.id, out.getValue(u.id), '.').getValue(first.id).toDouble()
                assertEquals("${c.id}/${u.id}", value, back, value * 1e-9)
            }
        }
    }

    @Test
    fun lengthsAreExact() {
        val out = conv(ConverterCatalog.LENGTH, "km", "1")
        assertEquals("1000", out["m"])
        assertEquals("100000", out["cm"])
        assertEquals("0.621371192237", out["mi"])
        assertEquals("39370.0787402", out["in"])
        assertEquals("2.54", conv(ConverterCatalog.LENGTH, "in", "1")["cm"])
    }

    @Test
    fun decimalCommaIsAccepted() {
        assertEquals("2500", conv(ConverterCatalog.MASS, "kg", "2,5")["g"])
        assertEquals("2,5", cat(ConverterCatalog.MASS).convert("g", "2500", ',')["kg"])
    }

    @Test
    fun temperature() {
        val out = conv(ConverterCatalog.TEMPERATURE, "c", "100")
        assertEquals("212", out["f"])
        assertEquals("373.15", out["k"])
        assertEquals("80", out["re"])
        assertEquals("-40", conv(ConverterCatalog.TEMPERATURE, "f", "-40")["c"])
        assertEquals("0", conv(ConverterCatalog.TEMPERATURE, "k", "273.15")["c"])
        assertEquals("671.67", conv(ConverterCatalog.TEMPERATURE, "c", "100")["r"])
    }

    @Test
    fun negativeValuesOnlyWhereMeaningful() {
        assertTrue(cat(ConverterCatalog.TEMPERATURE).allowNegative)
        assertEquals("-5", cat(ConverterCatalog.TEMPERATURE).sanitize("c", "-5"))
        assertEquals("5", cat(ConverterCatalog.LENGTH).sanitize("m", "-5"))
        assertEquals("", conv(ConverterCatalog.LENGTH, "m", "-5")["km"])
    }

    @Test
    fun fuelConsumption() {
        val out = conv(ConverterCatalog.FUEL, "l100", "8")
        assertEquals("12.5", out["kml"])
        assertEquals("29.4018229", out["mpgus"]!!.take(10))
        assertEquals("35.31", out["mpguk"]!!.take(5))
        assertEquals("8", conv(ConverterCatalog.FUEL, "kml", "12.5")["l100"])
        // 0 l/100 km ergibt keine endliche Angabe in den anderen Einheiten.
        assertEquals("", conv(ConverterCatalog.FUEL, "l100", "0")["kml"])
        assertEquals("", conv(ConverterCatalog.FUEL, "kml", "0")["l100"])
    }

    @Test
    fun speedEnergyPowerPressureAngle() {
        assertEquals("3.6", conv(ConverterCatalog.SPEED, "ms", "1")["kmh"])
        assertEquals("1.94384449244", conv(ConverterCatalog.SPEED, "ms", "1")["kn"])
        assertEquals("1000", conv(ConverterCatalog.ENERGY, "kwh", "1")["wh"])
        assertEquals("860.420650096", conv(ConverterCatalog.ENERGY, "kwh", "1")["kcal"])
        assertEquals("1.3596216173", conv(ConverterCatalog.POWER, "kw", "1")["ps"])
        assertEquals("1.01325", conv(ConverterCatalog.PRESSURE, "atm", "1")["bar"])
        assertEquals("14.503773773", conv(ConverterCatalog.PRESSURE, "bar", "1")["psi"]!!.take(12))
        assertEquals("180", conv(ConverterCatalog.ANGLE, "rad", "3.14159265358979")["deg"]!!.take(3))
        assertEquals("200", conv(ConverterCatalog.ANGLE, "deg", "180")["gon"])
        assertEquals("-90", conv(ConverterCatalog.ANGLE, "gon", "-100")["deg"])
    }

    @Test
    fun forceMassVolumeAreaTimeDensity() {
        assertEquals("9.80665", conv(ConverterCatalog.FORCE, "kp", "1")["n"])
        assertEquals("0.45359237", conv(ConverterCatalog.MASS, "lb", "1")["kg"])
        assertEquals("500", conv(ConverterCatalog.MASS, "pfd", "1")["g"])
        assertEquals("3.785411784", conv(ConverterCatalog.VOLUME, "gal", "1")["l"])
        assertEquals("10000", conv(ConverterCatalog.AREA, "ha", "1")["m2"])
        assertEquals("86400", conv(ConverterCatalog.TIME, "d", "1")["s"])
        assertEquals("1000", conv(ConverterCatalog.DENSITY, "gcm3", "1")["kgm3"])
    }

    @Test
    fun digitalSizes() {
        val out = conv(ConverterCatalog.DIGITAL, "gib", "1")
        assertEquals("1073741824", out["b"])
        assertEquals("1.073741824", out["gb"])
        assertEquals("8589934592", out["bit"])
        assertEquals("1024", out["mib"])
        assertEquals("1000", conv(ConverterCatalog.DIGITAL, "gb", "1")["mb"])
    }

    @Test
    fun prefixesIncludingNewSiPrefixes() {
        val out = conv(ConverterCatalog.PREFIXES, "kilo", "1")
        assertEquals("1000", out["base"])
        assertEquals("1000000000", out["mikro"])
        assertEquals("0.001", out["mega"])
        assertEquals("1E-30", conv(ConverterCatalog.PREFIXES, "base", "1")["quetta"])
        assertEquals("1E60", conv(ConverterCatalog.PREFIXES, "quetta", "1")["quecto"])
        assertEquals("0.5", conv(ConverterCatalog.PREFIXES, "kilo", "500")["mega"])
    }

    @Test
    fun scientificInputIsParsed() {
        assertEquals(1.5e-9, ConverterNumbers.parse("1.5E-9")!!, 1e-24)
        assertEquals(1500.0, ConverterNumbers.parse("1,5e3")!!, 0.0)
        assertNull(ConverterNumbers.parse(""))
        assertNull(ConverterNumbers.parse("-"))
        assertNull(ConverterNumbers.parse("."))
        assertNull(ConverterNumbers.parse("1e"))
        assertNull(ConverterNumbers.parse("1E999"))
        assertNull(ConverterNumbers.parse("abc"))
    }

    @Test
    fun sanitize() {
        assertEquals("12,5", ConverterNumbers.sanitize("1a2,5", false))
        assertEquals("12,53", ConverterNumbers.sanitize("12,5,3", false))
        assertEquals("-3.5", ConverterNumbers.sanitize("-3.5", true))
        assertEquals("35", ConverterNumbers.sanitize("3-5", true))
        assertEquals("1E-5", ConverterNumbers.sanitize("1e-5", false))
        assertEquals("5", ConverterNumbers.sanitize("E5", false))
        assertEquals(ConverterNumbers.MAX_INPUT_LENGTH, ConverterNumbers.sanitize("1".repeat(100), false).length)
        assertEquals("5", ConverterNumbers.toggleMinus("-5"))
        assertEquals("-5", ConverterNumbers.toggleMinus("5"))
        assertEquals("-", ConverterNumbers.toggleMinus(""))
    }

    @Test
    fun formatting() {
        assertEquals("0", ConverterNumbers.format(0.0, '.'))
        assertEquals("0", ConverterNumbers.format(-0.0, '.'))
        assertEquals("0.3", ConverterNumbers.format(0.1 + 0.2, '.'))
        assertEquals("1,5", ConverterNumbers.format(1.5, ','))
        assertEquals("123456789012", ConverterNumbers.format(123456789012.0, '.'))
        assertEquals("1E15", ConverterNumbers.format(1e15, '.'))
        assertEquals("1,5E-9", ConverterNumbers.format(1.5e-9, ','))
        assertEquals("-2.5E20", ConverterNumbers.format(-2.5e20, '.'))
        assertEquals("0.000001", ConverterNumbers.format(1e-6, '.'))
        assertEquals("", ConverterNumbers.format(Double.NaN, '.'))
        assertEquals("", ConverterNumbers.format(Double.POSITIVE_INFINITY, '.'))
    }

    @Test
    fun numberSystems() {
        val out = conv(ConverterCatalog.RADIX, "dec", "255")
        assertEquals("11111111", out["bin"])
        assertEquals("377", out["oct"])
        assertEquals("FF", out["hex"])
        assertEquals("CCLV", out["roman"])
        assertEquals("255", conv(ConverterCatalog.RADIX, "hex", "ff".uppercase())["dec"])
        assertEquals("10", conv(ConverterCatalog.RADIX, "bin", "1010")["dec"])
        assertEquals("1994", conv(ConverterCatalog.RADIX, "roman", "MCMXCIV")["dec"])
        assertEquals("", conv(ConverterCatalog.RADIX, "roman", "IIII")["dec"])
        // Außerhalb 1–3999 gibt es keine römische Schreibweise.
        assertEquals("", conv(ConverterCatalog.RADIX, "dec", "4000")["roman"])
        assertEquals("", conv(ConverterCatalog.RADIX, "dec", "0")["roman"])
        assertEquals("1100100", conv(ConverterCatalog.RADIX, "dec", "100")["bin"])
        // Große Zahlen bleiben exakt.
        assertEquals("FFFFFFFFFFFFFFFFFFFF", conv(ConverterCatalog.RADIX, "dec", "1208925819614629174706175")["hex"])
    }

    @Test
    fun numberSystemInputIsFilteredPerBase() {
        val c = cat(ConverterCatalog.RADIX)
        assertEquals("1010", c.sanitize("bin", "10a1 02"))
        assertEquals("1010", c.sanitize("bin", "1 0 1 0"))
        assertEquals("1A9F", c.sanitize("hex", "1a9fg"))
        assertEquals("12", c.sanitize("dec", "1x2"))
        assertEquals("MCM", c.sanitize("roman", "mcmzz"))
        assertEquals("7", c.sanitize("oct", "789"))
        assertEquals("", conv(ConverterCatalog.RADIX, "bin", "")["dec"])
    }

    @Test
    fun romanNumeralsRoundTrip() {
        for (n in 1..3999) {
            val text = RadixCategory.toRoman(BigInteger.valueOf(n.toLong()))!!
            assertEquals(n.toLong(), RadixCategory.fromRoman(text)!!.toLong())
        }
        assertNull(RadixCategory.fromRoman("IC"))
        assertNull(RadixCategory.fromRoman("VV"))
        assertNull(RadixCategory.fromRoman("LXL"))
        assertNull(RadixCategory.fromRoman("MMMM"))
        assertNull(RadixCategory.fromRoman(""))
    }

    @Test
    fun shoeSizes() {
        val out = conv(ConverterCatalog.SHOE, "eu", "42")
        assertEquals("8", out["uk"])
        assertEquals("9", out["usm"])
        assertEquals("10", out["usw"])
        assertEquals("26.5", out["cm"])
        assertEquals("10.45", out["in"])
        assertEquals("42", conv(ConverterCatalog.SHOE, "uk", "8")["eu"])
        // Zwischengrößen werden auf halbe Größen gerundet.
        assertEquals("40.5", conv(ConverterCatalog.SHOE, "uk", "7")["eu"])
        assertEquals("39.5", conv(ConverterCatalog.SHOE, "usw", "8")["eu"])
        assertEquals("43", conv(ConverterCatalog.SHOE, "usm", "10")["eu"])
        assertEquals("42", conv(ConverterCatalog.SHOE, "cm", "26.5")["eu"])
    }

    @Test
    fun clothesSizesWomen() {
        val out = conv(ConverterCatalog.CLOTHES_WOMEN, "eu", "38")
        assertEquals("40", out["fr"])
        assertEquals("44", out["it"])
        assertEquals("10", out["uk"])
        assertEquals("6", out["us"])
        assertEquals("M", out["letter"])
        assertEquals("38", conv(ConverterCatalog.CLOTHES_WOMEN, "letter", "m")["eu"])
        assertEquals("36", conv(ConverterCatalog.CLOTHES_WOMEN, "us", "4")["eu"])
        assertEquals("", conv(ConverterCatalog.CLOTHES_WOMEN, "eu", "37")["fr"])
        assertEquals("", conv(ConverterCatalog.CLOTHES_WOMEN, "eu", "")["fr"])
    }

    @Test
    fun clothesSizesMen() {
        val out = conv(ConverterCatalog.CLOTHES_MEN, "eu", "50")
        assertEquals("100", out["chest"])
        assertEquals("40", out["us"])
        assertEquals("L", out["letter"])
        assertEquals("54", conv(ConverterCatalog.CLOTHES_MEN, "letter", "xxl")["eu"])
        assertEquals("48", conv(ConverterCatalog.CLOTHES_MEN, "chest", "96")["eu"])
    }

    @Test
    fun clothesTablesAreConsistent() {
        listOf(ConverterCatalog.CLOTHES_WOMEN, ConverterCatalog.CLOTHES_MEN).forEach { id ->
            val c = cat(id)
            // Jeder Wert jeder Spalte führt auf dieselbe Zeile zurück wie der EU-Wert derselben Zeile.
            val euValues = mutableListOf<String>()
            var eu = if (id == ConverterCatalog.CLOTHES_WOMEN) 32 else 44
            while (c.convert("eu", eu.toString(), '.').values.any { it.isNotEmpty() }) {
                euValues += eu.toString()
                eu += 2
            }
            assertTrue(id, euValues.size >= 10)
            euValues.forEach { e ->
                val row = c.convert("eu", e, '.')
                row.forEach { (unit, value) ->
                    assertEquals("$id $e $unit", e, c.convert(unit, value, '.')["eu"])
                }
            }
        }
    }

    @Test
    fun labels() {
        val m = cat(ConverterCatalog.LENGTH).units.first { it.id == "mi" }
        assertEquals("Meile (mi)", m.label(true))
        assertEquals("Mile (mi)", m.label(false))
        assertEquals("mm²", cat(ConverterCatalog.AREA).units.first { it.id == "mm2" }.label(true))
        assertEquals("Ohne Präfix", cat(ConverterCatalog.PREFIXES).units.first { it.id == "base" }.label(true))
        assertEquals("Woche", cat(ConverterCatalog.TIME).units.first { it.id == "wk" }.label(true))
    }
}
