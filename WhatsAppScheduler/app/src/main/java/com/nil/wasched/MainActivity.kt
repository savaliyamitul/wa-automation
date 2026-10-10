@file:OptIn(ExperimentalMaterial3Api::class)

package com.nil.wasched

import android.app.Application
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

class VM(app: Application) : AndroidViewModel(app) {
    private val db = Db.get(app)
    val msgs = db.msgs().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val groups = db.groups().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private suspend fun insert(m: Msg) {
        val x = m.copy(status = if (m.dateTime <= System.currentTimeMillis()) "MISSED" else "PENDING")
        if (db.groups().byName(x.groupName) == null) db.groups().upsert(WaGroup(name = x.groupName))
        val id = db.msgs().upsert(x)
        Scheduler.schedule(getApplication(), x.copy(id = if (x.id == 0L) id else x.id))
    }

    fun saveMsg(m: Msg) = viewModelScope.launch { insert(m) }
    fun deleteMsg(m: Msg) = viewModelScope.launch { Scheduler.cancel(getApplication(), m.id); db.msgs().delete(m) }
    fun saveGroup(g: WaGroup) = viewModelScope.launch { db.groups().upsert(g) }
    fun deleteGroup(g: WaGroup) = viewModelScope.launch { db.groups().delete(g) }
    suspend fun exportJson() = JsonIO.export(db.msgs().list())

    fun import(text: String, done: (String) -> Unit) = viewModelScope.launch {
        try {
            val list = JsonIO.import(text)
            list.forEach { insert(it) }
            done("Imported ${list.size} record(s)")
        } catch (e: Exception) {
            done("Import failed: ${e.message}")
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { App() } } }
    }
}

private val dtFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
private fun Long.fmt(): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault()).format(dtFmt)

