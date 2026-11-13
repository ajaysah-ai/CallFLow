package com.callflow.ai

import android.content.Context
import org.json.JSONObject

/** The 10 legal documents. The same files are shown by the server at /legal. Edit them in CallFlowServer/public/legal. */
object Legal {
    class Doc(val slug: String, val title: String)

    private fun read(c: Context, name: String): String = c.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun docs(c: Context): List<Doc> = try {
        val a = JSONObject(read(c, "index.json")).getJSONArray("docs")
        List(a.length()) { i -> Doc(a.getJSONObject(i).getString("slug"), a.getJSONObject(i).getString("title")) }
    } catch (e: Exception) { emptyList() }

    fun text(c: Context, slug: String): String = try {
        val s = JSONObject(read(c, "settings.json"))
        Regex("\\{\\{(\\w+)\\}\\}").replace(read(c, "$slug.md")) { m -> s.optString(m.groupValues[1], m.value) }
    } catch (e: Exception) { "This document could not be loaded." }
}
