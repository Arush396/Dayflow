package com.dayflow.app

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val accents = listOf(0xFF6C8CFF, 0xFF34C7A5, 0xFFFF8A5B, 0xFFB388FF)
private val dayOrder = listOf(2, 3, 4, 5, 6, 7, 1)
private val dayNames = mapOf(1 to "Sun", 2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat")
private val remindOptions = listOf(0, 5, 10, 15, 30, 60)

class MainActivity : ComponentActivity() {
    private var openId by mutableIntStateOf(-1)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openId = intent.getIntExtra("id", -1)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val store = Store(this)
        setContent { DayFlowApp(store, openId) { openId = -1 } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); openId = intent.getIntExtra("id", -1) }
}

fun fmt(min: Int, is24: Boolean): String {
    val h = min / 60; val m = min % 60
    return if (is24) "%02d:%02d".format(h, m) else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}

fun daysLabel(d: Set<Int>) = when {
    d.size == 7 -> "Every day"
    d == setOf(2, 3, 4, 5, 6) -> "Weekdays"
    d == setOf(1, 7) -> "Weekends"
    else -> dayOrder.filter { it in d }.joinToString(" ") { dayNames[it]!! }
}

@Composable
fun DayFlowApp(store: Store, openId: Int, consume: () -> Unit) {
    val ctx = LocalContext.current
    var dark by remember { mutableStateOf(store.dark) }
    var is24 by remember { mutableStateOf(store.is24h) }
    var accent by remember { mutableIntStateOf(store.accent) }
    var entries by remember { mutableStateOf(store.loadOrSeed()) }
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        Scheduler.scheduleAll(ctx, entries)
        while (true) { delay(15_000); now = System.currentTimeMillis() }
    }
    LaunchedEffect(openId) {
        if (openId >= 0) { entries.firstOrNull { it.id == openId }?.let { editing = it; tab = 0 }; consume() }
    }

    fun commit(list: List<Entry>) {
        entries.filter { o -> list.none { it.id == o.id } }.forEach { Scheduler.cancel(ctx, it.id) }
        entries = list; store.save(list); Scheduler.scheduleAll(ctx, list)
    }

    val a = Color(accents[accent])
    val scheme = if (dark) darkColorScheme(primary = a, background = Color(0xFF0F1115), surface = Color(0xFF0F1115), surfaceVariant = Color(0xFF1A1D24))
    else lightColorScheme(primary = a, background = Color(0xFFF6F7FA), surface = Color(0xFFF6F7FA), surfaceVariant = Color(0xFFFFFFFF))

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Today") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.DateRange, null) }, label = { Text("Timetable") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            },
            floatingActionButton = {
                if (tab != 2) FloatingActionButton(onClick = {
                    editing = Entry(store.nextId(), "", 9 * 60, 60, (1..7).toSet())
                }) { Icon(Icons.Default.Add, "Add activity") }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> TodayScreen(entries, now, is24, { e, key ->
                        commit(entries.map {
                            if (it.id == e.id) it.copy(doneDates = if (key in it.doneDates) it.doneDates - key else it.doneDates + key) else it
                        })
                    }) { editing = it }
                    1 -> TimetableScreen(entries, is24, { e, on -> commit(entries.map { if (it.id == e.id) it.copy(enabled = on) else it }) }) { editing = it }
                    else -> SettingsScreen(dark, is24, accent,
                        { dark = it; store.dark = it }, { is24 = it; store.is24h = it }, { accent = it; store.accent = it })
                }
            }
        }
        editing?.let { e ->
            EntryDialog(e, entries.none { it.id == e.id }, is24,
                onSave = { s ->
                    commit(if (entries.any { it.id == s.id }) entries.map { if (it.id == s.id) s else it } else entries + s)
                    editing = null; Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                },
                onDuplicate = { s ->
                    commit(entries + s.copy(id = store.nextId(), title = s.title + " (copy)", doneDates = emptySet()))
                    editing = null; Toast.makeText(ctx, "Duplicated", Toast.LENGTH_SHORT).show()
                },
                onDelete = { s -> commit(entries.filter { it.id != s.id }); editing = null; Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show() },
                onClose = { editing = null })
        }
    }
}

