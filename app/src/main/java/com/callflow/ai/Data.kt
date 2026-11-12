package com.callflow.ai

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

val OUTCOMES = listOf(
    "Connected", "Not Answered", "Busy", "Switched Off", "Number Not Reachable", "Call Rejected",
    "Interested", "Not Interested", "Follow-up Required", "Wrong Number", "Other"
)

class Cust(val id: String, val ph: String, val name: String, val d: Map<String, String>, val batch: String = "", val amt: Double = 0.0) {
    /** Loan amount from the file: first column whose name has "loan" or "amount". */
    fun loan(): Double {
        if (amt > 0) return amt
        val k = d.keys.firstOrNull { Regex("loan|amount", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return 0.0
        return (d[k] ?: "").replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
    }
}
class Hist(val id: String, val cid: String, val n: Int, val oc: String, val rm: String, val fu: String, var done: Boolean, val dt: Long, var mt: Long = dt)
class Batch(val id: String, val name: String, val time: Long)
class Sess(var limit: Int, val queue: List<String>, var i: Int, var status: String, var stage: String)
class Row(val d: Map<String, String>, val ph: String, val st: String)
class Preview(val cols: List<String>, val raw: List<List<String>>, var pi: Int, var ni: Int, var li: Int) {
    fun labels(): List<String> = cols.mapIndexed { i, c -> c + "  (e.g. " + (raw.firstOrNull()?.getOrElse(i) { "" } ?: "").take(16) + ")" }
    fun amtOf(d: Map<String, String>): Double = if (li < 0) 0.0 else (d[cols[li]] ?: "").replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
    fun build(): List<Row> {
        val seen = HashSet<String>()
        Store.cust.forEach { seen.add(it.ph) }
        return raw.map { r ->
            val d = LinkedHashMap<String, String>()
            cols.forEachIndexed { i, c -> d[c] = r.getOrElse(i) { "" } }
            val ph = normPhone(r.getOrElse(pi) { "" })
            val st = if (ph == "") "invalid" else if (!seen.add(ph)) "duplicate" else "ok"
            Row(d, ph, st)
        }
    }
}

/** All app data is kept in one JSON file inside the app. Nothing leaves the phone. */
object Store {
    val cols = mutableListOf<String>()
    val cust = mutableListOf<Cust>()
    val hist = mutableListOf<Hist>()
    val calcs = mutableListOf<String>()
    val batches = mutableListOf<Batch>()
    var sess: Sess? = null
    var noRemark = false
    var pending = false
    var sawOff = false
    var autoCall = false

    fun remaining(): List<Cust> = cust.filter { c -> hist.none { it.cid == c.id } }
    fun attempts(id: String): List<Hist> = hist.filter { it.cid == id }
    fun byId(id: String): Cust? = cust.firstOrNull { it.id == id }
    fun inSel(sel: Int): List<Cust> = if (sel <= 0 || sel > batches.size) cust else cust.filter { it.batch == batches[sel - 1].id }
    fun dueFollowups(): List<Cust> {
        val t = today()
        return hist.filter { it.fu.isNotEmpty() && !it.done && it.fu <= t }.sortedBy { it.fu }.mapNotNull { byId(it.cid) }.distinctBy { it.id }
    }
    /** Resume: continue after the last customer already called, then go back to the ones missed. */
    fun queueFor(sel: Int, lim: Int): List<Cust> {
        if (sel > batches.size) return dueFollowups().take(lim)
        val l = inSel(sel)
        val last = l.indexOfLast { c -> hist.any { it.cid == c.id } }
        val un = { c: Cust -> hist.none { it.cid == c.id } }
        return (l.drop(last + 1).filter(un) + l.take(last + 1).filter(un)).take(lim)
    }
    private fun strs(a: JSONArray) = List(a.length()) { a.getString(it) }

    @Synchronized
    fun load(c: Context) {
        cols.clear(); cust.clear(); hist.clear(); calcs.clear(); batches.clear(); sess = null
        noRemark = false; pending = false; sawOff = false; autoCall = false
        val f = File(c.filesDir, "callflow.json")
        if (!f.exists()) return
        try {
            val j = JSONObject(f.readText())
            cols.addAll(strs(j.getJSONArray("cols")))
            val ca = j.getJSONArray("cust")
            for (i in 0 until ca.length()) {
                val o = ca.getJSONObject(i)
                val dj = o.getJSONObject("d")
                val d = LinkedHashMap<String, String>()
                val ks = dj.keys()
                while (ks.hasNext()) { val k = ks.next(); d[k] = dj.getString(k) }
                cust.add(Cust(o.getString("id"), o.getString("ph"), o.getString("name"), d, o.optString("batch", ""), o.optDouble("amt", 0.0)))
            }
            val ha = j.getJSONArray("hist")
            for (i in 0 until ha.length()) {
                val o = ha.getJSONObject(i)
                hist.add(Hist(o.getString("id"), o.getString("cid"), o.getInt("n"), o.getString("oc"), o.getString("rm"), o.getString("fu"), o.getBoolean("done"), o.getLong("dt"), o.optLong("mt", o.getLong("dt"))))
            }
            calcs.addAll(strs(j.getJSONArray("calcs")))
            val ba = j.optJSONArray("batches")
            if (ba != null) for (i in 0 until ba.length()) { val o = ba.getJSONObject(i); batches.add(Batch(o.getString("id"), o.getString("name"), o.getLong("time"))) }
            noRemark = j.optBoolean("noRemark"); pending = j.optBoolean("pending"); sawOff = j.optBoolean("sawOff"); autoCall = j.optBoolean("autoCall")
            val s = j.optJSONObject("sess")
            if (s != null) sess = Sess(s.getInt("limit"), strs(s.getJSONArray("queue")), s.getInt("i"), s.getString("status"), s.getString("stage"))
        } catch (e: Exception) { }
    }

    @Synchronized
    fun save(c: Context) {
        val j = JSONObject()
        j.put("cols", JSONArray(cols))
        val ca = JSONArray()
        for (x in cust) {
            val dj = JSONObject()
            for ((k, v) in x.d) dj.put(k, v)
            ca.put(JSONObject().put("id", x.id).put("ph", x.ph).put("name", x.name).put("d", dj).put("batch", x.batch).put("amt", x.amt))
        }
        j.put("cust", ca)
        val ha = JSONArray()
        for (h in hist) ha.put(JSONObject().put("id", h.id).put("cid", h.cid).put("n", h.n).put("oc", h.oc).put("rm", h.rm).put("fu", h.fu).put("done", h.done).put("dt", h.dt).put("mt", h.mt))
        j.put("hist", ha)
        val ba = JSONArray()
        for (b in batches) ba.put(JSONObject().put("id", b.id).put("name", b.name).put("time", b.time))
        j.put("batches", ba)
        j.put("calcs", JSONArray(calcs)).put("noRemark", noRemark).put("pending", pending).put("sawOff", sawOff).put("autoCall", autoCall)
        sess?.let { s -> j.put("sess", JSONObject().put("limit", s.limit).put("queue", JSONArray(s.queue)).put("i", s.i).put("status", s.status).put("stage", s.stage)) }
        val t = File(c.filesDir, "callflow.tmp")
        t.writeText(j.toString())
        t.renameTo(File(c.filesDir, "callflow.json"))
    }
}

fun normPhone(p: String): String {
    var d = p.trim().replace(Regex("\\.\\d+$"), "").filter { it.isDigit() }
    if (d.length == 12 && d.startsWith("91")) d = d.substring(2)
    if (d.length == 11 && d.startsWith("0")) d = d.substring(1)
    return if (Regex("^[6-9][0-9]{9}$").matches(d)) d else ""
}

fun parseCsv(t0: String): List<List<String>> {
    val t = t0.removePrefix("\uFEFF")
    val first = t.lineSequence().firstOrNull() ?: ""
    val delim = listOf(',', '|', '\t', ';').maxByOrNull { d -> first.count { it == d } } ?: ','
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val f = StringBuilder()
    var q = false
    var i = 0
    while (i < t.length) {
        val c = t[i]
        if (q) {
            if (c == '"') {
                if (i + 1 < t.length && t[i + 1] == '"') { f.append('"'); i++ } else q = false
            } else f.append(c)
        } else if (c == '"') q = true
        else if (c == delim) { row.add(f.toString()); f.setLength(0) }
        else if (c == '\n' || c == '\r') {
            if (c == '\r' && i + 1 < t.length && t[i + 1] == '\n') i++
            row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList()
        } else f.append(c)
        i++
    }
    if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
    return rows.filter { r -> r.any { it.isNotBlank() } }.map { r -> r.map { it.trim() } }
}

private fun pull(b: ByteArray): XmlPullParser {
    val p = Xml.newPullParser()
    p.setInput(ByteArrayInputStream(b), "UTF-8")
    return p
}
private fun num(v: String): String = try { BigDecimal(v).stripTrailingZeros().toPlainString() } catch (e: Exception) { v }

private fun readSst(b: ByteArray): List<String> {
    val p = pull(b)
    val out = ArrayList<String>()
    var sb = StringBuilder()
    var inT = false
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG) {
            if (p.name == "si") sb = StringBuilder()
            if (p.name == "t") inT = true
        } else if (ev == XmlPullParser.TEXT) {
            if (inT) sb.append(p.text)
        } else if (ev == XmlPullParser.END_TAG) {
            if (p.name == "t") inT = false
            if (p.name == "si") out.add(sb.toString())
        }
        ev = p.next()
    }
    return out
}

