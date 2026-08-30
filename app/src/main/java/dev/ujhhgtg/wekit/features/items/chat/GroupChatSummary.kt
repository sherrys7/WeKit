package dev.ujhhgtg.wekit.features.items.chat
import dev.ujhhgtg.wekit.R

import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Photo_library
import com.composables.icons.materialsymbols.outlined.Send
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Tune
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
import dev.ujhhgtg.wekit.ui.agent.MarkdownText
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.VectorPathDrawable
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.io.path.absolutePathString

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
        GroupSummaryActivity.launch(view.context, talker)
    }

    @Composable
    internal fun GroupSummaryDialog(
        talker: String,
        onDismiss: () -> Unit,
    ) {
        var report by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var timeRange by remember { mutableStateOf(GroupTimeRange.TODAY) }
        var customTopic by remember { mutableStateOf("") }
        var modelCapacity by remember { mutableStateOf(ModelCapacity.K256) }
        var showAiSettings by remember { mutableStateOf(false) }
        var collapsed by remember { mutableStateOf(false) }
        var showAdvanced by remember { mutableStateOf(false) }
        var generatedRangeRes by remember { mutableStateOf<Int?>(null) }
        var generatedAt by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        fun startGenerate() {
            isLoading = true
            errorMessage = null
            report = null
            generatedRangeRes = timeRange.labelRes
            generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            scope.launch {
                val result = generateReport(
                    talker,
                    timeRange,
                    customTopic = customTopic.trim().takeIf { it.isNotEmpty() },
                    modelCapacity = modelCapacity,
                    onDelta = { delta ->
                        withContext(Dispatchers.Main) { report = report.orEmpty() + delta }
                    },
                )
                isLoading = false
                result.fold(
                    onSuccess = { report = it },
                    onFailure = { errorMessage = it.message ?: "未知错误" },
                )
            }
        }

        val rangeLabel = remember(timeRange) {
            val (start, end) = groupRangeStartEnd(timeRange)
            val f = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
            "${f.format(Date(start))} ~ ${f.format(Date(end))}"
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            // 标题区：分析报告 + 日期范围，右上角调节图标
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ui_group_analyse_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = rangeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        MaterialSymbols.Outlined.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(4.dp))

                // 分组标题
                Text(
                    text = stringResource(R.string.ui_group_smart_insight),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(8.dp))

                // 主卡片
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(24.dp),
                        )
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            RoundedCornerShape(24.dp),
                        )
                        .padding(16.dp),
                ) {
                    // 卡片头部：魔法棒图标 + 标题 + 折叠箭头
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                MaterialSymbols.Outlined.Auto_awesome,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.ui_group_summary_card_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { collapsed = !collapsed }) {
                            Icon(
                                if (collapsed) MaterialSymbols.Outlined.Expand_more else MaterialSymbols.Outlined.Expand_less,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (!collapsed) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))

                        // 选择总结时段
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.ui_group_select_period),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { showAdvanced = !showAdvanced }, enabled = !isLoading) {
                                Icon(
                                    MaterialSymbols.Outlined.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = { showAiSettings = true }, enabled = !isLoading) {
                                Icon(
                                    MaterialSymbols.Outlined.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // 时段标签横向滚动
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GroupTimeRange.entries.forEach { range ->
                                val selected = timeRange == range
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (selected) MaterialTheme.colorScheme.background else Color.Transparent,
                                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                ) {
                                    Text(
                                        text = stringResource(range.labelRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clickable(enabled = !isLoading) { timeRange = range }
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }

                        // 模型容量（筛选展开）
                        if (showAdvanced) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.ui_group_model_capacity),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ModelCapacity.entries.forEach { capacity ->
                                    val selected = modelCapacity == capacity
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    ) {
                                        Text(
                                            text = capacity.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .clickable(enabled = !isLoading) { modelCapacity = capacity }
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 自定义主题输入框
                        OutlinedTextField(
                            value = customTopic,
                            onValueChange = { customTopic = it },
                            placeholder = { Text(stringResource(R.string.ui_group_custom_topic_hint)) },
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(12.dp))

                        // 开始生成
                        Button(
                            onClick = ::startGenerate,
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Icon(MaterialSymbols.Outlined.Auto_awesome, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.ui_group_generate_start))
                        }

                        if (isLoading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 12.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在生成智能分析...", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        errorMessage?.let { err ->
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }

                        report?.let { result ->
                            Spacer(Modifier.height(12.dp))
                            val rangeText = generatedRangeRes?.let { stringResource(it) }
                            val timeText = generatedAt
                            if (rangeText != null || timeText != null) {
                                Text(
                                    text = buildString {
                                        rangeText?.let { append("【").append(it).append("总结】") }
                                        timeText?.let { append("生成时间：").append(it) }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            MarkdownText(
                                markdown = result,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // 底部操作区：复制文字 / 发送文字 / 保存图像 / 发送图像
                if (!isLoading && report != null) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = {
                                    copyToClipboard(report!!)
                                    showToast("已复制报告内容")
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(MaterialSymbols.Outlined.Content_copy, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.ui_group_copy_text))
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        if (WeMessageApi.sendText(talker, report!!)) {
                                            showToast("已发送报告")
                                            onDismiss()
                                        } else {
                                            showToast("发送失败，请查看日志")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(MaterialSymbols.Outlined.Send, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.ui_group_send_text))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val saved = GroupReportImage.saveToGallery(HostInfo.application, report!!)
                                        showToastSuspend(if (saved != null) "已保存长图到相册" else "保存长图失败")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(MaterialSymbols.Outlined.Download, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.ui_group_save_image))
                            }
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val path = GroupReportImage.renderToFile(report!!)
                                        if (WeMessageApi.sendImage(talker, path.absolutePathString())) {
                                            showToastSuspend("长图已发送")
                                        } else {
                                            showToastSuspend("发送长图失败，请查看日志")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(MaterialSymbols.Outlined.Photo_library, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.ui_group_send_image))
                            }
                        }
                    }
                    }
            }
        }

        if (showAiSettings) {
            AiSettingsDialog(onDismiss = { showAiSettings = false })
        }
    }

    @Composable
    private fun AiSettingsDialog(onDismiss: () -> Unit) {
        var baseUrl by remember { mutableStateOf(AiModelConfig.baseUrl) }
        var apiPath by remember { mutableStateOf(AiModelConfig.apiPath) }
        var apiKey by remember { mutableStateOf(AiModelConfig.apiKey) }
        var modelId by remember { mutableStateOf(AiModelConfig.modelId) }

        AlertDialogContent(
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.ui_group_ai_settings_title),
                        modifier = Modifier.weight(1f),
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.ui_group_ai_settings_base_url)) },
                        placeholder = { Text(stringResource(R.string.ui_group_ai_settings_base_url_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiPath,
                        onValueChange = { apiPath = it },
                        label = { Text(stringResource(R.string.ui_group_ai_settings_path)) },
                        placeholder = { Text(stringResource(R.string.ui_group_ai_settings_path_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.ui_group_ai_settings_api_key)) },
                        placeholder = { Text(stringResource(R.string.ui_group_ai_settings_api_key_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        label = { Text(stringResource(R.string.ui_group_ai_settings_model_id)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        AiModelConfig.providerTypeName = ModelProviderType.OPENAI_CHAT_COMPLETION.name
                        AiModelConfig.baseUrl = baseUrl.trim()
                        AiModelConfig.apiPath = apiPath.trim()
                        AiModelConfig.apiKey = apiKey.trim()
                        AiModelConfig.modelId = modelId.trim()
                        showToast("已保存 API 配置")
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    private suspend fun generateReport(
        talker: String,
        range: GroupTimeRange,
        customTopic: String? = null,
        modelCapacity: ModelCapacity = ModelCapacity.K256,
        onDelta: suspend (String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val contacts = WeDatabaseApi.getGroupMembers(talker).associate { m ->
                m.wxId to (m.remarkName.takeUnless { it.isBlank() }?.let { "$it (${m.nickname})" } ?: m.nickname)
            }
            // 群内昵称优先，其次联系人备注/昵称
            val nicknameMap = WeDatabaseApi.getGroupNicknameMap(talker)
            val membersMap = contacts.toMutableMap().apply {
                nicknameMap.forEach { (wxId, nick) -> this[wxId] = nick }
            }

            val (startTime, endTime) = groupRangeStartEnd(range)
            val messagesInRange = WeDatabaseApi.getMessagesInRange(talker, startTime, endTime)

            if (messagesInRange.isEmpty()) {
                throw IllegalStateException("所选时间段内没有消息，无法生成统计报告")
            }

            // 统计与深度解析使用全部时段消息；自定义主题时 recentLines 内部按模型容量截取
            val messages = messagesInRange

            val statsReport = buildReport(messages, membersMap, talker)

            // 配置了 AI 模型时，用 AI 生成智能群聊分析
            if (AiModelConfig.isConfigured()) {
                aiGenerateReport(messages, membersMap, talker, statsReport, 2, customTopic, modelCapacity, onDelta)
            } else {
                throw IllegalStateException("未配置 AI 模型，请先点击右上角设置配置 API")
            }
        }
    }

    private fun buildAnalysisPrompt(
        depth: Int,
        statsReport: String,
        recentLines: String,
        customTopic: String? = null,
    ): Pair<String, String> {
        val systemPrompt: String
        val userPrompt: String

        if (customTopic != null) {
            systemPrompt = """你是微信群聊深度分析引擎，围绕用户指定主题，从群聊历史消息中提炼相关内容。
围绕主题：【$customTopic】
输出结构：
【主题概览】概述群聊中与该主题相关的整体情况。
【相关内容】按时间或逻辑梳理与该主题相关的讨论、事件、观点、进展。
【涉及人员】列出参与该主题讨论的成员及其主要观点、立场（不需要过度揣测隐私）。
【待办/行动项】与该主题相关的通知、任务、邀约、时间安排等行动信息，没有则填无。
【总结建议】结合讨论内容给出客观总结与参考建议。

硬性约束：
1. 只围绕用户指定主题分析，忽略无关闲聊内容。
2. 禁止脑补编造聊天中不存在的信息，信息不足时如实说明。
3. 使用 Markdown 排版输出，结构清晰、层级分明，便于手机阅读与生成长图。
4. 适度使用标题、列表、引用、加粗等 Markdown 语法，不滥用复杂嵌套。"""
            userPrompt = buildString {
                appendLine("群聊统计数据：")
                appendLine(statsReport)
                appendLine()
                appendLine("聊天记录片段：")
                appendLine(recentLines)
                appendLine()
                appendLine("请围绕主题【$customTopic】进行深度分析。")
            }
            return Pair(systemPrompt, userPrompt)
        }

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
3、可用 Markdown 标题、列表等简单语法排版，层次清晰
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
3、可用 Markdown 标题、列表、加粗等简单语法排版，层级清晰。
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
                // 深度分析：联想标题 + 内容概览 + 灵活模块（4~6 个）
                systemPrompt = """你为微信群聊分析引擎，基于群聊历史消息生成一份分析报告。

报告结构：
先根据聊天内容联想一个合适的标题作为开头，第一段进行简单说明或内容概览，然后进入具体模块分析。模块参考：
1. 主要内容：概括今天群里整体都在聊哪些大类事情，梳理聊天脉络。
2. 重点话题：提炼出几个核心讨论话题，每个话题简要说明大家聊了什么，不要逐句摘抄对话。
3. 整体氛围：描述群内聊天的情绪氛围，例如轻松搞笑、吐槽感慨、闲聊摆烂等。
4. 有趣亮点：提取好玩的梗、搞笑发言、有意思的分享、名场面，无关灌水碎话忽略。
5. 总结：最后做一段简短整体小结，如果有相约出行、聚会等计划，写明已确定内容和尚未敲定的部分，如没有则不需要提及。

模块标题不必与参考完全一致，模块数量也可以是四个或六个，按聊天内容灵活组织。

硬性约束：
1.禁止脑补编造聊天不存在的信息，信息不足时如实说明，不强行分析。
2.使用 Markdown 排版输出，结构清晰、层级分明，便于手机阅读与生成长图。
3.适度使用标题、列表、引用、加粗等 Markdown 语法，不滥用复杂嵌套。"""
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
        customTopic: String? = null,
        modelCapacity: ModelCapacity = ModelCapacity.K256,
        onDelta: suspend (String) -> Unit = {},
    ): String {
        check(AiModelConfig.resolvedBaseUrl().isNotBlank()) { "未配置 API 地址" }
        check(AiModelConfig.apiKey.isNotBlank()) { "未配置 API Key" }
        check(AiModelConfig.modelId.isNotBlank()) { "未配置模型 ID" }

        val provider = ModelProviderEntity(
            id = "ai_reply",
            type = AiModelConfig.providerType(),
            name = "AI回复",
            baseUrl = AiModelConfig.resolvedBaseUrl(),
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

        // 聊天片段：自定义主题时按容量给更多最近消息（估算每 token 约 1.5 字符），否则取最近 30 条
        val recentLines = if (customTopic != null) {
            val budgetChars = modelCapacity.tokens * 3 / 2
            val builder = StringBuilder()
            var used = 0
            for (msg in messages.asReversed()) {
                val line = "${resolveSenderName(extractSenderId(msg, membersMap), membersMap)}: ${extractTextContent(msg, membersMap)}"
                used += line.length
                if (used > budgetChars) break
                builder.insert(0, line + "\n")
            }
            builder.toString().trimEnd('\n')
        } else {
            messages.takeLast(30).joinToString("\n") { msg ->
                val sender = resolveSenderName(extractSenderId(msg, membersMap), membersMap)
                val text = extractTextContent(msg, membersMap)
                "$sender: $text"
            }
        }

        val (systemPrompt, userPrompt) = buildAnalysisPrompt(depth, statsReport, recentLines, customTopic)

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
                    onDelta(event.text)
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

            val hour = (msg.createTime / 1000 % 86400) / 3600
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
        // 发送者 wxid 必须是群内真实成员，避免特殊消息格式导致误切分
        val match = groupSenderRegex.find(msg.content)
        val rawSender = match?.groupValues?.get(1) ?: return "<未知>"
        if (membersMap.containsKey(rawSender)) return rawSender
        // 微信部分消息中发送者可能带后缀（如 xxxx:xxx）或被截断，尝试模糊匹配已知成员
        return membersMap.keys.firstOrNull { rawSender.startsWith(it) } ?: rawSender
    }

    private fun resolveSenderName(senderId: String, membersMap: Map<String, String>): String =
        membersMap[senderId] ?: senderId

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

/** 群聊分析时间范围 */
private enum class GroupTimeRange(val labelRes: Int) {
    TODAY(R.string.ui_group_range_today),
    YESTERDAY(R.string.ui_group_range_yesterday),
    THIS_WEEK(R.string.ui_group_range_this_week),
    LAST_WEEK(R.string.ui_group_range_last_week),
    THIS_MONTH(R.string.ui_group_range_this_month),
    LAST_MONTH(R.string.ui_group_range_last_month),
    THIS_YEAR(R.string.ui_group_range_this_year),
    LAST_YEAR(R.string.ui_group_range_last_year),
}

/** AI 上下文容量档位（token） */
private enum class ModelCapacity(val tokens: Long, val label: String) {
    K128(128 * 1024L, "128K"),
    K256(256 * 1024L, "256K"),
    K512(512 * 1024L, "512K"),
    M1(1024 * 1024L, "1M"),
}

/** 计算时间段 [start, end]（毫秒时间戳，与微信 message.createTime 单位一致） */
private fun groupRangeStartEnd(range: GroupTimeRange): Pair<Long, Long> {
    val now = System.currentTimeMillis()
    val startCal = Calendar.getInstance().apply { timeInMillis = now }
    val endCal = Calendar.getInstance().apply { timeInMillis = now }

    fun clearTime(c: Calendar) {
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
    }

    when (range) {
        GroupTimeRange.TODAY -> clearTime(startCal)
        GroupTimeRange.YESTERDAY -> {
            startCal.add(Calendar.DAY_OF_YEAR, -1)
            clearTime(startCal)
            clearTime(endCal)
        }
        GroupTimeRange.THIS_WEEK -> {
            startCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            clearTime(startCal)
        }
        GroupTimeRange.LAST_WEEK -> {
            startCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            clearTime(startCal)
            startCal.add(Calendar.WEEK_OF_YEAR, -1)
            endCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            clearTime(endCal)
        }
        GroupTimeRange.THIS_MONTH -> {
            startCal.set(Calendar.DAY_OF_MONTH, 1)
            clearTime(startCal)
        }
        GroupTimeRange.LAST_MONTH -> {
            startCal.set(Calendar.DAY_OF_MONTH, 1)
            clearTime(startCal)
            startCal.add(Calendar.MONTH, -1)
            endCal.set(Calendar.DAY_OF_MONTH, 1)
            clearTime(endCal)
        }
        GroupTimeRange.THIS_YEAR -> {
            startCal.set(Calendar.DAY_OF_YEAR, 1)
            clearTime(startCal)
        }
        GroupTimeRange.LAST_YEAR -> {
            startCal.set(Calendar.DAY_OF_YEAR, 1)
            clearTime(startCal)
            startCal.add(Calendar.YEAR, -1)
            endCal.set(Calendar.DAY_OF_YEAR, 1)
            clearTime(endCal)
        }
    }
    return startCal.timeInMillis to endCal.timeInMillis
}

private class GroupSummaryIcon : VectorPathDrawable(
    "M420,624L180,660L420,696L456,936L492,696L732,660L492,624L456,384ZM696,96L676,196L576,216L676,236L696,336L716,236L816,216L716,196Z"
)