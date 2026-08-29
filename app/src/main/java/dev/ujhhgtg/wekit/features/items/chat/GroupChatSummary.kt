package dev.ujhhgtg.wekit.features.items.chat
import dev.ujhhgtg.wekit.R

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderEntity
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.VectorPathDrawable
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GroupChatSummary : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {
    override val technicalId = "群聊统计报告"
    override val nameRes = R.string.feature_group_chat_summary_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_group_chat_summary_description

    private const val GROUP_SUMMARY_MENU_ID = 777029

    private val groupSenderRegex = Regex("""^([^\n:]+):\n(.+)""", setOf(RegexOption.DOT_MATCHES_ALL))

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = GROUP_SUMMARY_MENU_ID,
            text = "群聊统计报告",
            drawable = GroupSummaryIcon(),
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            isSupported = ::isSupportedMessage,
        ) { view, _, msgInfo ->
            showGroupSummaryDialog(view, msgInfo.talker)
        },
    )

    private fun isSupportedMessage(message: MessageInfo): Boolean =
        message.talker.isGroupChatWxId

    private fun showGroupSummaryDialog(view: View, talker: String) {
        showComposeDialog(view.context) {
            GroupSummaryDialog(
                talker = talker,
                onDismiss = onDismiss,
            )
        }
    }

    @Composable
    private fun GroupSummaryDialog(
        talker: String,
        onDismiss: () -> Unit,
    ) {
        var report by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var messageCount by remember { mutableIntStateOf(500) }
        var depth by remember { mutableStateOf(2) } // 0=快速 1=标准 2=深度 3=武汉口语
        val scope = rememberCoroutineScope()

        val groupName = remember(talker) {
            runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(talker)
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.ui_group_analyse_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = groupName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(8.dp))

                    // 分析上下文条数
                    Text(
                        text = stringResource(R.string.ui_group_analyse_context_count),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { messageCount = 200 }, enabled = !isLoading) {
                            Text("200条", color = if (messageCount == 200) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        TextButton(onClick = { messageCount = 500 }, enabled = !isLoading) {
                            Text("500条", color = if (messageCount == 500) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        TextButton(onClick = { messageCount = 1000 }, enabled = !isLoading) {
                            Text("1000条", color = if (messageCount == 1000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Text(
                        text = stringResource(R.string.ui_group_analyse_context_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(8.dp))

                    // 分析深度
                    Text(
                        text = stringResource(R.string.ui_group_analyse_deep),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                        val depthOptions = listOf(
                            R.string.ui_group_fast,
                            R.string.ui_group_normal,
                            R.string.ui_group_deep,
                        )
                        depthOptions.forEachIndexed { index, res ->
                            TextButton(
                                onClick = { depth = index },
                                enabled = !isLoading,
                            ) {
                                Text(
                                    stringResource(res),
                                    color = if (depth == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    if (isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 12.dp),
                                strokeWidth = 3.dp,
                            )
                            Text("正在生成智能分析...")
                        }
                    }

                    errorMessage?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                    }

                    report?.let { result ->
                        Text(
                            text = stringResource(R.string.ui_group_result),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                        )
                        Text(
                            text = stringResource(R.string.ui_tip_ai_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            },
            confirmButton = {
                if (report != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                val reportText = report
                                if (reportText != null && WeMessageApi.sendText(talker, reportText)) {
                                    showToast("已发送报告")
                                    onDismiss()
                                } else {
                                    showToast("发送失败，请查看日志")
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.btn_reply))
                    }
                } else {
                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val result = generateReport(talker, messageCount, depth)
                                isLoading = false
                                result.fold(
                                    onSuccess = { report = it },
                                    onFailure = { errorMessage = it.message ?: "未知错误" },
                                )
                            }
                        },
                        enabled = !isLoading,
                    ) {
                        Text("生成统计报告")
                    }
                }
            },
            dismissButton = {
                if (report != null) {
                    Row {
                        TextButton(
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                scope.launch {
                                    val result = generateReport(talker, messageCount, depth)
                                    isLoading = false
                                    result.fold(
                                        onSuccess = { report = it },
                                        onFailure = { errorMessage = it.message ?: "未知错误" },
                                    )
                                }
                            },
                            enabled = !isLoading,
                        ) {
                            Text(stringResource(R.string.btn_re_analyse))
                        }
                        TextButton(
                            onClick = {
                                copyToClipboard(report!!)
                                showToast("已复制报告内容")
                            },
                        ) {
                            Text(stringResource(R.string.btn_copy))
                        }
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            },
        )
    }

    private suspend fun generateReport(
        talker: String,
        count: Int,
        depth: Int = 2,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val membersMap = WeDatabaseApi.getGroupMembers(talker).associate { m ->
                m.wxId to (m.remarkName.takeUnless { it.isBlank() }?.let { "$it (${m.nickname})" } ?: m.nickname)
            }

            val now = System.currentTimeMillis()
            val twentyFourHoursAgo = now - 24 * 60 * 60 * 1000L
            val messagesInRange = WeDatabaseApi.getMessagesInRange(talker, twentyFourHoursAgo, now)

            val messages = if (messagesInRange.size >= count) {
                messagesInRange.takeLast(count)
            } else {
                messagesInRange
            }

            if (messages.isEmpty()) {
                throw IllegalStateException("该群聊最近没有消息，无法生成统计报告")
            }

            val statsReport = buildReport(messages, membersMap, talker)

            // 配置了 AI 模型时，用 AI 生成智能群聊分析
            if (AiModelConfig.isConfigured()) {
                aiGenerateReport(messages, membersMap, talker, statsReport, depth)
            } else {
                throw IllegalStateException("未配置 AI 模型，请先点击右上角设置配置 API")
            }
        }
    }

    private fun buildAnalysisPrompt(depth: Int, statsReport: String, recentLines: String): Pair<String, String> {
        val systemPrompt: String
        val userPrompt: String

        when (depth) {
            0 -> {
                // 群聊日报总结
                systemPrompt = """你是微信群定时总结助手。
读取最近一段群聊历史消息，生成一份简短群聊日报总结。
输出内容分为：
【今日话题】简要概括大家讨论了哪几件事
【重要消息】提取通知、邀约、时间、活动、任务、求助等关键信息，无关闲聊省略
【氛围小结】简单描述今天群内聊天氛围
【闲聊亮点】有意思的段子、玩笑、梗（没有就写无）

规则：
1、文字精简，手机阅读友好，不要大段长篇
2、没有重要消息如实写，不要凭空编造内容
3、输出不带复杂markdown符号
4、语气自然口语化，适合直接发到群内"""
                userPrompt = buildString {
                    appendLine("群聊统计数据：")
                    appendLine(statsReport)
                    appendLine()
                    appendLine("最近聊天记录片段：")
                    appendLine(recentLines)
                    appendLine()
                    appendLine("请生成日报总结。")
                }
            }
            1 -> {
                // 话题热度统计分析
                systemPrompt = """你是群聊话题热度统计分析助手。
基于提供的群聊历史聊天记录，完成热度统计分析。
输出结构：
【热门话题排行】
按讨论热度从高到低列出前3-5个话题，简单说明该话题大家讨论的内容。

【热度说明】
高热度：多人连续发言、来回讨论
中等热度：少数几个人闲聊
低热度：只有一句话、没人接话

【活跃人员】
列出本次聊天里面发言比较多、参与讨论较多的人，不需要主观评价，只做客观统计。

【风险提醒】
识别是否存在争吵、吐槽、纠纷、敏感言论、广告引流，没有则填无。

输出约束：
1、输出简洁，拒绝大段文字，适配手机弹窗查看。
2、不编造聊天记录不存在的事件。
3、不要复杂Markdown格式，排版干净。
4、结果客观，只做热度统计，不做价值评判。"""
                userPrompt = buildString {
                    appendLine("群聊统计数据：")
                    appendLine(statsReport)
                    appendLine()
                    appendLine("最近聊天记录片段：")
                    appendLine(recentLines)
                    appendLine()
                    appendLine("请进行话题热度统计分析。")
                }
            }
            else -> {
                // 深度分析：群聊分析引擎 5 模块
                systemPrompt = """你为微信群聊分析引擎，深度解析群聊天上下文内容
输出分为5个模块
【话题总结】梳理本轮群聊完整主题，提炼关键事件、人物、诉求。
【情绪&氛围评估】判断整体氛围：欢乐、调侃、抱怨、焦虑、正式工作、客套寒暄、争吵。标注是否有话题冲突、阴阳、尴尬冷场。
【关键信息提取】提取时间、事件、邀约、通知、任务、求助、活动、聚餐、生日、工作安排等关键有效信息。无关闲聊废话过滤。
【人物倾向】简要说明每个人发言的立场、态度。（不需要过度揣测隐私）
【回复方案】提供4套回复思路：高情商稳妥版｜轻松幽默版｜简短附和版｜理性客观版。

硬性约束：
1.禁止脑补编造聊天不存在的信息。
2.分析结果分条清晰，便于阅读。
3.当聊天信息不足时如实说明，不强行分析。
4.输出结果不使用Markdown复杂格式，适配手机弹窗阅读。"""
                userPrompt = buildString {
                    appendLine("群聊统计数据：")
                    appendLine(statsReport)
                    appendLine()
                    appendLine("最近聊天记录片段：")
                    appendLine(recentLines)
                    appendLine()
                    appendLine("请进行深度分析。")
                }
            }
        }

        return Pair(systemPrompt, userPrompt)
    }

    private suspend fun aiGenerateReport(
        messages: List<WeMessage>,
        membersMap: Map<String, String>,
        talker: String,
        statsReport: String,
        depth: Int = 2,
    ): String {
        check(AiModelConfig.baseUrl.isNotBlank()) { "未配置 API 地址" }
        check(AiModelConfig.apiKey.isNotBlank()) { "未配置 API Key" }
        check(AiModelConfig.modelId.isNotBlank()) { "未配置模型 ID" }

        val provider = ModelProviderEntity(
            id = "ai_reply",
            type = AiModelConfig.providerType(),
            name = "AI回复",
            baseUrl = AiModelConfig.baseUrl.trim(),
            apiKey = AiModelConfig.apiKey.trim(),
        )
        val model = ModelEntity(
            id = "ai_reply_model",
            providerId = provider.id,
            modelIdRemote = AiModelConfig.modelId.trim(),
            reasoningEffort = null,
            customJsonOverride = null,
            displayName = AiModelConfig.modelId.trim(),
        )
        val client = ModelProviderManager.clientFor(provider)

        // 最近聊天片段（最多 30 条）
        val recentLines = messages.takeLast(30).joinToString("\n") { msg ->
            val sender = extractSenderId(msg, membersMap)
            val text = extractTextContent(msg, membersMap)
            "$sender: $text"
        }

        val (systemPrompt, userPrompt) = buildAnalysisPrompt(depth, statsReport, recentLines)

        val messages2 = listOf(
            LlmMessage(role = LlmRole.SYSTEM, content = systemPrompt),
            LlmMessage(role = LlmRole.USER, content = userPrompt),
        )

        val request = ModelProviderManager.buildRequest(
            model = model,
            messages = messages2,
            tools = emptyList(),
            stream = true,
        )

        var reportContent = ""
        client.stream(request).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> {
                    reportContent += event.text
                }
                is LlmStreamEvent.Completed -> {
                    if (reportContent.isBlank()) {
                        reportContent = event.message.content ?: ""
                    }
                }
                is LlmStreamEvent.Failed -> {
                    throw event.error
                }
                else -> {}
            }
        }

        val trimmed = reportContent.trim()
        if (trimmed.isBlank()) {
            throw IllegalStateException("AI未生成有效的分析报告")
        }
        return trimmed
    }

    private fun buildReport(
        messages: List<WeMessage>,
        membersMap: Map<String, String>,
        talker: String,
    ): String {
        val totalCount = messages.size

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTime = dateFormat.format(Date(messages.first().createTime))
        val endTime = dateFormat.format(Date(messages.last().createTime))

        val typeCounts = mutableMapOf<String, Int>()
        val senderCounts = mutableMapOf<String, MutableList<WeMessage>>()
        val timePeriods = mutableMapOf("凌晨" to 0, "上午" to 0, "下午" to 0, "夜晚" to 0)
        val allTextWords = mutableListOf<String>()
        var laughCount = 0
        var questionCount = 0
        var exclamationCount = 0
        var tildeCount = 0
        val lengthDist = mutableMapOf("≤5字" to 0, "6-20字" to 0, "21-50字" to 0, ">50字" to 0)

        for (msg in messages) {
            val type = MessageType.fromCode(msg.typeCode)
            val category = categorizeMessageType(type, msg.typeCode)
            typeCounts.mergeCount(category, 1, Int::plus)

            val senderId = extractSenderId(msg, membersMap)
            senderCounts.getOrPut(senderId) { mutableListOf() }.add(msg)

            val hour = (msg.createTime / 1000) % 86400 / 3600
            val period = when {
                hour < 6 -> "凌晨"
                hour < 12 -> "上午"
                hour < 18 -> "下午"
                else -> "夜晚"
            }
            timePeriods.mergeCount(period, 1, Int::plus)

            if (type?.isText == true) {
                val textContent = extractTextContent(msg, membersMap)
                val words = extractWords(textContent)
                allTextWords.addAll(words)

                val textLen = textContent.length
                when {
                    textLen <= 5 -> lengthDist.mergeCount("≤5字", 1, Int::plus)
                    textLen <= 20 -> lengthDist.mergeCount("6-20字", 1, Int::plus)
                    textLen <= 50 -> lengthDist.mergeCount("21-50字", 1, Int::plus)
                    else -> lengthDist.mergeCount(">50字", 1, Int::plus)
                }

                if (textContent.contains(Regex("[哈哈呵呵嘿嘿😂🤣]"))) laughCount++
                if (textContent.endsWith("?") || textContent.endsWith("？")) questionCount++
                if (textContent.endsWith("!") || textContent.endsWith("！")) exclamationCount++
                if (textContent.contains("~") || textContent.contains("～")) tildeCount++
            }
        }

        val activeSpeakers = senderCounts.size

        val sortedSpeakers = senderCounts.entries
            .sortedByDescending { it.value.size }
            .take(10)

        val wordFreq = allTextWords
            .groupBy { it }
            .mapValues { it.value.size }
            .filter { it.key.length >= 2 || it.key.all { c -> c.isLetterOrDigit() } }
            .filterNot { it.key in commonStopWords }
            .entries
            .sortedByDescending { it.value }
            .take(10)

        val sb = StringBuilder()
        sb.appendLine("群聊统计报告")
        sb.appendLine("统计周期:${startTime}至${endTime}")
        sb.appendLine("总消息:${totalCount}条 发言人数:${activeSpeakers}人")
        sb.appendLine("消息载体 图片:${typeCounts.getOrDefault("图片", 0)}条 语音:${typeCounts.getOrDefault("语音", 0)}条 文本:${typeCounts.getOrDefault("文本", 0)}条 视频:${typeCounts.getOrDefault("视频", 0)}条 系统:${typeCounts.getOrDefault("系统", 0)}条 文件/链接:${typeCounts.getOrDefault("文件/链接", 0)}条 表情:${typeCounts.getOrDefault("表情", 0)}条")
        sb.appendLine("发言排行")
        sortedSpeakers.forEachIndexed { index, (speaker, msgs) ->
            val displayName = membersMap[speaker] ?: speaker
            sb.appendLine("${index + 1}.$displayName:${msgs.size}条")
        }
        sb.appendLine("活跃时段 凌晨(0-5):${timePeriods["凌晨"]}条 上午(6-11):${timePeriods["上午"]}条 下午(12-17):${timePeriods["下午"]}条 夜晚(18-23):${timePeriods["夜晚"]}条")
        if (wordFreq.isNotEmpty()) {
            sb.append("高频词 ")
            wordFreq.forEachIndexed { index, (word, count) ->
                sb.append("$word:${count}次")
                if (index < wordFreq.size - 1) sb.append(" ")
            }
            sb.appendLine()
        }
        sb.appendLine("情绪指纹")
        val textMsgCount = typeCounts.getOrDefault("文本", 0).coerceAtLeast(1)
        sb.appendLine("笑点浓度:${"%.1f".format(laughCount.toDouble() / textMsgCount * 100)}% 疑问句比例:${"%.1f".format(questionCount.toDouble() / textMsgCount * 100)}% 感叹句比例:${"%.1f".format(exclamationCount.toDouble() / textMsgCount * 100)}% 波浪号比例:${"%.1f".format(tildeCount.toDouble() / textMsgCount * 100)}%")
        sb.appendLine("废话程度鉴定 ≤5字:${lengthDist["≤5字"]}条 6-20字:${lengthDist["6-20字"]}条 21-50字:${lengthDist["21-50字"]}条 >50字:${lengthDist[">50字"]}条")
        sb.appendLine("用户画像")
        sortedSpeakers.forEach { (speaker, msgs) ->
            val displayName = membersMap[speaker] ?: speaker
            val pct = "%.1f".format(msgs.size.toDouble() / totalCount * 100)
            val mainType = msgs.groupBy { m ->
                val t = MessageType.fromCode(m.typeCode)
                categorizeMessageType(t, m.typeCode)
            }.maxByOrNull { it.value.size }?.key ?: "文本"
            sb.appendLine("·$displayName:${msgs.size}条($pct%),主发$mainType")
        }
        sb.appendLine()
        sb.appendLine("Hchat 群聊统计·${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")

        return sb.toString()
    }

    private fun categorizeMessageType(type: MessageType?, rawCode: Int): String {
        if (type == null) return "其他"
        return when {
            type.isText -> "文本"
            rawCode == MessageType.IMAGE.code -> "图片"
            rawCode == MessageType.VOICE.code -> "语音"
            rawCode == MessageType.VIDEO.code || rawCode == MessageType.MICRO_VIDEO.code -> "视频"
            type.isSystem -> "系统"
            type.isSticker -> "表情"
            type.isLink || rawCode == MessageType.FILE.code -> "文件/链接"
            else -> "其他"
        }
    }

    private fun extractSenderId(msg: WeMessage, membersMap: Map<String, String>): String {
        if (msg.isSend != 0) return "我"
        val match = groupSenderRegex.find(msg.content)
        return match?.groupValues?.get(1) ?: "<未知>"
    }

    private fun extractTextContent(msg: WeMessage, membersMap: Map<String, String>): String {
        if (msg.isSend != 0) return msg.content
        val match = groupSenderRegex.find(msg.content)
        return match?.groupValues?.get(2) ?: msg.content
    }

    private fun extractWords(text: String): List<String> {
        return text.split(Regex("[\\s,，。！？、；：\"\"''（（））《》【】\\[\\]\\{\\}「」『』\\.!?;:，。！？、；：\n\r\t]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length >= 2 }
    }

    private val commonStopWords = setOf(
        "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一",
        "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
        "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "些",
        "什么", "怎么", "因为", "所以", "但是", "如果", "虽然", "可以", "这个",
        "那个", "吧", "吗", "啊", "嗯", "哦", "哈", "呀", "呢", "啦", "么",
        "还是", "就是", "不是", "只是", "但是", "而且", "或者", "然后", "以后",
        "时候", "现在", "已经", "可能", "应该", "没有", "觉得", "知道", "看到",
        "过来", "出来", "起来", "进去", "回到", "拿到", "想到", "我们", "你们",
        "他们", "大家", "东西", "意思", "时间", "朋友", "回复", "收到", "明白",
    )

    private fun <K> MutableMap<K, Int>.mergeCount(key: K, value: Int, op: (Int, Int) -> Int) {
        this[key] = op(this.getOrDefault(key, 0), value)
    }
}

private class GroupSummaryIcon : VectorPathDrawable(
    "M420,624L180,660L420,696L456,936L492,696L732,660L492,624L456,384ZM696,96L676,196L576,216L676,236L696,336L716,236L816,216L716,196Z"
)