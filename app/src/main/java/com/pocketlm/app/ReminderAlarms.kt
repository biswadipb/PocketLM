package com.pocketlm.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pocketlm.app.data.Reminder
import com.pocketlm.app.data.ReminderBook
import com.pocketlm.app.data.Reminders

/**
 * Reminders the model set for the person. They are kept in their own small file (not the settings, so they are not
 * backed up or synced to other devices) and each one has an Android alarm that wakes [ReminderReceiver].
 */
object ReminderAlarms {
    const val ACTION_FIRE = "com.pocketlm.app.action.REMINDER"
    const val EXTRA_ID = "reminder_id"

    private const val PREFS = "reminders"
    private const val KEY = "list"
    private const val CHANNEL = "reminders"
    private const val NOTIFICATION_BASE = 3000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context): List<Reminder> = Reminders.fromJson(prefs(context).getString(KEY, null))

    @Synchronized
    private fun save(context: Context, list: List<Reminder>) {
        prefs(context).edit().putString(KEY, Reminders.toJson(list)).apply()
    }

    /** Whether alarms can go off at the exact minute. From Android 12 the person has to allow it for the app. */
    fun canBeExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    @Synchronized
    fun add(context: Context, at: Long, text: String): Reminder {
        val (list, reminder) = Reminders.add(load(context), at, text)
        save(context, list)
        schedule(context, reminder)
        return reminder
    }

    @Synchronized
    fun remove(context: Context, id: Int) {
        cancel(context, id)
        save(context, Reminders.remove(load(context), id))
    }

    private fun alarmIntent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("pocketlm://reminder/$id"))
            .putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context, reminder: Reminder) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = alarmIntent(context, reminder.id)
        try {
            if (canBeExact(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, intent)
            }
        } catch (e: SecurityException) {
            // The permission was taken away between the check and the call: an approximate alarm still works.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.at, intent)
        }
    }

    private fun cancel(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, id))
    }

    /** After a restart or an update the alarms are gone: set them again, and show the ones that went by in the meantime. */
    @Synchronized
    fun rescheduleAll(context: Context, now: Long = System.currentTimeMillis()) {
        for (reminder in load(context)) {
            if (reminder.at <= now) fire(context, reminder.id) else schedule(context, reminder)
        }
    }

    /** Shows the reminder and forgets it. */
    @Synchronized
    fun fire(context: Context, id: Int) {
        val reminder = load(context).firstOrNull { it.id == id } ?: return
        save(context, Reminders.remove(load(context), id))
        notify(context, reminder)
    }

    private fun notify(context: Context, reminder: Reminder) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Timers and reminders", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_BASE + reminder.id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Reminder")
            .setContentText(reminder.text)
            .setSubText("Piko")
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(reminder.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + reminder.id, notification) }
    }
}

/** What the model's reminder tool talks to: the reminders kept on this phone. */
class PhoneReminders(private val context: Context) : ReminderBook {
    override fun pending(): List<Reminder> = ReminderAlarms.load(context)

    override fun add(at: Long, text: String): Reminder = ReminderAlarms.add(context, at, text)

    override val exact: Boolean get() = ReminderAlarms.canBeExact(context)

    override val notificationsAllowed: Boolean get() = ReminderAlarms.notificationsAllowed(context)
}

/** Wakes up when a reminder is due, and sets the alarms again after a restart or an app update. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderAlarms.ACTION_FIRE -> ReminderAlarms.fire(context, intent.getIntExtra(ReminderAlarms.EXTRA_ID, -1))
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> ReminderAlarms.rescheduleAll(context)
        }
    }
}
