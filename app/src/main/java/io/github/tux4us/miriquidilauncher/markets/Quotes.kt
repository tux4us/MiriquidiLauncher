// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.markets

import java.time.LocalDate
import java.time.ZoneOffset

/** Gruppe eines Kurses: Börsenindex oder Rohstoff (Terminkontrakt). */
enum class QuoteGroup { Index, Commodity }

/**
 * Ein fest hinterlegter Kurs. [yahoo] ist das Symbol bei Yahoo Finance, [stooq] das Kürzel bei Stooq (`null`, wenn
 * dort nicht bekannt). [tag] ist bei Indizes die Region (z. B. „DE“), bei Rohstoffen die Handelseinheit
 * (z. B. „oz“, „bbl“). Die Anzeigenamen der Rohstoffe liegen in den App-Texten, [name] ist der Eigenname bzw. ein
 * neutraler Bezeichner.
 */
data class QuoteDef(
    val id: String,
    val yahoo: String,
    val stooq: String?,
    val name: String,
    val tag: String,
    val group: QuoteGroup,
)

/**
 * Katalog der angezeigten Indizes und Rohstoffe. Die Symbole beider Quellen wurden nicht live geprüft; liefert eine
 * Quelle für ein Symbol keine Daten, fehlt die Zeile.
 */
object QuoteCatalog {
    val indices: List<QuoteDef> = listOf(
        idx("dax", "^GDAXI", "^dax", "DAX", "DE"),
        idx("mdax", "^MDAXI", "^mdax", "MDAX", "DE"),
        idx("sdax", "^SDAXI", "^sdax", "SDAX", "DE"),
        idx("tecdax", "^TECDAX", "^tdax", "TecDAX", "DE"),
        idx("estoxx50", "^STOXX50E", null, "Euro Stoxx 50", "EU"),
        idx("dji", "^DJI", "^dji", "Dow Jones", "US"),
        idx("spx", "^GSPC", "^spx", "S&P 500", "US"),
        idx("ndq", "^IXIC", "^ndq", "Nasdaq Composite", "US"),
        idx("ndx", "^NDX", "^ndx", "Nasdaq 100", "US"),
        idx("rut", "^RUT", null, "Russell 2000", "US"),
        idx("ukx", "^FTSE", "^ukx", "FTSE 100", "GB"),
        idx("cac", "^FCHI", "^cac", "CAC 40", "FR"),
        idx("smi", "^SSMI", "^smi", "SMI", "CH"),
        idx("ibex", "^IBEX", "^ibex", "IBEX 35", "ES"),
        idx("aex", "^AEX", "^aex", "AEX", "NL"),
        idx("fmib", "FTSEMIB.MI", "^fmib", "FTSE MIB", "IT"),
        idx("atx", "^ATX", "^atx", "ATX", "AT"),
        idx("nkx", "^N225", "^nkx", "Nikkei 225", "JP"),
        idx("hsi", "^HSI", "^hsi", "Hang Seng", "HK"),
        idx("shc", "000001.SS", "^shc", "Shanghai Composite", "CN"),
        idx("kospi", "^KS11", "^kospi", "KOSPI", "KR"),
        idx("sensex", "^BSESN", "^snx", "BSE Sensex", "IN"),
        idx("nifty", "^NSEI", null, "Nifty 50", "IN"),
        idx("tsx", "^GSPTSE", "^tsx", "S&P/TSX", "CA"),
        idx("bvsp", "^BVSP", "^bvp", "Bovespa", "BR"),
        idx("asx200", "^AXJO", null, "ASX 200", "AU"),
    )

    val commodities: List<QuoteDef> = listOf(
        com("brent", "BZ=F", "cb.f", "Brent", "bbl"),
        com("wti", "CL=F", "cl.f", "WTI", "bbl"),
        com("gas", "NG=F", "ng.f", "Natural gas", "MMBtu"),
        com("heating", "HO=F", "ho.f", "Heating oil", "gal"),
        com("gold", "GC=F", "gc.f", "Gold", "oz"),
        com("silver", "SI=F", "si.f", "Silver", "oz"),
        com("platinum", "PL=F", "pl.f", "Platinum", "oz"),
        com("palladium", "PA=F", "pa.f", "Palladium", "oz"),
        com("copper", "HG=F", "hg.f", "Copper", "lb"),
        com("aluminium", "ALI=F", null, "Aluminium", "t"),
        com("wheat", "ZW=F", null, "Wheat", "bu"),
        com("corn", "ZC=F", null, "Corn", "bu"),
        com("coffee", "KC=F", null, "Coffee", "lb"),
        com("sugar", "SB=F", null, "Sugar", "lb"),
    )

    val all: List<QuoteDef> = indices + commodities

    fun byId(id: String): QuoteDef? = all.firstOrNull { it.id == id }

    private fun idx(id: String, yahoo: String, stooq: String?, name: String, region: String) =
        QuoteDef(id, yahoo, stooq, name, region, QuoteGroup.Index)

    private fun com(id: String, yahoo: String, stooq: String?, name: String, unit: String) =
        QuoteDef(id, yahoo, stooq, name, unit, QuoteGroup.Commodity)
}

