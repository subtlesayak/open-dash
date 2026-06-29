package com.example.opendash.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.ceil

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
    private fun isDue(m: MaintenanceItem, odo: Int): Boolean {
        val distanceDue = (m.lastDoneOdoKm + m.intervalKm - odo) < m.intervalKm * 0.25
        val schedule = Himalayan450MaintenanceSchedule.forItem(m)
        val months = schedule?.intervalMonths ?: return distanceDue
        val dueAt = Calendar.getInstance().apply {
            timeInMillis = m.lastDoneDateMs
            add(Calendar.MONTH, months)
        }.timeInMillis
        val remainingDays = ceil((dueAt - System.currentTimeMillis()) / 86_400_000.0).toLong()
        return distanceDue || remainingDays < months * 30 * 0.25
    }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putStringSet(KEY_NOTIFIED, stillKnownDue).apply()   // not flagged → can fire later
            return
        }
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled().not()) {
            prefs.edit().putStringSet(KEY_NOTIFIED, stillKnownDue).apply()   // not flagged → can fire later
            return
        }
        ensureChannel(ctx)
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
            val schedule = Himalayan450MaintenanceSchedule.forItem(m)
            val remainingDays = schedule?.intervalMonths?.let { months ->
                val dueAt = Calendar.getInstance().apply {
                    timeInMillis = m.lastDoneDateMs
                    add(Calendar.MONTH, months)
                }.timeInMillis
                ceil((dueAt - System.currentTimeMillis()) / 86_400_000.0).toLong()
            }
            if (remainingDays != null && remainingDays < 0) return "${m.name} — overdue by date"
            return if (remaining < 0) "${m.name} — overdue ${-remaining} km"
            else if (remainingDays != null) "${m.name} — due in $remaining km or ${remainingDays.coerceAtLeast(0)} days"
            else "${m.name} — due in $remaining km"
        }
        return if (due.size == 1) "Maintenance due" to line(due.first())
        else "${due.size} services due" to due.joinToString("\n") { line(it) }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Maintenance reminders", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Alerts when a service interval is due" }
            )
        }
    }
}