@Composable
fun TodayScreen(entries: List<Entry>, now: Long, is24: Boolean, onToggle: (Entry, String) -> Unit, onEdit: (Entry) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val key = dateKey(cal)
    val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val today = entries.filter { it.enabled && cal.get(Calendar.DAY_OF_WEEK) in it.days }.sortedBy { it.startMin }
    val done = today.count { key in it.doneDates }
    val next = today.firstOrNull { it.startMin > nowMin && key !in it.doneDates }
    val greeting = when (cal.get(Calendar.HOUR_OF_DAY)) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    val card = MaterialTheme.colorScheme.surfaceVariant

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 96.dp)) {
        item {
            Column {
                Text(greeting, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(cal.time) + " · " + fmt(nowMin, is24),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = card), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$done of ${today.size} completed · ${today.size - done} remaining", fontWeight = FontWeight.Medium)
                        LinearProgressIndicator(progress = { if (today.isEmpty()) 0f else done / today.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text(if (next != null) "Next: ${next.title} at ${fmt(next.startMin, is24)}" else "Nothing else scheduled today",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (today.isEmpty()) item { Text("Nothing scheduled today. Tap + to add an activity.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(today, key = { it.id }) { e ->
            val isDone = key in e.doneDates
            val current = nowMin in e.startMin until e.startMin + e.durationMin
            Card(colors = CardDefaults.cardColors(containerColor = card),
                border = if (current) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.fillMaxWidth().clickable { onEdit(e) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).alpha(if (isDone) 0.5f else 1f)) {
                        Text(e.title, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                            textDecoration = if (isDone) TextDecoration.LineThrough else null)
                        Text("${fmt(e.startMin, is24)} – ${fmt((e.startMin + e.durationMin) % 1440, is24)}" + if (current) " · Now" else "",
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(checked = isDone, onCheckedChange = { onToggle(e, key) })
                }
            }
        }
    }
}

@Composable
fun TimetableScreen(entries: List<Entry>, is24: Boolean, onEnable: (Entry, Boolean) -> Unit, onEdit: (Entry) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 96.dp)) {
        item { Text("Timetable", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        if (entries.isEmpty()) item { Text("No activities yet. Tap + to create your first one.") }
        items(entries.sortedBy { it.startMin }, key = { it.id }) { e ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().clickable { onEdit(e) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${fmt(e.startMin, is24)}  ${e.title}", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text("${daysLabel(e.days)} · ${e.durationMin} min", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = e.enabled, onCheckedChange = { onEnable(e, it) })
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(dark: Boolean, is24: Boolean, accent: Int, onDark: (Boolean) -> Unit, on24: (Boolean) -> Unit, onAccent: (Int) -> Unit) {
    val ctx = LocalContext.current
    val needExact = Build.VERSION.SDK_INT >= 31 && !ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Dark theme", Modifier.weight(1f)); Switch(dark, onDark) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("24-hour time", Modifier.weight(1f)); Switch(is24, on24) }
        Text("Accent color")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            accents.forEachIndexed { i, c ->
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(c)).clickable { onAccent(i) },
                    contentAlignment = Alignment.Center) { if (i == accent) Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
        if (needExact) {
            Text("Exact alarms are off, so reminders may arrive a few minutes late.")
            Button(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }) {
                Text("Allow exact reminders")
            }
        }
        Text("DayFlow 1.0 · All data stays on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EntryDialog(initial: Entry, isNew: Boolean, is24: Boolean, onSave: (Entry) -> Unit, onDuplicate: (Entry) -> Unit,
                onDelete: (Entry) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(initial.title) }
    var start by remember { mutableIntStateOf(initial.startMin) }
    var dur by remember { mutableStateOf(initial.durationMin.toString()) }
    var days by remember { mutableStateOf(initial.days) }
    var before by remember { mutableIntStateOf(initial.remindBefore) }
    var notes by remember { mutableStateOf(initial.notes) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun build() = initial.copy(title = title.trim(), startMin = start, durationMin = (dur.toIntOrNull() ?: 30).coerceIn(1, 1440),
        days = days, remindBefore = before, notes = notes)
    val valid = title.isNotBlank() && days.isNotEmpty()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (isNew) "New activity" else "Edit activity") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Activity name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { TimePickerDialog(ctx, { _, h, m -> start = h * 60 + m }, start / 60, start % 60, is24).show() },
                    modifier = Modifier.fillMaxWidth()) { Text("Start: ${fmt(start, is24)}") }
                OutlinedTextField(dur, { dur = it.filter(Char::isDigit).take(4) }, label = { Text("Duration (minutes)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    dayOrder.forEach { d ->
                        val on = d in days
                        Box(Modifier.size(40.dp).clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { days = if (on) days - d else days + d }, contentAlignment = Alignment.Center) {
                            Text(dayNames[d]!!.take(1), color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                OutlinedButton(onClick = { before = remindOptions[(remindOptions.indexOf(before) + 1) % remindOptions.size] },
                    modifier = Modifier.fillMaxWidth()) { Text(if (before == 0) "Remind: at start time" else "Remind: $before min before") }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
                if (!isNew) Row {
                    TextButton(onClick = { onDuplicate(build()) }, enabled = valid) { Text("Duplicate") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(build()) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete activity?") },
        text = { Text("“${initial.title}” will be removed and its reminders cancelled.") },
        confirmButton = { TextButton(onClick = { onDelete(initial) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
    )
}
