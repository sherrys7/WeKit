package dev.ujhhgtg.wekit.features.items.chat
import dev.ujhhgtg.wekit.R

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.agent.MarkdownText
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
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
import java.util.Date
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.math.roundToInt

object GroupChatSummary : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {
    override val technicalId = "群聊统计报告"
    override val nameRes = R.string.feature_group_chat_summary_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_group_chat_summary_description

    private const val GROUP_SUMMARY_MENU_ID = 777029

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
        var showCapacityDialog by remember { mutableStateOf(false) }
        var generatedRangeRes by remember { mutableStateOf<Int?>(null) }
        var generatedAt by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        // 核心指标（今日/历史）与统计区数据；stats 携带时段标记，时段切换时重新加载
        var coreMetrics by remember { mutableStateOf<GroupCoreMetrics?>(null) }
        var statsState by remember { mutableStateOf<Pair<GroupTimeRange, GroupStats>?>(null) }
        LaunchedEffect(talker) { coreMetrics = loadCoreMetrics(talker) }
        LaunchedEffect(talker, timeRange) { statsState = timeRange to loadGroupStats(talker, timeRange) }

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
                    extractLimit = AiModelConfig.extractLimit,
                    onDelta = { delta ->
                        withContext(Dispatchers.Main) { report = report.orEmpty() + delta }
                    },
                    precomputedStats = statsState?.takeIf { it.first == timeRange }?.second,
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

                // 核心指标
                GroupSectionLabel(R.string.ui_group_stat_core_title)
                Spacer(Modifier.height(8.dp))
                coreMetrics?.let { GroupCoreMetricsCard(it) }

                Spacer(Modifier.height(16.dp))

                // 分组标题：智能摘要
                GroupSectionLabel(R.string.ui_group_smart_insight)

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

