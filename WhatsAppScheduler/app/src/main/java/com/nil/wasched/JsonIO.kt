package com.nil.wasched

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object JsonIO {
    private val zone get() = ZoneId.systemDefault()

    fun export(msgs: List<Msg>): String {
        val arr = JSONArray()
        msgs.forEach { m ->
            val o = JSONObject()
            o.put("group", m.groupName)
            o.put("type", m.type.lowercase())
            if (m.type == "POLL") {
                o.put("question", m.pollQuestion)
                o.put("options", JSONArray(m.pollOptions.split("\n").filter { it.isNotBlank() }))
                o.put("allowMultiple", m.allowMultiple)
            } else {
                o.put("message", m.message)
            }
            if (m.url.isNotBlank()) o.put("url", m.url)
            o.put("datetime", LocalDateTime.ofInstant(Instant.ofEpochMilli(m.dateTime), zone).toString())
            arr.put(o)
        }
        return JSONObject().put("messages", arr).toString(2)
    }

    /** Throws IllegalArgumentException with a record number on invalid input. */
    fun import(text: String): List<Msg> {
        val arr = JSONObject(text).getJSONArray("messages")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            try {
                val type = o.optString("type", "text").uppercase()
                require(type == "TEXT" || type == "POLL") { "bad type" }
                val group = o.getString("group").trim()
                require(group.isNotEmpty()) { "group is empty" }
                val time = LocalDateTime.parse(o.getString("datetime")).atZone(zone).toInstant().toEpochMilli()
                val url = o.optString("url", "").trim()
                if (type == "POLL") {
                    val q = o.getString("question").trim()
                    val opts = o.getJSONArray("options").let { a -> (0 until a.length()).map { a.getString(it).trim() } }
                        .filter { it.isNotEmpty() }
                    require(q.isNotEmpty()) { "poll needs a question" }
                    require(opts.size in 2..12) { "poll needs 2-12 options" }
                    Msg(groupName = group, type = type, url = url, pollQuestion = q,
                        pollOptions = opts.joinToString("\n"), allowMultiple = o.optBoolean("allowMultiple", false),
                        dateTime = time)
                } else {
                    val msg = o.optString("message", "").trim()
                    require(msg.isNotEmpty() || url.isNotEmpty()) { "message and url both empty" }
                    Msg(groupName = group, type = type, message = msg, url = url, dateTime = time)
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Record ${i + 1}: ${e.message}")
            }
        }
    }
}
