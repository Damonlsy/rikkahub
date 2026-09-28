package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WORKFLOW_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.utils.circularBitmap
import me.rerere.rikkahub.utils.decodeAvatarBitmap

/** Damonlsy fork：工作流触发时以「AI 头像 + AI 名字」的消息形式弹出通知。 */
object WorkflowNotifier {
    private const val NOTIFICATION_ID = 2004

    fun notifyMessage(context: Context, outcome: WorkflowService.Outcome) {
        val name = outcome.assistantName.ifBlank { context.getString(R.string.app_name) }
        val avatarBitmap = runCatching {
            decodeAvatarBitmap(context, (outcome.assistantAvatar as? me.rerere.rikkahub.data.model.Avatar.Image)?.url.orEmpty())
                ?.let { circularBitmap(it) }
        }.getOrNull()

        val person = Person.Builder()
            .setName(name)
            .apply { avatarBitmap?.let { setIcon(IconCompat.createWithBitmap(it)) } }
            .build()

        val text = buildString {
            append(outcome.message)
            if (outcome.lockedApps.isNotEmpty()) {
                append("\n（已锁定：")
                append(outcome.lockedApps.joinToString("、"))
                append("）")
            }
        }

        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("我").build())
            .addMessage(text, System.currentTimeMillis(), person)

        val builder = NotificationCompat.Builder(context, WORKFLOW_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(name)
            .setContentText(outcome.message)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, outcome.conversationId))

        avatarBitmap?.let { builder.setLargeIcon(it) }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun contentIntent(context: Context, conversationId: String?): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            conversationId?.let { putExtra("conversationId", it) }
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
