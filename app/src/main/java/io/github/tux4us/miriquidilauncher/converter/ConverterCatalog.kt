// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.converter

import kotlin.math.PI
import kotlin.math.pow

/**
 * Alle Kategorien des Converters in der Reihenfolge der Auswahl. Umrechnungsfaktoren sind SI-Definitionen bzw. die
 * internationalen Festlegungen der angloamerikanischen Einheiten (z. B. 1 in = 0,0254 m, 1 lb = 0,45359237 kg).
 */
object ConverterCatalog {

    // Kennungen der Kategorien; die Oberfläche ordnet ihnen Namen und Hinweise zu.
    const val LENGTH = "length"
    const val AREA = "area"
    const val VOLUME = "volume"
    const val TIME = "time"
    const val TEMPERATURE = "temperature"
    const val SPEED = "speed"
    const val MASS = "mass"
    const val FORCE = "force"
    const val FUEL = "fuel"
    const val RADIX = "radix"
    const val ENERGY = "energy"
    const val POWER = "power"
    const val PRESSURE = "pressure"
    const val ANGLE = "angle"
    const val DENSITY = "density"
    const val SHOE = "shoe"
    const val CLOTHES_WOMEN = "clothes_women"
    const val CLOTHES_MEN = "clothes_men"
    const val DIGITAL = "digital"
    const val PREFIXES = "prefixes"

    /** Linearer Zusammenhang: Wert in der Basiseinheit = Wert × [factor]. */
    private fun linear(id: String, symbol: String, factor: Double, nameDe: String? = null, nameEn: String? = nameDe) =
        NumericUnit(ConverterUnit(id, symbol, nameDe, nameEn), { it * factor }, { it / factor })

    /** Zusammenhang über Hin- und Rückfunktion zur Basiseinheit. */
    private fun mapped(
        id: String,
        symbol: String,
        toBase: (Double) -> Double,
        fromBase: (Double) -> Double,
        nameDe: String? = null,
        nameEn: String? = nameDe,
        step: Double? = null,
    ) = NumericUnit(ConverterUnit(id, symbol, nameDe, nameEn), toBase, fromBase, step)

    private val length = NumericCategory(
        LENGTH,
        listOf(
            linear("um", "µm", 1e-6, "Mikrometer"),
            linear("mm", "mm", 1e-3, "Millimeter"),
            linear("cm", "cm", 1e-2, "Zentimeter", "Centimeter"),
            linear("m", "m", 1.0, "Meter", "Meter"),
            linear("km", "km", 1e3, "Kilometer"),
            linear("in", "in", 0.0254, "Zoll", "Inch"),
            linear("ft", "ft", 0.3048, "Fuß", "Foot"),
            linear("yd", "yd", 0.9144, "Yard"),
            linear("mi", "mi", 1609.344, "Meile", "Mile"),
            linear("nmi", "nmi", 1852.0, "Seemeile", "Nautical mile"),
            linear("au", "AE", 149_597_870_700.0, "Astronomische Einheit", "Astronomical unit"),
            linear("ly", "ly", 9_460_730_472_580_800.0, "Lichtjahr", "Light year"),
        ),
    )

    private val area = NumericCategory(
        AREA,
        listOf(
            linear("mm2", "mm²", 1e-6),
            linear("cm2", "cm²", 1e-4),
            linear("m2", "m²", 1.0),
            linear("a", "a", 100.0, "Ar", "Are"),
            linear("ha", "ha", 1e4, "Hektar", "Hectare"),
            linear("km2", "km²", 1e6),
            linear("in2", "in²", 0.0254 * 0.0254),
            linear("ft2", "ft²", 0.3048 * 0.3048),
            linear("yd2", "yd²", 0.9144 * 0.9144),
            linear("acre", "ac", 4046.8564224, "Acre"),
            linear("mi2", "mi²", 1609.344 * 1609.344),
        ),
    )

