package com.faiz.medicinereminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import android.view.View
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

// ---------- Medicine colours ----------
object Palette {
    private val hex = listOf(
        "#2F6FDB", "#7B4FC9", "#D64F8B", "#8A5A3C", "#4A55B8",
        "#0E8FA3", "#5B6B7B", "#A83EB0", "#1F3A5F"
    )
    val size get() = hex.size
    fun color(i: Int): Int = Color.parseColor(hex[((i % hex.size) + hex.size) % hex.size])
}

// ---------- Water ----------
object Water {
    fun on(c: Context) = Store.feature(c, "water", false)
    fun goal(c: Context) = Store.prefs(c).getInt("w_goal", 2400)
    fun glass(c: Context) = Store.prefs(c).getInt("w_glass", 250)
    fun mode(c: Context) = Store.prefs(c).getInt("w_mode", 0) // 0 = every 2 hours, 1 = spread to match goal
    fun wake(c: Context) = Store.prefs(c).getInt("w_wake", 7 * 60)
    fun sleep(c: Context) = Store.prefs(c).getInt("w_sleep", 22 * 60)

    fun saveSettings(c: Context, goal: Int, glass: Int, mode: Int, wake: Int, sleep: Int) {
        Store.prefs(c).edit().putInt("w_goal", goal).putInt("w_glass", glass).putInt("w_mode", mode)
            .putInt("w_wake", wake).putInt("w_sleep", sleep).apply()
    }

    fun logAll(c: Context): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        try {
            val o = JSONObject(Store.prefs(c).getString("waterlog", "{}"))
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.getInt(k)
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun today(c: Context): Int = logAll(c)[todayKey()] ?: 0

    fun add(c: Context, ml: Int): Int {
        val l = logAll(c)
        val k = todayKey()
        val v = maxOf(0, (l[k] ?: 0) + ml)
        l[k] = v
        val cutoff = Calendar.getInstance()
        cutoff.add(Calendar.DAY_OF_YEAR, -400)
        val cut = dayKey(cutoff)
        val o = JSONObject()
        for ((key, x) in l) if (key >= cut) o.put(key, x)
        Store.prefs(c).edit().putString("waterlog", o.toString()).apply()
        return v
    }

    // Reminder times (minutes since midnight), kept away from medicine times.
    fun slots(c: Context): List<Int> {
        val wake = wake(c)
        var sleep = minOf(sleep(c), 1439)
        if (sleep < wake + 120) sleep = minOf(1439, wake + 120)
        val raw = mutableListOf<Int>()
        if (mode(c) == 0) {
            var t = wake + 120
            while (t < sleep) {
                raw.add(t)
                t += 120
            }
        } else {
            val n = minOf(12, maxOf(1, Math.ceil(goal(c).toDouble() / maxOf(glass(c), 1)).toInt()))
            for (i in 1..n) raw.add(wake + i * (sleep - wake) / (n + 1))
        }
        val meds = Store.load(c).flatMap { it.times }
        val out = mutableListOf<Int>()
        for (r in raw) {
            var t = r
            var tries = 0
            while (tries < 8 && meds.any { Suggest.dist(it, t) < 30 }) {
                t += 15
                tries++
            }
            if (t < sleep) out.add(t)
        }
        return out.distinct().sorted().take(16)
    }
}

// ---------- Blood pressure ----------
class BpReading(val time: Long, val sys: Int, val dia: Int, val pulse: Int, val note: String)

object Bp {
    fun on(c: Context) = Store.feature(c, "bp", false)
    fun remind(c: Context) = Store.feature(c, "bp_remind", false)
    fun time(c: Context, i: Int) = Store.prefs(c).getInt("bp_t$i", if (i == 0) 8 * 60 else 20 * 60)
    fun setTime(c: Context, i: Int, v: Int) { Store.prefs(c).edit().putInt("bp_t$i", v).apply() }

