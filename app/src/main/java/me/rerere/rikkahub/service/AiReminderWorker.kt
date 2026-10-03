package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DailyReminderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/**
 * 每日提醒任务：到点把用户写好的提醒内容发进当前助手的会话，
 * 并通过 requestContext 注入系统说明，由 AI 自行决定要不要执行。
 */
class AiReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val chatService: ChatService by inject()
    private val conversationRepo: ConversationRepository by inject()

    override suspend fun doWork(): Result {
        return try {
            runReminder()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            val config = settingsStore.settingsFlowRaw.first().dailyReminder
            retryOrNext(config.hour, config.minute)
        }
    }

    private suspend fun runReminder(): Result {
        val settings = settingsStore.settingsFlowRaw.first()
        val config = settings.dailyReminder
        if (!config.enabled) return Result.success()
        if (config.content.isBlank()) {
            scheduleNext(applicationContext, config.hour, config.minute)
            return Result.success()
        }
        val today = LocalDate.now().toString()
        if (config.lastRunDate == today) {
            scheduleNext(applicationContext, config.hour, config.minute)
            return Result.success()
        }

        val assistant = settings.getCurrentAssistant()
        val conversationId = conversationRepo
            .getRecentConversations(assistant.id, 1)
            .firstOrNull()?.id
            ?: Uuid.random()
        chatService.initializeConversation(conversationId)

        chatService.sendMessage(
            conversationId = conversationId,
            content = listOf(UIMessagePart.Text(config.content)),
            answer = true,
            requestContext = buildRequestContext(config),
        )

        settingsStore.update { current ->
            current.copy(dailyReminder = current.dailyReminder.copy(lastRunDate = today))
        }
        scheduleNext(applicationContext, config.hour, config.minute)
        return Result.success()
    }

    private fun buildRequestContext(config: DailyReminderSetting): String = buildString {
        appendLine("这是一条由系统按用户设定的时间自动发出的每日提醒，不是用户此刻手动输入的消息。")
        appendLine("用户消息中的文字就是本次提醒的任务内容。")
        if (config.aiCanSkip) {
            appendLine("请自行判断这条提醒现在有没有必要执行：没有必要就直接简短回复说明不执行，不要强行去做；有必要就按提醒去做。")
        } else {
            appendLine("请按这条提醒执行，不要拒绝。")
        }
    }

    private fun retryOrNext(hour: Int, minute: Int): Result {
        if (runAttemptCount < 3) return Result.retry()
        scheduleNext(applicationContext, hour, minute)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_reminder"

        fun scheduleNext(context: Context, hour: Int, minute: Int) {
            val now = ZonedDateTime.now()
            var next = now.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59)).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = OneTimeWorkRequestBuilder<AiReminderWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
