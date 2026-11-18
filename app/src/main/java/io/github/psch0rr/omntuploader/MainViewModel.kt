package io.github.psch0rr.omntuploader

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileState(
    val analysis: LineProtocol.Analysis? = null,
    val analyzing: Boolean = false,
    val uploading: Boolean = false,
    val written: Int = 0,
    val result: String? = null,
    val resultOk: Boolean? = null,
    val checking: Boolean = false,
    val db: InfluxClient.DbCheck? = null,
)

data class UiState(
    val folder: Uri? = null,
    val files: List<LogFile> = emptyList(),
    val imported: Set<String> = emptySet(),
    val selected: Set<String> = emptySet(),          // LogFile.key
    val states: Map<String, FileState> = emptyMap(), // LogFile.key
    val scanning: Boolean = false,
    val uploading: Boolean = false,
    val checking: Boolean = false,
    val onlyNew: Boolean = true,
    val showJson: Boolean = false,
    val collapsed: Set<LogKind> = emptySet(),
    val settings: InfluxSettings = InfluxSettings(),
    val testMessage: String? = null,
    val testOk: Boolean? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val repo = LogRepository(app)
    private val _ui = MutableStateFlow(UiState(folder = store.folderUri, settings = store.load(), imported = store.importedKeys(),
        collapsed = store.collapsedKinds))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        if (store.folderUri != null) rescan()
    }

    fun setFolder(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            _ui.update { it.copy(error = "Folder access failed: ${e.message}") }
            return
        }
        store.folderUri = uri
        _ui.update { it.copy(folder = uri, selected = emptySet(), states = emptyMap()) }
        rescan()
    }

    /** Kategorie in der Liste ein- oder ausklappen; die Auswahl bleibt dabei erhalten. */
    fun toggleCollapsed(kind: LogKind) {
        _ui.update { s ->
            val c = if (kind in s.collapsed) s.collapsed - kind else s.collapsed + kind
            store.collapsedKinds = c
            s.copy(collapsed = c)
        }
    }

    fun setAllCollapsed(collapsed: Boolean) {
        val c = if (collapsed) LogKind.entries.toSet() else emptySet()
        store.collapsedKinds = c
        _ui.update { it.copy(collapsed = c) }
    }

    fun rescan() {
        val folder = _ui.value.folder ?: return
        _ui.update { it.copy(scanning = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.scan(folder) } }
            result.onSuccess { files ->
                val keys = files.map { it.key }.toSet()
                _ui.update { s ->
                    s.copy(files = files, scanning = false,
                        selected = s.selected intersect keys, states = s.states.filterKeys { it in keys })
                }
            }.onFailure { e ->
                _ui.update { it.copy(scanning = false, error = "Folder not readable – please choose it again (${e.message})") }
            }
        }
    }

    fun toggleOnlyNew() = _ui.update { it.copy(onlyNew = !it.onlyNew) }
    fun toggleShowJson() = _ui.update { it.copy(showJson = !it.showJson) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    fun visibleFiles(s: UiState = _ui.value): List<LogFile> = s.files.filter { f ->
        (s.showJson || f.kind != LogKind.IPERF3_JSON) && (!s.onlyNew || f.key !in s.imported || f.key in s.selected)
    }

    fun toggle(file: LogFile) {
        if (file.kind == LogKind.IPERF3_JSON || _ui.value.uploading) return
        val nowSelected = file.key !in _ui.value.selected
        _ui.update { s -> s.copy(selected = if (nowSelected) s.selected + file.key else s.selected - file.key) }
        if (nowSelected) analyze(file)
    }

    fun selectAll(kind: LogKind, select: Boolean) {
        if (_ui.value.uploading) return
        val files = visibleFiles().filter { it.kind == kind }
        _ui.update { s ->
            val keys = files.map { it.key }.toSet()
            s.copy(selected = if (select) s.selected + keys else s.selected - keys)
        }
        if (select) files.forEach { analyze(it) }
    }

    private fun setState(key: String, f: (FileState) -> FileState) =
        _ui.update { s -> s.copy(states = s.states + (key to f(s.states[key] ?: FileState()))) }

    private fun analyze(file: LogFile) {
        val st = _ui.value.states[file.key]
        if (st?.analysis != null || st?.analyzing == true) return
        setState(file.key) { it.copy(analyzing = true) }
        viewModelScope.launch {
            val a = withContext(Dispatchers.IO) {
                runCatching { repo.analyze(file) }.getOrElse { LineProtocol.Analysis(error = "not readable: ${it.message}") }
            }
            setState(file.key) { it.copy(analysis = a, analyzing = false) }
        }
    }

    fun upload() {
        val s = _ui.value
        val todo = s.files.filter { it.key in s.selected }
        if (todo.isEmpty() || s.uploading) return
        _ui.update { it.copy(uploading = true) }
        viewModelScope.launch {
            val client = InfluxClient(s.settings)
            var unreachable = false
            for (file in todo) {
                if (unreachable) {
                    setState(file.key) { it.copy(result = "not attempted – database not reachable", resultOk = false) }
                    continue
                }
                val analysis = _ui.value.states[file.key]?.analysis
                    ?: withContext(Dispatchers.IO) { runCatching { repo.analyze(file) }.getOrNull() }
                if (analysis == null || analysis.error != null || analysis.valid == 0) {
                    setState(file.key) {
                        it.copy(analysis = analysis, result = analysis?.error ?: "no valid lines", resultOk = false)
                    }
                    continue
                }
                setState(file.key) { it.copy(analysis = analysis, uploading = true, written = 0, result = null, resultOk = null) }
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        repo.openReader(file.uri).use { reader ->
                            client.upload(generateSequence { reader.readLine() }) { n ->
                                setState(file.key) { st -> st.copy(written = n) }
                            }
                        }
                    }
                }
                r.onSuccess { res ->
                    if (res.aborted) unreachable = true
                    val ok = res.failed == 0 && !res.aborted
                    if (ok) store.markImported(file.key)
                    setState(file.key) {
                        it.copy(uploading = false, written = res.written, resultOk = ok,
                            result = when {
                                ok -> "✓ ${res.written} lines written"
                                res.aborted -> res.errors.first()
                                else -> "${res.written} written, ${res.failed} failed: ${res.errors.joinToString(" | ")}"
                            })
                    }
                }.onFailure { e ->
                    setState(file.key) { it.copy(uploading = false, resultOk = false, result = "Error: ${e.message}") }
                }
            }
            _ui.update { st ->
                val done = st.files.filter { it.key in st.selected && st.states[it.key]?.resultOk == true }.map { it.key }
                st.copy(uploading = false, imported = store.importedKeys(), selected = st.selected - done.toSet())
            }
        }
    }

    /** Fragt für die ausgewählten Dateien die Datenbank ab. Zeigt nur an – der Upload bleibt immer möglich. */
    fun checkDatabase() {
        val s = _ui.value
        val todo = s.files.filter { it.key in s.selected }
        if (todo.isEmpty() || s.checking || s.uploading) return
        _ui.update { it.copy(checking = true) }
        viewModelScope.launch {
            val client = InfluxClient(s.settings)
            for (file in todo) {
                setState(file.key) { it.copy(checking = true) }
                val analysis = _ui.value.states[file.key]?.analysis
                    ?: withContext(Dispatchers.IO) { runCatching { repo.analyze(file) }.getOrNull() }
                val res = withContext(Dispatchers.IO) {
                    when {
                        analysis == null -> InfluxClient.DbCheck(InfluxClient.DbStatus.ERROR, 0.0, "File not readable")
                        analysis.error != null -> InfluxClient.DbCheck(InfluxClient.DbStatus.ERROR, 0.0, analysis.error)
                        else -> runCatching { client.checkExisting(analysis) }.getOrElse {
                            InfluxClient.DbCheck(InfluxClient.DbStatus.ERROR, 0.0, "Error: ${it.message}")
                        }
                    }
                }
                setState(file.key) { it.copy(checking = false, db = res, analysis = analysis ?: it.analysis) }
            }
            _ui.update { it.copy(checking = false) }
        }
    }

    fun unmarkImported(file: LogFile) {
        store.unmarkImported(file.key)
        _ui.update { it.copy(imported = store.importedKeys()) }
    }

    fun saveSettings(new: InfluxSettings) {
        store.save(new)
        _ui.update { it.copy(settings = store.load(), testMessage = null, testOk = null) }
    }

    fun testConnection(candidate: InfluxSettings) {
        _ui.update { it.copy(testMessage = "Testing …", testOk = null) }
        viewModelScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                runCatching { InfluxClient(candidate).test() }.getOrElse { false to "Error: ${it.message}" }
            }
            _ui.update { it.copy(testMessage = msg, testOk = ok) }
        }
    }
}
