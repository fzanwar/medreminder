package com.faiz.medicinereminder

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// ---------- Data ----------
// meal: 0 = empty stomach, 1 = after meal, 2 = anytime. times = minutes since midnight.
class Med(
    val id: Int,
    var name: String,
    var dose: String,
    var meal: Int,
    var times: MutableList<Int>,
    val created: String = todayKey(),
    val createdMin: Int = 0
)

class Counts {
    var t = 0
    var s = 0
    var m = 0
    val total get() = t + s + m
}

fun dayKey(cal: Calendar): String =
    String.format("%04d%02d%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

fun todayKey(): String = dayKey(Calendar.getInstance())

fun nowMinutes(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

fun fmt12(min: Int): String {
    val h = min / 60
    val m = min % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return String.format("%d:%02d %s", h12, m, if (h >= 12) "PM" else "AM")
}

// Doses before a medicine was added (on the day it was added) are not counted.
fun applies(m: Med, key: String, t: Int) = !(key == m.created && t < m.createdMin)

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("medicines", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Med> {
        val out = mutableListOf<Med>()
        var changed = false
        try {
            val arr = JSONArray(prefs(c).getString("meds", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getJSONArray("times")
                val times = mutableListOf<Int>()
                for (j in 0 until t.length()) times.add(t.getInt(j))
                var created = o.optString("created", "")
                var createdMin = o.optInt("createdMin", 0)
                if (created.isEmpty()) {
                    created = todayKey()
                    createdMin = nowMinutes()
                    changed = true
                }
                out.add(Med(o.getInt("id"), o.getString("name"), o.optString("dose", ""), o.optInt("meal", 1), times, created, createdMin))
            }
        } catch (_: Exception) {
        }
        if (changed) save(c, out)
        return out
    }

    fun save(c: Context, list: List<Med>) {
        val arr = JSONArray()
        for (m in list) {
            val o = JSONObject()
            o.put("id", m.id)
            o.put("name", m.name)
            o.put("dose", m.dose)
            o.put("meal", m.meal)
            o.put("times", JSONArray(m.times))
            o.put("created", m.created)
            o.put("createdMin", m.createdMin)
            arr.put(o)
        }
        prefs(c).edit().putString("meds", arr.toString()).apply()
    }

    // ----- history: "yyyyMMdd:id:idx" -> "T" (taken) or "S" (not taken) -----
    fun log(c: Context): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            val o = JSONObject(prefs(c).getString("log", "{}"))
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = o.getString(k)
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun setStatus(c: Context, date: String, id: Int, idx: Int, st: String?) {
        val l = log(c)
        val k = "$date:$id:$idx"
        if (st == null) l.remove(k) else l[k] = st
        val cutoff = Calendar.getInstance()
        cutoff.add(Calendar.DAY_OF_YEAR, -400)
        val cut = dayKey(cutoff)
        val o = JSONObject()
        for ((key, v) in l) if (key.substringBefore(":") >= cut) o.put(key, v)
        prefs(c).edit().putString("log", o.toString()).apply()
    }

    fun statusToday(c: Context, id: Int, idx: Int): String? = log(c)["${todayKey()}:$id:$idx"]

    // ----- settings -----
    fun name(c: Context): String = prefs(c).getString("name", "") ?: ""
    fun setName(c: Context, n: String) { prefs(c).edit().putString("name", n.trim()).putBoolean("nameAsked", true).apply() }
    fun nameAsked(c: Context): Boolean = prefs(c).getBoolean("nameAsked", false)

    // breakfast, lunch, dinner (minutes since midnight)
    fun meals(c: Context): IntArray {
        val p = prefs(c)
        return intArrayOf(p.getInt("b", 8 * 60 + 30), p.getInt("l", 13 * 60 + 30), p.getInt("d", 20 * 60))
    }

    fun saveMeals(c: Context, m: IntArray) {
        prefs(c).edit().putInt("b", m[0]).putInt("l", m[1]).putInt("d", m[2]).apply()
    }
}

// ---------- Time suggestion ----------
object Suggest {
    const val BEFORE_MEAL = 60   // empty-stomach medicines: 1 hour before the meal
    const val AFTER_MEAL = 30    // after-meal medicines: 30 minutes after the meal
    const val GAP = 30           // minimum minutes between two medicines

    fun norm(t: Int) = ((t % 1440) + 1440) % 1440
    fun dist(a: Int, b: Int): Int {
        val d = Math.abs(a - b)
        return minOf(d, 1440 - d)
    }

    fun suggest(c: Context, meal: Int, freq: Int, others: List<Int>): MutableList<Int> {
        val m = Store.meals(c)
        val anchors = when (freq) {
            1 -> listOf(m[0])
            2 -> listOf(m[0], m[2])
            3 -> listOf(m[0], m[1], m[2])
            else -> listOf(m[0], m[1], m[2], m[2] + 180)
        }
        val used = others.toMutableList()
        val out = mutableListOf<Int>()
        for (a in anchors) {
            var t = norm(when (meal) { 0 -> a - BEFORE_MEAL; 1 -> a + AFTER_MEAL; else -> a })
            val step = if (meal == 0) -GAP else GAP
            var tries = 0
            while (tries < 60 && used.any { dist(it, t) < GAP }) {
                t = norm(t + step)
                tries++
            }
            used.add(t)
            out.add(t)
        }
        return out
    }
}

// ---------- Alarms ----------
// kind: 0 = regular alarm, 1 = snooze, 2 = follow-up ("did you take it?")
object Scheduler {
    const val FIRE = "com.faiz.medicinereminder.FIRE"
    const val TAKEN = "com.faiz.medicinereminder.TAKEN"
    const val SNOOZE = "com.faiz.medicinereminder.SNOOZE"
    const val SKIP = "com.faiz.medicinereminder.SKIP"
    const val CHANNEL = "medicine_due_v2"
    const val CHANNEL_FOLLOW = "medicine_followup_v1"

    fun code(id: Int, idx: Int) = id * 10 + idx

    private fun pi(c: Context, id: Int, idx: Int, kind: Int): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).setAction(FIRE)
            .putExtra("id", id).putExtra("idx", idx).putExtra("kind", kind)
        val rc = code(id, idx) + when (kind) { 1 -> 10_000_000; 2 -> 60_000_000; else -> 0 }
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun nextTrigger(minutes: Int): Long {
        val cal = Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, minutes / 60)
        cal.set(Calendar.MINUTE, minutes % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now + 1000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    // Alarm-clock type alarms are exempt from battery saving (Doze), so they fire on time.
    fun setAlarm(c: Context, id: Int, idx: Int, at: Long, kind: Int) {
        val a = am(c)
        val p = pi(c, id, idx, kind)
        val show = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            a.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), p)
        } catch (_: SecurityException) {
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun schedule(c: Context, m: Med) {
        m.times.forEachIndexed { i, t -> setAlarm(c, m.id, i, nextTrigger(t), 0) }
    }

    fun scheduleAll(c: Context) {
        Store.load(c).forEach { schedule(c, it) }
    }

    fun cancelKind(c: Context, id: Int, idx: Int, kind: Int) {
        am(c).cancel(pi(c, id, idx, kind))
    }

    fun cancel(c: Context, id: Int) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (i in 0..3) {
            for (k in 0..2) am(c).cancel(pi(c, id, i, k))
            nm.cancel(code(id, i))
            nm.cancel(code(id, i) + 70_000_000)
        }
    }

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel("medicine_due_v1")
        val ch = NotificationChannel(CHANNEL, "Medicine reminders", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Time to take your medicine"
        ch.enableVibration(true)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
        nm.createNotificationChannel(ch)
        val f = NotificationChannel(CHANNEL_FOLLOW, "Did you take it?", NotificationManager.IMPORTANCE_DEFAULT)
        f.description = "Quiet follow-up if you did not respond to a reminder"
        f.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(f)
    }

    // Taken / Skip / Snooze - used by notification buttons, the pop-up and the Today screen.
    fun handle(c: Context, act: String, id: Int, idx: Int) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(code(id, idx))
        nm.cancel(code(id, idx) + 70_000_000)
        cancelKind(c, id, idx, 2)
        when (act) {
            TAKEN -> {
                cancelKind(c, id, idx, 1)
                Store.setStatus(c, todayKey(), id, idx, "T")
            }
            SKIP -> {
                cancelKind(c, id, idx, 1)
                Store.setStatus(c, todayKey(), id, idx, "S")
            }
            SNOOZE -> {
                setAlarm(c, id, idx, System.currentTimeMillis() + 10 * 60 * 1000L, 1)
                Toast.makeText(c, "Snoozed for 10 minutes", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val idx = intent.getIntExtra("idx", 0)
        val kind = intent.getIntExtra("kind", 0)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val code = Scheduler.code(id, idx)
        when (intent.action) {
            Scheduler.TAKEN, Scheduler.SKIP, Scheduler.SNOOZE -> Scheduler.handle(context, intent.action!!, id, idx)
            else -> {
                val m = Store.load(context).firstOrNull { it.id == id } ?: return
                if (idx >= m.times.size) return
                // Set tomorrow's alarm first so it is never lost.
                if (kind == 0) Scheduler.schedule(context, m)
                // Already marked today (for example taken early)? Stay quiet.
                if (Store.statusToday(context, id, idx) != null) return
                Scheduler.ensureChannel(context)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
                fun action(act: String, rc: Int) = PendingIntent.getBroadcast(
                    context, code + rc,
                    Intent(context, ReminderReceiver::class.java).setAction(act).putExtra("id", id).putExtra("idx", idx),
                    flags
                )
                fun act(label: String, icon: Int, a: String, rc: Int) =
                    Notification.Action.Builder(Icon.createWithResource(context, icon), label, action(a, rc)).build()
                val how = when (m.meal) { 0 -> "Empty stomach"; 1 -> "After meal"; else -> "Anytime" }
                val text = listOf(m.dose, how).filter { it.isNotBlank() }.joinToString(" • ")
                val who = Store.name(context)

                if (kind == 2) {
                    // Quiet follow-up that stays until answered.
                    val n = Notification.Builder(context, Scheduler.CHANNEL_FOLLOW)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setContentTitle("Did you take ${m.name}?")
                        .setContentText("Scheduled ${fmt12(m.times[idx])} - tap Taken or Not taken")
                        .setCategory(Notification.CATEGORY_REMINDER)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setContentIntent(open)
                        .addAction(act("Taken", android.R.drawable.ic_menu_save, Scheduler.TAKEN, 20_000_000))
                        .addAction(act("Not taken", android.R.drawable.ic_menu_close_clear_cancel, Scheduler.SKIP, 50_000_000))
                        .build()
                    nm.notify(code + 70_000_000, n)
                    return
                }

                // Check again 10 minutes later if there is still no answer.
                Scheduler.setAlarm(context, id, idx, System.currentTimeMillis() + 10 * 60 * 1000L, 2)
                val popup = PendingIntent.getActivity(
                    context, code + 40_000_000,
                    Intent(context, AlarmActivity::class.java).putExtra("id", id).putExtra("idx", idx)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    flags
                )
                val title = if (who.isBlank()) "Take: ${m.name}" else "$who, take: ${m.name}"
                val n = Notification.Builder(context, Scheduler.CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText("$text\nScheduled ${fmt12(m.times[idx])}"))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(open)
                    .setFullScreenIntent(popup, true)
                    .setTimeoutAfter(2 * 60 * 1000L)
                    .addAction(act("Taken", android.R.drawable.ic_menu_save, Scheduler.TAKEN, 20_000_000))
                    .addAction(act("Snooze", android.R.drawable.ic_menu_recent_history, Scheduler.SNOOZE, 30_000_000))
                    .addAction(act("Skip", android.R.drawable.ic_menu_close_clear_cancel, Scheduler.SKIP, 50_000_000))
                    .build()
                n.flags = n.flags or Notification.FLAG_INSISTENT // keep ringing until you respond
                nm.notify(code, n)
            }
        }
    }
}

// Pop-up that appears over the lock screen with the alarm.
class AlarmActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        build(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        build(intent)
    }

    private fun build(i: Intent) {
        val id = i.getIntExtra("id", -1)
        val idx = i.getIntExtra("idx", 0)
        val m = Store.load(this).firstOrNull { it.id == id }
        if (m == null || idx >= m.times.size || Store.statusToday(this, id, idx) != null) {
            finish()
            return
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#0B5D4A"))
        root.setPadding(dp(24), dp(48), dp(24), dp(48))

        fun text(t: String, size: Float, bold: Boolean = false) {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.gravity = Gravity.CENTER
            v.setTextColor(Color.WHITE)
            if (bold) v.setTypeface(null, Typeface.BOLD)
            v.setPadding(0, dp(4), 0, dp(4))
            root.addView(v)
        }
        val who = Store.name(this)
        text(if (who.isBlank()) "Time for your medicine" else "$who, time for your medicine", 16f)
        text(fmt12(m.times[idx]), 40f, true)
        text(m.name, 28f, true)
        val how = when (m.meal) { 0 -> "Empty stomach"; 1 -> "After meal"; else -> "Anytime" }
        text(listOf(m.dose, how).filter { it.isNotBlank() }.joinToString(" • "), 18f)

        fun btn(label: String, bg: String, act: String) {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.textSize = 20f
            b.setTextColor(Color.WHITE)
            val d = GradientDrawable()
            d.setColor(Color.parseColor(bg))
            d.cornerRadius = dp(14).toFloat()
            b.background = d
            b.setOnClickListener {
                Scheduler.handle(this, act, id, idx)
                finish()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
            lp.topMargin = dp(16)
            root.addView(b, lp)
        }
        btn("Taken", "#1FA67A", Scheduler.TAKEN)
        btn("Snooze 10 min", "#C98A12", Scheduler.SNOOZE)
        btn("Not taking (skip)", "#A33A3A", Scheduler.SKIP)
        setContentView(root)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.scheduleAll(context)
    }
}

// ---------- Screens ----------
class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var subtitle: TextView
    private var tab = 0
    private var reportDays = 7
    private var askedNotif = false
    private var askedExact = false
    private var askedFs = false
    private val green = Color.parseColor("#147D64")
    private val ink = Color.parseColor("#12332B")
    private val tabNames = arrayOf("Today", "Medicines", "Report", "Settings")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, r: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun tv(t: String, size: Float = 14f, bold: Boolean = false, color: Int = ink): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = roundBg(Color.WHITE)
        c.setPadding(dp(16), dp(12), dp(16), dp(12))
        return c
    }

    private fun add(v: View, top: Int = 10) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        content.addView(v, lp)
    }

    private fun mealLabel(i: Int) = when (i) { 0 -> "Empty stomach"; 1 -> "After meal"; else -> "Anytime" }
    private fun freqLabel(n: Int) = when (n) { 1 -> "Once a day"; 2 -> "Twice a day"; 3 -> "3 times a day"; else -> "4 times a day" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureChannel(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0B5D4A"))
        root.setOnApplyWindowInsetsListener { v, ins ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                top = b.top
                bottom = b.bottom
            } else {
                top = ins.systemWindowInsetTop
                bottom = ins.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            ins
        }

        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(dp(18), dp(12), dp(18), dp(12))
        head.addView(tv("Medicine Reminder", 22f, true, Color.WHITE))
        subtitle = tv("", 14f, false, Color.parseColor("#CFEDE3"))
        head.addView(subtitle)
        root.addView(head)

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setBackgroundColor(Color.parseColor("#F3F7F5"))

        val scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), dp(4), dp(12), dp(24))
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.setBackgroundColor(Color.WHITE)
        for (i in tabNames.indices) {
            val t = TextView(this)
            t.text = tabNames[i]
            t.textSize = 14f
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(14), 0, dp(14))
            t.setOnClickListener {
                tab = i
                render()
            }
            tabBar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(tabBar)

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (Store.name(this).isBlank() && !Store.nameAsked(this)) askName()
    }

    override fun onResume() {
        super.onResume()
        render()
        Scheduler.scheduleAll(this)
        checkSetup()
    }

    private fun render() {
        val who = Store.name(this)
        subtitle.text = if (who.isBlank()) "Take care of your health" else "Hello, $who"
        for (i in 0 until tabBar.childCount) {
            val t = tabBar.getChildAt(i) as TextView
            t.setTextColor(if (i == tab) green else Color.GRAY)
            t.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        content.removeAllViews()
        when (tab) {
            0 -> renderToday()
            1 -> renderMeds()
            2 -> renderReport()
            else -> renderSettings()
        }
    }

    // ----- Name -----
    private fun askName() {
        val e = EditText(this)
        e.hint = "Your name"
        e.setSingleLine()
        val p = LinearLayout(this)
        p.setPadding(dp(20), dp(8), dp(20), 0)
        p.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("What should I call you?")
            .setView(p)
            .setPositiveButton("Save") { _, _ ->
                Store.setName(this, e.text.toString())
                render()
            }
            .setNegativeButton("Skip") { _, _ -> Store.setName(this, "") }
            .show()
    }

    // ----- Today -----
    private fun renderToday() {
        val meds = Store.load(this)
        val key = todayKey()
        val log = Store.log(this)
        val now = nowMinutes()
        val doses = mutableListOf<Triple<Int, Med, Int>>()
        for (m in meds) m.times.forEachIndexed { i, t -> if (applies(m, key, t)) doses.add(Triple(t, m, i)) }
        doses.sortBy { it.first }
        if (doses.isEmpty()) {
            val t = tv("No doses today.\nAdd a medicine in the Medicines tab.", 16f)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(16), dp(48), dp(16), dp(16))
            add(t)
            return
        }
        val done = doses.count { log["$key:${it.second.id}:${it.third}"] == "T" }
        add(tv("Today: $done of ${doses.size} taken", 16f, true, green), 12)
        add(tv("Tap a dose to mark it taken or not taken.", 13f, false, Color.GRAY), 2)
        for ((t, m, i) in doses) {
            val st = log["$key:${m.id}:$i"]
            val c = card()
            c.addView(tv("${fmt12(t)}   ${m.name}" + if (m.dose.isNotBlank()) "  (${m.dose})" else "", 17f, true))
            c.addView(tv(mealLabel(m.meal), 13f, false, Color.GRAY))
            val (label, col) = when {
                st == "T" -> Pair("✓ Taken", "#1B8A5A")
                st == "S" -> Pair("✗ Not taken", "#B23B3B")
                t > now -> Pair("Upcoming", "#666666")
                else -> Pair("! Not marked - tap to update", "#C98A12")
            }
            c.addView(tv(label, 15f, true, Color.parseColor(col)))
            c.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("${m.name} - ${fmt12(t)}")
                    .setItems(arrayOf("Mark as taken", "Mark as not taken", "Clear mark")) { _, w ->
                        when (w) {
                            0 -> Scheduler.handle(this, Scheduler.TAKEN, m.id, i)
                            1 -> Scheduler.handle(this, Scheduler.SKIP, m.id, i)
                            else -> Store.setStatus(this, key, m.id, i, null)
                        }
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            add(c)
        }
    }

    // ----- Medicines -----
    private fun renderMeds() {
        val b = Button(this)
        b.text = "+ Add medicine"
        b.isAllCaps = false
        b.setOnClickListener { showForm(null) }
        add(b, 8)
        val meds = Store.load(this).sortedBy { it.times.minOrNull() ?: 0 }
        if (meds.isEmpty()) {
            val t = tv("No medicines yet.", 16f)
            t.gravity = Gravity.CENTER
            t.setPadding(dp(16), dp(32), dp(16), dp(16))
            add(t)
            return
        }
        add(tv("Tap a medicine to edit or delete it.", 13f, false, Color.GRAY), 2)
        for (m in meds) {
            val c = card()
            c.addView(tv(m.name + if (m.dose.isNotBlank()) "  (${m.dose})" else "", 18f, true))
            c.addView(tv(mealLabel(m.meal) + " • " + freqLabel(m.times.size), 14f))
            c.addView(tv(m.times.sorted().joinToString("   ") { fmt12(it) }, 16f, false, green))
            c.setOnClickListener { showManage(m) }
            add(c)
        }
    }

    // Edit / Delete live here, not on the list, so they cannot be hit by accident.
    private fun showManage(m: Med) {
        AlertDialog.Builder(this)
            .setTitle(m.name)
            .setMessage(
                (if (m.dose.isNotBlank()) "Dose: ${m.dose}\n" else "") +
                    mealLabel(m.meal) + " • " + freqLabel(m.times.size) + "\n" +
                    m.times.sorted().joinToString(", ") { fmt12(it) }
            )
            .setPositiveButton("Edit") { _, _ -> showForm(m) }
            .setNegativeButton("Delete") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Delete ${m.name}?")
                    .setMessage("Its reminders will stop. Past records stay in the report.")
                    .setPositiveButton("Yes, delete") { _, _ ->
                        Scheduler.cancel(this, m.id)
                        val all = Store.load(this)
                        all.removeAll { it.id == m.id }
                        Store.save(this, all)
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNeutralButton("Close", null)
            .show()
    }

    // ----- Report -----
    private fun renderReport() {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((label, days) in listOf(Pair("Last 7 days", 7), Pair("Last 30 days", 30))) {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.alpha = if (reportDays == days) 1f else 0.5f
            b.setOnClickListener {
                reportDays = days
                render()
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        add(row, 8)

        val meds = Store.load(this)
        val log = Store.log(this)
        val now = nowMinutes()
        val total = Counts()
        val perMed = LinkedHashMap<Int, Counts>()
        val perDay = mutableListOf<Pair<String, Counts>>()
        val fmt = java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault())
        for (d in 0 until reportDays) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -d)
            val key = dayKey(cal)
            val dc = Counts()
            for (m in meds) {
                m.times.forEachIndexed { i, t ->
                    if (key < m.created || !applies(m, key, t)) return@forEachIndexed
                    val st = log["$key:${m.id}:$i"]
                    val pm = perMed.getOrPut(m.id) { Counts() }
                    when {
                        st == "T" -> { total.t++; dc.t++; pm.t++ }
                        st == "S" -> { total.s++; dc.s++; pm.s++ }
                        d == 0 && t > now -> { }
                        else -> { total.m++; dc.m++; pm.m++ }
                    }
                }
            }
            perDay.add(Pair(fmt.format(cal.time), dc))
        }

        val sum = card()
        val pct = if (total.total == 0) "-" else "${total.t * 100 / total.total}%"
        sum.addView(tv("Taken on time: $pct", 20f, true, green))
        sum.addView(tv("✓ Taken: ${total.t}", 16f, false, Color.parseColor("#1B8A5A")))
        sum.addView(tv("✗ Not taken (skipped): ${total.s}", 16f, false, Color.parseColor("#B23B3B")))
        sum.addView(tv("! Missed (no answer): ${total.m}", 16f, false, Color.parseColor("#C98A12")))
        add(sum)

        if (total.total == 0) {
            add(tv("No records yet. The report fills up as you mark your doses.", 14f, false, Color.GRAY))
            return
        }

        add(tv("By medicine", 16f, true), 16)
        for (m in meds) {
            val c = perMed[m.id] ?: continue
            val card = card()
            card.addView(tv(m.name, 16f, true))
            card.addView(tv("✓ ${c.t}    ✗ ${c.s}    ! ${c.m}", 15f))
            add(card, 6)
        }

        add(tv("By day", 16f, true), 16)
        val days = card()
        for ((label, c) in perDay) {
            if (c.total == 0) continue
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.addView(tv(label, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(tv("✓ ${c.t}   ✗ ${c.s}   ! ${c.m}", 15f))
            r.setPadding(0, dp(4), 0, dp(4))
            days.addView(r)
        }
        add(days, 6)
    }

    // ----- Settings -----
    private fun renderSettings() {
        val nameCard = card()
        nameCard.addView(tv("Your name", 16f, true))
        val e = EditText(this)
        e.hint = "Your name"
        e.setSingleLine()
        e.setText(Store.name(this))
        nameCard.addView(e)
        val save = Button(this)
        save.text = "Save name"
        save.isAllCaps = false
        save.setOnClickListener {
            Store.setName(this, e.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            render()
        }
        nameCard.addView(save)
        add(nameCard, 8)

        val meals = Store.meals(this)
        val mc = card()
        mc.addView(tv("Meal times", 16f, true))
        mc.addView(tv("Breakfast ${fmt12(meals[0])}  •  Lunch ${fmt12(meals[1])}  •  Dinner ${fmt12(meals[2])}", 14f))
        val mb = Button(this)
        mb.text = "Change meal times"
        mb.isAllCaps = false
        mb.setOnClickListener { showMealTimes() }
        mc.addView(mb)
        add(mc)

        val rc = card()
        rc.addView(tv("Reliability", 16f, true))
        rc.addView(tv("Make sure reminders ring on a locked phone.", 14f))
        val rb = Button(this)
        rb.text = "Check settings"
        rb.isAllCaps = false
        rb.setOnClickListener { showReliability() }
        rc.addView(rb)
        add(rc)

        add(tv("Medicine Reminder v1.3", 12f, false, Color.GRAY), 16)
    }

    // ----- Permissions -----
    private fun checkSetup() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        if (Build.VERSION.SDK_INT >= 34 && !askedFs) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                askedFs = true
                Toast.makeText(this, "Allow 'Full screen notifications' so the pop-up shows on the lock screen", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkSetup()
    }

    // ----- Add / edit -----
    // Step 1: name, dose, meal rule, how many times a day.
    private fun showForm(existing: Med?) {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.setPadding(dp(20), dp(8), dp(20), 0)
        val name = EditText(this)
        name.hint = "Medicine name"
        name.setSingleLine()
        name.setText(existing?.name ?: "")
        val dose = EditText(this)
        dose.hint = "Dose (e.g. 1 tablet) - optional"
        dose.setSingleLine()
        dose.setText(existing?.dose ?: "")
        val meal = Spinner(this)
        meal.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Empty stomach", "After meal", "Anytime"))
        meal.setSelection(existing?.meal ?: 1)
        val freq = Spinner(this)
        freq.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Once a day", "Twice a day", "3 times a day", "4 times a day"))
        freq.setSelection((existing?.times?.size ?: 1) - 1)
        p.addView(name)
        p.addView(dose)
        p.addView(meal)
        p.addView(freq)

        val d = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add medicine" else "Edit medicine")
            .setView(p)
            .setPositiveButton("Next", null)
            .setNegativeButton("Cancel", null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isEmpty()) {
                Toast.makeText(this, "Enter the medicine name", Toast.LENGTH_SHORT).show()
            } else {
                d.dismiss()
                showTimes(existing, n, dose.text.toString().trim(), meal.selectedItemPosition, freq.selectedItemPosition + 1)
            }
        }
    }

    // Step 2: suggested times, each one editable.
    private fun showTimes(existing: Med?, name: String, dose: String, meal: Int, freq: Int) {
        val others = Store.load(this).filter { it.id != existing?.id }.flatMap { it.times }
        val times: MutableList<Int> =
            if (existing != null && existing.meal == meal && existing.times.size == freq) existing.times.toMutableList()
            else Suggest.suggest(this, meal, freq, others)

        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.setPadding(dp(20), dp(8), dp(20), 0)
        val note = TextView(this)
        note.text = "Suggested times - tap a time to change it.\n" +
            when (meal) {
                0 -> "Empty stomach: 1 hour before a meal."
                1 -> "After meal: 30 minutes after a meal."
                else -> "Anytime: at meal time."
            } + "\nMedicines are kept at least ${Suggest.GAP} minutes apart."
        note.setPadding(0, 0, 0, dp(8))
        p.addView(note)
        for (i in times.indices) {
            val b = Button(this)
            b.isAllCaps = false
            b.text = "Dose ${i + 1}:  ${fmt12(times[i])}"
            b.setOnClickListener {
                TimePickerDialog(this, { _, h, m ->
                    times[i] = h * 60 + m
                    b.text = "Dose ${i + 1}:  ${fmt12(times[i])}"
                }, times[i] / 60, times[i] % 60, false).show()
            }
            p.addView(b)
        }

        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(p)
            .setPositiveButton("Save") { _, _ ->
                val all = Store.load(this)
                val sorted = times.sorted().toMutableList()
                val med = if (existing != null) {
                    Scheduler.cancel(this, existing.id)
                    existing.name = name
                    existing.dose = dose
                    existing.meal = meal
                    existing.times = sorted
                    existing
                } else {
                    Med(newId(all), name, dose, meal, sorted, todayKey(), nowMinutes())
                }
                all.removeAll { it.id == med.id }
                all.add(med)
                Store.save(this, all)
                Scheduler.schedule(this, med)
                val close = others.any { o -> med.times.any { Suggest.dist(o, it) < Suggest.GAP } }
                if (close) Toast.makeText(this, "Note: a time is close to another medicine", Toast.LENGTH_LONG).show()
                render()
                checkSetup()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun newId(all: List<Med>): Int {
        var id: Int
        do {
            id = (1..99999).random()
        } while (all.any { it.id == id })
        return id
    }

    private fun showMealTimes() {
        val m = Store.meals(this)
        val names = arrayOf("Breakfast", "Lunch", "Dinner")
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.setPadding(dp(20), dp(8), dp(20), 0)
        val note = TextView(this)
        note.text = "Used to suggest medicine times. Existing medicines keep their times until you edit them."
        p.addView(note)
        for (i in 0..2) {
            val b = Button(this)
            b.isAllCaps = false
            b.text = "${names[i]}:  ${fmt12(m[i])}"
            b.setOnClickListener {
                TimePickerDialog(this, { _, h, mi ->
                    m[i] = h * 60 + mi
                    Store.saveMeals(this, m)
                    b.text = "${names[i]}:  ${fmt12(m[i])}"
                }, m[i] / 60, m[i] % 60, false).show()
            }
            p.addView(b)
        }
        AlertDialog.Builder(this).setTitle("Your meal times").setView(p)
            .setPositiveButton("Done") { _, _ -> render() }.show()
    }

    private fun showReliability() {
        AlertDialog.Builder(this)
            .setTitle("Make reminders reliable")
            .setMessage("To get reminders on a locked phone:\n\n1. Allow notifications.\n2. Allow 'Full screen notifications' (for the lock-screen pop-up).\n3. Set battery to 'Unrestricted' / 'No restrictions' for this app (some phones: Autostart ON).\n\nYou do not need to keep the app open.")
            .setPositiveButton("Battery settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            .setNeutralButton("App settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