    fun all(c: Context): MutableList<BpReading> {
        val out = mutableListOf<BpReading>()
        try {
            val a = JSONArray(Store.prefs(c).getString("bp", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(BpReading(o.getLong("t"), o.getInt("s"), o.getInt("d"), o.optInt("p", 0), o.optString("n", "")))
            }
        } catch (_: Exception) {
        }
        out.sortBy { it.time }
        return out
    }

    private fun save(c: Context, l: List<BpReading>) {
        val a = JSONArray()
        for (r in l.takeLast(3000)) {
            val o = JSONObject()
            o.put("t", r.time)
            o.put("s", r.sys)
            o.put("d", r.dia)
            o.put("p", r.pulse)
            o.put("n", r.note)
            a.put(o)
        }
        Store.prefs(c).edit().putString("bp", a.toString()).apply()
    }

    fun add(c: Context, r: BpReading) {
        val l = all(c)
        l.add(r)
        save(c, l)
    }

    fun remove(c: Context, time: Long) {
        val l = all(c)
        l.removeAll { it.time == time }
        save(c, l)
    }

    // Common AHA/ACC adult ranges. Neutral wording: a log, not a diagnosis.
    fun label(s: Int, d: Int): Pair<String, String> = when {
        s > 180 || d > 120 -> Pair("Very high range", "#8B1A1A")
        s >= 140 || d >= 90 -> Pair("High range (stage 2)", "#B23B3B")
        s >= 130 || d >= 80 -> Pair("High range (stage 1)", "#D9822B")
        s >= 120 -> Pair("Elevated", "#C98A12")
        s < 90 || d < 60 -> Pair("Low range", "#2F6FDB")
        else -> Pair("Normal range", "#1B8A5A")
    }

    fun avgText(l: List<BpReading>): String {
        if (l.isEmpty()) return "no readings"
        val s = l.sumOf { it.sys } / l.size
        val d = l.sumOf { it.dia } / l.size
        val p = l.filter { it.pulse > 0 }
        val pt = if (p.isEmpty()) "" else ", pulse ${p.sumOf { it.pulse } / p.size}"
        return "$s/$d$pt (${l.size} readings)"
    }
}

// Simple line chart of the latest readings.
class BpChart(c: Context, private val data: List<BpReading>) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.size < 2) return
        val d = resources.displayMetrics.density
        val left = 34f * d
        val right = width - 10f * d
        val top = 22f * d
        val bottom = height - 10f * d
        val lo = minOf(60, data.minOf { it.dia } - 10)
        val hi = maxOf(150, data.maxOf { it.sys } + 10)
        fun y(v: Int) = bottom - (v - lo).toFloat() / (hi - lo) * (bottom - top)
        fun x(i: Int) = left + i.toFloat() / (data.size - 1) * (right - left)
        p.textSize = 10f * d
        var g = (lo / 20) * 20
        while (g <= hi) {
            if (g >= lo) {
                p.color = Color.parseColor("#DDE3E0")
                p.strokeWidth = 1f
                canvas.drawLine(left, y(g), right, y(g), p)
                p.color = Color.GRAY
                canvas.drawText(g.toString(), 2f * d, y(g) + 4f * d, p)
            }
            g += 20
        }
        val sysC = Color.parseColor("#B23B3B")
        val diaC = Color.parseColor("#2F6FDB")
        for ((col, sel) in listOf(Pair(sysC, true), Pair(diaC, false))) {
            p.color = col
            p.strokeWidth = 2.5f * d
            for (i in 0 until data.size - 1) {
                canvas.drawLine(x(i), y(if (sel) data[i].sys else data[i].dia), x(i + 1), y(if (sel) data[i + 1].sys else data[i + 1].dia), p)
            }
            for (i in data.indices) canvas.drawCircle(x(i), y(if (sel) data[i].sys else data[i].dia), 3.5f * d, p)
        }
        p.color = sysC
        canvas.drawText("Systolic", left, 12f * d, p)
        p.color = diaC
        canvas.drawText("Diastolic", left + 70f * d, 12f * d, p)
    }
}

