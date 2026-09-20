package com.dayflow.app

import android.app.*
import android.content.*
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Entry(
    val id: Int,
    val title: String,
    val startMin: Int,
    val durationMin: Int,
    val days: Set<Int>,            // Calendar.SUNDAY(1)..SATURDAY(7)
    val notes: String = "",
    val remindBefore: Int = 0,     // minutes
    val enabled: Boolean = true,
    val doneDates: Set<String> = emptySet()
)

fun dateKey(c: Calendar) = "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

private fun JSONArray?.ints(): Set<Int> = if (this == null) emptySet() else (0 until length()).map { getInt(it) }.toSet()
private fun JSONArray?.strs(): Set<String> = if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()

class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("dayflow", Context.MODE_PRIVATE)
    var dark: Boolean get() = sp.getBoolean("dark", true); set(v) { sp.edit().putBoolean("dark", v).apply() }
    var is24h: Boolean get() = sp.getBoolean("h24", true); set(v) { sp.edit().putBoolean("h24", v).apply() }
    var accent: Int get() = sp.getInt("accent", 0); set(v) { sp.edit().putInt("accent", v).apply() }
    fun nextId(): Int { val n = sp.getInt("nid", 1); sp.edit().putInt("nid", n + 1).apply(); return n }

    fun load(): List<Entry> {
        val arr = JSONArray(sp.getString("entries", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getInt("id"), o.getString("title"), o.getInt("start"), o.getInt("dur"),
                o.optJSONArray("days").ints(), o.optString("notes"), o.optInt("before"),
                o.optBoolean("on", true), o.optJSONArray("done").strs())
        }
    }

    fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("title", e.title).put("start", e.startMin)
                .put("dur", e.durationMin).put("days", JSONArray(e.days.toList())).put("notes", e.notes)
                .put("before", e.remindBefore).put("on", e.enabled).put("done", JSONArray(e.doneDates.toList())))
        }
        sp.edit().putString("entries", arr.toString()).apply()
    }

    fun loadOrSeed(): List<Entry> {
        if (!sp.getBoolean("seeded", false)) {
            val all = (1..7).toSet()
            val seed = listOf(
                Entry(nextId(), "Wake Up", 7 * 60, 30, all),
                Entry(nextId(), "Study", 9 * 60, 120, all, remindBefore = 5),
                Entry(nextId(), "Lunch", 13 * 60, 60, all),
                Entry(nextId(), "Gym", 17 * 60, 60, all),
                Entry(nextId(), "Sleep", 22 * 60 + 30, 60, all)
            )
            save(seed); sp.edit().putBoolean("seeded", true).apply()
        }
        return load()
    }
}

object Scheduler {
    fun nextTrigger(e: Entry, now: Long): Long? {
        if (!e.enabled || e.days.isEmpty()) return null
        val c = Calendar.getInstance()
        for (offset in 0..7) {
            c.timeInMillis = now
            c.add(Calendar.DAY_OF_YEAR, offset)
            if (c.get(Calendar.DAY_OF_WEEK) !in e.days) continue
            c.set(Calendar.HOUR_OF_DAY, e.startMin / 60); c.set(Calendar.MINUTE, e.startMin % 60)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val t = c.timeInMillis - e.remindBefore * 60_000L
            if (t > now) return t
        }
        return null
    }

    private fun pending(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, id, Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancel(ctx: Context, id: Int) { ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id)) }

    fun schedule(ctx: Context, e: Entry) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, e.id)
        am.cancel(pi)
        val t = nextTrigger(e, System.currentTimeMillis()) ?: return
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        else
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
    }

    fun scheduleAll(ctx: Context, list: List<Entry>) = list.forEach { schedule(ctx, it) }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val e = Store(ctx).load().firstOrNull { it.id == id } ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dayflow", "Activity reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).putExtra("id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (e.remindBefore == 0) "Your scheduled activity starts now." else "Starts in ${e.remindBefore} minutes."
        val n = Notification.Builder(ctx, "dayflow")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(e.title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(id, n)
        Scheduler.schedule(ctx, e)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Scheduler.scheduleAll(ctx, Store(ctx).load()) }
}
