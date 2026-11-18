package io.github.psch0rr.omntuploader

/**
 * Prüfung von OMNT-Zeilen (InfluxDB Line Protocol, ms-Zeitstempel).
 */
object LineProtocol {

    /**
     * Version des Importer-Kerns (Prüfregeln + Upload-Ablauf).
     * Bei Änderungen an den Prüfregeln oder am Upload-Ablauf anheben.
     */
    const val IMPORTER_CORE_VERSION = "1.2.2"

    val KNOWN_MEASUREMENTS = setOf(
        "NetworkInformation", "InterfaceThroughput", "SignalStrength", "CellInformation",
        "IPAddressInformation", "Location", "BuildInformation", "BatteryInformation",
        "Ping", "Iperf3",
    )

    private val FIELD_VALUE = Regex(
        "^(?:\"(?:[^\"\\\\]|\\\\.)*\"" +              // String
            "|[-+]?\\d+[iu]" +                         // Integer
            "|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?" + // Float
            "|t|T|true|True|TRUE|f|F|false|False|FALSE)$"
    )

    data class Parsed(val measurement: String, val tags: Map<String, String>, val timestampMs: Long)

    data class Check(val ok: Boolean, val reason: String = "", val parsed: Parsed? = null)

    /** Index des ersten nicht escapten Leerzeichens ab [start]; Anführungszeichen optional beachten. */
    fun findUnescapedSpace(s: String, start: Int, respectQuotes: Boolean): Int {
        var i = start
        var inQuotes = false
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (respectQuotes && c == '"') {
                inQuotes = !inQuotes
            } else if (c == ' ' && !inQuotes) {
                return i
            }
            i++
        }
        return -1
    }

    fun splitUnescaped(s: String, sep: Char, respectQuotes: Boolean): List<String> {
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var i = 0
        var inQuotes = false
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                buf.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (respectQuotes && c == '"') inQuotes = !inQuotes
            if (c == sep && !inQuotes) {
                parts.add(buf.toString())
                buf.setLength(0)
            } else {
                buf.append(c)
            }
            i++
        }
        parts.add(buf.toString())
        return parts
    }

    fun check(line: String): Check {
        val tagEnd = findUnescapedSpace(line, 0, respectQuotes = false)
        if (tagEnd < 0) return Check(false, "no fields")
        val series = line.substring(0, tagEnd)
        val fieldEnd = findUnescapedSpace(line, tagEnd + 1, respectQuotes = true)
        if (fieldEnd < 0) return Check(false, "no timestamp")
        val fields = line.substring(tagEnd + 1, fieldEnd)
        val ts = line.substring(fieldEnd + 1)

        val parts = splitUnescaped(series, ',', respectQuotes = false)
        val measurement = parts[0]
        if (measurement !in KNOWN_MEASUREMENTS) {
            return Check(false, "unknown measurement '${measurement.take(30)}'")
        }
        if (ts.length != 13 || !ts.all { it in '0'..'9' }) {
            return Check(false, "timestamp incomplete (lines interleaved)")
        }

        val tags = LinkedHashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq <= 0 || eq == p.length - 1) return Check(false, "invalid tag")
            tags[p.substring(0, eq)] = p.substring(eq + 1)
                .replace("\\ ", " ").replace("\\,", ",").replace("\\=", "=")
        }
        for (f in splitUnescaped(fields, ',', respectQuotes = true)) {
            val eq = f.indexOf('=')
            if (eq <= 0 || !FIELD_VALUE.matches(f.substring(eq + 1))) return Check(false, "invalid field")
        }
        return Check(true, parsed = Parsed(measurement, tags, ts.toLong()))
    }

    data class Rejected(val lineNo: Int, val reason: String, val preview: String)

    data class Analysis(
        val total: Int = 0,
        val valid: Int = 0,
        val rejected: List<Rejected> = emptyList(),
        val rejectedCount: Int = 0,
        val perMeasurement: Map<String, Int> = emptyMap(),
        val runs: Set<String> = emptySet(),
        val devices: Set<String> = emptySet(),
        val firstMs: Long? = null,
        val lastMs: Long? = null,
        val error: String? = null,
        /** Eindeutige Zeitstempel je Measurement – Grundlage für die Datenbankprüfung. */
        val tsCounts: Map<String, Int> = emptyMap(),
    )

    /** Prüft alle Zeilen einer Datei. Die Datei selbst wird nie verändert. */
    fun analyze(lines: Sequence<String>): Analysis {
        var total = 0
        var valid = 0
        var rejectedCount = 0
        val rejected = mutableListOf<Rejected>()
        val per = sortedMapOf<String, Int>()
        val tsSets = HashMap<String, HashSet<Long>>()
        val runs = sortedSetOf<String>()
        val devices = sortedSetOf<String>()
        var first: Long? = null
        var last: Long? = null
        var lineNo = 0
        for (raw in lines) {
            lineNo++
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            if (total == 0 && line.trimStart().startsWith("{\"event\"")) {
                return Analysis(error = "iPerf3 raw data (JSON) – use the line-protocol file of the same measurement")
            }
            total++
            val c = check(line)
            val p = c.parsed
            if (!c.ok || p == null) {
                rejectedCount++
                if (rejected.size < 50) rejected.add(Rejected(lineNo, c.reason, line.take(140)))
                continue
            }
            valid++
            per[p.measurement] = (per[p.measurement] ?: 0) + 1
            tsSets.getOrPut(p.measurement) { HashSet() }.add(p.timestampMs)
            p.tags["measurement_name"]?.let { runs.add(it) }
            p.tags["device"]?.let { devices.add(it) }
            if (first == null || p.timestampMs < first) first = p.timestampMs
            if (last == null || p.timestampMs > last) last = p.timestampMs
        }
        return Analysis(total, valid, rejected, rejectedCount, per, runs, devices, first, last,
            tsCounts = tsSets.mapValues { it.value.size })
    }
}
