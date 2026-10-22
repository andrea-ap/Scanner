package com.scanner.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Promemoria sugli allegati: una sveglia di AlarmManager per allegato (`Photo.reminderAt`).
 * Allo scatto [ReminderReceiver] mostra la notifica e azzera il promemoria.
 */
object Reminders {
    private const val CHANNEL = "promemoria"
    private const val ACTION_FIRE = "com.scanner.app.REMINDER"
    const val EXTRA_PHOTO_ID = "photoId"
    const val EXTRA_SESSION_ID = "sessionId"

    fun format(at: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(at))

    /** Su Android 12+ le sveglie esatte richiedono il permesso "Sveglie e promemoria". */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun alarmIntent(context: Context, photoId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, photoId.toInt(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ACTION_FIRE)
                .setData(Uri.parse("scanner://reminder/$photoId")) // un'intent distinto per ogni allegato
                .putExtra(EXTRA_PHOTO_ID, photoId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(context: Context, photoId: Long, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, photoId)
        // senza il permesso la sveglia è inesatta: può arrivare qualche minuto dopo
        if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(context: Context, photoId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, photoId))
        NotificationManagerCompat.from(context).cancel(photoId.toInt())
    }

    /** Ripianifica tutti i promemoria (avvio, riavvio del telefono); quelli già scaduti vengono mostrati subito. */
    suspend fun rescheduleAll(context: Context) {
        val dao = AppDatabase.get(context).dao()
        val now = System.currentTimeMillis()
        for (p in dao.photosWithReminder()) {
            val at = p.reminderAt ?: continue
            if (at > now) schedule(context, p.id, at) else fire(context, p.id)
        }
    }

    /** Mostra la notifica per l'allegato e azzera il promemoria. */
    @SuppressLint("MissingPermission") // controllato da notificationsAllowed
    suspend fun fire(context: Context, photoId: Long) {
        val dao = AppDatabase.get(context).dao()
        val p = dao.photo(photoId) ?: return
        if (p.reminderAt == null) return
        dao.setReminder(photoId, null)
        val s = dao.sessionById(p.sessionId) ?: return
        if (!notificationsAllowed(context)) return

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Promemoria", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, photoId.toInt(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PHOTO_ID, photoId)
                .putExtra(EXTRA_SESSION_ID, p.sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val firstLine = p.ocrText.lineSequence().map { it.trim() }.firstOrNull { it.length > 3 }
        val text = firstLine ?: s.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: if (p.isPdf) "PDF" else "Foto"
        val preview = thumbnail(File(p.displayPath))
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Promemoria · ${shortTitle(s.name)}")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (preview != null) {
            builder.setLargeIcon(preview)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(preview).bigLargeIcon(null as Bitmap?))
        }
        try {
            NotificationManagerCompat.from(context).notify(photoId.toInt(), builder.build())
        } catch (e: SecurityException) {
            // permesso revocato nel frattempo
        }
    }

    private fun thumbnail(file: File): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1000) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Sveglia del promemoria, riavvio del telefono, aggiornamento dell'app, permesso sveglie concesso. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.hasExtra(Reminders.EXTRA_PHOTO_ID)) {
                    Reminders.fire(context, intent.getLongExtra(Reminders.EXTRA_PHOTO_ID, 0))
                } else {
                    Reminders.rescheduleAll(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
