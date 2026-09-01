package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
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
    val laughCount: Int,
    val exclamationCount: Int,
    val questionCount: Int,
    val tildeCount: Int,
    val coldCount: Int,
    val speechlessCount: Int,
    val lengthDist: List<Int>,
)

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
        val messages = WeDatabaseApi.getMessagesInRange(talker, start, end)
        computeGroupStats(messages, membersMap)
    }