    private val volume = NumericCategory(
        VOLUME,
        listOf(
            linear("ml", "ml", 1e-3, "Milliliter"),
            linear("cl", "cl", 1e-2, "Zentiliter", "Centiliter"),
            linear("dl", "dl", 1e-1, "Deziliter", "Deciliter"),
            linear("l", "l", 1.0, "Liter", "Liter"),
            linear("m3", "m³", 1e3, "Kubikmeter", "Cubic meter"),
            linear("in3", "in³", 0.016387064, "Kubikzoll", "Cubic inch"),
            linear("ft3", "ft³", 28.316846592, "Kubikfuß", "Cubic foot"),
            linear("tsp", "tsp", 0.00492892159375, "Teelöffel (US)", "Teaspoon (US)"),
            linear("tbsp", "tbsp", 0.01478676478125, "Esslöffel (US)", "Tablespoon (US)"),
            linear("floz", "fl oz", 0.0295735295625, "Flüssigunze (US)", "Fluid ounce (US)"),
            linear("cup", "cup", 0.2365882365, "Cup (US)", "Cup (US)"),
            linear("pt", "pt", 0.473176473, "Pint (US)", "Pint (US)"),
            linear("qt", "qt", 0.946352946, "Quart (US)", "Quart (US)"),
            linear("gal", "gal", 3.785411784, "Gallone (US)", "Gallon (US)"),
            linear("galuk", "gal (UK)", 4.54609, "Gallone (UK)", "Gallon (UK)"),
            linear("bbl", "bbl", 158.987294928, "Barrel (Öl)", "Barrel (oil)"),
        ),
    )

    private val time = NumericCategory(
        TIME,
        listOf(
            linear("us", "µs", 1e-6, "Mikrosekunde", "Microsecond"),
            linear("ms", "ms", 1e-3, "Millisekunde", "Millisecond"),
            linear("s", "s", 1.0, "Sekunde", "Second"),
            linear("min", "min", 60.0, "Minute", "Minute"),
            linear("h", "h", 3600.0, "Stunde", "Hour"),
            linear("d", "d", 86_400.0, "Tag", "Day"),
            linear("wk", "", 604_800.0, "Woche", "Week"),
            linear("mo", "", 2_629_800.0, "Monat (Ø)", "Month (avg.)"),
            linear("yr", "", 31_557_600.0, "Jahr (Ø)", "Year (avg.)"),
        ),
    )

    private val temperature = NumericCategory(
        TEMPERATURE,
        listOf(
            mapped("c", "°C", { it }, { it }, "Celsius", "Celsius"),
            mapped("f", "°F", { (it - 32.0) * 5.0 / 9.0 }, { it * 9.0 / 5.0 + 32.0 }, "Fahrenheit", "Fahrenheit"),
            mapped("k", "K", { it - 273.15 }, { it + 273.15 }, "Kelvin", "Kelvin"),
            mapped("r", "°R", { (it - 491.67) * 5.0 / 9.0 }, { it * 9.0 / 5.0 + 491.67 }, "Rankine", "Rankine"),
            mapped("re", "°Ré", { it * 1.25 }, { it * 0.8 }, "Réaumur", "Réaumur"),
        ),
        allowNegative = true,
    )

    private val speed = NumericCategory(
        SPEED,
        listOf(
            linear("ms", "m/s", 1.0),
            linear("kmh", "km/h", 1.0 / 3.6),
            linear("mph", "mph", 0.44704, "Meilen pro Stunde", "Miles per hour"),
            linear("kn", "kn", 1852.0 / 3600.0, "Knoten", "Knot"),
            linear("fts", "ft/s", 0.3048),
            linear("mach", "Ma", 340.29, "Mach (Meereshöhe, 15 °C)", "Mach (sea level, 15 °C)"),
            linear("c", "c", 299_792_458.0, "Lichtgeschwindigkeit", "Speed of light"),
        ),
    )

    private val mass = NumericCategory(
        MASS,
        listOf(
            linear("mg", "mg", 1e-6, "Milligramm", "Milligram"),
            linear("ct", "ct", 2e-4, "Karat", "Carat"),
            linear("g", "g", 1e-3, "Gramm", "Gram"),
            linear("oz", "oz", 0.028349523125, "Unze", "Ounce"),
            linear("ozt", "oz t", 0.0311034768, "Feinunze", "Troy ounce"),
            linear("lb", "lb", 0.45359237, "Pfund (engl.)", "Pound"),
            linear("pfd", "Pfd.", 0.5, "Pfund (500 g)", "Pfund (500 g)"),
            linear("kg", "kg", 1.0, "Kilogramm", "Kilogram"),
            linear("st", "st", 6.35029318, "Stone"),
            linear("ztr", "Ztr.", 50.0, "Zentner (50 kg)", "Zentner (50 kg)"),
            linear("t", "t", 1000.0, "Tonne", "Metric ton"),
        ),
    )

