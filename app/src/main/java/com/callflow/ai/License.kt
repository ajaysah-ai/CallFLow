package com.callflow.ai

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Config {
    /** true = nobody can use the app without an approved code and phone. */
    const val LICENSE_ENABLED = true
    /** Must start with https:// */
    const val SERVER_URL = "https://callflowserver.onrender.com/api/subscription"
    /** The app locks this many days after the last successful check with the server. */
    const val OFFLINE_DAYS = 7
}

/**
 * Talks to the backend. The BACKEND decides everything (code, phone approval, team subscription, user limit).
 * This file only asks, remembers the answer for offline use, and uploads call records for the team dashboard.
 */
object License {
    var lastMsg = ""
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private class Rec(val mt: Long, val json: JSONObject)

    private fun sp(c: Context) = c.getSharedPreferences("license", Context.MODE_PRIVATE)
    fun code(c: Context): String = sp(c).getString("code", "") ?: ""
    fun message(c: Context): String = sp(c).getString("msg", "") ?: ""

    /** Forgets the code but keeps this phone's ID. */
    fun clear(c: Context) {
        val id = sp(c).getString("dev", null)
        val e = sp(c).edit().clear()
        if (id != null) e.putString("dev", id)
        e.apply()
    }

    fun deviceId(c: Context): String {
        val p = sp(c)
        var id = p.getString("dev", null)
        if (id == null) { id = java.util.UUID.randomUUID().toString(); p.edit().putString("dev", id).apply() }
        return id
    }

    /** "ok", "activate" (no code yet), "pending" (phone not approved), "closed", "expired" (too long without a check) or "clock". */
    fun state(c: Context): String {
        val p = sp(c)
        if (code(c).isEmpty()) return "activate"
        val st = p.getString("status", "")
        if (st == "pending") return "pending"
        if (st != "running") return "closed"
        val now = System.currentTimeMillis()
        val maxT = p.getLong("maxT", 0L)
        if (now < maxT - 3600_000L) return "clock"
        if (now > maxT) p.edit().putLong("maxT", now).apply()
        if (now - p.getLong("lastOk", 0L) > Config.OFFLINE_DAYS * 86400_000L) return "expired"
        return "ok"
    }

    fun allowed(c: Context): Boolean = !Config.LICENSE_ENABLED || state(c) == "ok"

    fun lockMessage(c: Context, st: String): String = when (st) {
        "pending" -> "Waiting for approval. Ask your admin or team leader to approve this phone."
        "closed" -> message(c).ifEmpty { "Your access is closed." }
        "clock" -> "The date or time on this phone looks wrong. Set the correct date and time."
        else -> "Your access has ended."
    }

    private fun post(body: JSONObject, token: String? = null): JSONObject? = try {
        val h = URL(Config.SERVER_URL).openConnection() as HttpURLConnection
        h.requestMethod = "POST"
        h.connectTimeout = 30000
        h.readTimeout = 60000
        h.doOutput = true
        h.setRequestProperty("Content-Type", "application/json")
        if (token != null) h.setRequestProperty("Authorization", "Bearer $token")
        h.outputStream.use { it.write(body.toString().toByteArray()) }
        if (h.responseCode == 200) JSONObject(h.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }

    private fun remember(app: Context, r: JSONObject, code: String) {
        val p = sp(app)
        val st = r.optString("status", "closed")
        val e = p.edit().putString("code", code).putString("status", st).putString("msg", r.optString("message", ""))
            .putString("name", r.optString("name", p.getString("name", "") ?: ""))
        if (st == "running") { val n = System.currentTimeMillis(); e.putLong("lastOk", n).putLong("maxT", n) }
        e.apply()
    }

    private fun consent(app: Context): String = app.getSharedPreferences("consent", Context.MODE_PRIVATE).getString("v", "") ?: ""

    private fun mask(p: String) = if (p.length <= 4) p else "X".repeat(p.length - 4) + p.takeLast(4)

    /** Call records changed since the last upload. Taken on the main thread, then sent in the background. */
    private fun snapshot(app: Context): List<Rec> {
        val since = sp(app).getLong("syncMt", 0L)
        return Store.hist.filter { it.mt > since }.sortedBy { it.mt }.map { h ->
            val cu = Store.byId(h.cid)
            Rec(h.mt, JSONObject().put("id", h.id).put("cn", cu?.name ?: "").put("ph", mask(cu?.ph ?: ""))
                .put("at", h.n).put("rm", h.rm).put("fu", h.fu).put("fd", h.done).put("dt", h.dt))
        }
    }

    private fun push(app: Context, token: String, recs: List<Rec>) {
        var i = 0
        while (i < recs.size) {
            val chunk = recs.subList(i, minOf(i + 200, recs.size))
            val arr = JSONArray()
            chunk.forEach { arr.put(it.json) }
            val r = post(JSONObject().put("action", "sync").put("records", arr), token) ?: return
            if (r.optString("status") != "running") return
            val p = sp(app)
            p.edit().putLong("syncMt", maxOf(p.getLong("syncMt", 0L), chunk.last().mt)).apply()
            i += 200
        }
    }

    /**
     * Asks the server about this code and phone. done(result) runs on the main thread:
     * "running", "pending", "closed", or null when there is no internet.
     * When it is "running", changed call records are uploaded too.
     */
    fun verify(c: Context, codeIn: String?, activating: Boolean, done: (String?) -> Unit) {
        val app = c.applicationContext
        val useCode = codeIn ?: code(app)
        val batch = snapshot(app)
        exec.execute {
            var res: String? = null
            val r = post(JSONObject().put("action", "activate").put("code", useCode).put("deviceId", deviceId(app)).put("deviceName", Build.MODEL).put("consent", consent(app)))
            if (r != null) {
                val st = r.optString("status", "closed")
                lastMsg = r.optString("message", "")
                if (!activating || st != "closed") remember(app, r, useCode)
                if (st == "running" && r.has("token")) push(app, r.getString("token"), batch)
                res = st
            }
            main.post { done(res) }
        }
    }

    /** Joins a team with the leader's invite code. The backend checks the team's user limit. */
    fun join(c: Context, invite: String, name: String, done: (String?) -> Unit) {
        val app = c.applicationContext
        exec.execute {
            var res: String? = null
            val r = post(JSONObject().put("action", "join").put("inviteCode", invite).put("name", name)
                .put("deviceId", deviceId(app)).put("deviceName", Build.MODEL).put("consent", consent(app)))
            if (r != null) {
                val st = r.optString("status", "closed")
                lastMsg = r.optString("message", "")
                val code = r.optString("code", "")
                if (st != "closed" && code.isNotEmpty()) remember(app, r, code)
                res = st
            }
            main.post { done(res) }
        }
    }

    /** Deletes this user's account and all their call records on the server (withdraws consent). "deleted" or null. */
    fun deleteMe(c: Context, done: (String?) -> Unit) {
        val app = c.applicationContext
        val myCode = code(app)
        exec.execute {
            var res: String? = null
            val a = post(JSONObject().put("action", "activate").put("code", myCode).put("deviceId", deviceId(app))
                .put("deviceName", Build.MODEL).put("consent", consent(app)))
            val token = a?.optString("token", "") ?: ""
            if (token.isNotEmpty()) {
                val r = post(JSONObject().put("action", "deleteMe"), token)
                if (r != null && r.optBoolean("ok")) res = "deleted"
            }
            main.post { done(res) }
        }
    }
}
