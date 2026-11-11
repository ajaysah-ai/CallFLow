package com.callflow.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object Config {
    /** Keep false until your server is online. Then set your address below and change this to true. */
    const val LICENSE_ENABLED = false
    /** Must start with https:// */
    const val SERVER_URL = "https://your-domain.com/api/subscription"
    /** The app locks this many days after the last successful check. */
    const val OFFLINE_DAYS = 7
}

/** Unique-code subscription. Works offline for OFFLINE_DAYS, and checks the server whenever internet is available. */
object License {
    var lastMsg = ""

    private fun sp(c: Context) = c.getSharedPreferences("license", Context.MODE_PRIVATE)
    fun code(c: Context): String = sp(c).getString("code", "") ?: ""
    fun message(c: Context): String = sp(c).getString("msg", "") ?: ""
    fun clear(c: Context) { sp(c).edit().clear().apply() }

    private fun deviceId(c: Context): String {
        val p = sp(c)
        var id = p.getString("dev", null)
        if (id == null) { id = UUID.randomUUID().toString(); p.edit().putString("dev", id).apply() }
        return id
    }

    /** "ok", "activate" (no code yet), "closed", "expired" (7 days without a check) or "clock" (phone time moved back). */
    fun state(c: Context): String {
        val p = sp(c)
        if (code(c).isEmpty()) return "activate"
        if (p.getString("status", "") != "running") return "closed"
        val now = System.currentTimeMillis()
        val maxT = p.getLong("maxT", 0L)
        if (now < maxT - 3600_000L) return "clock"
        if (now > maxT) p.edit().putLong("maxT", now).apply()
        if (now - p.getLong("lastOk", 0L) > Config.OFFLINE_DAYS * 86400_000L) return "expired"
        return "ok"
    }

    fun allowed(c: Context): Boolean = !Config.LICENSE_ENABLED || state(c) == "ok"

    fun lockMessage(c: Context, st: String): String {
        val m = message(c)
        return when (st) {
            "closed" -> if (m.isNotEmpty()) m else "Your subscription is closed."
            "clock" -> "The date or time on this phone looks wrong. Set the correct date and time."
            else -> "Your subscription has ended."
        }
    }

    /**
     * Asks the server. done(result) runs on the main thread: "running", "closed", or null when there is no internet.
     * When activating, the code is saved only if the server says "running".
     */
    fun check(c: Context, code: String, activating: Boolean, done: (String?) -> Unit) {
        val app = c.applicationContext
        Thread {
            var res: String? = null
            try {
                val h = URL(Config.SERVER_URL).openConnection() as HttpURLConnection
                h.requestMethod = "POST"
                h.connectTimeout = 8000
                h.readTimeout = 8000
                h.doOutput = true
                h.setRequestProperty("Content-Type", "application/json")
                h.outputStream.use { it.write(JSONObject().put("code", code).put("deviceId", deviceId(app)).toString().toByteArray()) }
                if (h.responseCode == 200) {
                    val j = JSONObject(h.inputStream.bufferedReader().readText())
                    val st = j.optString("status", "closed")
                    lastMsg = j.optString("message", "")
                    if (!activating || st == "running") {
                        val e = sp(app).edit().putString("code", code).putString("status", st).putString("msg", lastMsg)
                        if (st == "running") { val n = System.currentTimeMillis(); e.putLong("lastOk", n).putLong("maxT", n) }
                        e.apply()
                    }
                    res = st
                }
            } catch (e: Exception) { }
            Handler(Looper.getMainLooper()).post { done(res) }
        }.start()
    }
}