/** Datenquellen für Indizes und Rohstoffe. */
object QuoteSource {
    const val YAHOO = "yahoo"
    const val STOOQ = "stooq"
}

/**
 * Ein abgerufener Kurs: [price] und [prevClose] (Schlusskurs der Vorsitzung) in [currency] (Hauptwährung, bei
 * Indizes Punkte der Börsenwährung; `null` = unbekannt), [closes] die letzten Tagesschlusskurse (älteste zuerst, der
 * letzte entspricht [price]).
 */
data class Quote(
    val id: String,
    val price: Double,
    val prevClose: Double?,
    val currency: String?,
    val closes: List<Double>,
    val source: String,
) {
    /** Veränderung gegenüber dem Vortagesschluss in Prozent. */
    val changePercent: Double? get() = prevClose?.takeIf { it > 0.0 }?.let { (price - it) / it * 100.0 }
}

/** Indizes und Rohstoffe mit Abrufzeit; bei einem Abruf fehlende Kurse bleiben vom früheren Stand erhalten. */
data class QuotesData(val quotes: List<Quote>, val atMillis: Long) {
    fun filledFrom(old: QuotesData?): QuotesData {
        if (old == null) return this
        val fresh = quotes.map { it.id }.toSet()
        return copy(quotes = quotes + old.quotes.filter { it.id !in fresh })
    }

    fun byId(id: String): Quote? = quotes.firstOrNull { it.id == id }
}

/** Darstellungszeitraum der Diagramme für Indizes, Rohstoffe und Krypto-Werte. */
enum class SeriesRange(val key: String, val yahooRange: String, val yahooInterval: String, val days: Int) {
    Day("1d", "1d", "5m", 1),
    Week("1w", "5d", "30m", 7),
    Month("1m", "1mo", "1d", 31),
    Year("1y", "1y", "1d", 366),
    FiveYears("5y", "5y", "1wk", 1830),
    ;

    /** Stooq liefert nur Tages- und Wochenwerte; für einen Tag gibt es dort keine Verlaufsdaten. */
    val stooqInterval: String get() = if (this == FiveYears) "w" else "d"
    val stooqSupported: Boolean get() = this != Day

    companion object {
        fun fromKey(key: String?): SeriesRange = entries.firstOrNull { it.key == key } ?: Month
    }
}

/** Zeitreihe eines Kurses: [times] in Unix-Sekunden, [values] gleich lang. */
data class Series(
    val times: List<Long>,
    val values: List<Double>,
    val prevClose: Double?,
    val currency: String?,
    val atMillis: Long,
) {
    val isUsable: Boolean get() = values.size >= 2 && values.size == times.size
}

/** Der Dienst meldet einen fehlenden, ungültigen oder ausgeschöpften API-Schlüssel (Stooq). */
class ApiKeyException(message: String) : Exception(message)

/** Liest die CSV-Antworten von Stooq (`https://stooq.com/q/d/l/`, seit 2026 nur mit eigenem API-Schlüssel). */
object StooqParser {
    class ParseException(message: String) : Exception(message)

    /** Zeitreihenadresse; [from] und [to] im Format `yyyyMMdd`. Der Schlüssel wird nur URL-kodiert angehängt. */
    fun historyUrl(symbol: String, from: String, to: String, interval: String, apiKey: String): String =
        "https://stooq.com/q/d/l/?s=" + enc(symbol) + "&d1=" + from + "&d2=" + to + "&i=" + interval + "&apikey=" + enc(apiKey)

    /** Zeitreihe aus `Date,Open,High,Low,Close,Volume`; Schlusskurse, aufsteigend nach Datum. */
    fun parseSeries(csv: String): Series {
        val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val header = lines.firstOrNull()?.split(',')?.map { it.trim().lowercase() }
        if (header == null || header.firstOrNull() != "date") {
            // Ohne gültigen Schlüssel oder bei ausgeschöpftem Kontingent kommt Text bzw. HTML statt CSV.
            throw ApiKeyException(lines.firstOrNull().orEmpty().take(80))
        }
        val closeIndex = header.indexOf("close")
        if (closeIndex < 0) throw ParseException("Keine Schlusskurse")
        val times = ArrayList<Long>()
        val values = ArrayList<Double>()
        for (line in lines.drop(1)) {
            val cells = line.split(',')
            val date = runCatching { LocalDate.parse(cells[0].trim()) }.getOrNull() ?: continue
            val close = cells.getOrNull(closeIndex)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            times += date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            values += close
        }
        if (values.isEmpty()) throw ParseException("Keine Daten")
        return Series(times, values, null, null, 0L)
    }

    /** Kurs aus einer Tageszeitreihe: letzter und vorletzter Schlusskurs. */
    fun toQuote(id: String, series: Series): Quote {
        val closes = series.values.takeLast(CLOSES_KEPT)
        return Quote(id, closes.last(), closes.getOrNull(closes.size - 2), null, closes, QuoteSource.STOOQ)
    }

    private fun enc(text: String) = java.net.URLEncoder.encode(text, "UTF-8")

    const val CLOSES_KEPT = 25
}
