// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tux4us.miriquidilauncher.MainActivity
import io.github.tux4us.miriquidilauncher.R
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** `true`, solange der Messenger sichtbar ist und die App im Vordergrund steht; dann benachrichtigt der Worker nicht. */
object ChatForeground {
    @Volatile
    var active: Boolean = false
}

/** Zeigt und entfernt die Benachrichtigungen. Der Inhalt bleibt auf dem Sperrbildschirm verborgen. */
class ChatNotifier(context: Context, private val showText: Boolean) : ChatNotifySink {
    private val context = context.applicationContext

    override fun show(room: ChatRoom, lines: List<String>, count: Int) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val res = context.resources
        val generic = res.getQuantityString(R.plurals.chat_notify_new, count, count)
        val open = PendingIntent.getActivity(
            context,
            ChatNotifyLogic.notificationId(room.token),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_TOKEN, room.token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(res.getString(R.string.scope_chat))
            .setContentText(generic)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(room.name.ifEmpty { res.getString(R.string.scope_chat) })
            .setContentText(if (showText) lines.lastOrNull().orEmpty() else generic)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setNumber(count)
        if (room.lastActivity > 0) builder.setWhen(room.lastActivity * 1000).setShowWhen(true)
        if (showText && lines.size > 1) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")).setSummaryText(generic))
        }
        val notification: Notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(ChatNotifyLogic.notificationId(room.token), notification)
        } catch (e: SecurityException) {
            // Berechtigung wurde zwischenzeitlich entzogen.
        }
    }

    override fun clear(token: String) = cancel(context, token)

    companion object {
        const val CHANNEL = "chat_messages"
        const val EXTRA_TOKEN = "chat_token"

        fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.chat_notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = context.getString(R.string.chat_notify_channel_desc)
            manager.createNotificationChannel(channel)
        }

        fun cancel(context: Context, token: String) {
            NotificationManagerCompat.from(context.applicationContext).cancel(ChatNotifyLogic.notificationId(token))
        }

        /** Entfernt alle Chat-Benachrichtigungen dieser App. */
        fun cancelAll(context: Context) {
            val manager = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
            manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
        }
    }
}

/** Plant die regelmäßige Abfrage (frühestens alle 15 Minuten; das System darf sie bei Energiesparmodi verschieben). */
object ChatNotifyScheduler {
    private const val UNIQUE = "chat_notify"

    fun apply(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            manager.cancelUniqueWork(UNIQUE)
            return
        }
        val request = PeriodicWorkRequestBuilder<ChatNotifyWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class ChatNotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = ChatStore(applicationContext)
        val settings = store.loadNotify()
        if (!settings.enabled) return Result.success()
        if (ChatForeground.active) return Result.success()
        val credentials = store.loadCredentials() ?: return Result.success()
        val remote = ChatRemote(credentials.baseUrl, credentials.user, credentials.password)
        val source = object : ChatNotifySource {
            override suspend fun rooms() = remote.rooms()
            override suspend fun messages(token: String, limit: Int) = remote.messages(token, false, 0L, limit)
        }
        return try {
            val next = ChatNotifyRunner.run(
                source = source,
                sink = ChatNotifier(applicationContext, settings.showText),
                baseline = store.loadBaseline(),
                ownId = credentials.userId,
                notify = ChatNotifier.canNotify(applicationContext),
            )
            store.saveBaseline(next)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Netz- oder Serverfehler, auch 401/403: nichts melden, die nächste Runde versucht es erneut.
            Result.success()
        }
    }
}