    private val force = NumericCategory(
        FORCE,
        listOf(
            linear("mn", "mN", 1e-3, "Millinewton"),
            linear("n", "N", 1.0, "Newton"),
            linear("kn", "kN", 1e3, "Kilonewton"),
            linear("mnn", "MN", 1e6, "Meganewton"),
            linear("dyn", "dyn", 1e-5, "Dyn"),
            linear("kp", "kp", 9.80665, "Kilopond (kgf)", "Kilogram-force (kgf)"),
            linear("lbf", "lbf", 4.4482216152605, "Pound-force", "Pound-force"),
            linear("pdl", "pdl", 0.138254954376, "Poundal"),
        ),
    )

    /** Basis ist der Verbrauch in l/100 km; die übrigen Angaben sind dazu umgekehrt proportional. */
    private fun reciprocal(id: String, symbol: String, constant: Double, nameDe: String, nameEn: String) =
        mapped(id, symbol, { constant / it }, { constant / it }, nameDe, nameEn)

    private val fuel = NumericCategory(
        FUEL,
        listOf(
            linear("l100", "l/100 km", 1.0, "Liter pro 100 km", "Liters per 100 km"),
            reciprocal("kml", "km/l", 100.0, "Kilometer pro Liter", "Kilometers per liter"),
            // 1 mpg (US) = 1 mi / 1 gal(US); 1 mpg (UK) = 1 mi / 1 gal(UK); 100 · gal / mi in l/100 km.
            reciprocal("mpgus", "mpg (US)", 100.0 * 3.785411784 / 1.609344, "Meilen pro Gallone (US)", "Miles per gallon (US)"),
            reciprocal("mpguk", "mpg (UK)", 100.0 * 4.54609 / 1.609344, "Meilen pro Gallone (UK)", "Miles per gallon (UK)"),
        ),
    )

    private val energy = NumericCategory(
        ENERGY,
        listOf(
            linear("j", "J", 1.0, "Joule"),
            linear("kj", "kJ", 1e3, "Kilojoule"),
            linear("mj", "MJ", 1e6, "Megajoule"),
            linear("gj", "GJ", 1e9, "Gigajoule"),
            linear("wh", "Wh", 3600.0, "Wattstunde", "Watt hour"),
            linear("kwh", "kWh", 3.6e6, "Kilowattstunde", "Kilowatt hour"),
            linear("mwh", "MWh", 3.6e9, "Megawattstunde", "Megawatt hour"),
            linear("cal", "cal", 4.184, "Kalorie", "Calorie"),
            linear("kcal", "kcal", 4184.0, "Kilokalorie", "Kilocalorie"),
            linear("btu", "BTU", 1055.05585262, "British Thermal Unit"),
            linear("ftlbf", "ft·lbf", 1.3558179483314, "Fuß-Pfund-Kraft", "Foot-pound force"),
            linear("ev", "eV", 1.602176634e-19, "Elektronenvolt", "Electronvolt"),
        ),
    )

    private val power = NumericCategory(
        POWER,
        listOf(
            linear("mw", "mW", 1e-3, "Milliwatt"),
            linear("w", "W", 1.0, "Watt"),
            linear("kw", "kW", 1e3, "Kilowatt"),
            linear("mww", "MW", 1e6, "Megawatt"),
            linear("gw", "GW", 1e9, "Gigawatt"),
            linear("ps", "PS", 735.49875, "Pferdestärke", "Metric horsepower"),
            linear("hp", "hp", 745.69987158227022, "Horsepower (mechanisch)", "Horsepower (mechanical)"),
            linear("btuh", "BTU/h", 1055.05585262 / 3600.0),
            linear("kcalh", "kcal/h", 4184.0 / 3600.0),
            linear("ftlbfs", "ft·lbf/s", 1.3558179483314),
        ),
    )

    private val pressure = NumericCategory(
        PRESSURE,
        listOf(
            linear("pa", "Pa", 1.0, "Pascal"),
            linear("hpa", "hPa", 100.0, "Hektopascal", "Hectopascal"),
            linear("kpa", "kPa", 1e3, "Kilopascal"),
            linear("mpa", "MPa", 1e6, "Megapascal"),
            linear("bar", "bar", 1e5, "Bar"),
            linear("mbar", "mbar", 100.0, "Millibar"),
            linear("atm", "atm", 101_325.0, "Physikalische Atmosphäre", "Standard atmosphere"),
            linear("at", "at", 98_066.5, "Technische Atmosphäre", "Technical atmosphere"),
            linear("mmhg", "mmHg", 133.322387415, "Millimeter Quecksilbersäule (Torr)", "Millimeter of mercury (Torr)"),
            linear("inhg", "inHg", 3386.389, "Zoll Quecksilbersäule", "Inch of mercury"),
            linear("psi", "psi", 6894.757293168, "Pound per square inch"),
            linear("mmh2o", "mmH₂O", 9.80665, "Millimeter Wassersäule", "Millimeter of water"),
        ),
    )

