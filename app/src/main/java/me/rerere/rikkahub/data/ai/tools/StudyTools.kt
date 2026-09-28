package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.StudyRepository

/**
 * 学习模式工具：AI 可查看用户的学习状态（今天背了多少新单词、复习了多少、学习了多久、总进度）。
 */
fun buildStudyTools(repository: StudyRepository): List<Tool> = listOf(
    Tool(
        name = "study_status",
        description = """
            学习模式状态查询：查看用户今天的学习情况，包括每个词库（高中必备词汇 high_school / 大学四六级必备词汇 cet46）
            今天新背的单词数、复习的单词数、学习时长（秒），以及总词数、已掌握数。
            当用户询问"我今天背了多少单词""学习状态怎么样""单词进度"等学习相关问题时使用。
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {},
                required = emptyList()
            )
        },
        execute = {
            listOf(UIMessagePart.Text(repository.statusJson().toString()))
        }
    )
)