// ---------- BMI ----------
object Bmi {
    fun asian(c: Context) = Store.feature(c, "bmi_asian", true)

    fun value(kg: Double, cm: Double): Double {
        val m = cm / 100.0
        return kg / (m * m)
    }

    fun category(b: Double, asian: Boolean): String = when {
        b < 18.5 -> "Underweight"
        asian && b < 23.0 -> "Healthy range"
        asian && b < 25.0 -> "Overweight"
        asian -> "Obese"
        b < 25.0 -> "Healthy range"
        b < 30.0 -> "Overweight"
        else -> "Obese"
    }

    // Healthy weight range (kg) for a height.
    fun range(cm: Double, asian: Boolean): Pair<Double, Double> {
        val m = cm / 100.0
        return Pair(18.5 * m * m, (if (asian) 22.9 else 24.9) * m * m)
    }
}

// ---------- Update check ----------
object Updater {
    // version.json must be in the root of the "main" branch of this repository.
    const val VERSION_URL = "https://raw.githubusercontent.com/fzanwar/medreminder/main/version.json"

    @Suppress("DEPRECATION")
    fun installedCode(c: Context): Long {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    fun installedName(c: Context): String =
        try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    fun enabled(c: Context) = Store.feature(c, "updatecheck", true)

    // Returns (version name, download url) if a newer version is known.
    fun pending(c: Context): Pair<String, String>? {
        val p = Store.prefs(c)
        val code = p.getLong("upd_code", 0)
        if (code <= installedCode(c)) return null
        return Pair(p.getString("upd_name", "") ?: "", p.getString("upd_url", "") ?: "")
    }

    fun dueForCheck(c: Context): Boolean =
        System.currentTimeMillis() - Store.prefs(c).getLong("upd_checked", 0) > 12 * 3600 * 1000L

    // Runs on a background thread. onDone(success, message) is called on that thread.
    fun check(c: Context, onDone: (Boolean, String) -> Unit) {
        val app = c.applicationContext
        Thread {
            try {
                val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val o = JSONObject(txt)
                val code = o.optLong("versionCode", 0)
                val e = Store.prefs(app).edit()
                e.putLong("upd_checked", System.currentTimeMillis())
                e.putLong("upd_code", code)
                e.putString("upd_name", o.optString("versionName", ""))
                e.putString("upd_url", o.optString("url", "https://github.com/fzanwar/medreminder/releases/latest"))
                e.apply()
                if (code > installedCode(app)) onDone(true, "New version ${o.optString("versionName", "")} is available") else onDone(true, "You have the latest version")
            } catch (_: Exception) {
                onDone(false, "Could not check for updates")
            }
        }.start()
    }
}

// ---------- Water and blood-pressure reminders ----------
object HealthAlarms {
    const val WATER_FIRE = "com.faiz.medicinereminder.WATER_FIRE"
    const val WATER_ADD = "com.faiz.medicinereminder.WATER_ADD"
    const val WATER_SNOOZE = "com.faiz.medicinereminder.WATER_SNOOZE"
    const val BP_FIRE = "com.faiz.medicinereminder.BP_FIRE"
    private const val CH_WATER = "water_v1"
    private const val CH_BP = "bp_v1"
    private const val NID_WATER = 92_000_000
    private const val NID_BP = 92_000_001

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(c: Context, action: String, slot: Int, snooze: Boolean): PendingIntent {
        val base = if (action == BP_FIRE) 91_000_000 else 90_000_000
        val i = Intent(c, ReminderReceiver::class.java).setAction(action).putExtra("slot", slot).putExtra("snooze", snooze)
        return PendingIntent.getBroadcast(c, base + slot + if (snooze) 100 else 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun set(c: Context, action: String, slot: Int, at: Long, snooze: Boolean) {
        val a = am(c)
        val p = pi(c, action, slot, snooze)
        try {
            if (Build.VERSION.SDK_INT < 31 || a.canScheduleExactAlarms()) a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) {
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun scheduleAll(c: Context) {
        for (s in 0..15) {
            am(c).cancel(pi(c, WATER_FIRE, s, false))
            am(c).cancel(pi(c, WATER_FIRE, s, true))
        }
        for (s in 0..1) am(c).cancel(pi(c, BP_FIRE, s, false))
        if (Water.on(c)) Water.slots(c).forEachIndexed { i, t -> set(c, WATER_FIRE, i, Scheduler.nextTrigger(t), false) }
        if (Bp.on(c) && Bp.remind(c)) for (i in 0..1) set(c, BP_FIRE, i, Scheduler.nextTrigger(Bp.time(c, i)), false)
    }

    private fun channels(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val w = NotificationChannel(CH_WATER, "Water reminders", NotificationManager.IMPORTANCE_DEFAULT)
        w.description = "Gentle reminders to drink water"
        nm.createNotificationChannel(w)
        val b = NotificationChannel(CH_BP, "Blood pressure reminders", NotificationManager.IMPORTANCE_DEFAULT)
        b.description = "Reminders to measure your blood pressure"
        nm.createNotificationChannel(b)
    }

    fun onReceive(c: Context, i: Intent) {
        val slot = i.getIntExtra("slot", 0)
        val snooze = i.getBooleanExtra("snooze", false)
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        when (i.action) {
            WATER_ADD -> {
                nm.cancel(NID_WATER)
                val v = Water.add(c, Water.glass(c))
                Toast.makeText(c, "Added. Today: $v ml", Toast.LENGTH_SHORT).show()
            }
            WATER_SNOOZE -> {
                nm.cancel(NID_WATER)
                set(c, WATER_FIRE, slot, System.currentTimeMillis() + 20 * 60 * 1000L, true)
                Toast.makeText(c, "Snoozed for 20 minutes", Toast.LENGTH_SHORT).show()
            }
            WATER_FIRE -> {
                if (!Water.on(c)) return
                if (!snooze) {
                    val ts = Water.slots(c)
                    if (slot < ts.size) set(c, WATER_FIRE, slot, Scheduler.nextTrigger(ts[slot]), false)
                }
                val got = Water.today(c)
                if (got >= Water.goal(c)) return // goal reached: no more reminders today
                channels(c)
                val who = Store.name(c)
                fun act(label: String, icon: Int, a: String, rc: Int) = Notification.Action.Builder(
                    Icon.createWithResource(c, icon), label,
                    PendingIntent.getBroadcast(c, rc, Intent(c, ReminderReceiver::class.java).setAction(a).putExtra("slot", slot), flags)
                ).build()
                val n = Notification.Builder(c, CH_WATER)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(if (who.isBlank()) "Time for a glass of water" else "$who, time for a glass of water")
                    .setContentText(String.format("%,d of %,d ml so far", got, Water.goal(c)))
                    .setColor(Color.parseColor("#2F6FDB"))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java), flags))
                    .addAction(act("+ ${Water.glass(c)} ml", android.R.drawable.ic_menu_add, WATER_ADD, 93_000_000 + slot))
                    .addAction(act("Snooze 20 min", android.R.drawable.ic_menu_recent_history, WATER_SNOOZE, 93_000_100 + slot))
                    .build()
                nm.notify(NID_WATER, n)
            }
            BP_FIRE -> {
                if (!Bp.on(c) || !Bp.remind(c)) return
                set(c, BP_FIRE, slot, Scheduler.nextTrigger(Bp.time(c, slot)), false)
                channels(c)
                val open = Intent(c, MainActivity::class.java).putExtra("tab", "Health")
                val n = Notification.Builder(c, CH_BP)
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("Time to measure your blood pressure")
                    .setContentText("Sit quietly for 5 minutes first. Tap to record the reading.")
                    .setColor(Color.parseColor("#B23B3B"))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, 4, open, flags))
                    .build()
                nm.notify(NID_BP, n)
            }
        }
    }
}
