package com.raillink.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.LocalTime
import java.time.ZoneId

/**
 * Event reminders: a phone notification 1 week before and the day before the next Rail Link event.
 * Scheduled each time the app opens, from events.json. Nothing to sign up for.
 */
object Reminders {
    private const val CHANNEL = "events"

    fun schedule(context: Context, ev: RailEvent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val zone = ZoneId.systemDefault()
        listOf(7L to 1, 1L to 2).forEach { (daysBefore, id) ->
            val whenAt = ev.date.minusDays(daysBefore).atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()
            val pi = pending(context, ev, daysBefore, id)
            if (whenAt > System.currentTimeMillis()) am.set(AlarmManager.RTC_WAKEUP, whenAt, pi) else am.cancel(pi)
        }
    }

    private fun pending(context: Context, ev: RailEvent, daysBefore: Long, id: Int): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .putExtra("name", ev.name).putExtra("venue", ev.venue)
            .putExtra("days", daysBefore).putExtra("date", ev.date.toString())
            .putExtra("start", ev.start?.toString() ?: "")
        return PendingIntent.getBroadcast(context, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(context: Context, i: Intent) {
        val name = i.getStringExtra("name") ?: return
        val venue = i.getStringExtra("venue") ?: ""
        val start = i.getStringExtra("start").orEmpty()
        val days = i.getLongExtra("days", 1)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Event reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val title = if (days >= 7) "$name is next week" else "$name is tomorrow"
        val text = listOf(venue, if (start.isNotBlank()) "doors $start" else "").filter { it.isNotBlank() }.joinToString(" · ")

        val open = PendingIntent.getActivity(context, 10,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEXT_EVENT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val maps = PendingIntent.getActivity(context, 11,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(venue)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        b.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (venue.isNotBlank()) b.addAction(Notification.Action.Builder(null, "Directions", maps).build())
        try { nm.notify(100 + days.toInt(), b.build()) } catch (_: SecurityException) { }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.show(context, intent)
}
