package com.shortsmonitor.core.notification

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
import com.shortsmonitor.app.MainActivity
import com.shortsmonitor.app.R

/**
 * 시스템 알림 헬퍼 (O단계 알림 설정).
 *
 * 의심 이벤트·관찰 오류 알림을 전송한다. 알림 설정(시스템 알림·의심 이벤트 알림·
 * 오류 알림·진동)과 알림 권한(POST_NOTIFICATIONS)을 모두 확인한 뒤에만 전송한다.
 * 알림 내용에 민감한 쿠키나 인증값은 포함되지 않는다.
 */
object NotificationHelper {

    private const val CHANNEL_EVENTS = "suspected_events"
    private const val CHANNEL_ERRORS = "observer_errors"
    private const val NOTIFICATION_ID_EVENT = 1001
    private const val NOTIFICATION_ID_ERROR = 1002

    /** 알림 채널을 생성한다 (앱 시작 시 또는 첫 전송 전). */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_EVENTS,
                    context.getString(R.string.notification_channel_events),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ERRORS,
                    context.getString(R.string.notification_channel_errors),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    /** 시스템 알림 권한이 있는지 확인한다. */
    fun canPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** 의심 이벤트 시스템 알림을 전송한다. [systemEnabled]·[eventEnabled]·[vibrate] 설정을 반영한다. */
    fun notifySuspectedEvent(
        context: Context,
        title: String,
        text: String,
        systemEnabled: Boolean,
        eventEnabled: Boolean,
        vibrate: Boolean,
    ) {
        if (!systemEnabled || !eventEnabled || !canPostNotifications(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context))
            .apply { if (vibrate) setVibrate(longArrayOf(0, 200, 100, 200)) }
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_EVENT, notification)
        }
    }

    /** 관찰 오류 시스템 알림을 전송한다. [systemEnabled]·[errorEnabled]·[vibrate] 설정을 반영한다. */
    fun notifyObserverError(
        context: Context,
        text: String,
        systemEnabled: Boolean,
        errorEnabled: Boolean,
        vibrate: Boolean,
    ) {
        if (!systemEnabled || !errorEnabled || !canPostNotifications(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ERRORS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_error_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(launchIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_ERROR, notification)
        }
    }

    private fun launchIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
