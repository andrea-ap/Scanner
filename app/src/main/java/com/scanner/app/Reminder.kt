package com.scanner.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val EXTRA_SESSION_ID = "sessionId"
private const val CHANNEL_ID = "promemoria"

private fun alarmIntent(context: Context, sessionId: Long): PendingIntent =
    PendingIntent.getBroadcast(
        context, sessionId.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_SESSION_ID, sessionId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

/** Programma (o riprogramma) la notifica della sessione all'ora indicata. */
fun scheduleReminder(context: Context, sessionId: Long, at: Long) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pi = alarmIntent(context, sessionId)
    // preciso se Android lo consente, altrimenti può ritardare di qualche minuto in risparmio energetico
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    } else {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

fun cancelReminder(context: Context, sessionId: Long) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    am.cancel(alarmIntent(context, sessionId))
}

fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Promemoria", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Promemoria sulle sessioni" }
        )
    }
}

/** Scatta all'ora del promemoria: mostra la notifica e azzera il promemoria della sessione. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_SESSION_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context).dao()
                val s = dao.allSessions().firstOrNull { it.id == id } ?: return@launch
                dao.setReminder(id, null)
                if (!hasNotificationPermission(context)) return@launch
                ensureChannel(context)
                val open = PendingIntent.getActivity(
                    context, id.toInt(),
                    Intent(context, MainActivity::class.java)
                        .putExtra(EXTRA_SESSION_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val body = s.notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Promemoria"
                val n = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(shortTitle(s.name))
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
                NotificationManagerCompat.from(context).notify(id.toInt(), n)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Dopo il riavvio del telefono gli allarmi vanno riprogrammati. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.get(context).dao().upcomingReminders(System.currentTimeMillis())
                    .forEach { s -> s.reminderAt?.let { scheduleReminder(context, s.id, it) } }
            } finally {
                pending.finish()
            }
        }
    }
}
