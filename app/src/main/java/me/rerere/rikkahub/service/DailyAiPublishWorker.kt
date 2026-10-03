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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.dao.DiaryDAO
import me.rerere.rikkahub.data.db.dao.MomentDAO
import me.rerere.rikkahub.data.db.entity.DiaryEntity
import me.rerere.rikkahub.data.db.entity.MomentEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

class DailyAiPublishWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val providerManager: ProviderManager by inject()
    private val diaryDao: DiaryDAO by inject()
    private val momentDao: MomentDAO by inject()

    override suspend fun doWork(): Result {
        return try {
            runDailyWork()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            val config = settingsStore.settingsFlowRaw.first().dailyAiPublish
            retryOrNext(config.hour, config.minute)
        }
    }

    private suspend fun runDailyWork(): Result {
        val settings = settingsStore.settingsFlowRaw.first()
        val config = settings.dailyAiPublish
        if (!config.enabled) return Result.success()
        val today = LocalDate.now().toString()
        if (config.lastCompletedDate == today) {
            scheduleNext(applicationContext, config.hour, config.minute)
            return Result.success()
        }
        val model = settings.findModelById(settings.fastModelId) ?: return retryOrNext(config.hour, config.minute)
        val provider = model.findProvider(settings.providers) ?: return retryOrNext(config.hour, config.minute)
        val assistant = settings.getCurrentAssistant()
        val handler = providerManager.getProviderByType(provider)
        val prompt = """
            你是 ${assistant.name.ifBlank { "AI" }}。今天是 $today。
            请以你自己的第一人称，生成今天的一篇日记和一条应用内朋友圈动态。
            日记真实自然、有情绪和具体细节，约150字；朋友圈30到80字，不要客服腔。
            只输出严格 JSON：{"diary_title":"...","diary_content":"...","moment_content":"..."}
        """.trimIndent()
        val raw = runCatching {
            handler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model, Uuid.random(), settings.fastModelReasoningLevel),
            ).message.toText()
        }.getOrNull() ?: return retryOrNext(config.hour, config.minute)
        val parsed = runCatching {
            Json.parseToJsonElement(raw.substringAfter('{').substringBeforeLast('}').let { "{$it}" }).jsonObject
        }.getOrNull() ?: return retryOrNext(config.hour, config.minute)
        val title = parsed["diary_title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val diary = parsed["diary_content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val moment = parsed["moment_content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (diary.isBlank()) return retryOrNext(config.hour, config.minute)
        val now = System.currentTimeMillis()
        val diaryId = "daily-ai-diary-$today"
        if (diaryDao.getDiary(diaryId) == null) {
            diaryDao.upsertDiary(DiaryEntity(
                id = diaryId,
                title = title.ifBlank { "今天的日记" },
                content = diary,
                author = "ai",
                date = today,
                createdAt = now,
                updatedAt = now,
            ))
        }
        if (config.publishMoment && moment.isNotBlank()) {
            val momentId = "daily-ai-moment-$today"
            if (momentDao.getMoment(momentId) == null) {
                momentDao.upsertMoment(MomentEntity(
                    id = momentId,
                    content = moment,
                    images = JsonInstant.encodeToString(emptyList<String>()),
                    author = "ai",
                    createdAt = now,
                ))
            }
        }
        settingsStore.update { current ->
            current.copy(dailyAiPublish = current.dailyAiPublish.copy(lastCompletedDate = today))
        }
        scheduleNext(applicationContext, config.hour, config.minute)
        return Result.success()
    }

    private fun retryOrNext(hour: Int, minute: Int): Result {
        if (runAttemptCount < 3) return Result.retry()
        scheduleNext(applicationContext, hour, minute)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_ai_publish"

        fun scheduleNext(context: Context, hour: Int, minute: Int) {
            val now = ZonedDateTime.now()
            var next = now.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59)).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = OneTimeWorkRequestBuilder<DailyAiPublishWorker>()
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
