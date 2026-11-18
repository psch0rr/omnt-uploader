package io.github.psch0rr.omntuploader

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPOutputStream

/** Schreibt Line Protocol in eine InfluxDB 2.x (gzip, Batches, Millisekunden). */
class InfluxClient(private val s: InfluxSettings) {

    data class Response(val code: Int, val body: String)

    data class UploadResult(
        val written: Int,
        val failed: Int,
        val errors: List<String>,
        /** true: Datenbank nicht erreichbar, Upload nach dem ersten Verbindungsfehler abgebrochen. */
        val aborted: Boolean = false,
    )

    /** Signalisiert den Abbruch aus flush() heraus. */
    private class Unreachable(val reason: String) : Exception(reason)

    enum class DbStatus { FULL, PARTIAL, NONE, ERROR }

    data class DbCheck(val status: DbStatus, val percent: Double, val message: String, val details: Map<String, Pair<Int, Int>> = emptyMap())

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        auth: Boolean = true,
    ): Response {
        val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val conn = URL(s.url.trimEnd('/') + path + q).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            if (auth) conn.setRequestProperty("Authorization", "Token ${s.token}")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun message(body: String): String =
        try {
            JSONObject(body).optString("message", body)
        } catch (e: Exception) {
            body
        }.take(300)

    private fun fluxStr(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun fluxTime(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))

    private fun anyOf(col: String, values: Collection<String>) =
        "(" + values.joinToString(" or ") { "r.$col == ${fluxStr(it)}" } + ")"

    /** Flux-Abfrage: eindeutige Zeitstempel je Measurement für Fahrt, Gerät und Zeitraum. */
    fun checkQuery(a: LineProtocol.Analysis): String {
        val filters = mutableListOf(anyOf("_measurement", a.tsCounts.keys.sorted()))
        if (a.runs.isNotEmpty()) filters.add(anyOf("measurement_name", a.runs))
        if (a.devices.isNotEmpty()) filters.add(anyOf("device", a.devices))
        return "from(bucket: ${fluxStr(s.bucket)})\n" +
            "  |> range(start: ${fluxTime(a.firstMs!!)}, stop: ${fluxTime(a.lastMs!! + 1)})\n" +
            "  |> filter(fn: (r) => ${filters.joinToString(" and ")})\n" +
            "  |> keep(columns: [\"_time\", \"_measurement\"])\n" +
            // count() kann keine Zeitspalte zählen → Hilfsspalte _value (String) mitzählen
            "  |> set(key: \"_value\", value: \"x\")\n" +
            "  |> group(columns: [\"_measurement\"])\n" +
            "  |> unique(column: \"_time\")\n" +
            "  |> count()\n"
    }

    /** Zeigt nur an, wie viel schon in der Datenbank steht – ändert nichts und sperrt keinen Upload. */
    fun checkExisting(a: LineProtocol.Analysis): DbCheck {
        if (a.tsCounts.isEmpty() || a.firstMs == null || a.lastMs == null) {
            return DbCheck(DbStatus.ERROR, 0.0, "no valid lines to check")
        }
        val body = JSONObject().put("query", checkQuery(a)).put("type", "flux").toString().toByteArray()
        val r = try {
            request("POST", "/api/v2/query", mapOf("org" to s.org), body,
                mapOf("Content-Type" to "application/json", "Accept" to "application/csv"))
        } catch (e: IOException) {
            return DbCheck(DbStatus.ERROR, 0.0, "Database not reachable: ${e.message}")
        }
        if (r.code != 200) return DbCheck(DbStatus.ERROR, 0.0, "HTTP ${r.code}: ${message(r.body)}")
        val db = parseCountCsv(r.body)
        val fileTotal = a.tsCounts.values.sum()
        val found = a.tsCounts.entries.sumOf { (m, n) -> minOf(db[m] ?: 0, n) }
        val details = a.tsCounts.mapValues { (m, n) -> (db[m] ?: 0) to n }
        val pct = if (fileTotal > 0) 100.0 * found / fileTotal else 0.0
        return when {
            found == 0 -> DbCheck(DbStatus.NONE, 0.0, "not in the database", details)
            found >= fileTotal -> DbCheck(DbStatus.FULL, 100.0, "already completely in the database", details)
            else -> DbCheck(DbStatus.PARTIAL, pct,
                "partly in the database (${String.format(java.util.Locale.US, "%.1f", pct)} %)", details)
        }
    }

    /** Annotiertes CSV der Flux-Antwort → Measurement → Anzahl. */
    fun parseCountCsv(text: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        var header: List<String>? = null
        for (line in text.lineSequence()) {
            val row = line.trimEnd('\r')
            if (row.isBlank()) { header = null; continue }
            if (row.startsWith("#")) continue
            val cols = row.split(',')
            if (header == null) { header = cols; continue }
            val m = cols.getOrNull(header.indexOf("_measurement")) ?: continue
            val n = cols.getOrNull(header.indexOf("_value"))?.toIntOrNull() ?: continue
            if (m.isNotEmpty()) out[m] = (out[m] ?: 0) + n
        }
        return out
    }

    /** Prüft Server, Token und Bucket. Rückgabe: (ok, Meldung). */
    fun test(): Pair<Boolean, String> {
        val health = try {
            request("GET", "/health", auth = false)
        } catch (e: IOException) {
            return false to "Server not reachable: ${e.message}"
        } catch (e: IllegalArgumentException) {
            return false to "Invalid URL: ${e.message}"
        }
        if (health.code != 200) return false to "Server responded with HTTP ${health.code}"
        val r = try {
            request("GET", "/api/v2/buckets", mapOf("org" to s.org, "name" to s.bucket))
        } catch (e: IOException) {
            return false to "Error: ${e.message}"
        }
        if (r.code == 401) return false to "Invalid token (HTTP 401)"
        if (r.code != 200) return false to "HTTP ${r.code}: ${message(r.body)}"
        val buckets = JSONObject(r.body).optJSONArray("buckets")
        if (buckets == null || buckets.length() == 0) {
            return false to "Bucket '${s.bucket}' not found in org '${s.org}'"
        }
        return true to "Connected – bucket '${s.bucket}' found"
    }

    /**
     * Schreibt nur die gültigen Zeilen, in Paketen zu [InfluxSettings.batchSize], gzip-komprimiert,
     * Präzision Millisekunden. [onProgress] erhält die bisher geschriebenen Zeilen.
     */
    fun upload(lines: Sequence<String>, onProgress: (Int) -> Unit): UploadResult {
        var written = 0
        var failed = 0
        val errors = mutableListOf<String>()
        val batch = ArrayList<String>(s.batchSize)
        var startLine = 1

        fun flush() {
            if (batch.isEmpty()) return
            val bytes = ByteArrayOutputStream().also { bos ->
                GZIPOutputStream(bos).use { gz -> gz.write((batch.joinToString("\n") + "\n").toByteArray()) }
            }.toByteArray()
            val r = try {
                request(
                    "POST", "/api/v2/write",
                    mapOf("org" to s.org, "bucket" to s.bucket, "precision" to "ms"),
                    bytes,
                    mapOf("Content-Type" to "text/plain; charset=utf-8", "Content-Encoding" to "gzip"),
                )
            } catch (e: IOException) {
                // Datenbank nicht erreichbar: sofort abbrechen statt jedes weitere Paket bis zum Timeout zu versuchen
                throw Unreachable(e.message ?: e.javaClass.simpleName)
            }
            if (r.code == 204) {
                written += batch.size
            } else {
                failed += batch.size
                if (errors.size < 10) {
                    errors.add("Lines $startLine–${startLine + batch.size - 1}: HTTP ${r.code} ${message(r.body)}")
                }
            }
            startLine += batch.size
            batch.clear()
            onProgress(written)
        }

        try {
            for (raw in lines) {
                val line = raw.trimEnd('\r')
                if (line.isBlank() || !LineProtocol.check(line).ok) continue
                batch.add(line)
                if (batch.size >= s.batchSize) flush()
            }
            flush()
        } catch (e: Unreachable) {
            val msg = "Database not reachable (${e.reason}) – upload aborted, " +
                if (written == 0) "nothing written" else "$written lines were written before"
            return UploadResult(written, failed, listOf(msg) + errors.take(9), aborted = true)
        }
        return UploadResult(written, failed, errors)
    }
}
