// Harness compile stubs for androidx.core.app / androidx.core.view / androidx.core.net (core-ktx is unreachable here).
@file:Suppress("unused", "PackageDirectoryMismatch", "MemberVisibilityCanBePrivate")

package androidx.core.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context

class NotificationCompat private constructor() {
    companion object {
        const val PRIORITY_DEFAULT = 0
        const val PRIORITY_LOW = -1
        const val PRIORITY_MIN = -2
        const val PRIORITY_HIGH = 1
        const val PRIORITY_MAX = 2
        const val VISIBILITY_PRIVATE = 0
        const val VISIBILITY_PUBLIC = 1
        const val VISIBILITY_SECRET = -1
        const val CATEGORY_ALARM = "alarm"
        const val CATEGORY_REMINDER = "reminder"
        const val CATEGORY_STATUS = "status"
    }

    abstract class Style

    class BigTextStyle : Style() {
        var bigText: CharSequence? = null
            private set
        fun bigText(text: CharSequence?): BigTextStyle { bigText = text; return this }
        fun setBigContentTitle(title: CharSequence?): BigTextStyle = this
    }

    class Builder(val context: Context, val channelId: String) {
        var smallIcon: Int = 0; private set
        var contentTitle: CharSequence? = null; private set
        var contentText: CharSequence? = null; private set
        var style: Style? = null; private set
        var priority: Int = PRIORITY_DEFAULT; private set
        var autoCancel: Boolean = false; private set
        var contentIntent: PendingIntent? = null; private set
        var category: String? = null; private set
        var visibility: Int = VISIBILITY_PRIVATE; private set
        var onlyAlertOnce: Boolean = false; private set
        var publicVersion: Notification? = null; private set
        var timeoutAfter: Long = 0L; private set

        fun setSmallIcon(icon: Int): Builder { smallIcon = icon; return this }
        fun setContentTitle(title: CharSequence?): Builder { contentTitle = title; return this }
        fun setContentText(text: CharSequence?): Builder { contentText = text; return this }
        fun setStyle(style: Style?): Builder { this.style = style; return this }
        fun setPriority(priority: Int): Builder { this.priority = priority; return this }
        fun setAutoCancel(autoCancel: Boolean): Builder { this.autoCancel = autoCancel; return this }
        fun setContentIntent(intent: PendingIntent?): Builder { contentIntent = intent; return this }
        fun setCategory(category: String?): Builder { this.category = category; return this }
        fun setVisibility(visibility: Int): Builder { this.visibility = visibility; return this }
        fun setOnlyAlertOnce(onlyAlertOnce: Boolean): Builder { this.onlyAlertOnce = onlyAlertOnce; return this }
        fun setShowWhen(show: Boolean): Builder = this
        fun setWhen(whenMillis: Long): Builder = this
        fun setSilent(silent: Boolean): Builder = this
        fun setPublicVersion(notification: Notification?): Builder { publicVersion = notification; return this }
        fun setTimeoutAfter(durationMs: Long): Builder { timeoutAfter = durationMs; return this }
        fun setSubText(text: CharSequence?): Builder = this
        fun setGroup(groupKey: String?): Builder = this

        /** Builds a plain framework Notification (no NotificationChannel plumbing on the JVM). */
        fun build(): Notification = Notification().also { n ->
            n.icon = smallIcon
            n.priority = priority
            n.contentIntent = contentIntent
            if (autoCancel) n.flags = n.flags or Notification.FLAG_AUTO_CANCEL
            n.publicVersion = publicVersion
        }
    }
}

class NotificationManagerCompat private constructor(private val context: Context) {
    private val manager: NotificationManager?
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    fun notify(id: Int, notification: Notification) { manager?.notify(id, notification) }
    fun notify(tag: String?, id: Int, notification: Notification) { manager?.notify(tag, id, notification) }
    fun cancel(id: Int) { manager?.cancel(id) }
    fun cancel(tag: String?, id: Int) { manager?.cancel(tag, id) }
    fun cancelAll() { manager?.cancelAll() }
    fun areNotificationsEnabled(): Boolean = manager?.areNotificationsEnabled() ?: false

    companion object {
        @JvmStatic fun from(context: Context): NotificationManagerCompat = NotificationManagerCompat(context)
    }
}