    private val angle = NumericCategory(
        ANGLE,
        listOf(
            linear("deg", "°", 1.0, "Grad", "Degree"),
            linear("rad", "rad", 180.0 / PI, "Radiant", "Radian"),
            linear("gon", "gon", 0.9, "Gon (Neugrad)", "Gradian"),
            linear("arcmin", "′", 1.0 / 60.0, "Bogenminute", "Arcminute"),
            linear("arcsec", "″", 1.0 / 3600.0, "Bogensekunde", "Arcsecond"),
            linear("rev", "U", 360.0, "Umdrehung", "Revolution"),
            linear("mil", "mil", 360.0 / 6400.0, "Strich (NATO)", "Mil (NATO)"),
        ),
        allowNegative = true,
    )

    private val density = NumericCategory(
        DENSITY,
        listOf(
            linear("kgm3", "kg/m³", 1.0),
            linear("gl", "g/l", 1.0),
            linear("gcm3", "g/cm³", 1000.0, "Gramm pro Kubikzentimeter (= kg/l)", "Grams per cubic centimeter (= kg/l)"),
            linear("lbft3", "lb/ft³", 16.01846337396),
            linear("lbin3", "lb/in³", 27_679.9047102),
            linear("lbgal", "lb/gal (US)", 119.826427316897),
            linear("ozin3", "oz/in³", 1729.99404),
        ),
    )

    // Schuhgröße: Basis ist die Leistenlänge in cm. Pariser Stich EU = 1,5 · L; UK = 3 · L/2,54 − 25 (Erwachsene);
    // US Herren = UK + 1; US Damen = UK + 2; Fußlänge = Leistenlänge − 1,5 cm (Zugabe).
    private fun ukToLast(uk: Double) = 2.54 * (uk + 25.0) / 3.0
    private fun lastToUk(last: Double) = 3.0 * last / 2.54 - 25.0
    private const val SHOE_ALLOWANCE_CM = 1.5

    private val shoe = NumericCategory(
        SHOE,
        listOf(
            mapped("eu", "EU", { it / 1.5 }, { it * 1.5 }, "EU (Pariser Stich)", "EU (Paris point)", step = 0.5),
            mapped("uk", "UK", ::ukToLast, ::lastToUk, "UK (Erwachsene)", "UK (adult)", step = 0.5),
            mapped("usm", "US M", { ukToLast(it - 1.0) }, { lastToUk(it) + 1.0 }, "US Herren", "US men", step = 0.5),
            mapped("usw", "US W", { ukToLast(it - 2.0) }, { lastToUk(it) + 2.0 }, "US Damen", "US women", step = 0.5),
            mapped("cm", "cm", { it + SHOE_ALLOWANCE_CM }, { it - SHOE_ALLOWANCE_CM }, "Fußlänge (cm)", "Foot length (cm)", step = 0.1),
            mapped("in", "in", { it * 2.54 + SHOE_ALLOWANCE_CM }, { (it - SHOE_ALLOWANCE_CM) / 2.54 }, "Fußlänge (in)", "Foot length (in)", step = 0.05),
        ),
    )

    private val clothesWomen = TableCategory(
        CLOTHES_WOMEN,
        listOf(
            ConverterUnit("eu", "EU/DE"),
            ConverterUnit("fr", "FR"),
            ConverterUnit("it", "IT"),
            ConverterUnit("uk", "UK"),
            ConverterUnit("us", "US"),
            ConverterUnit("letter", "XS–XXL", "Buchstaben", "Letters"),
        ),
        listOf(
            listOf("32", "34", "38", "4", "0", "XXS"),
            listOf("34", "36", "40", "6", "2", "XS"),
            listOf("36", "38", "42", "8", "4", "S"),
            listOf("38", "40", "44", "10", "6", "M"),
            listOf("40", "42", "46", "12", "8", "L"),
            listOf("42", "44", "48", "14", "10", "XL"),
            listOf("44", "46", "50", "16", "12", "XXL"),
            listOf("46", "48", "52", "18", "14", "3XL"),
            listOf("48", "50", "54", "20", "16", "4XL"),
            listOf("50", "52", "56", "22", "18", "5XL"),
            listOf("52", "54", "58", "24", "20", "6XL"),
        ),
    )

