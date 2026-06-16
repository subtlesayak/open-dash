package com.example.opendash.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts a reminder when a maintenance item comes due. There's no continuous background
 * service (lean, single-user app), so this is called whenever the data changes —
 * on app open and after any odometer/service edit (see [GarageViewModel] + MainActivity).
 *
 * De-dupe: we remember which items we've already flagged as due, so we only buzz when a
 * NEW item crosses into due — not on every reload. Servicing an item clears it, so it can
 * remind again next interval.
 */
object MaintenanceNotifier {

    private const val CHANNEL_ID = "maintenance"
    private const val NOTIF_ID = 4201
    private const val PREFS = "maint_notify"
    private const val KEY_NOTIFIED = "notified_sids"

    /** An item is "due" once it's within the last 25% of its interval (matches the UI warn/alert tone). */
    private fun isDue(m: MaintenanceItem, odo: Int): Boolean =
        (m.lastDoneOdoKm + m.intervalKm - odo) < m.intervalKm * 0.25

    fun check(context: Context, items: List<MaintenanceItem>, odometer: Int) {
        val ctx = context.applicationContext
        val due = items.filter { isDue(it, odometer) }
        val dueSids = due.map { it.sid }.toSet()

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()) ?: emptySet()
        // Drop serviced items from the remembered set so they can remind again next interval,
        // but keep still-due items we've ALREADY flagged. Crucially, don't mark newly-due items
        // as notified until we actually post — otherwise a reminder is lost when notifications
        // are off, and never fires once they're turned on.
        val stillKnownDue = notified intersect dueSids

        val newlyDue = dueSids - notified
        if (newlyDue.isEmpty()) { prefs.edit().putStringSet(KEY_NOTIFIED, stillKnownDue).apply(); return }

        ensureChannel(ctx)
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled().not()) {
            prefs.edit().putStringSet(KEY_NOTIFIED, stillKnownDue).apply()   // not flagged → can fire later
            return
        }
        prefs.edit().putStringSet(KEY_NOTIFIED, dueSids).apply()   // posting now → remember all due

        val (title, text) = buildText(due, odometer)
        val tap = PendingIntent.getActivity(
            ctx, 0,
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif) }
    }

    private fun buildText(due: List<MaintenanceItem>, odo: Int): Pair<String, String> {
        fun line(m: MaintenanceItem): String {
            val remaining = m.lastDoneOdoKm + m.intervalKm - odo
            return if (remaining < 0) "${m.name} — overdue ${-remaining} km"
            else "${m.name} — due in $remaining km"
        }
        return if (due.size == 1) "Maintenance due" to line(due.first())
        else "${due.size} services due" to due.joinToString("\n") { line(it) }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Maintenance reminders", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Alerts when a service interval is due" }
            )
        }
    }
}
