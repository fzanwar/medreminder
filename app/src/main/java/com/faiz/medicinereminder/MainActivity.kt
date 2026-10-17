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
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
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
    fun prefs(c: Context) = c.getSharedPreferences("medicines", Context.MODE_PRIVATE)

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

    // ----- medicine colours (same name = same colour) -----
    private fun colorMap(c: Context): JSONObject =
        try { JSONObject(prefs(c).getString("colors", "{}")) } catch (_: Exception) { JSONObject() }

    private fun usedColors(c: Context, o: JSONObject): Set<Int> =
        load(c).map { it.name.trim().lowercase() }.filter { o.has(it) }.map { o.getInt(it) }.toSet()

    fun suggestColor(c: Context): Int {
        val used = usedColors(c, colorMap(c))
        var i = 0
        while (i < Palette.size && i in used) i++
        return if (i >= Palette.size) 0 else i
    }

    fun colorIndex(c: Context, name: String): Int {
        val key = name.trim().lowercase()
        val o = colorMap(c)
        if (o.has(key)) return o.getInt(key)
        val used = usedColors(c, o)
        var i = 0
        while (i < Palette.size && i in used) i++
        if (i >= Palette.size) i = o.length() % Palette.size
        o.put(key, i)
        prefs(c).edit().putString("colors", o.toString()).apply()
        return i
    }

    fun setColorIndex(c: Context, name: String, idx: Int) {
        val o = colorMap(c)
        o.put(name.trim().lowercase(), idx)
        prefs(c).edit().putString("colors", o.toString()).apply()
    }

    // ----- body measurements (BMI, water goal) -----
    fun height(c: Context): Float = prefs(c).getFloat("height", 0f)
    fun weight(c: Context): Float = prefs(c).getFloat("weight", 0f)
    fun age(c: Context): Int = prefs(c).getInt("age", 0)
    fun setWeight(c: Context, w: Float) { prefs(c).edit().putFloat("weight", w).apply() }
    fun setBody(c: Context, h: Float, w: Float, a: Int) {
        prefs(c).edit().putFloat("height", h).putFloat("weight", w).putInt("age", a).apply()
    }

    fun weights(c: Context): List<Pair<Long, Double>> {
        val out = mutableListOf<Pair<Long, Double>>()
        try {
            val a = JSONArray(prefs(c).getString("weights", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Pair(o.getLong("t"), o.getDouble("k")))
            }
        } catch (_: Exception) {
        }
        return out.sortedBy { it.first }
    }

    fun addWeight(c: Context, kg: Double) {
        fun dk(t: Long) = dayKey(Calendar.getInstance().apply { timeInMillis = t })
        val now = System.currentTimeMillis()
        val l = weights(c).filter { dk(it.first) != dk(now) }.toMutableList()
        l.add(Pair(now, kg))
        val a = JSONArray()
        for ((t, k) in l.takeLast(400)) {
            val o = JSONObject()
            o.put("t", t)
            o.put("k", k)
            a.put(o)
        }
        prefs(c).edit().putString("weights", a.toString()).apply()
    }

    fun feature(c: Context, name: String, default: Boolean): Boolean = prefs(c).getBoolean("feat_$name", default)
    fun setFeature(c: Context, name: String, on: Boolean) { prefs(c).edit().putBoolean("feat_$name", on).apply() }
    fun noticeSeen(c: Context): Boolean = prefs(c).getBoolean("noticeSeen", false)
    fun setNoticeSeen(c: Context) { prefs(c).edit().putBoolean("noticeSeen", true).apply() }

    // breakfast, lunch, dinner (minutes since midnight)
    fun meals(c: Context): IntArray {
        val p = prefs(c)
        return intArrayOf(p.getInt("b", 8 * 60 + 30), p.getInt("l", 13 * 60 + 30), p.getInt("d", 20 * 60))
    }

    fun saveMeals(c: Context, m: IntArray) {
        prefs(c).edit().putInt("b", m[0]).putInt("l", m[1]).putInt("d", m[2]).apply()
    }
}

// ---------- Daily health quotes (original lines, work offline) ----------
object Quotes {
    private val list = listOf(
        "Small steps every day add up to big changes in health.",
        "Medicine taken on time works best.",
        "A glass of water is a small act of self-care.",
        "Rest is part of recovery, not a delay in it.",
        "Consistency beats intensity, every single day.",
        "Your future self will thank you for today's good habits.",
        "Listen to your body; it speaks quietly before it shouts.",
        "A ten-minute walk is better than no walk.",
        "Healthy habits are built one ordinary day at a time.",
        "Sleep is not a luxury; it is how the body repairs itself.",
        "Eat slowly. Your body notices when you pay attention.",
        "Progress, not perfection, is the goal.",
        "Missing a dose is not a failure; getting back on track is a win.",
        "Good health is a daily routine, not a one-time effort.",
        "Breathe in calm, breathe out tension.",
        "A colourful plate is a healthy plate.",
        "Move a little more than yesterday.",
        "Kind words to yourself are good for your health too.",
        "Your health is worth a few minutes every day.",
        "Stay curious about how your body feels.",
        "Fresh air and sunlight are free medicine for the mood.",
        "Take the stairs when you can.",
        "Prevention is easier than cure.",
        "A calm mind helps a healthy body.",
        "Today is a good day to start.",
        "Drink water, stretch, smile: repeat.",
        "Ask your doctor when in doubt; questions are part of good care.",
        "Little habits, repeated, become big strengths.",
        "Healing takes time; be patient with yourself.",
        "Fuel your body like you care about it.",
        "A short pause to breathe can reset a hard day.",
        "Wash your hands, protect your health.",
        "Sit less, move more.",
        "Gratitude is good for the heart.",
        "Keep your routine simple and your reminders on.",
        "Every healthy choice counts, however small.",
        "Good sleep, good food, good company: a simple recipe.",
        "Be as patient with your body as you are with others.",
        "Energy follows care.",
        "Stretch in the morning; thank your body for another day.",
        "Health is the quiet foundation of every other goal.",
        "Do not wait for symptoms to take care of yourself.",
        "One healthy meal at a time.",
        "A regular routine is a gift to your body.",
        "Laughter is light exercise for the soul.",
        "Switch off screens early; sleep will thank you.",
        "Plan your day around your health, not the other way around.",
        "You do not have to be perfect, just persistent.",
        "Check in with how you feel, not only with how you look.",
        "Take your time; healthy things grow slowly.",
        "A good habit started today is a gift for years.",
        "Stay hydrated, stay kind, stay moving.",
        "Rest well; tomorrow needs you.",
        "Strong body, calm mind, steady routine.",
        "Choose the habit you will keep, not the one that sounds impressive.",
        "Your body does a lot for you every second; return the favour.",
        "Celebrate small wins, like a dose taken on time.",
        "Fruits and vegetables: the easiest upgrade.",
        "Slow down; health is not a race.",
        "Care for yourself the way you care for the people you love.",
        "Morning light helps the body wake up well.",
        "Regular meals help steady your energy.",
        "Quiet moments are good medicine for stress.",
        "Walk after a meal; digestion likes it.",
        "Be gentle on days when energy is low.",
        "Good health begins with small decisions.",
        "Set a reminder, build a routine, protect your health.",
        "What you do daily matters more than what you do occasionally.",
        "Look after your health today so tomorrow is easier.",
        "Be proud of showing up for your health.",
        "A balanced day: some work, some rest, some play.",
        "Keep moving, even if it is slow.",
        "Health is wealth you carry with you.",
        "Notice what makes you feel well, and do more of it.",
        "Healthy ageing is built in everyday moments.",
        "Keep a regular sleep time, even on holidays.",
        "Choose water over a sugary drink when you can.",
        "You are doing better than you think.",
        "Take a deep breath. You have got this.",
        "Health is a journey, and every day is a step."
    )