    private val clothesMen = TableCategory(
        CLOTHES_MEN,
        listOf(
            ConverterUnit("eu", "EU/DE"),
            ConverterUnit("chest", "cm", "Brustumfang (cm)", "Chest (cm)"),
            ConverterUnit("us", "US/UK", "US/UK (Zoll)", "US/UK (inch)"),
            ConverterUnit("letter", "S–XXL", "Buchstaben", "Letters"),
        ),
        listOf(
            listOf("44", "88", "34", "XS"),
            listOf("46", "92", "36", "S"),
            listOf("48", "96", "38", "M"),
            listOf("50", "100", "40", "L"),
            listOf("52", "104", "42", "XL"),
            listOf("54", "108", "44", "XXL"),
            listOf("56", "112", "46", "3XL"),
            listOf("58", "116", "48", "4XL"),
            listOf("60", "120", "50", "5XL"),
            listOf("62", "124", "52", "6XL"),
        ),
    )

    private val digital = NumericCategory(
        DIGITAL,
        listOf(
            linear("bit", "bit", 0.125, "Bit"),
            linear("kbit", "kbit", 125.0, "Kilobit"),
            linear("mbit", "Mbit", 125_000.0, "Megabit"),
            linear("gbit", "Gbit", 125_000_000.0, "Gigabit"),
            linear("b", "B", 1.0, "Byte"),
            linear("kb", "kB", 1e3, "Kilobyte"),
            linear("kib", "KiB", 1024.0, "Kibibyte"),
            linear("mb", "MB", 1e6, "Megabyte"),
            linear("mib", "MiB", 1024.0.pow(2), "Mebibyte"),
            linear("gb", "GB", 1e9, "Gigabyte"),
            linear("gib", "GiB", 1024.0.pow(3), "Gibibyte"),
            linear("tb", "TB", 1e12, "Terabyte"),
            linear("tib", "TiB", 1024.0.pow(4), "Tebibyte"),
            linear("pb", "PB", 1e15, "Petabyte"),
            linear("pib", "PiB", 1024.0.pow(5), "Pebibyte"),
        ),
    )

    /** SI-Präfixe von Quetta (10³⁰) bis quecto (10⁻³⁰); der Wert wird in der Basiseinheit ohne Präfix mitgeführt. */
    private fun prefix(id: String, symbol: String, exponent: Int, nameDe: String, nameEn: String = nameDe) =
        mapped(id, symbol, { it * 10.0.pow(exponent) }, { it / 10.0.pow(exponent) }, nameDe, nameEn)

    private val prefixes = NumericCategory(
        PREFIXES,
        listOf(
            prefix("quetta", "Q", 30, "Quetta"),
            prefix("ronna", "R", 27, "Ronna"),
            prefix("yotta", "Y", 24, "Yotta"),
            prefix("zetta", "Z", 21, "Zetta"),
            prefix("exa", "E", 18, "Exa"),
            prefix("peta", "P", 15, "Peta"),
            prefix("tera", "T", 12, "Tera"),
            prefix("giga", "G", 9, "Giga"),
            prefix("mega", "M", 6, "Mega"),
            prefix("kilo", "k", 3, "Kilo"),
            prefix("hekto", "h", 2, "Hekto", "Hecto"),
            prefix("deka", "da", 1, "Deka"),
            prefix("base", "", 0, "Ohne Präfix", "No prefix"),
            prefix("dezi", "d", -1, "Dezi", "Deci"),
            prefix("zenti", "c", -2, "Zenti", "Centi"),
            prefix("milli", "m", -3, "Milli"),
            prefix("mikro", "µ", -6, "Mikro", "Micro"),
            prefix("nano", "n", -9, "Nano"),
            prefix("piko", "p", -12, "Piko", "Pico"),
            prefix("femto", "f", -15, "Femto"),
            prefix("atto", "a", -18, "Atto"),
            prefix("zepto", "z", -21, "Zepto"),
            prefix("yocto", "y", -24, "Yocto"),
            prefix("ronto", "r", -27, "Ronto"),
            prefix("quecto", "q", -30, "Quecto"),
        ),
        allowNegative = true,
    )

    val categories: List<ConverterCategory> = listOf(
        length, area, volume, time, temperature, speed, mass, force, fuel, RadixCategory(RADIX), energy, power, pressure,
        angle, density, shoe, clothesWomen, clothesMen, digital, prefixes,
    )

    val ids: List<String> = categories.map { it.id }

    fun byId(id: String?): ConverterCategory? = categories.firstOrNull { it.id == id }

    /** Kennung der Kategorie, die beim ersten Öffnen gezeigt wird. */
    val default: ConverterCategory get() = categories.first()
}