private fun readSheet(b: ByteArray, sst: List<String>): List<List<String>> {
    val p = pull(b)
    val rows = java.util.TreeMap<Int, MutableMap<Int, String>>()
    var ref = ""
    var ty = ""
    var v = StringBuilder()
    var inl = StringBuilder()
    var inV = false
    var inT = false
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG) {
            if (p.name == "c") {
                ref = p.getAttributeValue(null, "r") ?: ""
                ty = p.getAttributeValue(null, "t") ?: ""
                v = StringBuilder(); inl = StringBuilder()
            }
            if (p.name == "v") inV = true
            if (p.name == "t") inT = true
        } else if (ev == XmlPullParser.TEXT) {
            if (inV) v.append(p.text)
            if (inT) inl.append(p.text)
        } else if (ev == XmlPullParser.END_TAG) {
            if (p.name == "v") inV = false
            if (p.name == "t") inT = false
            if (p.name == "c") {
                val s = v.toString()
                val value = when (ty) {
                    "s" -> sst.getOrElse(s.toIntOrNull() ?: -1) { "" }
                    "inlineStr" -> inl.toString()
                    "str", "b", "e" -> s
                    else -> num(s)
                }
                val letters = ref.takeWhile { it.isLetter() }
                val rn = ref.drop(letters.length).toIntOrNull() ?: 0
                var ci = 0
                for (ch in letters) ci = ci * 26 + (ch.uppercaseChar() - 'A' + 1)
                ci -= 1
                if (value.isNotEmpty() && ci >= 0) rows.getOrPut(rn) { HashMap() }[ci] = value
            }
        }
        ev = p.next()
    }
    return rows.values.map { m -> val mx = m.keys.maxOrNull() ?: 0; (0..mx).map { m[it] ?: "" } }
}