    fun today(): String {
        val c = Calendar.getInstance()
        val n = c.get(Calendar.DAY_OF_YEAR) + 366 * (c.get(Calendar.YEAR) - 2026)
        return list[((n % list.size) + list.size) % list.size]
    }
}

// ---------- Report data (used by the Report tab and by Export) ----------
fun mealText(i: Int) = when (i) { 0 -> "Empty stomach"; 1 -> "After meal"; else -> "Anytime" }

class DoseRow(val dateFull: String, val dateLabel: String, val time: Int, val med: String, val dose: String, val meal: Int, val status: String)

class Extra(val title: String, val summary: List<String>, val detail: List<String>)

class ReportData(
    val days: Int,
    val from: String,
    val to: String,
    val total: Counts,
    val perMed: List<Pair<String, Counts>>,
    val perDay: List<Pair<String, Counts>>,
    val rows: List<DoseRow>,
    val extras: List<Extra>
)

fun buildReport(c: Context, days: Int): ReportData {
    val meds = Store.load(c)
    val log = Store.log(c)
    val now = nowMinutes()
    val total = Counts()
    val perMed = LinkedHashMap<Int, Counts>()
    val perDay = mutableListOf<Pair<String, Counts>>()
    val rows = mutableListOf<DoseRow>()
    val fmtDay = java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault())
    val fmtFull = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    var from = ""
    var to = ""
    for (d in days - 1 downTo 0) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -d)
        if (d == days - 1) from = fmtFull.format(cal.time)
        if (d == 0) to = fmtFull.format(cal.time)
        val key = dayKey(cal)
        val dc = Counts()
        val dayRows = mutableListOf<DoseRow>()
        for (m in meds) {
            m.times.forEachIndexed { i, t ->
                if (key < m.created || !applies(m, key, t)) return@forEachIndexed
                val st = log["$key:${m.id}:$i"]
                val label: String? = when {
                    st == "T" -> "Taken"
                    st == "S" -> "Not taken"
                    d == 0 && t > now -> null
                    else -> "Missed"
                }
                if (label != null) {
                    val pm = perMed.getOrPut(m.id) { Counts() }
                    for (x in listOf(total, dc, pm)) {
                        when (label) {
                            "Taken" -> x.t++
                            "Not taken" -> x.s++
                            else -> x.m++
                        }
                    }
                    dayRows.add(DoseRow(fmtFull.format(cal.time), fmtDay.format(cal.time), t, m.name, m.dose, m.meal, label))
                }
            }
        }
        rows.addAll(dayRows.sortedBy { it.time })
        perDay.add(Pair(fmtDay.format(cal.time), dc))
    }
    val byMed = meds.mapNotNull { m -> perMed[m.id]?.let { Pair(m.name, it) } }
    val extras = mutableListOf<Extra>()
    if (Water.on(c)) {
        val wl = Water.logAll(c)
        val goal = Water.goal(c)
        val keys = (0 until days).map { d ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -d)
            dayKey(cal)
        }
        val first = wl.keys.minOrNull()
        val rel = if (first == null) emptyList() else keys.filter { it >= first }
        val lines = if (rel.isEmpty()) listOf("No water logged in this period.") else listOf(
            "Goal met on ${rel.count { (wl[it] ?: 0) >= goal }} of ${rel.size} days",
            "Average: ${rel.sumOf { wl[it] ?: 0 } / rel.size} ml per day (goal $goal ml)"
        )
        extras.add(Extra("Water", lines, emptyList()))
    }
    if (Steps.on(c)) {
        val h = Steps.hist(c)
        val vals = (0 until days).map { d ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -d)
            h.optInt(dayKey(cal), 0)
        }.filter { it > 0 }
        val lines = if (vals.isEmpty()) listOf("No steps recorded in this period.") else listOf(
            "Average: ${vals.sum() / vals.size} steps per day",
            "Best day: ${vals.max()} steps",
            "Target ${Steps.target(c)} reached on ${vals.count { it >= Steps.target(c) }} of ${vals.size} days"
        )
        extras.add(Extra("Steps", lines, emptyList()))
    }
    if (Bp.on(c)) {
        val start = Calendar.getInstance()
        start.add(Calendar.DAY_OF_YEAR, -(days - 1))
        start.set(Calendar.HOUR_OF_DAY, 0)
        start.set(Calendar.MINUTE, 0)
        start.set(Calendar.SECOND, 0)
        start.set(Calendar.MILLISECOND, 0)
        val rs = Bp.all(c).filter { it.time >= start.timeInMillis }
        val f = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault())
        val sum = if (rs.isEmpty()) listOf("No readings in this period.") else listOf(
            "Readings: ${rs.size}",
            "Average: ${Bp.avgText(rs)}",
            "Highest systolic: ${rs.maxOf { it.sys }}   Lowest systolic: ${rs.minOf { it.sys }}"
        )
        val det = rs.takeLast(40).map {
            "${f.format(java.util.Date(it.time))}   ${it.sys}/${it.dia}" + (if (it.pulse > 0) "   pulse ${it.pulse}" else "") + "   (${Bp.label(it.sys, it.dia).first})"
        }
        extras.add(Extra("Blood pressure", sum, det))
    }
    return ReportData(days, from, to, total, byMed, perDay, rows, extras)
}

// ---------- Export: PDF, CSV, plain text ----------
object Exporter {
    private fun pct(c: Counts) = if (c.total == 0) "-" else "${c.t * 100 / c.total}%"

