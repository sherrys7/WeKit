package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 核心指标：今日发言人数 / 今日消息数 / 历史总消息 */
internal data class GroupCoreMetrics(
    val todaySpeakers: Int,
    val todayMessages: Int,
    val historyTotal: Int,
)

/** 单个成员的发言统计（含主发消息类型，供文本报告「用户画像」使用） */
internal data class SenderStat(val name: String, val count: Int, val mainType: String)

/** 群聊统计结果（结构化，供可视化卡片与文本报告共用） */
internal data class GroupStats(
    val periodStart: Long,
    val periodEnd: Long,
    val totalMessages: Int,
    val speakerCount: Int,
    val textCount: Int,
    val senders: List<SenderStat>,
    val hourly: List<Int>,
    val codeCounts: Map<Int, Int>,
    val words: Map<String, Int>,
    val laughCount: Int,
    val exclamationCount: Int,
    val questionCount: Int,
    val tildeCount: Int,
    val coldCount: Int,
    val speechlessCount: Int,
    val lengthDist: List<Int>,
)

/** 活跃发言排行条目（独立时段统计） */
internal data class RankingEntry(val name: String, val count: Int)

/** 群聊分析时间范围 */
internal enum class GroupTimeRange(val labelRes: Int) {
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
internal enum class ModelCapacity(val tokens: Long, val label: String) {
    K128(128 * 1024L, "128K"),
    K256(256 * 1024L, "256K"),
    K512(512 * 1024L, "512K"),
    M1(1024 * 1024L, "1M"),
    M2(2048 * 1024L, "2M"),
}

/** AI 容量档位对应的自动提取消息条数上限（对应 Hchat aiAutoMessageLimit） */
internal fun ModelCapacity.autoMessageLimit(): Int = when (this) {
    ModelCapacity.K128 -> 3000
    ModelCapacity.K256 -> 6000
    ModelCapacity.K512 -> 12000
    ModelCapacity.M1 -> 25000
    ModelCapacity.M2 -> 50000
}

/** 深度分析采样与词云设置（对应 Hchat ana_sample_limit / ana_word_count / ana_min_len） */
internal object GroupAnalyzePrefs {
    var sampleLimit by WePrefs.prefOption("ana_sample_limit", 500)
    var wordCount by WePrefs.prefOption("ana_word_count", 40)
    var minWordLength by WePrefs.prefOption("ana_min_len", 2)
    var activityDays by WePrefs.prefOption("ana_activity_days", 7)

