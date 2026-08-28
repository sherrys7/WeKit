package dev.ujhhgtg.wekit.features.items.chat

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Analytics
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.AnalyticsIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AnalyzeMessages : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "分析消息"
    override val nameRes = R.string.feature_analyze_messages_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_analyze_messages_description

    private const val TAG = "AnalyzeMessages"
    private const val MENU_ID = 777026
    private const val SENDER_TOP_N = 50

    var sampleLimit by prefOption("ana_sample_limit", 500)
    var minWordLen by prefOption("ana_min_len", 2)
    var wordCount by prefOption("ana_word_count", 40)

    private val mh = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

    data class SenderStat(val name: String, val count: Int, val isMe: Boolean)

    data class AnalyzeResult(
        val displayName: String,
        val isGroup: Boolean,
        val totalCount: Int,
        val myCount: Int,
        val otherCount: Int,
        val senderRanks: List<SenderStat>,
        val typeCounts: List<Pair<String, Int>>,
        val hourly: IntArray,
        val topWords: List<Pair<String, Int>>,
        val emoWords: List<Pair<String, Int>>,
        val chineseChars: Int,
        val englishChars: Int,
        val otherChars: Int,
        val avgLen: Int,
        val firstTime: Long,
        val lastTime: Long,
    )

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                MENU_ID,
                localizedChatString(R.string.chat_analyze_menu),
                AnalyticsIcon,
                MaterialSymbols.Outlined.Analytics,
                isSupported = { true },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
                onClick = { view, _, msgInfo ->
                    if (msgInfo.talker.isEmpty()) {
                        showToast(view.context, "无法获取当前会话")
                        return@MenuItem
                    }
                    showAnalysisDialog(view.context, msgInfo.talker)
                }
            )
        )
    }

    private fun showAnalysisDialog(context: android.content.Context, talker: String) {
        var loading by mutableStateOf(true)
        var result by mutableStateOf<AnalyzeResult?>(null)

        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(localizedChatString(R.string.chat_analyze_menu)) },
                text = {
                    when {
                        loading -> {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "正在分析聊天记录…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        result == null -> {
                            Text("分析失败，请稍后重试")
                        }

                        else -> {
                            val r = result!!
                            LazyAnalysisContent(r)
                        }
                    }
                },
                confirmButton = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showSettingsDialog(context) }) {
                            Text("参数设置")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onDismiss() }) {
                            Text("关闭")
                        }
                    }
                }
            )
        }

        analyze(talker) { res ->
            loading = false
            result = res
        }
    }

    @Composable
    private fun LazyAnalysisContent(r: AnalyzeResult) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SummaryCard(r)
            Spacer(Modifier.height(8.dp))
            SendCard(r)
            Spacer(Modifier.height(8.dp))
            TypeCard(r)
            Spacer(Modifier.height(8.dp))
            HourlyCard(r)
            if (r.isGroup) {
                Spacer(Modifier.height(8.dp))
                RankCard(r)
            }
            if (r.topWords.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                WordsCard(r)
            }
            Spacer(Modifier.height(8.dp))
            RatioCard(r)
        }
    }

    @Composable
    private fun SectionCard(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }

    @Composable
    private fun StatBar(label: String, value: Int, max: Int, color: Color = MaterialTheme.colorScheme.primary) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(72.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(12.dp)
                    .background(
                        color.copy(alpha = 0.15f),
                        RoundedCornerShape(6.dp),
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(value.toFloat() / max.coerceAtLeast(1).toFloat())
                        .height(12.dp)
                        .background(color, RoundedCornerShape(6.dp))
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("$value", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun SummaryCard(r: AnalyzeResult) {
        val span = if (r.firstTime > 0 && r.lastTime >= r.firstTime) {
            "${dateFormat.format(Date(r.firstTime * 1000))} ~ ${dateFormat.format(Date(r.lastTime * 1000))}"
        } else {
            "暂无数据"
        }
        SectionCard("概览") {
            InfoRow("会话", r.displayName)
            InfoRow("时间跨度", span)
            InfoRow("消息总数", "${r.totalCount}")
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun SendCard(r: AnalyzeResult) {
        SectionCard("收发统计") {
            StatBar("我", r.myCount, r.myCount + r.otherCount)
            Spacer(Modifier.height(4.dp))
            StatBar(
                "对方",
                r.otherCount,
                r.myCount + r.otherCount,
                MaterialTheme.colorScheme.tertiary,
            )
        }
    }

    @Composable
    private fun TypeCard(r: AnalyzeResult) {
        val max = r.typeCounts.maxOfOrNull { it.second } ?: 1
        SectionCard("消息类型分布") {
            if (r.typeCounts.isEmpty()) {
                Text("暂无数据", style = MaterialTheme.typography.bodyMedium)
            } else {
                r.typeCounts.forEach { (label, count) ->
                    StatBar(label, count, max)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    @Composable
    private fun HourlyCard(r: AnalyzeResult) {
        SectionCard("24 小时活跃度") {
            val max = r.hourly.max().coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.Bottom) {
                for (h in 0 until 24) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .width(6.dp)
                                .height(if (r.hourly[h] > 0) (40f * r.hourly[h] / max).dp else 2.dp)
                                .background(
                                    if (r.hourly[h] > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(3.dp),
                                )
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0点", style = MaterialTheme.typography.labelSmall)
                Text("12点", style = MaterialTheme.typography.labelSmall)
                Text("24点", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    @Composable
    private fun RankCard(r: AnalyzeResult) {
        SectionCard("发送者排行 TOP ${r.senderRanks.size}") {
            if (r.senderRanks.isEmpty()) {
                Text("暂无数据", style = MaterialTheme.typography.bodyMedium)
            } else {
                r.senderRanks.forEachIndexed { index, s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(28.dp),
                        )
                        Text(
                            s.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (s.isMe) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${s.count}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    @Composable
    private fun WordsCard(r: AnalyzeResult) {
        SectionCard("高频词") {
            r.topWords.chunked(3).forEach { rowWords ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    rowWords.forEach { (word, count) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(end = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "$word $count",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                    repeat(3 - rowWords.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (r.emoWords.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("语气词", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    r.emoWords.joinToString("  ") { (w, c) -> "$w×$c" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @Composable
    private fun RatioCard(r: AnalyzeResult) {
        val totalChars = r.chineseChars + r.englishChars + r.otherChars
        SectionCard("内容统计") {
            StatBar("中文", r.chineseChars, totalChars)
            Spacer(Modifier.height(4.dp))
            StatBar("英文", r.englishChars, totalChars, MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(4.dp))
            StatBar("其他", r.otherChars, totalChars, MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            InfoRow("平均消息长度", "${r.avgLen} 字")
        }
    }

    private fun showSettingsDialog(context: android.content.Context) {
        showComposeDialog(context) {
            var sample by remember { mutableIntStateOf(sampleLimit) }
            var minLen by remember { mutableIntStateOf(minWordLen) }
            var count by remember { mutableIntStateOf(wordCount) }

            AlertDialogContent(
                title = { Text("分析参数设置") },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = "采样条数上限",
                                    description = "参与分析的最近消息条数",
                                    value = sample,
                                    startInt = 50,
                                    endInt = 5000,
                                    stepSize = 50,
                                    onValueChange = { sample = it; sampleLimit = it },
                                )
                            }
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = "最小词长",
                                    description = "忽略短于此长度的词",
                                    value = minLen,
                                    startInt = 1,
                                    endInt = 10,
                                    stepSize = 1,
                                    onValueChange = { minLen = it; minWordLen = it },
                                )
                            }
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = "高频词数量",
                                    description = "词频统计展示的数量上限",
                                    value = count,
                                    startInt = 10,
                                    endInt = 100,
                                    stepSize = 5,
                                    onValueChange = { count = it; wordCount = it },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { onDismiss() }) {
                        Text("完成")
                    }
                }
            )
        }
    }

    private fun analyze(talker: String, cb: (AnalyzeResult?) -> Unit) {
        Thread {
            val result = runCatching { doAnalyze(talker) }
                .onFailure { WeLogger.e(TAG, "analyze messages failed for $talker", it) }
                .getOrNull()
            mh.post { cb(result) }
        }.start()
    }

    private fun doAnalyze(talker: String): AnalyzeResult {
        val isGroup = talker.isGroupChatWxId
        val displayName = runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(talker)
        val selfWxId = WeApi.selfWxId
        val otherName = if (isGroup) "群成员" else displayName

        val limit = sampleLimit.coerceIn(1, 50000)

        val sendRows = WeDatabaseApi.executeQuery(
            "SELECT isSend, COUNT(*) AS cnt FROM message WHERE talker = ? AND type != 10000 GROUP BY isSend",
            arrayOf(talker)
        )
        var myCount = 0
        var otherCount = 0
        for (row in sendRows) {
            val cnt = rowInt(row, "cnt")
            if (rowInt(row, "isSend") == 1) myCount += cnt else otherCount += cnt
        }
        val totalCount = myCount + otherCount

        val sampleRows = WeDatabaseApi.executeQuery(
            "SELECT content, createTime, type, isSend FROM message WHERE talker = ? ORDER BY createTime DESC LIMIT $limit",
            arrayOf(talker)
        )

        val typeMap = LinkedHashMap<String, Int>()
        val senderMap = LinkedHashMap<String, Int>()
        val wordMap = LinkedHashMap<String, Int>()
        val emoMap = LinkedHashMap<String, Int>()
        val hourly = IntArray(24)
        var chineseChars = 0
        var englishChars = 0
        var otherChars = 0
        var textLenSum = 0
        var textMsgCount = 0
        var firstTime = 0L
        var lastTime = 0L

        for (row in sampleRows) {
            val content = rowStr(row, "content")
            val createTime = rowLong(row, "createTime")
            val typeCode = rowInt(row, "type")
            val isSend = rowInt(row, "isSend")

            if (createTime > 0) {
                if (firstTime == 0L || createTime < firstTime) firstTime = createTime
                if (createTime > lastTime) lastTime = createTime
                val hour = (createTime % 86400L / 3600L).toInt()
                if (hour in 0..23) hourly[hour]++
            }

            val type = MessageType.fromCode(typeCode)
            val label = when {
                typeCode == MessageType.IMAGE.code && content.contains("<ext>gif</ext>") -> "GIF"
                type != null -> type.displayName
                else -> "其他"
            }
            typeMap[label] = typeMap.getOrDefault(label, 0) + 1

            when {
                isSend == 1 -> senderMap[ME] = senderMap.getOrDefault(ME, 0) + 1
                isGroup -> {
                    val nick = content.substringBefore(':').ifBlank { "未知" }
                    senderMap[nick] = senderMap.getOrDefault(nick, 0) + 1
                }
                else -> senderMap[otherName] = senderMap.getOrDefault(otherName, 0) + 1
            }

            if (typeCode == MessageType.TEXT.code) {
                for (w in tokenize(content, minWordLen.coerceAtLeast(1))) {
                    wordMap[w] = wordMap.getOrDefault(w, 0) + 1
                }
                for (emo in EMOTION_WORDS) {
                    val c = emo.toRegex().findAll(content).count()
                    if (c > 0) emoMap[emo] = emoMap.getOrDefault(emo, 0) + c
                }
                val len = content.length
                textLenSum += len
                textMsgCount++
                val cn = charChineseRegex.findAll(content).count()
                val en = charEnglishRegex.findAll(content).count()
                chineseChars += cn
                englishChars += en
                otherChars += len - cn - en
            }
        }

        val senderRanks = senderMap.entries
            .sortedByDescending { it.value }
            .take(SENDER_TOP_N)
            .map { (name, count) -> SenderStat(name, count, name == ME) }

        val topWords = wordMap.entries
            .sortedByDescending { it.value }
            .take(wordCount.coerceIn(1, 200))
            .map { it.key to it.value }

        val emoWords = emoMap.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

        return AnalyzeResult(
            displayName = displayName,
            isGroup = isGroup,
            totalCount = totalCount,
            myCount = myCount,
            otherCount = otherCount,
            senderRanks = senderRanks,
            typeCounts = typeMap.entries.map { it.key to it.value },
            hourly = hourly,
            topWords = topWords,
            emoWords = emoWords,
            chineseChars = chineseChars,
            englishChars = englishChars,
            otherChars = otherChars,
            avgLen = if (textMsgCount > 0) textLenSum / textMsgCount else 0,
            firstTime = firstTime,
            lastTime = lastTime,
        )
    }

    private fun tokenize(text: String, minLen: Int): List<String> {
        val words = mutableListOf<String>()
        for (m in chineseRegex.findAll(text)) words.add(m.value)
        for (m in englishRegex.findAll(text)) words.add(m.value)
        return words.filter { it.length >= minLen }
    }

    private fun rowStr(row: Map<String, Any?>, key: String): String = row[key]?.toString() ?: ""

    private fun rowInt(row: Map<String, Any?>, key: String): Int {
        return when (val v = row[key]) {
            is Int -> v
            is Long -> v.toInt()
            is Number -> v.toInt()
            else -> 0
        }
    }

    private fun rowLong(row: Map<String, Any?>, key: String): Long {
        return when (val v = row[key]) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toLong()
            else -> 0L
        }
    }

    private val chineseRegex = Regex("[\\u4e00-\\u9fa5]+")
    private val englishRegex = Regex("[a-zA-Z0-9]+")
    private val charChineseRegex = Regex("[\\u4e00-\\u9fa5]")
    private val charEnglishRegex = Regex("[a-zA-Z]")

    private val ME = "我"

    private val EMOTION_WORDS = listOf("吗", "哈", "无语", "笑", "！", "？", "~", "。。。")
}
