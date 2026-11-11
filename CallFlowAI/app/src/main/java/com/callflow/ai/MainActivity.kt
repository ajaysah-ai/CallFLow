package com.callflow.ai

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

class PieView(c: Context, private val a: Double, private val b: Double, private val f: Double) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val total = a + b + f
        if (total <= 0) return
        val size = minOf(width, height).toFloat() - 16f
        val left = (width - size) / 2f
        val rect = RectF(left, 8f, left + size, 8f + size)
        val colors = intArrayOf(0xFFC6F432.toInt(), 0xFFFFD60A.toInt(), 0xFF2ECC71.toInt())
        var start = -90f
        listOf(a, b, f).forEachIndexed { i, v ->
            val sweep = (v / total * 360).toFloat()
            paint.color = colors[i]
            cv.drawArc(rect, start, sweep, true, paint)
            start += sweep
        }
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var tabs: LinearLayout
    private val names = listOf("Home", "Call", "EMI", "More")
    private var tab = 0
    private var preview: Preview? = null
    private var exportKind = "cust"
    private var limText = "25"
    private var selSrc = 0
    private var importName = "Imported file"
    private var remEt: TextInputEditText? = null
    private var eSched: List<DoubleArray> = emptyList()
    private var dRm = ""
    private var dFu = ""
    private var eAmt = "71500"
    private var eRate = "30.5"
    private var ePer = "12"
    private var eEmi = ""
    private var eFee = "4.13"
    private var eYears = false
    private var eSolve = 3
    private var eNote = "Default: loan ₹65,000 + 10% (₹6,500) = ₹71,500"
    private var eMsg = ""
    private var eRes: DoubleArray? = null
    private var actCode = ""
    private var actMsg = ""
    private var lastTry = 0L
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var cd = -1
    private var cdText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readImport(uri) }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) writeExport(uri) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
        scroll.addView(body)
        tabs = LinearLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        unregisterNet()
        cancelCountdown()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Store.load(this)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(7)
        if (intent.getBooleanExtra("ended", false)) {
            intent.removeExtra("ended")
            tab = 1
        }
        registerNet()
        runCheck(false)
        render()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        render()
    }

    /* ---------- small helpers ---------- */
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun inr(x: Double): String = "₹ " + NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }.format(x)
    private fun fmtNum(x: Double): String = if (x == floor(x)) x.toLong().toString() else String.format(Locale.US, "%.2f", x)

    private fun tv(s: String, size: Float = 16f, bold: Boolean = false, color: Int? = null): TextView = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (color != null) setTextColor(color)
    }

    private fun LinearLayout.put(v: View, top: Int = 8) {
        addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    private fun section(title: String? = null): LinearLayout {
        val card = MaterialCardView(this)
        card.setCardBackgroundColor(0xFF1A1F14.toInt())
        card.strokeColor = 0xFF3A4A1A.toInt()
        card.strokeWidth = dp(1)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        card.addView(inner)
        body.put(card, 12)
        if (title != null) inner.put(tv(title, 18f, true, 0xFFC6F432.toInt()), 0)
        return inner
    }

    private fun btn(label: String, primary: Boolean = true, f: () -> Unit): MaterialButton {
        val b = if (primary) MaterialButton(this) else MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { f() }
        return b
    }

    private fun addRow(parent: LinearLayout, vararg bs: MaterialButton) {
        val r = LinearLayout(this)
        for (b in bs) r.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        parent.put(r, 8)
    }

    private fun field(label: String, value: String, numeric: Boolean, onChange: (String) -> Unit): TextInputLayout {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = label
        val et = TextInputEditText(til.context)
        if (numeric) et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        et.setText(value)
        et.doAfterTextChanged { onChange(it.toString()) }
        til.addView(et)
        return til
    }

    /* ---------- screens ---------- */
    /* ---------- subscription code ---------- */
    private fun licenseOk(): Boolean {
        if (!Config.LICENSE_ENABLED) return true
        val st = License.state(this)
        if (st == "ok") return true
        body.removeAllViews()
        tabs.removeAllViews()
        if (st == "activate") {
            val c = section("Activate CallFlow AI")
            c.put(tv("Enter your unique code to use the app.", 14f), 6)
            c.put(field("Unique code", actCode, false) { actCode = it }, 12)
            c.put(btn("Activate") { activate() }, 12)
            if (actMsg.isNotEmpty()) c.put(tv(actMsg, 14f, true, 0xFFFF6B5E.toInt()), 12)
        } else {
            val c = section("Subscription closed")
            c.put(tv(License.lockMessage(this, st), 16f), 8)
            c.put(btn("Check again", false) { toast("Checking..."); runCheck(true) }, 14)
            c.put(btn("Use a different code", false) { License.clear(this); actMsg = ""; render() })
        }
        val ft = tv("© 2026 CallFlow AI · All rights reserved by Ajay Sah", 12f, false, 0xFF8A9A5B.toInt())
        ft.gravity = Gravity.CENTER
        body.put(ft, 20)
        return false
    }

    private fun activate() {
        val code = actCode.trim().uppercase()
        if (code.isEmpty()) { toast("Enter your code"); return }
        actMsg = "Checking..."
        render()
        License.check(this, code, true) { res ->
            actMsg = if (res == null) "Could not reach the server. Try again." else if (res == "running") "" else License.lastMsg.ifEmpty { "This code is closed." }
            render()
        }
    }

    private fun runCheck(force: Boolean) {
        if (!Config.LICENSE_ENABLED) return
        val code = License.code(this)
        if (code.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastTry < 3600_000L) return
        lastTry = now
        val before = License.state(this)
        License.check(this, code, false) { if (License.state(this) != before) render() }
    }

    private fun registerNet() {
        if (!Config.LICENSE_ENABLED || netCb != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runOnUiThread { runCheck(true) } }
        }
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) { }
    }

    private fun unregisterNet() {
        val cb = netCb ?: return
        try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (e: Exception) { }
        netCb = null
    }

    private fun render() {
        if (!licenseOk()) return
        body.removeAllViews()
        tabs.removeAllViews()
        names.forEachIndexed { i, n ->
            val b = if (i == tab) MaterialButton(this) else MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.text = n
            b.isAllCaps = false
            b.setOnClickListener { tab = i; render() }
            tabs.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        when (tab) {
            0 -> home()
            1 -> callTab()
            2 -> emiTab()
            else -> moreTab()
        }
        val ft = tv("© 2026 CallFlow AI · All rights reserved by Ajay Sah", 12f, false, 0xFF8A9A5B.toInt())
        ft.gravity = Gravity.CENTER
        body.put(ft, 20)
        scroll.scrollTo(0, 0)
    }

    private fun home() {
        val c = section("CallFlow AI")
        if (Config.LICENSE_ENABLED && License.message(this).isNotEmpty()) c.put(tv(License.message(this), 14f, true, 0xFFFFD60A.toInt()), 0)
        val total = Store.cust.size
        val rem = Store.remaining().size
        val items = listOf(
            "Total" to total, "Called" to (total - rem), "Remaining" to rem,
            "Calls made" to Store.hist.size,
            "Calls today" to Store.hist.count { dayOf(it.dt) == today() },
            "Follow-ups" to Store.hist.count { it.fu.isNotEmpty() && !it.done }
        )
        items.chunked(3).forEach { r ->
            val row = LinearLayout(this)
            r.forEach { p ->
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
                box.addView(tv(p.second.toString(), 24f, true))
                box.addView(tv(p.first, 12f))
                row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
            }
            c.put(row, 4)
        }

        val s = Store.sess
        val sc = section("Calling session")
        if (s != null && s.status != "done") {
            sc.put(tv("${s.i} of ${s.limit} calls done" + if (s.status == "paused") " (paused)" else ""), 4)
            sc.put(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = s.limit; progress = s.i }, 8)
            sc.put(btn("Go to call screen") { tab = 1; render() }, 10)
            sc.put(btn("Stop session", false) { stopSession() })
        } else if (total == 0) {
            sc.put(tv("Import a customer file first.", 14f), 4)
        } else {
            val opts = mutableListOf("All lists ($rem left)")
            Store.batches.forEachIndexed { i, b ->
                opts.add("${b.name} (${Store.inSel(i + 1).count { x -> Store.hist.none { it.cid == x.id } }} left)")
            }
            opts.add("Follow-ups due (${Store.dueFollowups().size})")
            if (selSrc >= opts.size) selSrc = 0
            sc.put(tv("Which list to call?", 14f), 0)
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts)
            sp.setSelection(selSrc)
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (pos != selSrc) { selSrc = pos; render() } }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            sc.put(sp, 4)
            val q = Store.queueFor(selSrc, 100000)
            if (q.isEmpty()) {
                sc.put(tv("Nothing left to call in this list.", 14f), 8)
            } else {
                sc.put(tv("Starts from: ${q[0].name} (${q[0].ph})", 14f, true), 8)
                sc.put(field("How many customers to call now? (${q.size} available)", limText, true) { limText = it }, 8)
                sc.put(btn("Start calling") { startSession() }, 10)
            }
        }

        val im = section("Customer file")
        im.put(tv("CSV or Excel (.xlsx) with a phone column. The loan amount column is used in the EMI tab.", 14f), 0)
        im.put(btn("Choose file") { pickFile.launch(arrayOf("*/*")) }, 10)
        preview?.let { p ->
            val rws = p.build()
            val ok = rws.count { it.st == "ok" }
            val bad = rws.count { it.st == "invalid" }
            val dup = rws.count { it.st == "duplicate" }
            im.put(tv("File: $importName\nReady: $ok   Invalid: $bad   Duplicate: $dup", 15f, true), 12)
            im.put(tv("Check the columns. Change them if they are wrong:", 13f), 8)
            pickCol(im, "Phone column", p.labels(), p.pi) { p.pi = it; render() }
            pickCol(im, "Name column", p.labels(), p.ni) { p.ni = it; render() }
            pickCol(im, "Loan amount column", listOf("None") + p.labels(), p.li + 1) { p.li = it - 1; render() }
            rws.take(5).forEach { r ->
                val loan = if (p.li >= 0) " · loan " + (r.d[p.cols[p.li]] ?: "") else ""
                im.put(tv((r.d[p.cols[p.ni]] ?: "") + " · " + (if (r.ph.isEmpty()) "invalid" else r.ph) + loan + " · " + r.st, 13f), 2)
            }
            im.put(btn("Save $ok customers") { saveImport(p) }, 10)
            im.put(btn("Cancel", false) { preview = null; render() })
        }

        val pm = section("Auto-reopen after call")
        val hasPhone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasNotif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val hasOver = Settings.canDrawOverlays(this)
        val hasCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        pm.put(tv("Allow all four. They let the app place calls and come back when a call ends:", 14f), 0)
        pm.put(btn((if (hasPhone) "✓ " else "") + "Allow phone state", false) { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 1) }, 10)
        pm.put(btn((if (hasNotif) "✓ " else "") + "Allow notifications", false) { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) })
        pm.put(btn((if (hasOver) "✓ " else "") + "Allow display over other apps", false) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        pm.put(btn((if (hasCall) "✓ " else "") + "Allow calling", false) { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 3) })
        pm.put(tv("On Realme also set: Settings > Battery > this app > Unrestricted, and allow Auto-start.", 13f), 8)

        val cb = CheckBox(this).apply {
            text = "Allow saving a call with no remark"
            isChecked = Store.noRemark
            setOnCheckedChangeListener { _, v -> Store.noRemark = v; Store.save(this@MainActivity) }
        }
        val ac = CheckBox(this).apply {
            text = "Auto-call the next customer (5 second countdown, you can pause or end)"
            isChecked = Store.autoCall
            setOnCheckedChangeListener { _, v -> Store.autoCall = v; Store.save(this@MainActivity) }
        }
        val sx = section()
        sx.put(cb, 0)
        sx.put(ac, 8)
    }

    private fun pickCol(parent: LinearLayout, label: String, opts: List<String>, sel: Int, onPick: (Int) -> Unit) {
        parent.put(tv(label, 13f), 8)
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, opts)
        sp.setSelection(sel.coerceIn(0, opts.size - 1))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (pos != sel) onPick(pos) }
            override fun onNothingSelected(a: AdapterView<*>?) {}
        }
        parent.put(sp, 2)
    }

    private fun readImport(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val isZip = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            val rows = if (isZip) parseXlsx(bytes) else parseCsv(String(bytes, Charsets.UTF_8))
            if (rows.size < 2) { toast("The file has no customer rows"); return }
            importName = try {
                contentResolver.query(uri, null, null, null, null)?.use { cu -> if (cu.moveToFirst()) cu.getString(cu.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }
            } catch (e: Exception) { null } ?: "Imported file"
            preview = buildPreview(rows)
            if (preview == null) toast("Could not find a phone number column")
            tab = 0
            render()
        } catch (e: Exception) {
            toast("Could not read this file")
        }
    }

    private fun saveImport(p: Preview) {
        val nameCol = p.cols[p.ni]
        val b = Batch(UUID.randomUUID().toString(), importName, System.currentTimeMillis())
        Store.batches.add(b)
        p.cols.forEach { if (it !in Store.cols) Store.cols.add(it) }
        p.build().filter { it.st == "ok" }.forEach { r ->
            Store.cust.add(Cust(UUID.randomUUID().toString(), r.ph, (r.d[nameCol] ?: "").ifEmpty { r.ph }, r.d, b.id, p.amtOf(r.d)))
        }
        selSrc = Store.batches.size
        Store.save(this)
        preview = null
        toast("Customers saved")
        render()
    }

    private fun startSession() {
        val rem = Store.queueFor(selSrc, 100000)
        val lim = (limText.toIntOrNull() ?: 0).coerceAtMost(rem.size)
        if (lim < 1) { toast("Enter 1 or more calls"); return }
        Store.sess = Sess(lim, rem.take(lim).map { it.id }, 0, "active", "call")
        Store.save(this)
        tab = 1
        if (Store.autoCall) startCountdown()
        render()
    }

    private fun stopSession() {
        AlertDialog.Builder(this).setMessage("End calling now? Saved remarks stay. A remark you have not saved will be lost.")
            .setPositiveButton("End calls") { _, _ -> Store.sess = null; Store.pending = false; Store.save(this); tab = 0; render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun skip() {
        val s = Store.sess ?: return
        s.i++
        s.stage = "call"
        if (s.i >= s.queue.size) s.status = "done"
        Store.save(this)
        render()
    }

    private fun startCountdown() {
        handler.removeCallbacksAndMessages(null)
        cd = 5
        handler.postDelayed(object : Runnable {
            override fun run() {
                cd--
                if (cd <= 0) {
                    cd = -1
                    val s = Store.sess
                    val c = if (s != null && s.status == "active" && s.stage == "call" && s.i < s.queue.size) Store.byId(s.queue[s.i]) else null
                    if (c != null) callNow(c, true) else render()
                } else {
                    cdText?.text = "Calling in $cd seconds..."
                    handler.postDelayed(this, 1000)
                }
            }
        }, 1000)
    }

    private fun cancelCountdown() {
        handler.removeCallbacksAndMessages(null)
        cd = -1
    }

    /** direct = true places the call itself (needs the calling permission). Otherwise it only opens the dialer. */
    private fun callNow(c: Cust, direct: Boolean = false) {
        if (!License.allowed(this)) { render(); return }
        val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (direct && !canCall) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 3)
            toast("Allow calling, then tap Open dialer")
            render()
            return
        }
        Store.pending = true
        Store.sawOff = false
        Store.save(this)
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        try { startActivity(Intent(action, Uri.parse("tel:" + c.ph))) } catch (e: Exception) { toast("Could not start the call") }
    }

    private fun callTab() {
        val s = Store.sess
        if (s == null) { section("Call").put(tv("No session yet. Start one from Home."), 6); return }
        if (s.status == "done" || s.i >= s.queue.size) {
            val c = section("Session finished")
            c.put(tv("You completed ${s.i} calls."), 6)
            c.put(btn("Export data") { Store.sess = null; Store.save(this); tab = 3; render() }, 12)
            c.put(btn("Back to Home", false) { Store.sess = null; Store.save(this); tab = 0; render() })
            return
        }
        val pr = section("Call ${s.i + 1} of ${s.limit}")
        pr.put(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = s.limit; progress = s.i }, 8)
        if (s.status == "paused") {
            pr.put(tv("Session paused."), 8)
            pr.put(btn("Resume") { s.status = "active"; Store.save(this); if (Store.autoCall && s.stage == "call") startCountdown(); render() }, 10)
            pr.put(btn("Stop session", false) { stopSession() })
            return
        }
        val cu = Store.byId(s.queue[s.i])
        if (cu == null) { pr.put(btn("Skip") { skip() }, 10); return }

        val cc = section()
        cc.put(tv(cu.name, 22f, true), 0)
        cc.put(tv(cu.ph, 30f, true), 4)
        Store.cols.forEach { k -> val v = cu.d[k]; if (!v.isNullOrEmpty() && normPhone(v) != cu.ph) cc.put(tv("$k: $v", 15f), 2) }
        val ln = cu.loan()
        if (ln > 0) cc.put(tv("Loan ${inr(ln)} + 10% = ${inr(ln * 1.1)}", 15f, true), 8)
        val past = Store.attempts(cu.id)
        if (past.isNotEmpty()) cc.put(tv("Earlier attempts: ${past.size}", 13f), 6)
        past.lastOrNull()?.let { if (it.rm.isNotEmpty()) cc.put(tv("Last remark: ${it.rm}", 13f), 4) }

        if (s.stage == "call" && cd >= 0) {
            val w = section("Next call")
            val t = tv("Calling in $cd seconds...", 20f, true)
            cdText = t
            w.put(t, 6)
            w.put(btn("Pause calls") { cancelCountdown(); s.status = "paused"; Store.save(this); render() }, 10)
            w.put(btn("Call now", false) { cancelCountdown(); callNow(cu, true) })
            w.put(btn("End calls", false) { cancelCountdown(); stopSession() })
        }
        if (s.stage == "call") {
            val a = section()
            a.put(btn("Open dialer") { callNow(cu) }.apply { textSize = 20f; minHeight = dp(64) }, 0)
            a.put(btn("Call finished", false) { Store.pending = false; s.stage = "remark"; Store.save(this); render() }, 10)
            if (ln > 0) a.put(btn("Check EMI for this customer", false) {
                eAmt = (ln * 1.1).roundToLong().toString()
                eSolve = 3; eRes = null; eMsg = ""
                eNote = "${cu.name}: loan ${inr(ln)} + 10% = ${inr(ln * 1.1)}"
                tab = 2; render()
            })
            addRow(a,
                btn("Pause", false) { s.status = "paused"; Store.save(this); render() },
                btn("Skip", false) { skip() },
                btn("Stop", false) { stopSession() })
        } else {
            val f = section("Call remark")
            f.put(tv("${cu.name} · ${cu.ph}", 15f), 0)
            f.put(tv("Tap to add a remark, or type your own:", 13f), 10)
            val rm = field("Remark", dRm, false) { dRm = it }
            rm.editText?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            rm.editText?.minLines = 3
            remEt = rm.editText as? TextInputEditText
            val have = dRm.split(",").map { it.trim() }
            val cg = ChipGroup(this)
            SUGGEST.forEach { ph ->
                val ch = Chip(this)
                ch.text = ph
                ch.isCheckable = true
                ch.isChecked = have.any { it.equals(ph, true) }
                ch.setOnCheckedChangeListener { _, on -> toggleRemark(ph, on) }
                cg.addView(ch)
            }
            f.put(cg, 4)
            f.put(rm, 6)
            f.put(tv("Follow-up (optional):", 13f), 10)
            val fg = ChipGroup(this)
            listOf("Tomorrow" to 1, "In 3 days" to 3, "In 1 week" to 7).forEach { (l, d) ->
                val ch = Chip(this)
                ch.text = l
                ch.setOnClickListener { dFu = daysFromNow(d); render() }
                fg.addView(ch)
            }
            val none = Chip(this)
            none.text = "None"
            none.setOnClickListener { dFu = ""; render() }
            fg.addView(none)
            f.put(fg, 4)
            val fu = field("Follow-up date", dFu, false) { }
            fu.editText?.isFocusable = false
            fu.editText?.setOnClickListener { pickDate() }
            f.put(fu, 6)
            f.put(btn("Save and next customer") { saveRemark() }, 12)
            addRow(f,
                btn("Pause calls", false) { s.status = "paused"; Store.save(this); render() },
                btn("End calls", false) { stopSession() })
            f.put(btn("Save and end calls", false) { saveRemark(true) })
            f.put(btn("Back", false) { s.stage = "call"; Store.save(this); render() })
        }
    }

    private fun toggleRemark(ph: String, on: Boolean) {
        val et = remEt ?: return
        val parts = (et.text?.toString() ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.equals(ph, true) }.toMutableList()
        if (on) parts.add(ph)
        et.setText(parts.joinToString(", "))
        et.setSelection(et.text?.length ?: 0)
    }

    private fun daysFromNow(n: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, n)
        return dayOf(c.timeInMillis)
    }

    private fun pickDate() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d -> dFu = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d); render() },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun saveRemark(endAfter: Boolean = false) {
        val s = Store.sess ?: return
        val c = Store.byId(s.queue[s.i]) ?: return
        if (dRm.isBlank() && !Store.noRemark) { toast("Write or tap a remark first"); return }
        Store.hist.filter { it.cid == c.id && it.fu.isNotEmpty() && !it.done }.forEach { it.done = true }
        Store.hist.add(Hist(UUID.randomUUID().toString(), c.id, Store.attempts(c.id).size + 1, "", dRm.trim(), dFu, false, System.currentTimeMillis()))
        s.i++
        s.stage = "call"
        if (endAfter || s.i >= s.queue.size) s.status = "done"
        dRm = ""; dFu = ""
        Store.save(this)
        if (Store.autoCall && s.status == "active") startCountdown()
        render()
    }

    /* ---------- EMI ---------- */
    private fun rs(x: Double): String =
        if (kotlin.math.abs(x) < 0.5) "₹0" else "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }.format(x)

    private fun emiRow(c: LinearLayout, idx: Int, label: String, v: String, set: (String) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val rb = RadioButton(this).apply { isChecked = eSolve == idx; setOnClickListener { eSolve = idx; eMsg = ""; render() } }
        val f = field(if (eSolve == idx) "$label - result" else label, v, true, set)
        if (eSolve == idx) f.isEnabled = false
        row.addView(rb, LinearLayout.LayoutParams(-2, -2))
        row.addView(f, LinearLayout.LayoutParams(0, -2, 1f))
        c.put(row, 10)
    }

    private fun schedRow(a: String, b: String, c: String, d: String, head: Boolean): LinearLayout {
        val row = LinearLayout(this)
        val w = floatArrayOf(0.7f, 1.2f, 1.1f, 1.3f)
        listOf(a, b, c, d).forEachIndexed { i, t ->
            val x = tv(t, 12f, head, if (head) 0xFFC6F432.toInt() else null)
            if (i > 0) x.gravity = Gravity.END
            row.addView(x, LinearLayout.LayoutParams(0, -2, w[i]))
        }
        return row
    }

    private fun emiTab() {
        val c = section("EMI Calculator")
        if (eNote.isNotEmpty()) c.put(tv(eNote, 14f, true, 0xFFFFD60A.toInt()), 0)
        c.put(tv("Total loan = loan amount + 10%. Interest is on the reducing balance.", 13f), 4)
        c.put(tv("Tap the circle of the box you want to find. Fill the other boxes.", 13f), 4)
        emiRow(c, 0, "Amount (₹)", eAmt) { eAmt = it }
        emiRow(c, 1, "Interest (% a year)", eRate) { eRate = it }
        emiRow(c, 2, if (eYears) "Period (years)" else "Period (months)", ePer) { ePer = it }
        c.put(btn(if (eYears) "Period is in years - tap for months" else "Period is in months - tap for years", false) { eYears = !eYears; render() }, 4)
        emiRow(c, 3, "EMI (₹ a month)", eEmi) { eEmi = it }
        c.put(field("Processing fee (%)", eFee, true) { eFee = it }, 10)
        addRow(c,
            btn("Reset", false) {
                eAmt = "71500"; eRate = "30.5"; ePer = "12"; eEmi = ""; eFee = "4.13"; eYears = false; eSolve = 3
                eNote = "Default: loan ₹65,000 + 10% (₹6,500) = ₹71,500"; eMsg = ""; eRes = null; eSched = emptyList(); render()
            },
            btn("Calculate") { calcEmi() })
        if (eMsg.isNotEmpty()) c.put(tv(eMsg, 14f, true, 0xFFFF6B5E.toInt()), 10)

        val r = eRes
        if (r != null) {
            val rc = section("Result")
            listOf("Monthly EMI" to r[0], "Total interest" to r[1], "Processing fee" to r[2], "Total payment" to r[3]).forEach { p ->
                val row = LinearLayout(this)
                row.addView(tv(p.first, 16f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(tv(rs(p.second), 16f, true))
                rc.put(row, 8)
            }
            rc.addView(PieView(this, r[4], r[1], r[2]), LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(12) })
            val t = r[3]
            rc.put(tv("● Principal ${String.format(Locale.US, "%.1f", r[4] / t * 100)}%", 14f, false, 0xFFC6F432.toInt()), 4)
            rc.put(tv("● Interest ${String.format(Locale.US, "%.1f", r[1] / t * 100)}%", 14f, false, 0xFFFFD60A.toInt()), 2)
            rc.put(tv("● Fee ${String.format(Locale.US, "%.1f", r[2] / t * 100)}%", 14f, false, 0xFF2ECC71.toInt()), 2)
        }
        if (eSched.isNotEmpty()) {
            val sc = section("EMI schedule (${eSched.size} months)")
            sc.put(schedRow("Month", "Principal", "Interest", "Balance", true), 6)
            eSched.forEachIndexed { i, r2 -> sc.put(schedRow("${i + 1}", rs(r2[0]), rs(r2[1]), rs(r2[2]), false), 3) }
        }
        section().put(btn("Past calculations", false) { showCalcs() }, 0)
    }

    private fun showCalcs() {
        AlertDialog.Builder(this).setTitle("Past calculations")
            .setMessage(if (Store.calcs.isEmpty()) "No calculations yet." else Store.calcs.joinToString("\n\n"))
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> Store.calcs.clear(); Store.save(this) }
            .show()
    }

    private fun calcEmi() {
        fun n(s: String): Double? = s.replace(",", "").trim().toDoubleOrNull()
        val fee = n(eFee) ?: 0.0
        var p = n(eAmt)
        var rate = n(eRate)
        var months = n(ePer)?.times(if (eYears) 12 else 1)
        var e = n(eEmi)
        when (eSolve) {
            0 -> if (e != null && rate != null && months != null) p = Emi.principal(e, rate, months)
            1 -> if (p != null && e != null && months != null) rate = Emi.rate(p, e, months)
            2 -> if (p != null && e != null && rate != null) months = Emi.months(p, e, rate)
            else -> if (p != null && rate != null && months != null) e = Math.round(Emi.emi(p, rate, months)).toDouble()
        }
        if (p == null || rate == null || months == null || e == null || p <= 0 || months <= 0 || e <= 0) {
            eMsg = "Fill the other boxes with correct numbers. If all are filled, these numbers do not match."
            eRes = null; eSched = emptyList()
            render()
            return
        }
        val mc = ceil(months - 1e-9)
        if (mc > 1200) {
            eMsg = "Period is too long. Use 100 years or less."
            eRes = null; eSched = emptyList()
            render()
            return
        }
        when (eSolve) {
            0 -> eAmt = fmtNum(p)
            1 -> eRate = fmtNum(rate)
            2 -> ePer = fmtNum(if (eYears) mc / 12 else mc)
            else -> eEmi = e.roundToLong().toString()
        }
        val sched = Emi.schedule(p, rate, e, mc.toInt())
        val interest = sched.sumOf { it[1] }
        val fe = p * fee / 100
        eRes = doubleArrayOf(e, interest, fe, p + interest + fe, p)
        eSched = sched
        eMsg = ""
        Store.calcs.add(0, "${rs(p)} · ${fmtNum(rate)}% · ${mc.toInt()} months\nEMI ${rs(e)} · Total ${rs(p + interest + fe)}")
        while (Store.calcs.size > 20) Store.calcs.removeAt(Store.calcs.size - 1)
        Store.save(this)
        render()
    }

    /* ---------- history and export ---------- */
    private fun moreTab() {
        val fu = Store.hist.filter { it.fu.isNotEmpty() && !it.done }.sortedBy { it.fu }
        if (fu.isNotEmpty()) {
            val c = section("Pending follow-ups")
            fu.forEach { h ->
                val cu = Store.byId(h.cid)
                c.put(tv("${cu?.name ?: ""} · ${cu?.ph ?: ""}\nDue ${h.fu}\n${h.rm}", 14f), 10)
                c.put(btn("Mark done", false) { h.done = true; Store.save(this); render() }, 4)
            }
        }
        val ex = section("Export")
        ex.put(tv("The customer file keeps your columns and adds call_status, call_datetime, remarks, follow_up_date and attempt_number.", 13f), 0)
        ex.put(btn("Export customers CSV") { exportKind = "cust"; saveFile.launch("callflow-customers.csv") }, 10)
        ex.put(btn("Export call history CSV", false) { exportKind = "hist"; saveFile.launch("callflow-history.csv") })

        val hc = section("History (${Store.hist.size})")
        if (Store.hist.isEmpty()) hc.put(tv("No calls recorded yet.", 14f), 6)
        Store.hist.asReversed().take(100).forEach { h ->
            val cu = Store.byId(h.cid)
            hc.put(tv("${cu?.name ?: ""} · ${cu?.ph ?: ""}\n${fmtDt(h.dt)} · Attempt ${h.n}" + (if (h.fu.isNotEmpty()) " · Follow-up ${h.fu}" else "") + "\n${h.rm}", 14f), 10)
        }
        section().put(btn("Delete all data", false) {
            AlertDialog.Builder(this).setMessage("Delete all customers and history? This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    Store.cust.clear(); Store.hist.clear(); Store.cols.clear(); Store.sess = null; Store.pending = false
                    Store.save(this); render()
                }
                .setNegativeButton("Cancel", null).show()
        }, 0)
    }

    private fun writeExport(uri: Uri) {
        try {
            val text = if (exportKind == "hist") historyCsv() else customersCsv()
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            toast("File saved")
        } catch (e: Exception) {
            toast("Could not save the file")
        }
    }
}