    // style: 2 = title, 1 = heading, 0 = normal
    fun lines(c: Context, r: ReportData, detail: Boolean): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        out.add(Pair("Medicine Report", 2))
        val who = Store.name(c)
        if (who.isNotBlank()) out.add(Pair("Name: $who", 0))
        out.add(Pair("Period: ${r.from} to ${r.to} (${r.days} days)", 0))
        out.add(Pair("Generated: " + java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date()), 0))
        out.add(Pair("", 0))
        out.add(Pair("Summary", 1))
        out.add(Pair("Taken: ${r.total.t}   Not taken: ${r.total.s}   Missed: ${r.total.m}   (Taken ${pct(r.total)})", 0))
        out.add(Pair("", 0))
        out.add(Pair("By medicine", 1))
        for ((n, x) in r.perMed) out.add(Pair("$n:  Taken ${x.t}, Not taken ${x.s}, Missed ${x.m}", 0))
        out.add(Pair("", 0))
        out.add(Pair("By day", 1))
        for ((n, x) in r.perDay) if (x.total > 0) out.add(Pair("$n:  Taken ${x.t}, Not taken ${x.s}, Missed ${x.m}", 0))
        for (e in r.extras) {
            out.add(Pair("", 0))
            out.add(Pair(e.title, 1))
            for (l in e.summary) out.add(Pair(l, 0))
            if (detail) for (l in e.detail) out.add(Pair(l, 0))
        }
        if (detail) {
            out.add(Pair("", 0))
            out.add(Pair("All doses", 1))
            for (x in r.rows) {
                val dose = if (x.dose.isBlank()) "" else " (${x.dose})"
                out.add(Pair("${x.dateLabel}  ${fmt12(x.time)}  ${x.med}$dose - ${x.status}", 0))
            }
        }
        out.add(Pair("", 0))
        out.add(Pair("Personal log made with Medicine Reminder. Not medical advice.", 0))
        out.add(Pair("Created by Faiz Anwar", 0))
        return out
    }

    fun text(c: Context, r: ReportData): String = lines(c, r, false).joinToString("\n") { it.first }

    private fun dir(c: Context): java.io.File {
        val d = java.io.File(c.cacheDir, "exports")
        d.mkdirs()
        d.listFiles()?.forEach { it.delete() }
        return d
    }

    fun csv(c: Context, r: ReportData): java.io.File {
        val f = java.io.File(dir(c), "MedicineReport_${todayKey()}.csv")
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("﻿")
        sb.append("Date,Time,Medicine,Dose,Meal rule,Status\n")
        for (x in r.rows) {
            sb.append(q(x.dateFull)).append(",").append(q(fmt12(x.time))).append(",").append(q(x.med)).append(",")
                .append(q(x.dose)).append(",").append(q(mealText(x.meal))).append(",").append(q(x.status)).append("\n")
        }
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    fun pdf(c: Context, r: ReportData): java.io.File {
        val f = java.io.File(dir(c), "MedicineReport_${todayKey()}.pdf")
        val w = 595
        val h = 842
        val m = 40f
        val doc = PdfDocument()
        val paint = Paint()
        paint.isAntiAlias = true
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var y = m
        for ((text, style) in lines(c, r, true)) {
            paint.textSize = when (style) { 2 -> 20f; 1 -> 13f; else -> 11f }
            paint.typeface = if (style >= 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = if (style >= 1) Color.parseColor("#147D64") else Color.parseColor("#222222")
            val lh = if (text.isEmpty()) 8f else paint.textSize + 6f
            if (y + lh > h - m) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
                y = m
            }
            y += lh
            if (text.isNotEmpty()) {
                val n = paint.breakText(text, true, w - 2 * m, null)
                page.canvas.drawText(text.substring(0, n), m, y, paint)
            }
        }
        doc.finishPage(page)
        java.io.FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
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
        HealthAlarms.scheduleAll(c)
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
            HealthAlarms.WATER_FIRE, HealthAlarms.WATER_ADD, HealthAlarms.WATER_SNOOZE, HealthAlarms.BP_FIRE -> HealthAlarms.onReceive(context, intent)
            HealthAlarms.STEPS_READ, HealthAlarms.STEPS_NUDGE -> HealthAlarms.onReceive(context, intent, goAsync())
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
                        .setColor(Palette.color(Store.colorIndex(context, m.name)))
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
                    .setColor(Palette.color(Store.colorIndex(context, m.name)))
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
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(24).toFloat()
        }
        pill.setPadding(dp(18), dp(8), dp(22), dp(8))
        val dot = View(this)
        val dg = GradientDrawable()
        dg.shape = GradientDrawable.OVAL
        dg.setColor(Palette.color(Store.colorIndex(this, m.name)))
        dot.background = dg
        val dlp = LinearLayout.LayoutParams(dp(16), dp(16))
        dlp.rightMargin = dp(10)
        pill.addView(dot, dlp)
        val pn = TextView(this)
        pn.text = m.name
        pn.textSize = 26f
        pn.setTypeface(null, Typeface.BOLD)
        pn.setTextColor(Color.parseColor("#12332B"))
        pill.addView(pn)
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        plp.topMargin = dp(6)
        plp.bottomMargin = dp(6)
        root.addView(pill, plp)
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
    private var tab = "Today"
    private lateinit var scroll: ScrollView
    private var pausedAt = 0L
    private val blinkers = mutableListOf<android.animation.ObjectAnimator>()
    private var reportDays = 7
    private var askedNotif = false
    private var askedExact = false
    private var askedFs = false
    private val green = Color.parseColor("#147D64")
    private val ink = Color.parseColor("#12332B")
    private val gray = Color.parseColor("#6B7F78")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, r: Int = 14) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun tv(t: CharSequence, size: Float = 14f, bold: Boolean = false, color: Int = ink): TextView {
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

    // White card with a coloured stripe on the left. Returns (outer card, inner body).
    private fun stripeCard(color: Int): Pair<LinearLayout, LinearLayout> {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.HORIZONTAL
        outer.background = roundBg(Color.WHITE)
        outer.clipToOutline = true
        val bar = View(this)
        bar.setBackgroundColor(color)
        outer.addView(bar, LinearLayout.LayoutParams(dp(7), ViewGroup.LayoutParams.MATCH_PARENT))
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(14), dp(10), dp(14), dp(10))
        outer.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return Pair(outer, body)
    }

    private fun colored(prefix: String, rest: String, color: Int): SpannableString {
        val s = SpannableString(prefix + rest)
        s.setSpan(ForegroundColorSpan(color), 0, prefix.length, 0)
        return s
    }

    private fun add(v: View, top: Int = 10) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        content.addView(v, lp)
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun numberField(hint: String, value: String, decimal: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setSingleLine()
        e.inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        e.setText(value)
        return e
    }

    private fun num(e: EditText): Double? = e.text.toString().trim().toDoubleOrNull()

    private fun mealLabel(i: Int) = mealText(i)
    private fun freqLabel(n: Int) = when (n) { 1 -> "Once a day"; 2 -> "Twice a day"; 3 -> "3 times a day"; else -> "4 times a day" }
    private fun medColor(name: String) = Palette.color(Store.colorIndex(this, name))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.ensureChannel(this)
        tab = intent.getStringExtra("tab") ?: "Today"

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

        scroll = ScrollView(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), dp(4), dp(12), dp(24))
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.setBackgroundColor(Color.WHITE)
        body.addView(tabBar)

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (!Store.noticeSeen(this)) showNotice() else if (Store.name(this).isBlank() && !Store.nameAsked(this)) askName()
    }

    override fun onPause() {
        super.onPause()
        pausedAt = System.currentTimeMillis()
        stopBlink()
    }

    private fun stopBlink() {
        for (a in blinkers) a.cancel()
        blinkers.clear()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77) {
            Steps.readOnce(this) { if (!isFinishing) render() }
            HealthAlarms.scheduleAll(this)
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > 20_000) scroll.post { scroll.scrollTo(0, 0) }
        if (Steps.on(this)) Steps.readOnce(this) { if (!isFinishing) render() }
        Scheduler.scheduleAll(this)
        checkSetup()
        if (Updater.enabled(this) && Updater.dueForCheck(this)) {
            Updater.check(this) { _, _ -> runOnUiThread { if (!isFinishing) render() } }
        }
    }

    private fun tabs(): List<String> {
        val l = mutableListOf("Today", "Medicines", "Report")
        if (Bp.on(this) || Store.feature(this, "bmi", false)) l.add("Health")
        l.add("Settings")
        return l
    }

    private fun render() {
        val who = Store.name(this)
        subtitle.text = if (who.isBlank()) "Take care of your health" else "Hello, $who"
        val names = tabs()
        if (tab !in names) tab = "Today"
        tabBar.removeAllViews()
        for (n in names) {
            val t = TextView(this)
            t.text = n
            t.textSize = 14f
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(14), 0, dp(14))
            t.setTextColor(if (n == tab) green else Color.GRAY)
            t.setTypeface(null, if (n == tab) Typeface.BOLD else Typeface.NORMAL)
            t.setOnClickListener {
                tab = n
                render()
                scroll.post { scroll.scrollTo(0, 0) }
            }
            tabBar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        stopBlink()
        content.removeAllViews()
        when (tab) {
            "Today" -> renderToday()
            "Medicines" -> renderMeds()
            "Report" -> renderReport()
            "Health" -> renderHealth()
            else -> renderSettings()
        }
    }

    // ----- First-run notice and name -----
    private fun showNotice() {
        AlertDialog.Builder(this)
            .setTitle("Before you start")
            .setMessage("Medicine Reminder is a reminder and personal log. It is not medical advice and does not replace your doctor or pharmacist. Always follow your doctor's instructions for your medicines.\n\nYour health data stays on this phone.")
            .setCancelable(false)
            .setPositiveButton("I understand") { _, _ ->
                Store.setNoticeSeen(this)
                if (Store.name(this).isBlank() && !Store.nameAsked(this)) askName()
            }
            .show()
    }

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
    private fun addFooter() {
        val f = tv("Created by Faiz Anwar", 12f, false, gray)
        f.gravity = Gravity.CENTER
        add(f, 24)
    }

    private fun renderToday() {
        Updater.pending(this)?.let { (name, url) ->
            val u = card()
            u.background = roundBg(Color.parseColor("#FFF3D6"))
            u.addView(tv("New version $name available", 15f, true))
            u.addView(tv("Tap to download", 13f, false, gray))
            u.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                }
            }
            add(u, 8)
        }
        if (Store.feature(this, "quote", true)) {
            val q = card()
            q.background = roundBg(Color.parseColor("#E3F2EC"))
            q.addView(tv("Thought for today", 12f, true, green))
            q.addView(tv(Quotes.today(), 15f))
            add(q, 8)
        }
        if (Water.on(this)) renderWaterCard()
        if (Steps.on(this)) renderStepsCard()

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
            addFooter()
            return
        }
        val done = doses.count { log["$key:${it.second.id}:${it.third}"] == "T" }
        add(tv("Today: $done of ${doses.size} taken", 16f, true, green), 12)
        add(tv("Tap a dose to mark it taken or not taken.", 13f, false, Color.GRAY), 2)
        val nextDose = doses.firstOrNull { it.first > now && log["$key:${it.second.id}:${it.third}"] == null }
        val nextKey = nextDose?.let { "${it.second.id}:${it.third}" }
        for ((t, m, i) in doses) {
            val st = log["$key:${m.id}:$i"]
            val col = medColor(m.name)
            val (outer, c) = stripeCard(col)
            val title = tv(colored("● ${fmt12(t)}", "   ${m.name}" + if (m.dose.isNotBlank()) "  (${m.dose})" else "", col), 17f, true)
            c.addView(title)
            val (label, scol) = when {
                st == "T" -> Pair("✓ Taken", "#1B8A5A")
                st == "S" -> Pair("✗ Not taken", "#B23B3B")
                t > now -> Pair("Upcoming", "#666666")
                else -> Pair("! Not marked", "#C98A12")
            }
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.addView(tv(mealLabel(m.meal), 13f, false, Color.GRAY), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val stv = tv(label, 14f, true, Color.parseColor(scol))
            stv.gravity = Gravity.END
            line.addView(stv)
            c.addView(line)
            if (nextKey == "${m.id}:$i") {
                val a = android.animation.ObjectAnimator.ofFloat(outer, "alpha", 1f, 0.45f)
                a.duration = 700
                a.repeatMode = android.animation.ValueAnimator.REVERSE
                a.repeatCount = android.animation.ValueAnimator.INFINITE
                a.start()
                blinkers.add(a)
            }
            outer.setOnClickListener {
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
            add(outer)
        }
        addFooter()
    }

    private fun renderWaterCard() {
        val goal = Water.goal(this)
        val glass = Water.glass(this)
        val ml = Water.today(this)
        val blue = Color.parseColor("#2F6FDB")
        val c = card()
        c.addView(tv("Water", 16f, true, blue))
        c.addView(tv(String.format("%,d ml of %,d ml", ml, goal) + if (ml >= goal) "   Goal reached!" else "", 15f))
        val pct = minOf(100, ml * 100 / maxOf(goal, 1))
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = roundBg(Color.parseColor("#DDE7F5"), 6)
        val fill = View(this)
        fill.background = roundBg(blue, 6)
        bar.addView(fill, LinearLayout.LayoutParams(0, dp(12), pct.toFloat()))
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(12), (100 - pct).toFloat()))
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
        blp.topMargin = dp(8)
        c.addView(bar, blp)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun rb(label: String, f: () -> Unit) {
            row.addView(button(label, f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        rb("+ $glass ml") {
            Water.add(this, glass)
            render()
        }
        rb("Undo") {
            Water.add(this, -glass)
            render()
        }
        rb("Other...") {
            val e = numberField("Amount in ml", "")
            val p = LinearLayout(this)
            p.setPadding(dp(20), dp(8), dp(20), 0)
            p.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            AlertDialog.Builder(this).setTitle("Add water").setView(p)
                .setPositiveButton("Add") { _, _ ->
                    val v = num(e)
                    if (v != null && v > 0 && v <= 3000) {
                        Water.add(this, v.toInt())
                        render()
                    }
                }.setNegativeButton("Cancel", null).show()
        }
        c.addView(row)
        add(c, 8)
    }

    private fun renderStepsCard() {
        val green2 = Color.parseColor("#1F8A5B")
        val c = card()
        c.addView(tv("Steps", 16f, true, green2))
        if (!Steps.hasSensor(this)) {
            c.addView(tv("This phone has no step sensor.", 14f, false, gray))
        } else if (!Steps.hasPermission(this)) {
            c.addView(tv("Allow \"Physical activity\" to count your steps.", 14f))
            c.addView(button("Allow") { requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), 77) })
        } else {
            val got = Steps.today(this)
            val goal = Steps.goal(this)
            c.addView(tv(String.format("%,d of %,d steps", got, goal) + if (got >= goal) "   Goal reached!" else "", 15f))
            val pct = minOf(100, got * 100 / maxOf(goal, 1))
            val bar = LinearLayout(this)
            bar.background = roundBg(Color.parseColor("#DCEFE6"), 6)
            val fill = View(this)
            fill.background = roundBg(green2, 6)
            bar.addView(fill, LinearLayout.LayoutParams(0, dp(12), pct.toFloat()))
            bar.addView(View(this), LinearLayout.LayoutParams(0, dp(12), (100 - pct).toFloat()))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
            lp.topMargin = dp(8)
            c.addView(bar, lp)
            c.addView(tv("Counted by the phone, updated when you open the app and about every hour.", 11f, false, gray))
        }
        add(c, 8)
    }

    // ----- Medicines -----
    private fun renderMeds() {
        add(button("+ Add medicine") { showForm(null) }, 8)
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
            val col = medColor(m.name)
            val (outer, c) = stripeCard(col)
            c.addView(tv(colored("● ", m.name + if (m.dose.isNotBlank()) "  (${m.dose})" else "", col), 18f, true))
            c.addView(tv(mealLabel(m.meal) + " • " + freqLabel(m.times.size), 14f))
            c.addView(tv(m.times.sorted().joinToString("   ") { fmt12(it) }, 16f, false, col))
            outer.setOnClickListener { showManage(m) }
            add(outer)
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
                        HealthAlarms.scheduleAll(this)
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
        for ((label, days) in listOf(Pair("7 days", 7), Pair("30 days", 30), Pair("90 days", 90))) {
            val b = button(label) {
                reportDays = days
                render()
            }
            b.alpha = if (reportDays == days) 1f else 0.5f
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        add(row, 8)

        val r = buildReport(this, reportDays)
        val total = r.total
        val okC = Color.parseColor("#1B8A5A")
        val noC = Color.parseColor("#B23B3B")
        val missC = Color.parseColor("#E0A526")
        fun stack(c: Counts, h: Int): LinearLayout {
            val b = LinearLayout(this)
            b.background = roundBg(Color.parseColor("#E3EAE7"), 5)
            b.clipToOutline = true
            val tt = maxOf(c.total, 1).toFloat()
            for ((n, col) in listOf(Pair(c.t, okC), Pair(c.s, noC), Pair(c.m, missC))) {
                if (n > 0) {
                    val v = View(this)
                    v.setBackgroundColor(col)
                    b.addView(v, LinearLayout.LayoutParams(0, dp(h), n / tt * 100f))
                }
            }
            if (c.total == 0) b.addView(View(this), LinearLayout.LayoutParams(0, dp(h), 1f))
            return b
        }
        val sum = card()
        val pctN = if (total.total == 0) 0 else total.t * 100 / total.total
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(Ring(this, pctN, okC), LinearLayout.LayoutParams(dp(96), dp(96)))
        val tx = LinearLayout(this)
        tx.orientation = LinearLayout.VERTICAL
        tx.setPadding(dp(16), 0, 0, 0)
        tx.addView(tv("Taken on time", 13f, false, gray))
        tx.addView(tv("✓ Taken: ${total.t}", 16f, true, okC))
        tx.addView(tv("✗ Not taken: ${total.s}", 16f, true, noC))
        tx.addView(tv("! Missed: ${total.m}", 16f, true, Color.parseColor("#C98A12")))
        top.addView(tx)
        sum.addView(top)
        val sl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sl.topMargin = dp(12)
        sum.addView(stack(total, 14), sl)
        sum.addView(tv("Green = taken, red = not taken, amber = missed", 11f, false, gray))
        add(sum)

        if (total.total == 0) {
            add(tv("No dose records yet. The report fills up as you mark your doses.", 14f, false, Color.GRAY))
        } else {
            add(tv("By medicine", 16f, true), 14)
            for ((name, c) in r.perMed) {
                val col = medColor(name)
                val (outer, body) = stripeCard(col)
                body.addView(tv(colored("● ", name, col), 16f, true))
                val bl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                bl.topMargin = dp(6)
                body.addView(stack(c, 10), bl)
                body.addView(tv("✓ ${c.t}    ✗ ${c.s}    ! ${c.m}    (${if (c.total == 0) 0 else c.t * 100 / c.total}% taken)", 13f))
                add(outer, 6)
            }

            add(tv("Day by day", 16f, true), 16)
            val days = card()
            for ((label, c) in r.perDay.reversed()) {
                if (c.total == 0) continue
                val line = LinearLayout(this)
                line.orientation = LinearLayout.HORIZONTAL
                line.gravity = Gravity.CENTER_VERTICAL
                line.setPadding(0, dp(5), 0, dp(5))
                line.addView(tv(label, 13f), LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
                line.addView(stack(c, 12), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val ct = tv("${c.t}/${c.total}", 13f, true, if (c.t == c.total) okC else ink)
                ct.gravity = Gravity.END
                line.addView(ct, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
                days.addView(line)
            }
            add(days, 6)
        }

        for (e in r.extras) {
            val ec = card()
            ec.addView(tv(e.title, 16f, true))
            for (l in e.summary) ec.addView(tv(l, 14f))
            add(ec, 10)
        }

        if (total.total > 0 || r.extras.isNotEmpty()) add(button("Export / Share report") { showExport() }, 16)
    }

    private fun showExport() {
        AlertDialog.Builder(this)
            .setTitle("Export last $reportDays days")
            .setItems(arrayOf("PDF (to print or show a doctor)", "CSV (Excel / Google Sheets)", "Plain text (for WhatsApp / SMS)")) { _, w ->
                try {
                    val r = buildReport(this, reportDays)
                    when (w) {
                        0 -> shareFile(Exporter.pdf(this, r), "application/pdf")
                        1 -> shareFile(Exporter.csv(this, r), "text/csv")
                        else -> {
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Exporter.text(this, r))
                            startActivity(Intent.createChooser(i, "Share report"))
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not create the report", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareFile(f: java.io.File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND)
        i.type = mime
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.clipData = ClipData.newRawUri("", uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share report"))
        Toast.makeText(this, "This report contains health information. Share it carefully.", Toast.LENGTH_LONG).show()
    }

    // ----- Health tab: blood pressure and BMI -----
    private fun renderHealth() {
        if (Bp.on(this)) renderBp()
        if (Store.feature(this, "bmi", false)) renderBmi()
    }

    private fun fmtDT(t: Long) = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t))

    private fun renderBp() {
        val all = Bp.all(this)
        val c = card()
        c.addView(tv("Blood pressure", 16f, true, Color.parseColor("#B23B3B")))
        if (all.isEmpty()) {
            c.addView(tv("No readings yet.", 14f, false, gray))
        } else {
            val last = all.last()
            val (lab, col) = Bp.label(last.sys, last.dia)
            c.addView(tv("${last.sys}/${last.dia} mmHg" + if (last.pulse > 0) "   •   pulse ${last.pulse}" else "", 22f, true))
            c.addView(tv(lab, 15f, true, Color.parseColor(col)))
            c.addView(tv(fmtDT(last.time), 12f, false, gray))
            if (last.sys > 180 || last.dia > 120) {
                c.addView(tv("Rest for 5 minutes and measure again. If it stays this high, or you have chest pain, breathlessness, weakness, a severe headache or trouble speaking or seeing, get urgent medical help.", 13f, false, Color.parseColor("#8B1A1A")))
            } else if (last.sys < 90 || last.dia < 60) {
                c.addView(tv("If you feel dizzy or faint, talk to your doctor.", 13f, false, gray))
            }
            if (last.pulse > 0 && (last.pulse < 50 || last.pulse > 100)) {
                c.addView(tv("Pulse is outside the usual resting range of about 60-100.", 13f, false, gray))
            }
        }
        c.addView(button("+ Add reading") { showAddBp() })
        add(c, 8)

        if (all.size >= 2) {
            val chart = card()
            chart.addView(tv("Recent readings", 14f, true))
            chart.addView(BpChart(this, all.takeLast(20)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
            add(chart)
            val now = System.currentTimeMillis()
            val a = card()
            a.addView(tv("Averages", 14f, true))
            a.addView(tv("Last 7 days: " + Bp.avgText(all.filter { it.time >= now - 7 * 86400000L }), 14f))
            a.addView(tv("Last 30 days: " + Bp.avgText(all.filter { it.time >= now - 30 * 86400000L }), 14f))
            add(a)
        }
        if (all.isNotEmpty()) {
            val h = card()
            h.addView(tv("History (tap to delete)", 14f, true))
            for (r in all.reversed().take(10)) {
                val (_, col) = Bp.label(r.sys, r.dia)
                val line = tv(colored("● ", "${fmtDT(r.time)}   ${r.sys}/${r.dia}" + (if (r.pulse > 0) "   p ${r.pulse}" else "") + (if (r.note.isNotBlank()) "   ${r.note}" else ""), Color.parseColor(col)), 14f)
                line.setPadding(0, dp(6), 0, dp(6))
                line.setOnClickListener {
                    AlertDialog.Builder(this).setTitle("Delete this reading?")
                        .setMessage("${fmtDT(r.time)}   ${r.sys}/${r.dia}")
                        .setPositiveButton("Delete") { _, _ ->
                            Bp.remove(this, r.time)
                            render()
                        }.setNegativeButton("Cancel", null).show()
                }
                h.addView(line)
            }
            add(h)
        }
        add(tv("Ranges follow common adult guidance (AHA/ACC). Your doctor may set different targets for you. This log does not diagnose anything.", 12f, false, gray), 6)
    }

    private fun showAddBp() {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.setPadding(dp(20), dp(8), dp(20), 0)
        p.addView(tv("Sit quietly for 5 minutes before measuring.", 13f, false, gray))
        val s = numberField("Systolic (upper number)", "")
        val d = numberField("Diastolic (lower number)", "")
        val pu = numberField("Pulse (optional)", "")
        val n = EditText(this)
        n.hint = "Note (optional), e.g. after medicine"
        n.setSingleLine()
        p.addView(s)
        p.addView(d)
        p.addView(pu)
        p.addView(n)
        val dlg = AlertDialog.Builder(this).setTitle("Add BP reading").setView(p)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val sv = num(s)?.toInt()
            val dv = num(d)?.toInt()
            val pv = num(pu)?.toInt() ?: 0
            if (sv == null || dv == null || sv !in 50..300 || dv !in 30..200 || dv >= sv || (pv != 0 && pv !in 20..250)) {
                Toast.makeText(this, "Please check the numbers", Toast.LENGTH_SHORT).show()
            } else {
                Bp.add(this, BpReading(System.currentTimeMillis(), sv, dv, pv, n.text.toString().trim()))
                dlg.dismiss()
                render()
                if (sv > 180 || dv > 120) {
                    AlertDialog.Builder(this).setTitle("Very high reading")
                        .setMessage("Rest for 5 minutes and measure again. If it stays this high, or you have chest pain, breathlessness, weakness, a severe headache or trouble speaking or seeing, get urgent medical help.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun renderBmi() {
        val c = card()
        c.addView(tv("BMI and ideal weight", 16f, true, green))
        val h = numberField("Height (cm)", Store.height(this).let { if (it > 0) it.toInt().toString() else "" })
        val w = numberField("Weight (kg)", Store.weight(this).let { if (it > 0) String.format("%.1f", it.toDouble()) else "" }, true)
        val a = numberField("Age (years)", Store.age(this).let { if (it > 0) it.toString() else "" })
        c.addView(h)
        c.addView(w)
        c.addView(a)
        val sw = Switch(this)
        sw.text = "Use Asian cut-offs (recommended for India)"
        sw.isChecked = Bmi.asian(this)
        sw.setOnCheckedChangeListener { _, on ->
            Store.setFeature(this, "bmi_asian", on)
            render()
        }
        c.addView(sw)
        c.addView(button("Calculate and save") {
            val hv = num(h)
            val wv = num(w)
            val av = num(a)?.toInt() ?: 0
            if (hv == null || wv == null || hv !in 100.0..230.0 || wv !in 20.0..300.0 || (av != 0 && av !in 5..110)) {
                Toast.makeText(this, "Please check height, weight and age", Toast.LENGTH_SHORT).show()
            } else {
                Store.setBody(this, hv.toFloat(), wv.toFloat(), av)
                Store.addWeight(this, wv)
                render()
            }
        })
        add(c, 8)

        val hv = Store.height(this).toDouble()
        val wv = Store.weight(this).toDouble()
        if (hv <= 0 || wv <= 0) return
        val age = Store.age(this)
        val r = card()
        if (age in 1..17) {
            r.addView(tv("BMI for children and teens is judged on growth charts, not these cut-offs. Please ask your doctor.", 14f))
            add(r)
            return
        }
        val asian = Bmi.asian(this)
        val bmi = Bmi.value(wv, hv)
        val (lo, hi) = Bmi.range(hv, asian)
        r.addView(tv(String.format("BMI %.1f", bmi) + "  -  " + Bmi.category(bmi, asian), 20f, true))
        r.addView(tv(String.format("Healthy weight for your height: %.1f - %.1f kg", lo, hi), 15f))
        when {
            wv > hi -> {
                val over = wv - hi
                val wkMin = Math.ceil(over / 0.5).toInt()
                val wkMax = Math.ceil(over / 0.25).toInt()
                r.addView(tv(String.format("You are %.1f kg above the top of this range.", over), 15f, true, Color.parseColor("#C98A12")))
                r.addView(tv("A safe pace is about 0.25-0.5 kg a week, so roughly $wkMin-$wkMax weeks. Slow and steady lasts longer.", 14f))
                r.addView(tv("Ideas that help:", 14f, true))
                for (t in listOf(
                    "Smaller portions, eaten slowly. Stop when comfortably full.",
                    "Swap sugary drinks and sweets for water, buttermilk or fruit.",
                    "Fill half your plate with vegetables; add protein and fibre (dal, curd, eggs, sprouts).",
                    "Walk about 30 minutes on most days (150 minutes a week) and build up slowly.",
                    "Sleep 7-8 hours; poor sleep makes hunger harder to control.",
                    "Weigh yourself once a week, at the same time of day.",
                    "Avoid crash diets and fat-burner pills."
                )) r.addView(tv("•  $t", 14f))
            }
            wv < lo -> {
                r.addView(tv(String.format("You are %.1f kg below this range.", lo - wv), 15f, true, Color.parseColor("#2F6FDB")))
                r.addView(tv("Regular, nutritious meals matter. If this is not usual for you, or you are losing weight without trying, please talk to your doctor.", 14f))
            }
            else -> r.addView(tv("You are in the healthy range. Keep up your routine.", 15f, true, Color.parseColor("#1B8A5A")))
        }
        r.addView(tv(
            if (asian) "Asian cut-offs: healthy 18.5-22.9, overweight 23-24.9, obese 25 and above."
            else "International cut-offs: healthy 18.5-24.9, overweight 25-29.9, obese 30 and above.", 12f, false, gray
        ))
        val ws = Store.weights(this)
        if (ws.size >= 2) {
            r.addView(tv("Weight history", 14f, true))
            val f = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            for ((t, kg) in ws.takeLast(5).reversed()) r.addView(tv("${f.format(java.util.Date(t))}:  ${String.format("%.1f", kg)} kg", 14f))
        }
        r.addView(tv("BMI is only a rough guide. It can mislead for athletes, older adults, children and during pregnancy. If you have a medical condition or take regular medicines, check with your doctor before changing diet or exercise. This is not medical advice.", 12f, false, gray))
        add(r)
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
        nameCard.addView(button("Save name") {
            Store.setName(this, e.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            render()
        })
        add(nameCard, 8)

        val meals = Store.meals(this)
        val mc = card()
        mc.addView(tv("Meal times", 16f, true))
        mc.addView(tv("Breakfast ${fmt12(meals[0])}  •  Lunch ${fmt12(meals[1])}  •  Dinner ${fmt12(meals[2])}", 14f))
        mc.addView(button("Change meal times") { showMealTimes() })
        add(mc)

        // Optional features
        val fc = card()
        fc.addView(tv("Features (turn on only what you want)", 16f, true))
        fun feature(label: String, key: String, def: Boolean, after: () -> Unit = {}) {
            val sw = Switch(this)
            sw.text = label
            sw.isChecked = Store.feature(this, key, def)
            sw.setPadding(0, dp(8), 0, dp(8))
            sw.setOnCheckedChangeListener { _, on ->
                Store.setFeature(this, key, on)
                after()
                HealthAlarms.scheduleAll(this)
                render()
            }
            fc.addView(sw)
        }
        feature("Daily health quote", "quote", true)
        feature("Water reminders", "water", false)
        feature("Blood pressure tracker", "bp", false)
        feature("BMI and ideal weight", "bmi", false)
        feature("Step counter", "steps", false) {
            if (Store.feature(this, "steps", false) && !Steps.hasPermission(this))
                requestPermissions(arrayOf(android.Manifest.permission.ACTIVITY_RECOGNITION), 77)
        }
        feature("Check for app updates (uses internet)", "updatecheck", true)
        add(fc)

        if (Water.on(this)) addWaterSettings()
        if (Bp.on(this)) addBpSettings()
        if (Steps.on(this)) addStepSettings()

        val uc = card()
        uc.addView(tv("App version", 16f, true))
        uc.addView(tv("Installed: v${Updater.installedName(this)}", 14f))
        uc.addView(button("Check for updates now") {
            Toast.makeText(this, "Checking...", Toast.LENGTH_SHORT).show()
            Updater.check(this) { _, msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    render()
                }
            }
        })
        add(uc)

        val rc = card()
        rc.addView(tv("Reliability", 16f, true))
        rc.addView(tv("Make sure reminders ring on a locked phone.", 14f))
        rc.addView(button("Check settings") { showReliability() })
        add(rc)

        val ab = card()
        ab.addView(tv("About", 16f, true))
        ab.addView(tv("Medicine Reminder v${Updater.installedName(this)}", 13f, false, Color.GRAY))
        ab.addView(tv("Created by", 14f))
        ab.addView(tv("Faiz Anwar", 18f, true, green))
        ab.addView(tv("A reminder and personal log. Not medical advice; it does not replace your doctor or pharmacist. Your health data stays on this phone. The internet is used only to check for new versions, if you leave that switch on.", 12f, false, Color.GRAY))
        add(ab)
    }

    private fun addWaterSettings() {
        val c = card()
        c.addView(tv("Water settings", 16f, true, Color.parseColor("#2F6FDB")))
        val savedW = Store.weight(this)
        val weight = numberField("Weight (kg) - to suggest a goal", if (savedW > 0) String.format("%.0f", savedW.toDouble()) else "", true)
        val goal = numberField("Daily goal (ml)", Water.goal(this).toString())
        val glass = numberField("Glass size (ml) - 250 is common, or your own", Water.glass(this).toString())
        c.addView(tv("Your weight (kg)", 13f, true))
        c.addView(tv("Used only to suggest a daily goal.", 12f, false, gray))
        c.addView(weight)
        c.addView(button("Suggest goal from weight") {
            val kg = num(weight)
            if (kg != null && kg in 20.0..300.0) goal.setText((Math.round(kg * 33 / 50.0) * 50).toString())
            else Toast.makeText(this, "Enter your weight first", Toast.LENGTH_SHORT).show()
        })
        c.addView(tv("Daily goal (ml)", 13f, true))
        c.addView(tv("Total water you want to drink in a day. 1000 ml = 1 litre.", 12f, false, gray))
        c.addView(goal)
        c.addView(tv("Glass size (ml)", 13f, true))
        c.addView(tv("How much one tap on + adds. A normal glass is 250 ml.", 12f, false, gray))
        c.addView(glass)
        c.addView(tv("A common guide is about 30-35 ml per kg a day. Heat, exercise and illness raise it. If you have a kidney, heart or liver condition, or your doctor limits fluids, use your doctor's number.", 12f, false, gray))

        c.addView(tv("Reminder style", 14f, true))
        c.addView(tv("Every 2 hours: a fixed reminder. Spread: reminders sized to reach your goal between wake and sleep time.", 12f, false, gray))
        val rg = RadioGroup(this)
        val r0 = RadioButton(this)
        r0.text = "Every 2 hours"
        r0.id = 1001
        val r1 = RadioButton(this)
        r1.text = "Spread through the day to match my goal"
        r1.id = 1002
        rg.addView(r0)
        rg.addView(r1)
        rg.check(if (Water.mode(this) == 0) 1001 else 1002)
        c.addView(rg)

        var wake = Water.wake(this)
        var sleep = Water.sleep(this)
        val wb = Button(this)
        wb.isAllCaps = false
        wb.text = "Wake time: ${fmt12(wake)}"
        wb.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                wake = h * 60 + m
                wb.text = "Wake time: ${fmt12(wake)}"
            }, wake / 60, wake % 60, false).show()
        }
        val sb = Button(this)
        sb.isAllCaps = false
        sb.text = "Sleep time: ${fmt12(sleep)}"
        sb.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                sleep = h * 60 + m
                sb.text = "Sleep time: ${fmt12(sleep)}"
            }, sleep / 60, sleep % 60, false).show()
        }
        c.addView(tv("Wake-up and sleep time", 13f, true))
        c.addView(tv("Reminders only come between these two times. Keep at least 2 hours apart.", 12f, false, gray))
        c.addView(wb)
        c.addView(sb)
        c.addView(button("Save water settings") {
            val gv = num(goal)?.toInt()
            val sv = num(glass)?.toInt()
            if (gv == null || sv == null || gv !in 500..6000 || sv !in 50..1000 || sleep <= wake + 120) {
                Toast.makeText(this, "Please check the numbers and times", Toast.LENGTH_LONG).show()
            } else {
                Water.saveSettings(this, gv, sv, if (rg.checkedRadioButtonId == 1001) 0 else 1, wake, sleep)
                val kg = num(weight)
                if (kg != null && kg in 20.0..300.0) Store.setWeight(this, kg.toFloat())
                HealthAlarms.scheduleAll(this)
                Toast.makeText(this, "Saved. ${Water.slots(this).size} reminders a day.", Toast.LENGTH_LONG).show()
                render()
            }
        })
        add(c)
    }

    private fun addStepSettings() {
        val c = card()
        val g = Color.parseColor("#1F8A5B")
        c.addView(tv("Step settings", 16f, true, g))
        c.addView(tv("Daily step target (now ${String.format("%,d", Steps.target(this))})", 14f, true))
        c.addView(tv("Pick a target that suits you. Start low if you are not active now. Any steady increase is good.", 12f, false, gray))
        val row = LinearLayout(this)
        for (v in listOf(4000, 6000, 8000, 10000)) {
            row.addView(button("${v / 1000}k") {
                Steps.setTarget(this, v)
                HealthAlarms.scheduleAll(this)
                render()
            }.also { it.alpha = if (Steps.target(this) == v) 1f else 0.5f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        c.addView(row)
        val e = numberField("Or type your own target", "")
        c.addView(e)
        c.addView(button("Save my target") {
            val v = num(e)?.toInt()
            if (v == null || v !in 1000..30000) Toast.makeText(this, "Enter a number between 1,000 and 30,000", Toast.LENGTH_LONG).show()
            else {
                Steps.setTarget(this, v)
                render()
            }
        })
        val sw = Switch(this)
        sw.text = "Build up gradually"
        sw.isChecked = Steps.gradual(this)
        sw.setOnCheckedChangeListener { _, on ->
            Store.setFeature(this, "st_grad", on)
            render()
        }
        c.addView(sw)
        c.addView(tv("Today's goal is about 12% above your recent average, up to your target (now ${String.format("%,d", Steps.goal(this))}).", 12f, false, gray))
        val sw2 = Switch(this)
        sw2.text = "Evening nudge at 7 PM if the goal is not met"
        sw2.isChecked = Steps.nudge(this)
        sw2.setOnCheckedChangeListener { _, on ->
            Store.setFeature(this, "st_nudge", on)
            HealthAlarms.scheduleAll(this)
        }
        c.addView(sw2)
        c.addView(tv("Steps are counted by your phone's own sensor. Nothing is sent anywhere. If you have a health condition, ask your doctor what is right for you.", 12f, false, gray))
        add(c)
    }

    private fun addBpSettings() {
        val c = card()
        c.addView(tv("Blood pressure reminders", 16f, true, Color.parseColor("#B23B3B")))
        val sw = Switch(this)
        sw.text = "Remind me to measure BP"
        sw.isChecked = Bp.remind(this)
        sw.setOnCheckedChangeListener { _, on ->
            Store.setFeature(this, "bp_remind", on)
            HealthAlarms.scheduleAll(this)
        }
        c.addView(sw)
        for (i in 0..1) {
            val b = Button(this)
            b.isAllCaps = false
            b.text = "Reminder ${i + 1}: ${fmt12(Bp.time(this, i))}"
            b.setOnClickListener {
                val t = Bp.time(this, i)
                TimePickerDialog(this, { _, h, m ->
                    Bp.setTime(this, i, h * 60 + m)
                    HealthAlarms.scheduleAll(this)
                    b.text = "Reminder ${i + 1}: ${fmt12(h * 60 + m)}"
                }, t / 60, t % 60, false).show()
            }
            c.addView(b)
        }
        add(c)
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

    // ----- Add / edit medicine -----
    // Step 1: name, dose, meal rule, how many times a day, colour.
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

        // Colour: automatic, but you can pick another one.
        var sel = if (existing != null) Store.colorIndex(this, existing.name) else Store.suggestColor(this)
        var touched = false
        p.addView(tv("Colour", 13f, false, gray))
        val circles = mutableListOf<View>()
        fun paint() {
            circles.forEachIndexed { i, v ->
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(Palette.color(i))
                if (i == sel) g.setStroke(dp(3), ink)
                v.background = g
            }
        }
        var rowL: LinearLayout? = null
        for (i in 0 until Palette.size) {
            if (i % 5 == 0) {
                rowL = LinearLayout(this)
                rowL.orientation = LinearLayout.HORIZONTAL
                p.addView(rowL)
            }
            val v = View(this)
            val lp = LinearLayout.LayoutParams(dp(34), dp(34))
            lp.setMargins(0, dp(6), dp(10), dp(6))
            v.setOnClickListener {
                sel = i
                touched = true
                paint()
            }
            rowL!!.addView(v, lp)
            circles.add(v)
        }
        paint()

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
                showTimes(existing, n, dose.text.toString().trim(), meal.selectedItemPosition, freq.selectedItemPosition + 1, if (touched || existing != null) sel else -1)
            }
        }
    }

    // Step 2: suggested times, each one editable.
    private fun showTimes(existing: Med?, name: String, dose: String, meal: Int, freq: Int, color: Int) {
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
            } + "\nMedicines are kept at least ${Suggest.GAP} minutes apart.\nThese are only suggestions - follow your doctor's or pharmacist's instructions."
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
                if (color >= 0) Store.setColorIndex(this, name, color) else Store.colorIndex(this, name)
                Scheduler.schedule(this, med)
                HealthAlarms.scheduleAll(this)
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
