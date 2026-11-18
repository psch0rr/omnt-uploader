package io.github.psch0rr.omntuploader

import android.content.Context
import android.net.Uri

data class InfluxSettings(
    val url: String = "",
    val org: String = "",
    val bucket: String = "",
    val token: String = "",
    val batchSize: Int = 5000,
)

/**
 * Einstellungen, gewählter Ordner und bereits importierte Dateien.
 * Liegt im privaten App-Speicher; Backups sind im Manifest abgeschaltet (Token).
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("omnt_uploader", Context.MODE_PRIVATE)

    fun load(): InfluxSettings {
        val d = InfluxSettings()
        return InfluxSettings(
            url = prefs.getString("url", d.url) ?: d.url,
            org = prefs.getString("org", d.org) ?: d.org,
            bucket = prefs.getString("bucket", d.bucket) ?: d.bucket,
            token = prefs.getString("token", d.token) ?: d.token,
            batchSize = prefs.getInt("batch_size", d.batchSize),
        )
    }

    fun save(s: InfluxSettings) {
        prefs.edit()
            .putString("url", s.url.trim())
            .putString("org", s.org.trim())
            .putString("bucket", s.bucket.trim())
            .putString("token", s.token.trim())
            .putInt("batch_size", s.batchSize.coerceAtLeast(1))
            .apply()
    }

    var folderUri: Uri?
        get() = prefs.getString("folder_uri", null)?.let(Uri::parse)
        set(value) {
            prefs.edit().putString("folder_uri", value?.toString()).apply()
        }

    /** Schlüssel einer Datei: Dokument-ID + Größe + Änderungszeit. Ändert sich die Datei, gilt sie wieder als neu. */
    fun importedKeys(): Set<String> = prefs.getStringSet("imported", emptySet()) ?: emptySet()

    fun markImported(key: String) {
        val set = importedKeys().toMutableSet()
        set.add(key)
        prefs.edit().putStringSet("imported", set).apply()
    }

    /** Eingeklappte Kategorien der Dateiliste; bleibt über App-Neustarts erhalten. JSON-Rohdaten sind anfangs zu. */
    var collapsedKinds: Set<LogKind>
        get() = (prefs.getStringSet("collapsed_kinds", null) ?: setOf(LogKind.IPERF3_JSON.name))
            .mapNotNull { n -> LogKind.entries.firstOrNull { it.name == n } }.toSet()
        set(value) {
            prefs.edit().putStringSet("collapsed_kinds", value.map { it.name }.toSet()).apply()
        }

    fun unmarkImported(key: String) {
        val set = importedKeys().toMutableSet()
        set.remove(key)
        prefs.edit().putStringSet("imported", set).apply()
    }
}
