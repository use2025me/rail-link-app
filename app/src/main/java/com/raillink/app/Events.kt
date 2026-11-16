package com.raillink.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** One Rail Link event, read from devilmediagroup.com/events.json. */
data class RailEvent(
    val name: String,
    val date: LocalDate,
    val start: LocalTime?,
    val end: LocalTime?,
    val venue: String,
    val link: String?,
) {
    fun daysToGo(today: LocalDate = LocalDate.now()): Long = ChronoUnit.DAYS.between(today, date)

    fun countdownText(): String = when (val d = daysToGo()) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> "$d days to go"
    }
}

object Events {
    const val URL_EVENTS = "https://devilmediagroup.com/events.json"

    /** Downloads the list and returns the next event that hasn't happened yet (or null). Runs off the main thread. */
    fun fetchNext(): RailEvent? = try {
        val conn = (URL(URL_EVENTS).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Cache-Control", "no-cache")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        parse(text).filter { it.daysToGo() >= 0 }.minByOrNull { it.date }
    } catch (e: Exception) {
        null
    }

    fun parse(text: String): List<RailEvent> {
        val arr = JSONObject(text).optJSONArray("events") ?: return emptyList()
        val out = mutableListOf<RailEvent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            try {
                out += RailEvent(
                    name = o.getString("name"),
                    date = LocalDate.parse(o.getString("date")),
                    start = o.optString("start").takeIf { it.isNotBlank() }?.let { LocalTime.parse(it) },
                    end = o.optString("end").takeIf { it.isNotBlank() }?.let { LocalTime.parse(it) },
                    venue = o.optString("venue"),
                    link = o.optString("link").takeIf { it.startsWith("https://") },
                )
            } catch (e: Exception) { /* skip a badly written entry */ }
        }
        return out
    }
}
