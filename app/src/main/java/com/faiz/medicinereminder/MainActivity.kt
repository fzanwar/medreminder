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
class Med(val id: Int, var name: String, var dose: String, var meal: Int, var times: MutableList<Int>)

fun fmt12(min: Int): String {
    val h = min / 60
    val m = min % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return String.format("%d:%02d %s", h12, m, if (h >= 12) "PM" else "AM")
}

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("medicines", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Med> {
        val out = mutableListOf<Med>()
        try {
            val arr = JSONArray(prefs(c).getString("meds", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getJSONArray("times")
                val times = mutableListOf<Int>()
                for (j in 0 until t.length()) times.add(t.getInt(j))
                out.add(Med(o.getInt("id"), o.getString("name"), o.optString("dose", ""), o.optInt("meal", 1), times))
            }
        } catch (_: Exception) {
        }
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
            arr.put(o)
        }
        prefs(c).edit().putString("meds", arr.toString()).apply()
    }

    private fun today() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    // Today's record per dose: "T" = taken, "S" = skipped (not taking).
    fun setStatus(c: Context, id: Int, idx: Int, st: String) {
        val p = prefs(c)
        val o = try { JSONObject(p.getString("log", "{}")) } catch (_: Exception) { JSONObject() }
        val ks = mutableListOf<String>()
        val iter = o.keys()
        while (iter.hasNext()) ks.add(iter.next())
        ks.filter { !it.startsWith(today()) }.forEach { o.remove(it) }
        o.put("${today()}:$id:$idx", st)
        p.edit().putString("log", o.toString()).apply()
    }

    fun status(c: Context, id: Int, idx: Int): String? = try {
        val o = JSONObject(prefs(c).getString("log", "{}"))
        val k = "${today()}:$id:$idx"
        if (o.has(k)) o.getString(k) else null
    } catch (_: Exception) {
        null
    }

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
object Scheduler {
    const val FIRE = "com.faiz.medicinereminder.FIRE"
    const val TAKEN = "com.faiz.medicinereminder.TAKEN"
    const val SNOOZE = "com.faiz.medicinereminder.SNOOZE"
    const val SKIP = "com.faiz.medicinereminder.SKIP"
    const val CHANNEL = "medicine_due_v2"

    fun code(id: Int, idx: Int) = id * 10 + idx

    private fun pi(c: Context, id: Int, idx: Int, snooze: Boolean): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).setAction(FIRE)
            .putExtra("id", id).putExtra("idx", idx).putExtra("snooze", snooze)
        val rc = code(id, idx) + if (snooze) 10_000_000 else 0
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

    fun setAlarm(c: Context, id: Int, idx: Int, at: Long, snooze: Boolean) {
        val a = am(c)
        val p = pi(c, id, idx, snooze)
        val exact = Build.VERSION.SDK_INT < 31 || a.canScheduleExactAlarms()
        try {
            if (exact) a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) {
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun schedule(c: Context, m: Med) {
        m.times.forEachIndexed { i, t -> setAlarm(c, m.id, i, nextTrigger(t), false) }
    }

    fun scheduleAll(c: Context) {
        Store.load(c).forEach { schedule(c, it) }
    }

    fun cancelSnooze(c: Context, id: Int, idx: Int) {
        am(c).cancel(pi(c, id, idx, true))
    }

    fun cancel(c: Context, id: Int) {
        for (i in 0..3) {
            am(c).cancel(pi(c, id, i, false))
            am(c).cancel(pi(c, id, i, true))
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
    }

    // Taken / Skip / Snooze - used by the notification buttons and the pop-up screen.
    fun handle(c: Context, act: String, id: Int, idx: Int) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(code(id, idx))
        when (act) {
            TAKEN -> {
                cancelSnooze(c, id, idx)
                Store.setStatus(c, id, idx, "T")
            }
            SKIP -> {
                cancelSnooze(c, id, idx)
                Store.setStatus(c, id, idx, "S")
            }
            SNOOZE -> {
                setAlarm(c, id, idx, System.currentTimeMillis() + 10 * 60 * 1000L, true)
                Toast.makeText(c, "Snoozed for 10 minutes", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val idx = intent.getIntExtra("idx", 0)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val code = Scheduler.code(id, idx)
        when (intent.action) {
            Scheduler.TAKEN, Scheduler.SKIP, Scheduler.SNOOZE -> Scheduler.handle(context, intent.action!!, id, idx)
            else -> {
                val m = Store.load(context).firstOrNull { it.id == id } ?: return
                if (idx >= m.times.size) return
                // Set tomorrow's alarm first so it is never lost.
                if (!intent.getBooleanExtra("snooze", false)) Scheduler.schedule(context, m)
                Scheduler.ensureChannel(context)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
                val popup = PendingIntent.getActivity(
                    context, code + 40_000_000,
                    Intent(context, AlarmActivity::class.java).putExtra("id", id).putExtra("idx", idx)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    flags
                )
                fun action(act: String, rc: Int) = PendingIntent.getBroadcast(
                    context, code + rc,
                    Intent(context, ReminderReceiver::class.java).setAction(act).putExtra("id", id).putExtra("idx", idx),
                    flags
                )
                fun act(label: String, icon: Int, a: String, rc: Int) =
                    Notification.Action.Builder(Icon.createWithResource(context, icon), label, action(a, rc)).build()
                val how = when (m.meal) { 0 -> "Empty stomach"; 1 -> "After meal"; else -> "Anytime" }
                val text = listOf(m.dose, how).filter { it.isNotBlank() }.joinToString(" • ")
                val n = Notification.Builder(context, Scheduler.CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Take: ${m.name}")
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
        if (m == null || idx >= m.times.size) {
            finish()
            return
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#0B5D4A"))
        root.setPadding(dp(24), dp(48), dp(24), dp(48))

        fun text(t: String, size: Float, bold: Boolean = false): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.gravity = Gravity.CENTER
            v.setTextColor(Color.WHITE)
            if (bold) v.setTypeface(null, Typeface.BOLD)
            v.setPadding(0, dp(4), 0, dp(4))
            root.addView(v)
            return v
        }
        text("Time for your medicine", 16f)
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

// ---------- Screen ----------
class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private val green = Color.parseColor("#147D64")
    private var askedNotif = false
    private var askedExact = false
    private var askedFs = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, r: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

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

        val title = TextView(this)
        title.text = "Medicine Reminder"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        title.setTypeface(null, Typeface.BOLD)
        title.setPadding(dp(18), dp(14), dp(18), dp(14))
        root.addView(title)

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setBackgroundColor(Color.parseColor("#F3F7F5"))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(8), dp(8), dp(8), 0)
        fun btn(label: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { onClick() }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return b
        }
        btn("+ Add") { showForm(null) }
        btn("Meal times") { showMealTimes() }
        btn("Reliability") { showReliability() }
        body.addView(row)

        val scroll = ScrollView(this)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(12), dp(8), dp(12), dp(24))
        scroll.addView(list)
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
        Scheduler.scheduleAll(this)
        checkSetup()
    }

    private fun checkSetup() {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && !askedExact) {
            val a = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!a.canScheduleExactAlarms()) {
                askedExact = true
                Toast.makeText(this, "Please allow 'Alarms & reminders' for this app", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                }
                return
            }
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

    private fun render() {
        list.removeAllViews()
        val meds = Store.load(this).sortedBy { it.times.minOrNull() ?: 0 }
        if (meds.isEmpty()) {
            val t = TextView(this)
            t.text = "No medicines yet.\nTap \"+ Add\" to add one."
            t.textSize = 16f
            t.gravity = Gravity.CENTER
            t.setPadding(dp(16), dp(48), dp(16), dp(16))
            list.addView(t)
            return
        }
        for (m in meds) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.background = roundBg(Color.WHITE)
            card.setPadding(dp(16), dp(12), dp(16), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)

            val name = TextView(this)
            name.text = m.name + if (m.dose.isNotBlank()) "  (${m.dose})" else ""
            name.textSize = 18f
            name.setTypeface(null, Typeface.BOLD)
            name.setTextColor(Color.parseColor("#12332B"))
            card.addView(name)

            val info = TextView(this)
            info.text = mealLabel(m.meal) + " • " + freqLabel(m.times.size)
            info.textSize = 14f
            card.addView(info)

            val times = TextView(this)
            times.text = m.times.mapIndexed { i, t ->
                fmt12(t) + when (Store.status(this, m.id, i)) { "T" -> " ✓"; "S" -> " ✗"; else -> "" }
            }.joinToString("   ")
            times.textSize = 16f
            times.setTextColor(green)
            times.setPadding(0, dp(4), 0, dp(4))
            card.addView(times)

            val br = LinearLayout(this)
            br.orientation = LinearLayout.HORIZONTAL
            br.gravity = Gravity.END
            val edit = Button(this)
            edit.text = "Edit"
            edit.setOnClickListener { showForm(m) }
            val del = Button(this)
            del.text = "Delete"
            del.setOnClickListener {
                AlertDialog.Builder(this).setTitle("Delete ${m.name}?")
                    .setPositiveButton("Delete") { _, _ ->
                        Scheduler.cancel(this, m.id)
                        val all = Store.load(this)
                        all.removeAll { it.id == m.id }
                        Store.save(this, all)
                        render()
                    }.setNegativeButton("Cancel", null).show()
            }
            br.addView(edit)
            br.addView(del)
            card.addView(br)
            list.addView(card, lp)
        }
    }

    private fun mealLabel(i: Int) = when (i) { 0 -> "Empty stomach"; 1 -> "After meal"; else -> "Anytime" }
    private fun freqLabel(n: Int) = when (n) { 1 -> "Once a day"; 2 -> "Twice a day"; 3 -> "3 times a day"; else -> "4 times a day" }

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
                val med = existing ?: Med(newId(all), name, dose, meal, times)
                if (existing != null) Scheduler.cancel(this, existing.id)
                med.name = name
                med.dose = dose
                med.meal = meal
                med.times = times.sorted().toMutableList()
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
        AlertDialog.Builder(this).setTitle("Your meal times").setView(p).setPositiveButton("Done", null).show()
    }

    private fun showReliability() {
        AlertDialog.Builder(this)
            .setTitle("Make reminders reliable")
            .setMessage("To get reminders on a locked phone:\n\n1. Allow notifications.\n2. Allow 'Alarms & reminders' and 'Full screen notifications' (for the lock-screen pop-up).\n3. Set battery to 'Unrestricted' / 'No restrictions' for this app (some phones: Autostart ON).\n\nYou do not need to keep the app open.")
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