    fun reportSampleLimit(): Int = sampleLimit.coerceIn(100, 50_000)
    fun reportWordCount(): Int = wordCount.coerceIn(10, 80)
    fun reportMinWordLength(): Int = minWordLength.coerceIn(2, 10)
    fun reportActivityDays(): Int = activityDays.coerceIn(1, 60)
}

/** 计算时间段 [start, end]（毫秒时间戳，与微信 message.createTime 单位一致） */
internal fun groupRangeStartEnd(range: GroupTimeRange): Pair<Long, Long> {
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

private val groupSenderRegex = Regex("""^([^\n:]+):\n(.+)""", setOf(RegexOption.DOT_MATCHES_ALL))

internal fun extractSenderId(msg: WeMessage, membersMap: Map<String, String>): String {
    if (msg.isSend != 0) return "我"
    // 发送者 wxid 必须是群内真实成员，避免特殊消息格式导致误切分
    val match = groupSenderRegex.find(msg.content)
    val rawSender = match?.groupValues?.get(1) ?: return "<未知>"
    if (membersMap.containsKey(rawSender)) return rawSender
    // 微信部分消息中发送者可能带后缀（如 xxxx:xxx）或被截断，尝试模糊匹配已知成员
    return membersMap.keys.firstOrNull { rawSender.startsWith(it) } ?: rawSender
}

internal fun resolveSenderName(senderId: String, membersMap: Map<String, String>): String =
    membersMap[senderId] ?: senderId

internal fun extractTextContent(msg: WeMessage, membersMap: Map<String, String>): String {
    if (msg.isSend != 0) return msg.content
    val match = groupSenderRegex.find(msg.content)
    return match?.groupValues?.get(2) ?: msg.content
}

private val perfunctoryReplyRegex =
    Regex("^(嗯+|哦+|噢+|啊+|哈+|好|好的|行|好吧|中|6+|ok|OK|Ok)[~～]?\\s*$")

private val speechlessRegex = Regex("。{2,}|…+|无语|服了|醉了")

private val laughRegex = Regex("[哈哈呵呵嘿嘿😂🤣]")

private const val WORD_STOP_WORDS =
    "我们你们他们这个那个什么怎么可以就是不是没有一个现在然后因为所以已经还是感觉知道真哈哈呵呵好的收到表情图片视频语音消息"

/** 文本清洗（对应 Hchat cleanMessageText）：截断、剥离发送者前缀与 XML 标签 */
internal fun cleanMessageText(raw: String): String {
    var value = raw
    if (value.length > 2000) value = value.substring(0, 2000)
    val split = value.indexOf(":\n")
    if (split > 0 && split < 80) value = value.substring(split + 2)
    value = value.replace(Regex("<[^>]+>"), " ")
    value = value.trim()
    return if (value.length > 600) value.substring(0, 600) else value
}

private fun <K> MutableMap<K, Int>.mergeCount(key: K, value: Int, op: (Int, Int) -> Int) {
    this[key] = op(this.getOrDefault(key, 0), value)
}

/** 文本报告的消息载体分类（与 UI 侧「内容载体偏好」分类独立） */
internal fun categorizeMessageType(type: MessageType?, rawCode: Int): String {
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

internal fun computeGroupStats(messages: List<WeMessage>, membersMap: Map<String, String>): GroupStats {
    val totalCount = messages.size

    val codeCounts = mutableMapOf<Int, Int>()
    val senderCounts = mutableMapOf<String, MutableList<WeMessage>>()
    val hourly = MutableList(24) { 0 }
    var laughCount = 0
    var questionCount = 0
    var exclamationCount = 0
    var tildeCount = 0
    var coldCount = 0
    var speechlessCount = 0
    val lengthDist = MutableList(4) { 0 }
    val words = HashMap<String, Int>()
    val minWordLength = GroupAnalyzePrefs.reportMinWordLength()

    val cal = Calendar.getInstance()
    for (msg in messages) {
        codeCounts.mergeCount(msg.typeCode, 1, Int::plus)

        val senderId = extractSenderId(msg, membersMap)
        senderCounts.getOrPut(senderId) { mutableListOf() }.add(msg)

        cal.timeInMillis = msg.createTime
        hourly[cal.get(Calendar.HOUR_OF_DAY)]++

        val type = MessageType.fromCode(msg.typeCode)
        if (type?.isText == true) {
            val textContent = extractTextContent(msg, membersMap)

            val textLen = textContent.length
            when {
                textLen <= 5 -> lengthDist[0]++
                textLen <= 20 -> lengthDist[1]++
                textLen <= 50 -> lengthDist[2]++
                else -> lengthDist[3]++
            }

            if (laughRegex.containsMatchIn(textContent)) laughCount++
            if (textContent.endsWith("?") || textContent.endsWith("？")) questionCount++
            if (textContent.endsWith("!") || textContent.endsWith("！")) exclamationCount++
            if (textContent.contains("~") || textContent.contains("～")) tildeCount++
            if (perfunctoryReplyRegex.containsMatchIn(textContent.trim())) coldCount++
            if (speechlessRegex.containsMatchIn(textContent)) speechlessCount++

            // 高频语义词频（对应 Hchat 1383-1397）：中文连续段切词，长段按最短词长滑窗，含停用词过滤
            val normalized = cleanMessageText(textContent).replace(Regex("[^一-龥]+"), " ")
            for (piece in normalized.split(Regex("\\s+"))) {
                val word = piece.trim()
                if (word.length < minWordLength) continue
                if (word.length <= 8) {
                    words.mergeCount(word, 1, Int::plus)
                } else {
                    for (w in 0..(word.length - minWordLength)) {
                        val part = word.substring(w, w + minWordLength)
                        if (minWordLength == 2 && WORD_STOP_WORDS.contains(part)) continue
                        words.mergeCount(part, 1, Int::plus)
                    }
                }
            }
        }
    }

    val textCount = codeCounts.entries
        .filter { MessageType.fromCode(it.key)?.isText == true }
        .sumOf { it.value }

    val senders = senderCounts.entries
        .sortedByDescending { it.value.size }
        .take(10)
        .map { (senderId, msgs) ->
            val mainType = msgs.groupBy { m ->
                categorizeMessageType(MessageType.fromCode(m.typeCode), m.typeCode)
            }.maxByOrNull { it.value.size }?.key ?: "文本"
            SenderStat(resolveSenderName(senderId, membersMap), msgs.size, mainType)
        }

    return GroupStats(
        periodStart = messages.firstOrNull()?.createTime ?: 0,
        periodEnd = messages.lastOrNull()?.createTime ?: 0,
        totalMessages = totalCount,
        speakerCount = senderCounts.size,
        textCount = textCount,
        senders = senders,
        hourly = hourly,
        codeCounts = codeCounts,
        words = words,
        laughCount = laughCount,
        exclamationCount = exclamationCount,
        questionCount = questionCount,
        tildeCount = tildeCount,
        coldCount = coldCount,
        speechlessCount = speechlessCount,
        lengthDist = lengthDist,
    )
}

/** 渲染文本版统计报告（AI 提示词输入，格式与历史版本一致） */
internal fun renderStatsReport(stats: GroupStats): String {
    val sb = StringBuilder()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    sb.appendLine("群聊统计报告")
    sb.appendLine("统计周期:${dateFormat.format(Date(stats.periodStart))}至${dateFormat.format(Date(stats.periodEnd))}")
    sb.appendLine("总消息:${stats.totalMessages}条 发言人数:${stats.speakerCount}人")
    val typeCounts = mutableMapOf<String, Int>()
    stats.codeCounts.forEach { (code, count) ->
        typeCounts.mergeCount(categorizeMessageType(MessageType.fromCode(code), code), count, Int::plus)
    }
    sb.appendLine("消息载体 图片:${typeCounts.getOrDefault("图片", 0)}条 语音:${typeCounts.getOrDefault("语音", 0)}条 文本:${typeCounts.getOrDefault("文本", 0)}条 视频:${typeCounts.getOrDefault("视频", 0)}条 系统:${typeCounts.getOrDefault("系统", 0)}条 文件/链接:${typeCounts.getOrDefault("文件/链接", 0)}条 表情:${typeCounts.getOrDefault("表情", 0)}条")
    sb.appendLine("发言排行")
    stats.senders.forEachIndexed { index, sender ->
        sb.appendLine("${index + 1}.${sender.name}:${sender.count}条")
    }
    val periodOf = { from: Int, to: Int -> stats.hourly.subList(from, to + 1).sum() }
    sb.appendLine("活跃时段 凌晨(0-5):${periodOf(0, 5)}条 上午(6-11):${periodOf(6, 11)}条 下午(12-17):${periodOf(12, 17)}条 夜晚(18-23):${periodOf(18, 23)}条")
    sb.appendLine("情绪指纹")
    val textMsgCount = stats.textCount.coerceAtLeast(1)
    fun pct(count: Int) = "%.1f".format(count.toDouble() / textMsgCount * 100)
    sb.appendLine("笑点浓度:${pct(stats.laughCount)}% 疑问句比例:${pct(stats.questionCount)}% 感叹句比例:${pct(stats.exclamationCount)}% 波浪号比例:${pct(stats.tildeCount)}%")
    sb.appendLine("废话程度鉴定 ≤5字:${stats.lengthDist[0]}条 6-20字:${stats.lengthDist[1]}条 21-50字:${stats.lengthDist[2]}条 >50字:${stats.lengthDist[3]}条")
    sb.appendLine("用户画像")
    stats.senders.forEach { sender ->
        val percentage = "%.1f".format(sender.count.toDouble() / stats.totalMessages * 100)
        sb.appendLine("·${sender.name}:${sender.count}条($percentage%),主发${sender.mainType}")
    }
    sb.appendLine()
    return sb.toString()
}

internal suspend fun loadGroupMembersMap(talker: String): Map<String, String> =
    withContext(Dispatchers.IO) {
        val contacts = WeDatabaseApi.getGroupMembers(talker).associate { m ->
            m.wxId to (m.remarkName.takeUnless { it.isBlank() }?.let { "$it (${m.nickname})" } ?: m.nickname)
        }
        // 群内昵称优先，其次联系人备注/昵称
        val nicknameMap = WeDatabaseApi.getGroupNicknameMap(talker)
        contacts.toMutableMap().apply {
            nicknameMap.forEach { (wxId, nick) -> this[wxId] = nick }
        }
    }

internal suspend fun loadCoreMetrics(talker: String): GroupCoreMetrics =
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todayMessages = WeDatabaseApi.getMessagesInRange(talker, todayStart, now)
        val membersMap = loadGroupMembersMap(talker)
        GroupCoreMetrics(
            todaySpeakers = todayMessages.map { extractSenderId(it, membersMap) }.distinct().size,
            todayMessages = todayMessages.size,
            historyTotal = WeDatabaseApi.getMessageCountInRange(talker, 0, now),
        )
    }

internal suspend fun loadGroupStats(talker: String, range: GroupTimeRange): GroupStats =
    withContext(Dispatchers.IO) {
        val membersMap = loadGroupMembersMap(talker)
        val (start, end) = groupRangeStartEnd(range)
        // 深度采样：仅取时段内最近 N 条消息参与统计，避免大时段全量拉取
        val messages = WeDatabaseApi.getMessagesInRangeDesc(talker, start, end, GroupAnalyzePrefs.reportSampleLimit())
        computeGroupStats(messages, membersMap)
    }

/** SQL 侧切分群消息发送者：isSend=1 为自己，否则取 content 首个「sender:」前缀（与 extractSenderId 语义一致） */
private const val RANKING_SENDER_SQL =
    "CASE WHEN isSend = 1 THEN ? " +
        "WHEN instr(content, ':' || char(10)) > 0 THEN substr(content, 1, instr(content, ':' || char(10)) - 1) " +
        "WHEN instr(content, ':') > 0 THEN substr(content, 1, instr(content, ':') - 1) ELSE NULL END"

/** 按发送者聚合时段内消息条数（排除系统消息与群 ID 前缀），供排行与活跃检测共用 */
private fun querySenderCounts(talker: String, start: Long, end: Long): Map<String, Int> {
    val sql = "SELECT $RANKING_SENDER_SQL AS sender, COUNT(*) AS cnt " +
        "FROM message WHERE talker = ? AND type != 10000 " +
        "AND createTime >= ? AND createTime <= ? " +
        "GROUP BY sender"
    val counts = mutableMapOf<String, Int>()
    for (row in WeDatabaseApi.executeQuery(sql, arrayOf(WeApi.selfWxId, talker, start, end))) {
        val senderId = row["sender"]?.toString()?.trim().orEmpty()
        if (senderId.isEmpty() || senderId.isGroupChatWxId) continue
        counts[senderId] = (row["cnt"] as Number).toInt()
    }
    return counts
}

/**
 * 活跃发言排行：按独立于总结时段的日历区间统计 Top10 发言者。
 * SQL 聚合避免长时段（今年/去年）全量拉取消息；排除系统消息（type=10000）。
 */
internal suspend fun loadGroupRanking(talker: String, range: GroupTimeRange): List<RankingEntry> =
    withContext(Dispatchers.IO) {
        val (start, end) = groupRangeStartEnd(range)
        val counts = querySenderCounts(talker, start, end)
        val membersMap = loadGroupMembersMap(talker)
        counts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (senderId, count) ->
                val name = if (senderId == WeApi.selfWxId) "我" else resolveSenderName(senderId, membersMap)
                RankingEntry(name, count)
            }
    }

/** 低活跃成员（发言条数，含 0 条） */
internal data class LowActivityMember(val wxid: String, val name: String, val count: Int)

/** 活跃检测结果 */
internal data class ActivityResult(
    val totalMembers: Int,
    val activeMembers: Int,
    val members: List<LowActivityMember>,
)

/** 群聊活跃检测：统计最近 [days] 天每个成员的发言条数（升序，含 0 条），周期独立于总结时段 */
internal suspend fun loadActivityResult(talker: String, days: Int): ActivityResult =
    withContext(Dispatchers.IO) {
        val end = System.currentTimeMillis()
        val start = end - days * 24L * 60 * 60 * 1000
        val counts = querySenderCounts(talker, start, end)
        val membersMap = loadGroupMembersMap(talker)
        val selfWxId = WeApi.selfWxId
        val members = membersMap.keys
            .filter { !it.isGroupChatWxId && it != selfWxId }
            .map { wxid -> LowActivityMember(wxid, membersMap[wxid] ?: wxid, counts[wxid] ?: 0) }
            .sortedWith(compareBy<LowActivityMember> { it.count }.thenBy { it.name })
        ActivityResult(members.size, members.count { it.count > 0 }, members)
    }