private fun pickDateTime(ctx: Context, cur: Long, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = cur }
    DatePickerDialog(ctx, { _, y, mo, d ->
        TimePickerDialog(ctx, { _, h, mi ->
            onPick(Calendar.getInstance().apply { set(y, mo, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

@Composable
fun App(vm: VM = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var editMsg by remember { mutableStateOf<Msg?>(null) }
    var newMsg by remember { mutableStateOf(false) }
    var editGroup by remember { mutableStateOf<WaGroup?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    val msgs by vm.msgs.collectAsState()
    val groups by vm.groups.collectAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("Messages", "Groups", "Setup").forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) FloatingActionButton(onClick = { newMsg = true }) { Icon(Icons.Default.Add, "Add message") }
            else if (tab == 1) FloatingActionButton(onClick = { newGroup = true }) { Icon(Icons.Default.Add, "Add group") }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> MessagesTab(vm, msgs) { editMsg = it }
                1 -> GroupsTab(groups) { editGroup = it }
                else -> SetupTab()
            }
        }
    }

    if (newMsg || editMsg != null) MsgDialog(
        initial = editMsg, groups = groups,
        onDismiss = { newMsg = false; editMsg = null },
        onDelete = { vm.deleteMsg(it); newMsg = false; editMsg = null },
        onSave = { vm.saveMsg(it); newMsg = false; editMsg = null }
    )
    if (newGroup || editGroup != null) GroupDialog(
        initial = editGroup,
        onDismiss = { newGroup = false; editGroup = null },
        onDelete = { vm.deleteGroup(it); newGroup = false; editGroup = null },
        onSave = { vm.saveGroup(it); newGroup = false; editGroup = null }
    )
}

@Composable
fun MessagesTab(vm: VM, msgs: List<Msg>, onEdit: (Msg) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val imp = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() } ?: ""
            vm.import(text) { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
        }
    }
    val exp = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            scope.launch {
                val text = vm.exportJson()
                ctx.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { w -> w.write(text) }
                Toast.makeText(ctx, "Exported", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { imp.launch(arrayOf("*/*")) }) { Text("Import JSON") }
            OutlinedButton(onClick = { exp.launch("wa_schedule.json") }) { Text("Export JSON") }
        }
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs, key = { it.id }) { m ->
                Card(onClick = { onEdit(m) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${m.groupName} · ${m.type} · ${m.status}", fontWeight = FontWeight.Bold)
                        Text(m.dateTime.fmt())
                        Text(if (m.type == "POLL") "Poll: ${m.pollQuestion}" else m.message.ifBlank { m.url }, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
fun GroupsTab(groups: List<WaGroup>, onEdit: (WaGroup) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(groups, key = { it.id }) { g ->
            Card(onClick = { onEdit(g) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(g.name, fontWeight = FontWeight.Bold)
                    Text(listOf(if (g.enabled) "Enabled" else "Disabled", if (g.business) "WhatsApp Business" else "WhatsApp").joinToString(" · "))
                    if (g.prefix.isNotBlank() || g.suffix.isNotBlank()) Text("Prefix/suffix set")
                }
            }
        }
    }
}

@Composable
fun MsgDialog(initial: Msg?, groups: List<WaGroup>, onDismiss: () -> Unit, onDelete: (Msg) -> Unit, onSave: (Msg) -> Unit) {
    val ctx = LocalContext.current
    var group by remember { mutableStateOf(initial?.groupName ?: groups.firstOrNull()?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "TEXT") }
    var message by remember { mutableStateOf(initial?.message ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var question by remember { mutableStateOf(initial?.pollQuestion ?: "") }
    var options by remember { mutableStateOf(initial?.pollOptions ?: "") }
    var multi by remember { mutableStateOf(initial?.allowMultiple ?: false) }
    var time by remember { mutableLongStateOf(initial?.dateTime ?: (System.currentTimeMillis() + 3_600_000)) }
    var menu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New message" else "Edit message") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(group.ifBlank { "Choose group" }) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        groups.forEach { g -> DropdownMenuItem(text = { Text(g.name) }, onClick = { group = g.name; menu = false }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "TEXT", onClick = { type = "TEXT" }, label = { Text("Text") })
                    FilterChip(selected = type == "POLL", onClick = { type = "POLL" }, label = { Text("Poll") })
                }
                if (type == "TEXT") {
                    OutlinedTextField(message, { message = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(question, { question = it }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(options, { options = it }, label = { Text("Options (one per line, 2–12)") }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = multi, onCheckedChange = { multi = it })
                        Text("  Allow multiple answers")
                    }
                }
                OutlinedTextField(
                    url, { url = it },
                    label = { Text(if (type == "POLL") "URL (sent as a separate message after the poll)" else "URL (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { pickDateTime(ctx, time) { time = it } }) { Text(time.fmt()) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val opts = options.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val err = when {
                    group.isBlank() -> "Choose a group"
                    type == "POLL" && question.isBlank() -> "Poll needs a question"
                    type == "POLL" && opts.size !in 2..12 -> "Poll needs 2–12 options"
                    type == "TEXT" && message.isBlank() && url.isBlank() -> "Message is empty"
                    else -> null
                }
                if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
                else onSave(
                    Msg(
                        id = initial?.id ?: 0, groupName = group, type = type,
                        message = if (type == "TEXT") message.trim() else "",
                        url = url.trim(),
                        pollQuestion = if (type == "POLL") question.trim() else "",
                        pollOptions = if (type == "POLL") opts.joinToString("\n") else "",
                        allowMultiple = multi, dateTime = time
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun GroupDialog(initial: WaGroup?, onDismiss: () -> Unit, onDelete: (WaGroup) -> Unit, onSave: (WaGroup) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var business by remember { mutableStateOf(initial?.business ?: false) }
    var prefix by remember { mutableStateOf(initial?.prefix ?: "") }
    var suffix by remember { mutableStateOf(initial?.suffix ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New group" else "Edit group") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Group name (exactly as in WhatsApp)") },
                    enabled = initial == null, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(enabled, { enabled = it }); Text("  Enabled") }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(business, { business = it }); Text("  WhatsApp Business") }
                OutlinedTextField(prefix, { prefix = it }, label = { Text("Prefix (text messages)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(suffix, { suffix = it }, label = { Text("Suffix (text messages)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onSave(WaGroup(id = initial?.id ?: 0, name = name.trim(), enabled = enabled,
                    business = business, prefix = prefix, suffix = suffix))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun SetupTab() {
    val ctx = LocalContext.current
    fun open(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val pkgUri = Uri.parse("package:${ctx.packageName}")
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Accessibility service: " + if (WaService.instance != null) "ON" else "OFF (enable it below)", fontWeight = FontWeight.Bold)
        Button(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("1. Enable accessibility service") }
        Button(onClick = { if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)) }) { Text("2. Allow exact alarms") }
        Button(onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("3. Battery: set this app to unrestricted") }
        Button(onClick = { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)) }) { Text("4. Allow display over other apps") }
        Text("Keep WhatsApp logged in and the phone switched on. Group names must match WhatsApp exactly.")
    }
}