/** Reads the first sheet of an .xlsx file (an xlsx is a zip of XML files). */
fun parseXlsx(bytes: ByteArray): List<List<String>> {
    var sstB: ByteArray? = null
    var shB: ByteArray? = null
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        var e = z.nextEntry
        while (e != null) {
            if (e.name == "xl/sharedStrings.xml") sstB = z.readBytes()
            else if (e.name.startsWith("xl/worksheets/sheet") && shB == null) shB = z.readBytes()
            e = z.nextEntry
        }
    }
    val sh = shB ?: return emptyList()
    val sst = sstB?.let { readSst(it) } ?: emptyList()
    return readSheet(sh, sst)
}

/** Finds the phone, name and loan columns. Works with or without a header row, and with phones written like "₹ 8,80,90,94,805.00". */
fun buildPreview(rows: List<List<String>>): Preview? {
    val width = rows.maxOf { it.size }
    val headless = rows[0].any { normPhone(it) != "" }
    val cols = List(width) { i -> if (headless) "Column ${i + 1}" else rows[0].getOrElse(i) { "" }.trim().ifEmpty { "Column ${i + 1}" } }
    val data = if (headless) rows else rows.drop(1)
    if (data.isEmpty()) return null
    fun valid(i: Int) = data.count { normPhone(it.getOrElse(i) { "" }) != "" }
    var pi = cols.indexOfFirst { Regex("phone|mobile|mob|contact|number", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    if (pi < 0 || valid(pi) * 2 < data.size) pi = cols.indices.maxByOrNull { valid(it) } ?: -1
    if (pi < 0 || valid(pi) == 0) return null
    fun alpha(i: Int) = data.sumOf { r -> val v = r.getOrElse(i) { "" }; if (v.any { it.isDigit() }) 0 else v.length }
    var ni = cols.indexOfFirst { it.contains("name", true) }
    if (ni < 0 || ni == pi) ni = cols.indices.filter { it != pi }.maxByOrNull { alpha(it) } ?: pi
    if (alpha(ni) == 0) ni = pi
    var li = cols.indices.firstOrNull { it != pi && Regex("loan|amount|amt", RegexOption.IGNORE_CASE).containsMatchIn(cols[it]) } ?: -1
    if (li < 0) li = cols.indices.firstOrNull { i ->
        i != pi && i != ni && data.count { Regex("^[₹ ]*[0-9,]{4,9}(\\.[0-9]+)?$").matches(it.getOrElse(i) { "" }.trim()) } * 2 >= data.size
    } ?: -1
    return Preview(cols, data, pi, ni, li)
}

fun toCsv(rows: List<List<String>>): String = "\uFEFF" + rows.joinToString("\r\n") { r ->
    r.joinToString(",") { x ->
        if (x.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + x.replace("\"", "\"\"") + "\"" else x
    }
}

fun fmtDt(t: Long): String = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", java.util.Locale.US).format(java.util.Date(t))

fun dayOf(t: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(t))
fun today(): String = dayOf(System.currentTimeMillis())

val SUGGEST = listOf(
    "Interested", "N/I", "RNR", "Busy", "Call back", "Follow-up",
    "Incoming issue", "Switched off", "Not reachable", "Wrong number", "Documents pending", "Will visit branch"
)

fun customersRows(): List<List<String>> {
    val head = Store.cols + listOf("remarks", "call_datetime", "follow_up_date", "attempt_number")
    return listOf(head) + Store.cust.map { c ->
        val a = Store.attempts(c.id)
        val base = Store.cols.map { c.d[it] ?: "" }
        val l = a.lastOrNull()
        if (l == null) base + listOf("", "", "", "0")
        else base + listOf(a.map { it.rm }.filter { it.isNotEmpty() }.joinToString(" | "), fmtDt(l.dt), l.fu, a.size.toString())
    }
}
fun customersCsv(): String = toCsv(customersRows())

fun historyRows(): List<List<String>> {
    val head = listOf("customer", "phone", "attempt", "call_datetime", "remarks", "follow_up_date", "follow_up_status")
    return listOf(head) + Store.hist.map { h ->
        val c = Store.byId(h.cid)
        listOf(c?.name ?: "", c?.ph ?: "", h.n.toString(), fmtDt(h.dt), h.rm, h.fu, if (h.fu.isEmpty()) "" else if (h.done) "Done" else "Pending")
    }
}

fun followupRows(): List<List<String>> {
    val head = listOf("customer", "phone", "follow_up_date", "status", "remarks")
    return listOf(head) + Store.hist.filter { it.fu.isNotEmpty() }.sortedBy { it.fu }.map { h ->
        val c = Store.byId(h.cid)
        listOf(c?.name ?: "", c?.ph ?: "", h.fu, if (h.done) "Done" else "Pending", h.rm)
    }
}

class MyStats(val total: Int, val customers: Int, val today: Int, val last7: Int, val fuSet: Int, val fuPending: Int, val lastCall: Long, val daily: List<Pair<String, Int>>)

fun myStats(): MyStats {
    val a = Store.hist
    val t = today()
    val daily = (6 downTo 0).map { i -> val d = dayOf(System.currentTimeMillis() - i * 86400000L); d to a.count { dayOf(it.dt) == d } }
    return MyStats(a.size, a.map { it.cid }.toSet().size, a.count { dayOf(it.dt) == t }, daily.sumOf { it.second },
        a.count { it.fu.isNotEmpty() }, a.count { it.fu.isNotEmpty() && !it.done }, a.maxOfOrNull { it.dt } ?: 0L, daily)
}

fun performanceRows(): List<List<String>> {
    val m = myStats()
    return listOf(
        listOf("Metric", "Value"),
        listOf("Total calls", m.total.toString()), listOf("Customers called", m.customers.toString()),
        listOf("Calls today", m.today.toString()), listOf("Calls last 7 days", m.last7.toString()),
        listOf("Follow-ups set", m.fuSet.toString()), listOf("Follow-ups pending", m.fuPending.toString()),
        listOf("Last call", if (m.lastCall > 0) fmtDt(m.lastCall) else ""),
        listOf("", ""), listOf("Date", "Calls")
    ) + m.daily.map { listOf(it.first, it.second.toString()) }
}

/** Writes a simple .xlsx file (one sheet, all cells as text, bold header). Opens in Excel and Google Sheets. */
fun toXlsx(rows: List<List<String>>, sheet: String): ByteArray {
    fun esc(s: String) = s.filter { it.code >= 32 || it == '\n' || it == '\t' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun col(i: Int): String {
        var s = ""
        var n = i + 1
        while (n > 0) { val m = (n - 1) % 26; s = ('A' + m) + s; n = (n - 1) / 26 }
        return s
    }
    val ncol = maxOf(1, rows.maxOfOrNull { it.size } ?: 1)
    val cols = (1..ncol).joinToString("") { """<col min="$it" max="$it" width="22" customWidth="1"/>""" }
    val body = rows.mapIndexed { ri, r ->
        "<row r=\"${ri + 1}\">" + r.mapIndexed { ci, v ->
            "<c r=\"${col(ci)}${ri + 1}\"" + (if (ri == 0) " s=\"1\"" else "") + " t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
        }.joinToString("") + "</row>"
    }.joinToString("")
    val name = esc(sheet.filter { it !in "\\/?*[]:" }.take(31).ifEmpty { "Sheet1" })
    val x = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        fun put(n: String, t: String) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        put("[Content_Types].xml", x + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        put("_rels/.rels", x + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", x + """<workbook xmlns="$ns" xmlns:r="$rel"><sheets><sheet name="$name" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        put("xl/_rels/workbook.xml.rels", x + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$rel/styles" Target="styles.xml"/></Relationships>""")
        put("xl/styles.xml", x + """<styleSheet xmlns="$ns"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        put("xl/worksheets/sheet1.xml", x + """<worksheet xmlns="$ns"><cols>$cols</cols><sheetData>$body</sheetData></worksheet>""")
    }
    return bos.toByteArray()
}
