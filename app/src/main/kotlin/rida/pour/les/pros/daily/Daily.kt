package rida.pour.les.pros.daily

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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rida.pour.les.pros.MainActivity
import rida.pour.les.pros.R
import rida.pour.les.pros.agent.ChatService
import rida.pour.les.pros.agent.DailyOutcome
import rida.pour.les.pros.data.repo.AppSettings
import rida.pour.les.pros.data.repo.SettingsRepository
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DailyEntryPoint {
    fun chatService(): ChatService
    fun settings(): SettingsRepository
}

private fun entryPoint(context: Context) =
    EntryPointAccessors.fromApplication(context.applicationContext, DailyEntryPoint::class.java)

/**
 * Programmation du job quotidien : alarme non exacte dans une fenêtre de 15 minutes
 * (aucune permission d'alarme exacte, réservée par Google Play aux apps réveil/agenda).
 */
object DailyScheduler {
    private const val WINDOW_MS = 15 * 60 * 1000L

    fun schedule(context: Context, s: AppSettings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, DailyReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (!s.digestEnabled || s.digestDays.isEmpty()) {
            am.cancel(pi)
            return
        }
        val now = ZonedDateTime.now(s.zone)
        var next = now.with(s.digestLocalTime).truncatedTo(ChronoUnit.MINUTES)
        if (!next.isAfter(now)) next = next.plusDays(1)
        var guard = 0
        while (!s.isDigestDay(next.toLocalDate()) && guard++ < 7) next = next.plusDays(1)
        am.setWindow(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), WINDOW_MS, pi)
    }

    /** Vrai si l'heure du job du jour est passée (pour le rattrapage à l'ouverture de l'app). */
    fun isDue(s: AppSettings): Boolean {
        val now = ZonedDateTime.now(s.zone)
        return s.digestEnabled && s.isDigestDay(now.toLocalDate()) && !now.toLocalTime().isBefore(s.digestLocalTime)
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val ep = entryPoint(context)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                ep.chatService().runDaily()?.let { Notifications.showDaily(context, it) }
            } catch (_: Exception) {
                // L'erreur éventuelle est déjà visible dans le chat ; on reprogramme quand même.
            } finally {
                runCatching { DailyScheduler.schedule(context, ep.settings().current()) }
                pending.finish()
            }
        }
    }
}

/** Reprogramme l'alarme après un redémarrage, une mise à jour de l'app ou un changement de fuseau. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val ep = entryPoint(context)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                DailyScheduler.schedule(context, ep.settings().current())
            } finally {
                pending.finish()
            }
        }
    }
}

object Notifications {
    const val CHANNEL_DAILY = "rida_daily"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DAILY, "Échéances du jour", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Digest quotidien du RIDA (échéances et recopie des commentaires)"
            },
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showDaily(context: Context, outcome: DailyOutcome) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_DAILY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(outcome.title)
            .setContentText(outcome.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(outcome.text + "\nOuvre le chat pour le détail."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1001, n)
        } catch (_: SecurityException) {
            // Permission retirée entre-temps : le digest reste dans le chat.
        }
    }
}
