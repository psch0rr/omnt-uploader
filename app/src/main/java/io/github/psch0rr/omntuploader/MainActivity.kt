package io.github.psch0rr.omntuploader

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Versionsangabe für Kopfzeile und Einstellungen: App-Version und Version des Importer-Kerns. */
val VERSION_LABEL = "App ${BuildConfig.VERSION_NAME} · Importer core ${LineProtocol.IMPORTER_CORE_VERSION}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private val OK_COLOR = Color(0xFF15803D)
private val WARN_COLOR = Color(0xFFB45309)

private fun fmtTime(ms: Long?): String =
    if (ms == null || ms <= 0) "–" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

private fun fmtSize(b: Long): String = when {
    b >= 1_000_000 -> String.format(Locale.US, "%.1f MB", b / 1_000_000.0)
    b >= 1_000 -> String.format(Locale.US, "%.0f KB", b / 1_000.0)
    else -> "$b B"
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Startet die Ordnerauswahl direkt in Documents/omnt
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setFolder(uri)
    }
    val initialUri = remember {
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/omnt")
    }

    BackHandler(enabled = showSettings) { showSettings = false }
    if (showSettings) {
        SettingsScreen(ui, vm, onBack = { showSettings = false })
    } else {
        FilesScreen(ui, vm, onPickFolder = { pickFolder.launch(initialUri) }, onSettings = { showSettings = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(ui: UiState, vm: MainViewModel, onPickFolder: () -> Unit, onSettings: () -> Unit) {
    val visible = vm.visibleFiles(ui)
    val selectedFiles = ui.files.filter { it.key in ui.selected }
    val configured = with(ui.settings) { url.isNotBlank() && org.isNotBlank() && bucket.isNotBlank() && token.isNotBlank() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OMNT Uploader")
                        Text(VERSION_LABEL, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (ui.folder != null) {
                        IconButton(onClick = vm::rescan, enabled = !ui.scanning && !ui.uploading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = {
            if (selectedFiles.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        val valid = selectedFiles.sumOf { ui.states[it.key]?.analysis?.valid ?: 0 }
                        Text("${selectedFiles.size} file(s) selected · $valid valid lines · bucket ${ui.settings.bucket}",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Prüfen zeigt nur an, was schon in der Datenbank steht; hochladen geht trotzdem immer
                            OutlinedButton(onClick = vm::checkDatabase,
                                enabled = !ui.checking && !ui.uploading && configured,
                                modifier = Modifier.weight(1f)) {
                                Text(if (ui.checking) "Checking …" else "Check database")
                            }
                            Button(onClick = vm::upload, enabled = !ui.uploading && configured,
                                modifier = Modifier.weight(1f)) {
                                Text(if (ui.uploading) "Uploading …" else "Upload")
                            }
                        }
                        if (!configured) {
                            Text("Please enter server and token in the settings first.",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.folder == null) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Grant access to the OMNT folder", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Choose the folder Documents/omnt. The app only reads from it – the logs are never modified.")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onPickFolder) { Text("Choose folder") }
                        }
                    }
                }
                return@LazyColumn
            }
            item {
                // FlowRow: bricht auf schmalen Displays in eine zweite Zeile um
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = ui.onlyNew, onClick = vm::toggleOnlyNew, label = { Text("New only") },
                        modifier = Modifier.align(Alignment.CenterVertically))
                    FilterChip(selected = ui.showJson, onClick = vm::toggleShowJson, label = { Text("Show JSON") },
                        modifier = Modifier.align(Alignment.CenterVertically))
                    val kindsShown = LogKind.entries.filter { k -> visible.any { it.kind == k } }
                    if (kindsShown.isNotEmpty()) {
                        val allClosed = kindsShown.all { it in ui.collapsed }
                        TextButton(onClick = { vm.setAllCollapsed(!allClosed) }) {
                            Text(if (allClosed) "Expand all" else "Collapse all")
                        }
                    }
                    TextButton(onClick = onPickFolder, enabled = !ui.uploading) { Text("Folder") }
                }
            }
            ui.error?.let { msg ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = vm::dismissError) { Text("OK") }
                        }
                    }
                }
            }
            if (ui.scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!ui.scanning && visible.isEmpty()) {
                item {
                    Text(if (ui.onlyNew && ui.files.isNotEmpty()) "No new logs – all have already been uploaded."
                    else "No OMNT logs found in this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            for (kind in LogKind.entries) {
                val files = visible.filter { it.kind == kind }
                if (files.isEmpty()) continue
                val collapsed = kind in ui.collapsed
                item(key = "header-$kind") {
                    val nSel = files.count { it.key in ui.selected }
                    Surface(
                        onClick = { vm.toggleCollapsed(kind) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (collapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                contentDescription = if (collapsed) "Expand" else "Collapse")
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text("${kind.title} (${files.size})", style = MaterialTheme.typography.titleSmall)
                                if (nSel > 0) {
                                    Text("$nSel selected", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (kind != LogKind.IPERF3_JSON) {
                                val all = files.all { it.key in ui.selected }
                                TextButton(onClick = { vm.selectAll(kind, !all) }, enabled = !ui.uploading) {
                                    Text(if (all) "None" else "All")
                                }
                            }
                        }
                    }
                }
                if (!collapsed) {
                    items(files, key = { it.key }) { f ->
                        FileRow(f, ui, vm)
                    }
                }
            }
        }
    }
}

@Composable
fun FileRow(f: LogFile, ui: UiState, vm: MainViewModel) {
    val selected = f.key in ui.selected
    val imported = f.key in ui.imported
    val st = ui.states[f.key]
    val isJson = f.kind == LogKind.IPERF3_JSON
    var showRejected by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isJson && !ui.uploading) { vm.toggle(f) },
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Checkbox(checked = selected, onCheckedChange = { vm.toggle(f) }, enabled = !isJson && !ui.uploading)
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.folder.ifEmpty { "." }} · ${fmtSize(f.size)} · ${fmtTime(f.modifiedMs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isJson) {
                    Text("Not needed: the same values are in the iPerf3 file with the same UUID.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (imported) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("already uploaded", style = MaterialTheme.typography.bodySmall, color = OK_COLOR)
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { vm.unmarkImported(f) }, enabled = !ui.uploading) {
                            Text("Remove mark", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (st?.analyzing == true) Text("Checking …", style = MaterialTheme.typography.bodySmall)
                st?.analysis?.let { a ->
                    Spacer(Modifier.height(4.dp))
                    if (a.error != null) {
                        Text(a.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Run: ${a.runs.joinToString().ifEmpty { "–" }} · ${a.devices.joinToString()}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${fmtTime(a.firstMs)} – ${fmtTime(a.lastMs)}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            Text("${a.valid} valid", style = MaterialTheme.typography.bodySmall)
                            if (a.rejectedCount > 0) {
                                Text(" · ${a.rejectedCount} rejected", style = MaterialTheme.typography.bodySmall,
                                    color = WARN_COLOR, modifier = Modifier.clickable { showRejected = !showRejected })
                            }
                        }
                        if (showRejected) {
                            a.rejected.take(10).forEach { r ->
                                Text("Line ${r.lineNo}: ${r.reason}", style = MaterialTheme.typography.labelSmall, color = WARN_COLOR)
                            }
                        }
                    }
                }
                if (st?.uploading == true) {
                    val total = (st.analysis?.valid ?: 0).coerceAtLeast(1)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { st.written / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
                if (st?.checking == true) Text("Checking database …", style = MaterialTheme.typography.bodySmall)
                st?.db?.let { d ->
                    var showDetails by remember { mutableStateOf(false) }
                    Text("DB: ${d.message}", style = MaterialTheme.typography.bodySmall,
                        color = when (d.status) {
                            InfluxClient.DbStatus.FULL -> OK_COLOR
                            InfluxClient.DbStatus.PARTIAL -> WARN_COLOR
                            InfluxClient.DbStatus.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
                            InfluxClient.DbStatus.ERROR -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.clickable(enabled = d.details.isNotEmpty()) { showDetails = !showDetails })
                    if (showDetails) {
                        d.details.forEach { (m, v) ->
                            Text("$m: ${v.first} of ${v.second} timestamps", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                st?.result?.let { r ->
                    Text(r, style = MaterialTheme.typography.bodySmall,
                        color = if (st?.resultOk == true) OK_COLOR else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ui: UiState, vm: MainViewModel, onBack: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(ui.settings.url) }
    var org by rememberSaveable { mutableStateOf(ui.settings.org) }
    var bucket by rememberSaveable { mutableStateOf(ui.settings.bucket) }
    var token by rememberSaveable { mutableStateOf(ui.settings.token) }
    var batch by rememberSaveable { mutableStateOf(ui.settings.batchSize.toString()) }
    fun current() = InfluxSettings(url.trim(), org.trim(), bucket.trim(), token.trim(), batch.toIntOrNull() ?: 5000)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(url, { url = it }, label = { Text("InfluxDB-URL") }, singleLine = true,
                placeholder = { Text("http://influxdb.example.com:8086") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(org, { org = it }, label = { Text("Organization") }, singleLine = true,
                placeholder = { Text("my-org") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bucket, { bucket = it }, label = { Text("Bucket") }, singleLine = true,
                placeholder = { Text("my-bucket") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(batch, { batch = it.filter(Char::isDigit) }, label = { Text("Lines per write request") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.testConnection(current()) }) { Text("Test connection") }
                Button(onClick = { vm.saveSettings(current()); onBack() }) { Text("Save") }
            }
            ui.testMessage?.let {
                Text(it, color = when (ui.testOk) {
                    true -> OK_COLOR
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            }
            HorizontalDivider()
            Text("The InfluxDB server must be reachable from the phone. If it is in an internal network, connect the VPN first.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Precision: milliseconds. Uploading a file again creates no duplicates.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(VERSION_LABEL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
