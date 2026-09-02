package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.composables.icons.materialsymbols.outlined.Chips
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Extension
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Format_list_numbered
import com.composables.icons.materialsymbols.outlined.Graphic_eq
import com.composables.icons.materialsymbols.outlined.Groups
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Notes
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Sunny
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.ui.content.Button
import kotlin.math.roundToInt

// 设计图固定强调色（进度条/圆点等小元素，深浅色主题下均可读）
private val BarYellow = Color(0xFFFFC107)
private val BarRed = Color(0xFFEF5350)
private val BarBlue = Color(0xFF42A5F5)
private val BarPink = Color(0xFFEC407A)
private val BarGray = Color(0xFF9E9E9E)
private val BarGreen = Color(0xFF66BB6A)
private val BarOrange = Color(0xFFFFA726)
private val BarPurple = Color(0xFFAB47BC)
private val RankGold = Color(0xFFFFD54F)

/** 「核心指标」三列卡：今日发言人数 / 今日消息数 / 历史总消息 */
@Composable
internal fun GroupCoreMetricsCard(metrics: GroupCoreMetrics) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
            .padding(vertical = 16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            CoreMetricItem(MaterialSymbols.Outlined.Groups, metrics.todaySpeakers, R.string.ui_group_stat_core_today_speakers, Modifier.weight(1f))
            CoreMetricItem(MaterialSymbols.Outlined.Chat, metrics.todayMessages, R.string.ui_group_stat_core_today_messages, Modifier.weight(1f))
            CoreMetricItem(MaterialSymbols.Outlined.History, metrics.historyTotal, R.string.ui_group_stat_core_history_total, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CoreMetricItem(icon: ImageVector, value: Int, labelRes: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 「深度图表」统计模块组：8 个可折叠可视化卡（首卡为群聊活跃检测，含独立周期滑条；活跃发言排行使用独立时段，其余跟随总结时段） */
@Composable
internal fun GroupStatsCharts(
    stats: GroupStats,
    talker: String,
    onShowLowActivity: (List<LowActivityMember>) -> Unit,
) {
    var rankCollapsed by remember { mutableStateOf(true) }
    var wordsCollapsed by remember { mutableStateOf(true) }
    var routineCollapsed by remember { mutableStateOf(true) }
    var emotionCollapsed by remember { mutableStateOf(true) }
    var lengthCollapsed by remember { mutableStateOf(true) }
    var hourlyCollapsed by remember { mutableStateOf(true) }
    var typeCollapsed by remember { mutableStateOf(true) }

    // 群聊活跃检测：独立周期滑条（最近 1~60 天），含低活跃成员弹窗入口
    GroupActivityCard(talker, onShowLowActivity)
    Spacer(Modifier.height(12.dp))

    StatCard(MaterialSymbols.Outlined.Format_list_numbered, R.string.ui_group_stat_rank_title, rankCollapsed, { rankCollapsed = !rankCollapsed }) {
        GroupRankingCard(talker)
    }
    Spacer(Modifier.height(12.dp))
    StatCard(MaterialSymbols.Outlined.Chips, R.string.ui_group_stat_words_title, wordsCollapsed, { wordsCollapsed = !wordsCollapsed }) {
        WordCloud(stats)
    }
    Spacer(Modifier.height(12.dp))
    StatCard(MaterialSymbols.Outlined.Schedule, R.string.ui_group_stat_routine_title, routineCollapsed, { routineCollapsed = !routineCollapsed }) {
        RoutineGrid(stats)
    }
    Spacer(Modifier.height(12.dp))
    StatCard(MaterialSymbols.Outlined.Favorite, R.string.ui_group_stat_emotion_title, emotionCollapsed, { emotionCollapsed = !emotionCollapsed }) {
        EmotionBars(stats)
    }
    Spacer(Modifier.height(12.dp))
    StatCard(MaterialSymbols.Outlined.Notes, R.string.ui_group_stat_length_title, lengthCollapsed, { lengthCollapsed = !lengthCollapsed }) {
        LengthBars(stats)
    }
    Spacer(Modifier.height(12.dp))
    StatCard(MaterialSymbols.Outlined.Graphic_eq, R.string.ui_group_stat_hourly_title, hourlyCollapsed, { hourlyCollapsed = !hourlyCollapsed }) {
        HourlyBars(stats)
    }
    Spacer(Modifier.height(12.dp))
    StatCard(MaterialSymbols.Outlined.Extension, R.string.ui_group_stat_type_title, typeCollapsed, { typeCollapsed = !typeCollapsed }) {
        TypeBars(stats)
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    titleRes: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggle) {
                Icon(
                    if (collapsed) MaterialSymbols.Outlined.Expand_more else MaterialSymbols.Outlined.Expand_less,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!collapsed) {
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 活跃发言排行卡：独立于总结时段的日历区间选择（今天~去年），切换时段重新查询 */
@Composable
private fun GroupRankingCard(talker: String) {
    var rankingRange by remember { mutableStateOf(GroupTimeRange.TODAY) }
    var rankingState by remember { mutableStateOf<Pair<GroupTimeRange, List<RankingEntry>>?>(null) }
    LaunchedEffect(talker, rankingRange) {
        rankingState = rankingRange to loadGroupRanking(talker, rankingRange)
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GroupTimeRange.entries.forEach { range ->
                val selected = rankingRange == range
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
                            .clickable { rankingRange = range }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        val ranking = rankingState?.takeIf { it.first == rankingRange }?.second
        when {
            ranking == null -> Text(
                stringResource(R.string.ui_group_ranking_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ranking.isEmpty() -> Text(
                stringResource(R.string.ui_group_ranking_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> RankList(ranking)
        }
    }
}

/** 活跃发言排行：名次圆标 + 名称 + 条数 + 进度条 */
@Composable
private fun RankList(entries: List<RankingEntry>) {
    val maxCount = entries.maxOfOrNull { it.count } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEachIndexed { index, entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(if (index == 0) RankGold else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                Text(
                    stringResource(R.string.ui_group_stat_count_fmt, entry.count),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatBar(
                fraction = if (maxCount > 0) entry.count.toFloat() / maxCount else 0f,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 高频语义特征（词云）：按词频降序展示前 N 个高频词 */
@Composable
private fun WordCloud(stats: GroupStats) {
    val entries = stats.words.entries
        .sortedByDescending { it.value }
        .take(GroupAnalyzePrefs.reportWordCount())
    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.ui_group_stat_words_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val maxCount = entries.first().value.coerceAtLeast(1)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        entries.forEach { (word, count) ->
            val ratio = count.toFloat() / maxCount
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f + 0.16f * ratio),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = word,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (ratio > 0.6f) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 群聊活跃检测卡：独立周期滑条（最近 1~60 天，最左 1 天、最右 60 天），活跃/群成员/未发言三指标 + 低活跃成员弹窗入口 */
@Composable
internal fun GroupActivityCard(
    talker: String,
    onShowLowActivity: (List<LowActivityMember>) -> Unit,
) {
    var activityCollapsed by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<ActivityResult?>(null) }
    var days by remember { mutableStateOf(GroupAnalyzePrefs.reportActivityDays()) }
    LaunchedEffect(talker, days) {
        result = loadActivityResult(talker, days)
    }

    StatCard(MaterialSymbols.Outlined.Groups, R.string.ui_group_activity_title, activityCollapsed, { activityCollapsed = !activityCollapsed }) {
        // 周期滑条（样式对齐「分析深度」）：滑条值与天数正向映射，最左 1 天、最右 60 天
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ui_group_activity_period),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.ui_group_activity_days, days),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = days.toFloat(),
            onValueChange = {
                val newDays = it.roundToInt()
                if (newDays != days) {
                    days = newDays
                    GroupAnalyzePrefs.activityDays = newDays
                }
            },
            valueRange = 1f..60f,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            ActivityMetric(result?.activeMembers ?: 0, R.string.ui_group_activity_active, Modifier.weight(1f))
            ActivityMetric(result?.totalMembers ?: 0, R.string.ui_group_activity_total, Modifier.weight(1f))
            ActivityMetric((result?.totalMembers ?: 0) - (result?.activeMembers ?: 0), R.string.ui_group_activity_inactive, Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        val members = result?.members.orEmpty()
        Button(
            onClick = { onShowLowActivity(members) },
            enabled = members.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
        ) {
            Text(stringResource(R.string.ui_group_activity_show_inactive))
        }
    }
}

@Composable
private fun ActivityMetric(value: Int, labelRes: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class RoutineItem(val titleRes: Int, val timeText: String, val icon: ImageVector, val container: Color, val onContainer: Color)

/** 聊天作息图鉴：2x2 四宫格（时段计数） */
@Composable
private fun RoutineGrid(stats: GroupStats) {
    fun hourSum(from: Int, to: Int) = stats.hourly.subList(from, to + 1).sum()
    val colorScheme = MaterialTheme.colorScheme
    val items = listOf(
        RoutineItem(R.string.ui_group_stat_routine_owl, "0:00 - 04:00", MaterialSymbols.Outlined.Auto_awesome, colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer),
        RoutineItem(R.string.ui_group_stat_routine_early, "05:00 - 08:00", MaterialSymbols.Outlined.Sunny, colorScheme.secondaryContainer, colorScheme.onSecondaryContainer),
        RoutineItem(R.string.ui_group_stat_routine_work, "09:00 - 18:00", MaterialSymbols.Outlined.Notes, colorScheme.primaryContainer, colorScheme.onPrimaryContainer),
        RoutineItem(R.string.ui_group_stat_routine_night, "19:00 - 23:00", MaterialSymbols.Outlined.Schedule, colorScheme.errorContainer, colorScheme.onErrorContainer),
    )
    val counts = listOf(hourSum(0, 4), hourSum(5, 8), hourSum(9, 18), hourSum(19, 23))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEachIndexed { colIndex, item ->
                    val count = counts[rowIndex * 2 + colIndex]
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(item.container, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                    ) {
                        Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = item.onContainer)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(item.titleRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = item.onContainer)
                        Text(item.timeText, style = MaterialTheme.typography.labelSmall, color = item.onContainer.copy(alpha = 0.7f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.ui_group_stat_count_fmt, count),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = item.onContainer,
                        )
                    }
                }
            }
        }
    }
}

private data class LabeledCount(val labelRes: Int, val count: Int, val color: Color)

/** 情绪指数探测：5 项彩色指标条 */
@Composable
private fun EmotionBars(stats: GroupStats) {
    val items = listOf(
        LabeledCount(R.string.ui_group_stat_emotion_joy, stats.laughCount, BarYellow),
        LabeledCount(R.string.ui_group_stat_emotion_anger, stats.exclamationCount, BarRed),
        LabeledCount(R.string.ui_group_stat_emotion_curiosity, stats.questionCount, BarBlue),
        LabeledCount(R.string.ui_group_stat_emotion_cold, stats.coldCount, BarPink),
        LabeledCount(R.string.ui_group_stat_emotion_speechless, stats.speechlessCount, BarGray),
    )
    LabeledBars(items)
}

/** 废话程度鉴定：4 档消息长度分布 */
@Composable
private fun LengthBars(stats: GroupStats) {
    val items = listOf(
        LabeledCount(R.string.ui_group_stat_length_terse, stats.lengthDist[0], BarGreen),
        LabeledCount(R.string.ui_group_stat_length_normal, stats.lengthDist[1], BarBlue),
        LabeledCount(R.string.ui_group_stat_length_chatty, stats.lengthDist[2], BarOrange),
        LabeledCount(R.string.ui_group_stat_length_verbose, stats.lengthDist[3], BarPurple),
    )
    LabeledBars(items, showDot = false)
}

/** 全天活跃频次：24 小时柱状图 */
@Composable
private fun HourlyBars(stats: GroupStats) {
    val maxCount = stats.hourly.maxOrNull() ?: 0
    val barColor = MaterialTheme.colorScheme.primary
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            stats.hourly.forEachIndexed { hour, count ->
                val fraction = if (maxCount > 0) count.toFloat() / maxCount else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction.coerceIn(0.02f, 1f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(barColor),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("0", "6", "12", "18", "24").forEach { h ->
                Text(h, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 内容载体偏好：按设计图 8 类展示 */
@Composable
private fun TypeBars(stats: GroupStats) {
    fun codeSum(vararg types: MessageType) = stats.codeCounts.entries
        .filter { entry -> types.any { it.code == entry.key } }
        .sumOf { it.value }
    val known = mutableListOf<Int>().apply {
        add(codeSum(MessageType.IMAGE))
        add(codeSum(MessageType.QUOTE))
        add(codeSum(MessageType.TEXT))
        add(codeSum(MessageType.STICKER, MessageType.SO_GOU_EMOJI))
        add(codeSum(MessageType.SYSTEM, MessageType.SYSTEM_NOTICE))
        add(codeSum(MessageType.TRANSFER))
        add(codeSum(MessageType.VOICE))
    }
    val items = listOf(
        LabeledCount(R.string.ui_group_stat_type_image, known[0], MaterialTheme.colorScheme.primary),
        LabeledCount(R.string.ui_group_stat_type_quote, known[1], MaterialTheme.colorScheme.primary),
        LabeledCount(R.string.ui_group_stat_type_text, known[2], MaterialTheme.colorScheme.primary),
        LabeledCount(R.string.ui_group_stat_type_sticker, known[3], MaterialTheme.colorScheme.primary),
        LabeledCount(R.string.ui_group_stat_type_system, known[4], MaterialTheme.colorScheme.primary),
        LabeledCount(R.string.ui_group_stat_type_other, stats.totalMessages - known.sum(), MaterialTheme.colorScheme.primary),
        LabeledCount(R.string.ui_group_stat_type_transfer, known[5], MaterialTheme.colorScheme.primary),
        LabeledCount(R.string.ui_group_stat_type_voice, known[6], MaterialTheme.colorScheme.primary),
    )
    LabeledBars(items, showDot = false)
}

@Composable
private fun LabeledBars(items: List<LabeledCount>, showDot: Boolean = true) {
    val maxCount = items.maxOf { it.count }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDot) {
                    Box(Modifier.size(10.dp).background(item.color, CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(item.labelRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${item.count}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatBar(
                fraction = if (maxCount > 0) item.count.toFloat() / maxCount else 0f,
                color = item.color,
            )
        }
    }
}

@Composable
private fun StatBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
    }
}
