package io.github.psch0rr.omntuploader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedReader
import java.io.InputStreamReader

enum class LogKind(val title: String) {
    GENERAL("Measurement logs"),
    PING("Ping"),
    IPERF3("iPerf3"),
    IPERF3_JSON("iPerf3 raw data (JSON)"),
}

data class LogFile(
    val uri: Uri,
    val documentId: String,
    val name: String,
    val folder: String,       // Pfad relativ zum gewählten Ordner, z. B. "log" oder "iperf3/lineprotocol"
    val size: Long,
    val modifiedMs: Long,
    val kind: LogKind,
) {
    /** Ändert sich die Datei (Größe/Zeit), gilt sie wieder als nicht importiert. */
    val key: String get() = "$documentId|$size|$modifiedMs"
}

/**
 * Durchsucht den freigegebenen OMNT-Ordner (Storage Access Framework) samt Unterordnern
 * und ordnet jede Datei anhand ihres Inhalts einer Log-Art zu.
 */
class LogRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    private val candidateName = Regex(""".*\.(txt(_\d+)?|lp|json)$""", RegexOption.IGNORE_CASE)
    private val generalName = Regex("""^\d{4}_\d{2}_\d{2}_\d{2}_\d{2}_\d{2}\.txt(_\d+)?$""")

    fun scan(treeUri: Uri, maxDepth: Int = 5): List<LogFile> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = mutableListOf<LogFile>()
        walk(treeUri, rootId, "", 0, maxDepth, out)
        return out.sortedByDescending { it.modifiedMs }
    }

    private fun walk(tree: Uri, parentId: String, path: String, depth: Int, maxDepth: Int, out: MutableList<LogFile>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < maxDepth) walk(tree, id, if (path.isEmpty()) name else "$path/$name", depth + 1, maxDepth, out)
                    continue
                }
                if (size == 0L || !candidateName.matches(name)) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                val kind = classify(uri, name) ?: continue
                out.add(LogFile(uri, id, name, path, size, modified, kind))
            }
        }
    }

    /** Erste Zeile lesen und daran die Log-Art erkennen; Dateiname als Rückfallebene. */
    private fun classify(uri: Uri, name: String): LogKind? {
        val first = try {
            openReader(uri).use { r -> generateSequence { r.readLine() }.take(20).firstOrNull { it.isNotBlank() } }
        } catch (e: Exception) {
            null
        } ?: return null
        val head = first.trimStart()
        if (head.startsWith("{") && head.contains("\"event\"")) return LogKind.IPERF3_JSON
        return when (head.substringBefore(',')) {
            "Ping" -> LogKind.PING
            "Iperf3" -> LogKind.IPERF3
            in LineProtocol.KNOWN_MEASUREMENTS -> LogKind.GENERAL
            else -> if (generalName.matches(name)) LogKind.GENERAL else null
        }
    }

    fun openReader(uri: Uri): BufferedReader =
        BufferedReader(InputStreamReader(resolver.openInputStream(uri) ?: error("File not readable"), Charsets.UTF_8))

    fun analyze(file: LogFile): LineProtocol.Analysis =
        openReader(file.uri).use { r -> LineProtocol.analyze(generateSequence { r.readLine() }) }
}