                    AnimatedVisibility(
                        visible = !collapsed,
                        enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(200)),
                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(150)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))

                        // 选择总结时段
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.ui_group_select_period),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { showCapacityDialog = true }, enabled = !isLoading) {
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

                Spacer(Modifier.height(16.dp))

                // 深度图表：本地统计可视化
                GroupSectionLabel(R.string.ui_group_deep_charts)
                Spacer(Modifier.height(8.dp))
                statsState?.let { (_, stats) -> GroupStatsCharts(stats) }

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

        AnimatedDialog(visible = showAiSettings, onDismiss = { showAiSettings = false }) {
            AiSettingsDialog(onDismiss = { showAiSettings = false })
        }
        AnimatedDialog(visible = showCapacityDialog, onDismiss = { showCapacityDialog = false }) {
            ModelCapacitySamplingDialog(
                capacity = modelCapacity,
                onCapacityChange = { modelCapacity = it },
                onDismiss = { showCapacityDialog = false },
            )
        }
    }

    /** 带遮罩与过渡动画的弹窗容器：淡入淡出遮罩 + 内容缩放上移进入/退出 */
    @Composable
    private fun AnimatedDialog(
        visible: Boolean,
        onDismiss: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(onClick = onDismiss),
                )
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220)) + slideInVertically(animationSpec = tween(220)) { it / 8 },
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(180)) + slideOutVertically(animationSpec = tween(180)) { it / 8 },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp),
            ) {
                content()
            }
        }
    }

    @Composable
    private fun GroupSectionLabel(titleRes: Int) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    @Composable
    private fun AiSettingsDialog(onDismiss: () -> Unit) {
        fun saveSetting(save: () -> Unit) {
            AiModelConfig.providerTypeName = ModelProviderType.OPENAI_CHAT_COMPLETION.name
            save()
            showToast("已保存 API 配置")
        }

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
                    SegmentedColumn {
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_base_url),
                                value = AiModelConfig.baseUrl,
                                onValueChange = { saveSetting { AiModelConfig.baseUrl = it.trim() } },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_base_url),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.ui_group_ai_settings_base_url_hint),
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_path),
                                value = AiModelConfig.apiPath,
                                onValueChange = { saveSetting { AiModelConfig.apiPath = it.trim() } },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_path),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.ui_group_ai_settings_path_hint),
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_api_key),
                                value = AiModelConfig.apiKey,
                                onValueChange = { saveSetting { AiModelConfig.apiKey = it.trim() } },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_api_key),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.ui_group_ai_settings_api_key_hint),
                                password = true,
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.ui_group_ai_settings_model_id),
                                value = AiModelConfig.modelId,
                                onValueChange = { saveSetting { AiModelConfig.modelId = it.trim() } },
                                dialogTitle = stringResource(R.string.ui_group_ai_settings_model_id),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                            )
                        }
                    }
                }
            },
            confirmButton = null,
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_close))
                }
            },
        )
    }

    @Composable
    private fun ModelCapacitySamplingDialog(
        capacity: ModelCapacity,
        onCapacityChange: (ModelCapacity) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var draftCapacity by remember { mutableStateOf(capacity) }
        var draftLimit by remember { mutableIntStateOf(AiModelConfig.extractLimit) }

        AlertDialogContent(
            title = {
                Text(
                    text = stringResource(R.string.ui_group_capacity_sampling_title),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.ui_group_model_capacity),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModelCapacity.entries.forEach { capacity ->
                            val selected = draftCapacity == capacity
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            ) {
                                Text(
                                    text = capacity.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable { draftCapacity = capacity }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.ui_group_extract_limit),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (draftLimit == 0) {
                                stringResource(R.string.ui_group_extract_auto)
                            } else {
                                "${draftLimit}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = draftLimit.toFloat(),
                        onValueChange = { draftLimit = it.roundToInt() },
                        valueRange = 0f..3000f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.ui_group_extract_limit_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCapacityChange(draftCapacity)
                        AiModelConfig.extractLimit = draftLimit
                        showToast("已保存")
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
        extractLimit: Int = 0,
        onDelta: suspend (String) -> Unit = {},
        precomputedStats: GroupStats? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val membersMap = loadGroupMembersMap(talker)

            val (startTime, endTime) = groupRangeStartEnd(range)
            val messagesInRange = WeDatabaseApi.getMessagesInRange(talker, startTime, endTime)

            if (messagesInRange.isEmpty()) {
                throw IllegalStateException("所选时间段内没有消息，无法生成统计报告")
            }

            // 统计与深度解析使用全部时段消息；自定义主题时 recentLines 内部按模型容量截取
            val messages = messagesInRange

            // 统计区已加载相同时段的数据时直接复用，避免重复查询
            val stats = precomputedStats ?: computeGroupStats(messages, membersMap)
            val statsReport = renderStatsReport(stats)

            // 配置了 AI 模型时，用 AI 生成智能群聊分析
            if (AiModelConfig.isConfigured()) {
                aiGenerateReport(messages, membersMap, talker, statsReport, customTopic, modelCapacity, extractLimit, onDelta)
            } else {
                throw IllegalStateException("未配置 AI 模型，请先点击右上角设置配置 API")
            }
        }
    }

    private fun buildAnalysisPrompt(
        statsReport: String,
        recentLines: String,
        customTopic: String? = null,
    ): Pair<String, String> {
        if (customTopic != null) {
            val systemPrompt = """你是微信群聊深度分析引擎，围绕用户指定主题，从群聊历史消息中提炼相关内容。
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
            val userPrompt = buildString {
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

        // 默认深度分析
        val systemPrompt = """你是一个微信聊天分析助手。请根据以下聊天记录，总结出这段时间内大家聊了哪些主要内容，重点话题，整体氛围如何，并提取一些有趣的点。语言请幽默生动，排版清晰。如果记录较少请简短回复。"""
        val userPrompt = buildString {
            appendLine("群聊统计数据：")
            appendLine(statsReport)
            appendLine()
            appendLine("聊天记录片段：")
            appendLine(recentLines)
        }
        return Pair(systemPrompt, userPrompt)
    }

    private suspend fun aiGenerateReport(
        messages: List<WeMessage>,
        membersMap: Map<String, String>,
        talker: String,
        statsReport: String,
        customTopic: String? = null,
        modelCapacity: ModelCapacity = ModelCapacity.K256,
        extractLimit: Int = 0,
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

        // 聊天片段：extractLimit > 0 时取最近 N 条；否则按容量自动估算（默认主题约 3000 条，自定义主题按 token 预算）
        val recentLines = if (extractLimit > 0) {
            messages.takeLast(extractLimit).joinToString("\n") { msg ->
                val sender = resolveSenderName(extractSenderId(msg, membersMap), membersMap)
                val text = extractTextContent(msg, membersMap)
                "$sender: $text"
            }
        } else {
            val autoLimit = if (customTopic != null) {
                // 自定义主题：按 token 预算估算字符数，从最新往回填
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
                // 默认主题：自动取最近约 1000 条，避免超出大部分模型上下文
                messages.takeLast(1000).joinToString("\n") { msg ->
                    val sender = resolveSenderName(extractSenderId(msg, membersMap), membersMap)
                    val text = extractTextContent(msg, membersMap)
                    "$sender: $text"
                }
            }
            autoLimit
        }

        val (systemPrompt, userPrompt) = buildAnalysisPrompt(statsReport, recentLines, customTopic)

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

}


private class GroupSummaryIcon : VectorPathDrawable(
    "M420,624L180,660L420,696L456,936L492,696L732,660L492,624L456,384ZM696,96L676,196L576,216L676,236L696,336L716,236L816,216L716,196Z"
)