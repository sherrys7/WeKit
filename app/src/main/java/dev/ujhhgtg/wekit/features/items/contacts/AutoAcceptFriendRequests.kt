package dev.ujhhgtg.wekit.features.items.contacts
import dev.ujhhgtg.wekit.R

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.PlaceholderChips
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.luckypray.dexkit.DexKitBridge
import kotlin.random.Random

@SuppressLint("SetTextI18n")
object AutoAcceptFriendRequests : ClickableFeature(), IResolveDex,
    WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "自动同意好友申请"

    // 自动备注占位符
    private const val PLACEHOLDER_NICKNAME = $$"$nickname"
    private const val PLACEHOLDER_TIME = $$"$time"
    private const val DEFAULT_TEXT_FORMAT = $$"$nickname ($time)"
    private const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd"

    override val nameRes = R.string.feature_auto_accept_friend_requests_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_accept_friend_requests_description

    private const val TAG = "AutoAcceptFriendReq"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ==================== 持久化偏好 ====================
    private var masterEnabled by prefOption("aafr_master_enabled", false)
    private var delayMode by prefOption("aafr_delay_mode", 0) // 0 = fixed, 1 = random
    private var fixedDelayMs by prefOption("aafr_fixed_delay_ms", 1000)
    private var randomDelayMinMs by prefOption("aafr_random_delay_min_ms", 1000)
    private var randomDelayMaxMs by prefOption("aafr_random_delay_max_ms", 5000)
    private var welcomeText by prefOption("aafr_welcome_text", "你好，我已通过你的好友申请")
    private var sendWelcome by prefOption("aafr_send_welcome", true)
    private var blacklistJson by prefOption("aafr_blacklist", "[]")

    // 自动备注偏好
    private var remarkEnabled by prefOption("aafr_remark_enabled", true)
    private var remarkTextFormat by prefOption("aafr_remark_text_format", DEFAULT_TEXT_FORMAT)
    private var remarkTimeFormat by prefOption("aafr_remark_time_format", DEFAULT_TIME_FORMAT)

    // 已处理的好友请求（防重复处理）
    private val processedRequests = mutableSetOf<String>()

    private fun getBlacklist(): Set<String> {
        return runCatching {
            json.decodeFromString<Set<String>>(blacklistJson)
        }.getOrDefault(emptySet())
    }

    enum class DelayMode(val value: Int, val description: String) {
        FIXED(0, "固定延迟"),
        RANDOM(1, "随机延迟")
    }

    // ==================== DexKit — 好友验证接受方法 ====================

    /**
     * 8.0.76 起 NetSceneVerifyUser 混淆为 pluginsdk.model.m3，旧日志字符串
     * "summerverify opcode[%s], verifyContent[%s], verifyScene[%s]" 被移除，
     * 接受操作的 opcode 改为 MM_VERIFYUSER_VERIFYOK 语义。
     * <init> 内含断言日志 "init MUST use opcode == MM_VERIFYUSER_VERIFYOK"，
     * 以此定位构造器（首参为 opcode）。
     */
    private const val OPCODE_VERIFY_ACCEPT = 3 // 8.0.77: MM_VERIFYUSER_VERIFYOK（实测构造器校验通过值，1/2 均被拒）

    // 好友验证接受方法：NetSceneVerifyUser / NetSceneAddFriend 等
    // 在 WeChat 中，接受好友验证的典型方法是 VerifyUserTask 或 NetSceneVerifyUser
    private val methodVerifyAccept by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "This NetSceneVerifyUser init MUST use opcode == MM_VERIFYUSER_VERIFYOK"
            )
        }
    }

    // 8.0.76 的 NetSceneVerifyUser 构造器（m3.<init>），用于主动构造"接受"请求
    // 使用内联查找版 dexConstructor（resolveInlineDex 时自动解析）
    private val ctorVerifyUserAccept by dexConstructor {
        matcher {
            usingEqStrings("This NetSceneVerifyUser init MUST use opcode == MM_VERIFYUSER_VERIFYOK")
        }
    }

    // 好友验证页面的 initView — 用于检测用户手动进入验证页面时提取信息
    // 备用：如果 NetScene 方法无法匹配，通过验证页面输入来触发
    private val methodVerifyOkClick by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.VerifyUserUtil",
                "verify ok clicked"
            )
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        // 8.0.76+：NetSceneVerifyUser 混淆为 m3，<init> 含 MM_VERIFYUSER_VERIFYOK 断言日志
        methodVerifyAccept.find(dexKit, allowFailure = true) {
            matcher {
                usingEqStrings(
                    "This NetSceneVerifyUser init MUST use opcode == MM_VERIFYUSER_VERIFYOK"
                )
            }
        }

        // 旧版特征（8.0.7x 及更早）
        if (methodVerifyAccept.isPlaceholder) {
            methodVerifyAccept.find(dexKit, allowFailure = true) {
                matcher {
                    usingEqStrings(
                        "MicroMsg.NetSceneVerifyUser",
                        "summerverify opcode[%s], verifyContent[%s], verifyScene[%s]"
                    )
                }
            }
        }

        methodVerifyOkClick.find(dexKit, allowFailure = true) {
            matcher {
                usingEqStrings(
                    "MicroMsg.VerifyUserUtil",
                    "verify ok clicked"
                )
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun onEnable() {
        WeLogger.i(TAG, "onEnable: masterEnabled=" + masterEnabled + " ctorVerifyUserAccept=" + !ctorVerifyUserAccept.isPlaceholder + " methodVerifyOkClick=" + !methodVerifyOkClick.isPlaceholder)
        WeDatabaseListenerApi.addListener(this)
        startRcontactPoller()
        // 检查 DexKit 方法是否成功解析
        val verifyUnavailable = methodVerifyAccept.isPlaceholder
        val verifyOkUnavailable = methodVerifyOkClick.isPlaceholder

        if (verifyUnavailable && verifyOkUnavailable) {
            WeLogger.w(TAG, "当前微信版本暂不支持自动同意好友申请 — DexKit 方法均未匹配")
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showToast("当前微信版本暂不支持自动同意好友申请")
                }
            }
        }

        // 钩住好友验证接受方法，在微信内部接受好友请求时触发后续逻辑
        runCatching {
            if (!ctorVerifyUserAccept.isPlaceholder) {
                // 8.0.77: NetSceneVerifyUser 混淆为 p3, 接受入口在 <init>(opcode=VERIFYOK), 用构造器 hook
                ctorVerifyUserAccept.constructor.hookAfter {
                    // 8.0.76 的 <init>(opcode, userId, ticket, scene, ...) 与旧版
                    // NetSceneVerifyUser 接受方法参数布局不同，先防护再取参。
                    if (args.size < 3) return@hookAfter
                    if (!masterEnabled) return@hookAfter
                    val opcode = args[0] as? Int ?: return@hookAfter
                    // 8.0.76: MM_VERIFYUSER_VERIFYOK == 1；旧版接受 opcode == 2
                    if (opcode != OPCODE_VERIFY_ACCEPT && opcode != 2) return@hookAfter

                    // 8.0.76: args[1] = userId(encryptUsername), args[2] = ticket
                    // 旧版: args[1] = verifyContent("v2_encrypt@ticket@scene"), args[2] = verifyScene
                    val arg1 = (args[1] as? String)?.takeIf { it.isNotBlank() } ?: return@hookAfter

                    WeLogger.i(TAG, "friend request accepted via verify: arg1=$arg1")
                    WeLogger.i(
                        TAG,
                        "verify ctor args: size=" + args.size + " " +
                            args.joinToString(" | ") { a -> a?.let { it.javaClass.simpleName + "=" + it } ?: "null" }
                    )
                    // 欢迎语统一在 acceptFriendRequest 发送；此处只记录（本 hook 会同时被模块
                    // 构造与微信原生接受触发，若也发欢迎语会与模块路径重复发送）。
                }
            } else if (!methodVerifyAccept.isPlaceholder) {
                methodVerifyAccept.hookAfter {
                    if (args.size < 3) return@hookAfter
                    if (!masterEnabled) return@hookAfter
                    val opcode = args[0] as? Int ?: return@hookAfter
                    if (opcode != OPCODE_VERIFY_ACCEPT && opcode != 2) return@hookAfter
                    val arg1 = (args[1] as? String)?.takeIf { it.isNotBlank() } ?: return@hookAfter
                    WeLogger.i(TAG, "friend request accepted via legacy method: arg1=$arg1")
                }
            } else {
                WeLogger.w(TAG, "methodVerifyAccept not resolved, auto-accept will use database listener fallback")
            }
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to hook verify accept", e)
        }

        // 自动备注：钩住 SayHi（打招呼/添加好友）界面，自动填充备注名
        "com.tencent.mm.plugin.profile.ui.SayHiWithSnsPermissionUI".toClass().reflekt()
            .firstMethod("initView").hookBefore {
                if (!remarkEnabled) return@hookBefore
                val activity = thisObject as? Activity ?: return@hookBefore
                val intent = activity.intent ?: return@hookBefore
                val nickname = intent.getStringExtra("Contact_Nick") ?: ""
                if (nickname.isNotEmpty()) {
                    val remark = remarkTextFormat
                        .replace(PLACEHOLDER_NICKNAME, nickname)
                        .replace(PLACEHOLDER_TIME, formatEpoch(System.currentTimeMillis(), remarkTimeFormat))
                    intent.putExtra("Contact_RemarkName", remark)
                    WeLogger.i(TAG, "auto remark succeeded: $remark")
                }
            }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        pollerScope.cancel()
        processedRequests.clear()
        processedWxids.clear()
    }

    // ==================== 数据库监听 — 检测好友验证消息 ====================

    override fun onInsert(table: String, values: ContentValues) {
        WeLogger.i(TAG, "onInsert: table=" + table + " masterEnabled=" + masterEnabled)
        if (table == "rcontact") {
            handleRcontactInsert()
            return
        }
        if (table == "VerifyRecordMsgInfo" || table == "fmessage_msginfo") {
            val content = values.getAsString("content") ?: values.getAsString("msgContent")
            WeLogger.i(TAG, "[verifyInsert] table=$table contentLen=${content?.length ?: 0}")
            if (!content.isNullOrEmpty()) {
                WeLogger.i(TAG, "[verifyInsert] content=$content")
                handleFriendVerifyContent(content)
            }
            return
        }
        if (table != "message") return
        if (!masterEnabled) return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }
            .onFailure { WeLogger.e(TAG, "MessageInfo.fromContentValues failed", it) }
            .getOrNull() ?: return
        if (msgInfo.isSelfSender) return
        if (msgInfo.typeCode != MessageType.FRIEND_VERIFY.code) return

        val content = msgInfo.content ?: return
        if (content.isEmpty()) return

        handleFriendVerifyContent(content, msgInfo.talker)
    }

    /** 解析好友验证消息 XML 内容并执行自动同意（onInsert 与 message 轮询共用） */
    private fun handleFriendVerifyContent(content: String, talker: String? = null) {
        val encryptUsername = extractXmlValue(content, "encryptusername")
            ?: extractAttr(content, "encryptusername")
        val ticket = extractXmlValue(content, "ticket") ?: extractAttr(content, "ticket")
        val scene = extractXmlValue(content, "scene") ?: extractAttr(content, "scene") ?: ""
        val fromUser = extractXmlValue(content, "fromusername") ?: extractAttr(content, "fromusername")

        if (encryptUsername.isNullOrEmpty() || ticket.isNullOrEmpty()) {
            WeLogger.d(TAG, "friend verify message missing required fields")
            return
        }

        handleVerifyAccept(encryptUsername, ticket, scene, fromUser, talker)
    }

    /** 通用自动同意入口：去重/黑名单后按延迟执行 accept（验证记录表与验证消息共用） */
    private fun handleVerifyAccept(
        encryptUsername: String,
        ticket: String,
        scene: String,
        fromUser: String? = null,
        messageTalker: String? = null
    ) {
        // 去重检查：key 只用 encryptUsername（稳定标识）。ticket 是同一次申请
        // 每次写入都变的一次性防重放值（微信会连插多条 VerifyRecordMsgInfo，
        // 每条 ticket 都不同），不能进 key，否则同一申请会被重复 accept。
        val requestKey = encryptUsername
        if (requestKey in processedRequests) {
            WeLogger.d(TAG, "duplicate friend request, skipped")
            return
        }
        processedRequests.add(requestKey)
        // 跨路径去重：把可能的真实 wxid 也登记进 processedWxids，
        // 避免同一次申请又被 rcontact 轮询路径重复 accept 并重复发欢迎语
        listOfNotNull(fromUser, messageTalker)
            .filter { it.isNotBlank() && !it.startsWith("v2_") && !it.startsWith("v3_") }
            .forEach(processedWxids::add)
        // 清理过期记录
        if (processedRequests.size > 200) {
            processedRequests.clear()
        }

        // 黑名单检查
        val blacklist = getBlacklist()
        if (encryptUsername in blacklist) {
            WeLogger.i(TAG, "user $encryptUsername is in blacklist, skipped")
            return
        }
        // 也检查原始 wxId
        if (fromUser != null && fromUser in blacklist) {
            WeLogger.i(TAG, "user $fromUser is in blacklist, skipped")
            return
        }

        WeLogger.i(TAG, "auto-accepting friend request: encryptUsername=$encryptUsername ticket=$ticket scene=$scene")

        // 计算延迟
        val delayMs = when (delayMode) {
            DelayMode.RANDOM.value -> {
                val min = randomDelayMinMs.coerceAtLeast(0)
                val max = randomDelayMaxMs.coerceAtLeast(min)
                Random.nextLong(min.toLong(), (max + 1).toLong())
            }
            else -> fixedDelayMs.coerceAtLeast(0).toLong()
        }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (delayMs > 0) {
                    delay(delayMs)
                }
                acceptFriendRequest(encryptUsername, ticket, scene)
                WeLogger.i(TAG, "friend request accepted: encryptUsername=$encryptUsername")

                // 发送欢迎语：轮询等待好友关系建立，优先用 talker/fromUser，其次 rcontact 反查
                if (sendWelcome && welcomeText.isNotBlank()) {
                    sendWelcomeWithRetry(encryptUsername, fromUser, messageTalker)
                }
            }.onFailure { e ->
                WeLogger.e(TAG, "failed to accept friend request", e)
            }
        }
    }

    /**
     * 构造 NetSceneVerifyUser 接受请求。8.0.77 构造器校验 opcode == MM_VERIFYUSER_VERIFYOK，
     * 实测该校验通过的值为 3（1/2 均报 "MUST use opcode == MM_VERIFYUSER_VERIFYOK"）。
     * 遍历 1..8 仅为兼容其他版本（若首个值即成功则不再尝试后续）。
     */
    private fun buildVerifyUserAccept(encryptUsername: String, ticket: String, scene: Int): Any? {
        var firstError: Throwable? = null
        for (op in 1..8) {
            try {
                val ns = ctorVerifyUserAccept.newInstance(op, encryptUsername, ticket, scene, "", 0, null, null)
                WeLogger.i(TAG, "verify accept ctor OK with opcode=$op")
                return ns
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
            }
        }
        WeLogger.w(TAG, "all opcodes failed, firstError=" + (firstError?.cause ?: firstError))
        return null
    }

    private fun acceptFriendRequest(encryptUsername: String, ticket: String, scene: String) {
        // 8.0.77：构造 NetSceneVerifyUser，构造器校验 opcode == MM_VERIFYUSER_VERIFYOK，
        // 该常量真实值不明（1/2 均被拒），这里遍历候选 opcode 找到能通过校验的值。
        runCatching {
            if (!ctorVerifyUserAccept.isPlaceholder) {
                val sceneInt = scene.toIntOrNull() ?: 0
                val netScene = buildVerifyUserAccept(encryptUsername, ticket, sceneInt)
                if (netScene != null) {
                    WeNetSceneApi.sendNetScene(netScene)
                    WeLogger.i(TAG, "verify accept NetScene sent: encryptUsername=$encryptUsername")
                    return
                }
            }
        }.onFailure { e ->
            WeLogger.w(TAG, "8.0.76 accept via ctor failed, trying legacy path", e)
            WeLogger.w(TAG, "ctor accept cause: " + (e.cause?.toString() ?: "no cause"))
        }

        if (!methodVerifyAccept.isPlaceholder) {
            // 旧版：直接调用接受方法（opcode 2）
            methodVerifyAccept.method.invoke(
                null, // static method
                2, // opcode = accept
                "v2_$encryptUsername@$ticket@$scene", // verifyContent
                "", // verifyScene
                ""  // additional
            )
        } else {
            // 回退：通过数据库操作模拟接受
            // 这是简化的实现，实际接受需要调用微信的 NetScene 接口
            WeLogger.w(TAG, "methodVerifyAccept not available, using fallback accept")
            fallbackAccept(encryptUsername, ticket, scene)
        }
    }

    // ==================== rcontact 表监听 — 捕获免验证被添加 ====================

    private val mainHandler = Handler(Looper.getMainLooper())
    private val processedWxids = mutableSetOf<String>()
    private val pollerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastProcessedRowid = -1L
    private var lastMsgRowid = -1L

    /** 诊断：打印 rcontact 表真实列名 */
    private fun probeRcontact() {
        runCatching {
            WeDatabaseApi.rawQuery("SELECT * FROM rcontact LIMIT 1").use { cursor ->
                WeLogger.i(TAG, "[probe] columns=" + cursor.columnNames.joinToString(","))
            }
        }.onFailure { WeLogger.e(TAG, "[probe] failed: " + (it.message ?: it.toString())) }
    }

    /** 延迟轮询 rcontact：等微信数据库就绪后每 2s 查一次最新插入行 */
    private fun startRcontactPoller() {
        pollerScope.launch {
            var waited = 0L
            while (!WeDatabaseApi.isReady && waited < 60_000L) {
                delay(500)
                waited += 500
            }
            if (!WeDatabaseApi.isReady) {
                WeLogger.w(TAG, "[poller] db not ready after 60s, abort")
                return@launch
            }
            WeLogger.i(TAG, "[poller] started, db ready")
            probeRcontact()
            while (isActive) {
                delay(2000)
                if (masterEnabled) {
                    handleRcontactInsert()
                    handleMessageInsert()
                }
            }
        }
    }

    /** 查询 rcontact 最新插入行，按 5s 时间窗口过滤历史数据 */
    private fun handleRcontactInsert() {
        runCatching {
            if (lastProcessedRowid < 0) {
                // 首次：记录当前最大 rowid，跳过存量联系人
                WeDatabaseApi.rawQuery("SELECT MAX(rowid) FROM rcontact").use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) lastProcessedRowid = cursor.getLong(0)
                }
                WeLogger.i(TAG, "[rcontact] baseline rowid=$lastProcessedRowid")
                return
            }
            WeDatabaseApi.rawQuery(
                "SELECT rowid, username, type, ticket, nickname, encryptUsername FROM rcontact WHERE rowid > " +
                    lastProcessedRowid + " ORDER BY rowid ASC LIMIT 20"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val rowid = cursor.getLong(0)
                    val wxId = cursor.getString(1) ?: continue
                    val type = cursor.getInt(2)
                    val ticket = cursor.getString(3)
                    val nick = cursor.getString(4)
                    val encUsername = cursor.getString(5)
                    WeLogger.i(TAG, "[rcontactRow] rowid=$rowid wx=$wxId type=$type ticket=$ticket nick=$nick enc=$encUsername")
                    if (rowid > lastProcessedRowid) lastProcessedRowid = rowid
                    if (wxId in processedWxids) continue
                    when (type) {
                        2 -> {
                            WeLogger.i(TAG, "[autoAccept] detect friend apply wx=$wxId")
                            if (ticket.isNullOrEmpty()) continue
                            processedWxids.add(wxId)
                            // 跨路径去重：同时登记到 processedRequests（key=encryptUsername），
                            // 避免同一申请又被 message/VerifyRecord 路径（handleVerifyAccept）重复接受并重复发欢迎语
                            if (!encUsername.isNullOrEmpty()) processedRequests.add(encUsername)
                            val delayMs = if (delayMode == DelayMode.RANDOM.value) {
                                val min = randomDelayMinMs.coerceAtLeast(0).toLong()
                                val max = randomDelayMaxMs.coerceAtLeast(0).toLong().coerceAtLeast(min)
                                Random.nextLong(min, max + 1)
                            } else fixedDelayMs.coerceAtLeast(0).toLong()
                            val target = if (encUsername.isNullOrEmpty()) wxId else encUsername
                            mainHandler.postDelayed({
                                CoroutineScope(Dispatchers.IO).launch {
                                    runCatching {
                                        acceptFriendRequest(target, ticket, "")
                                        WeLogger.i(TAG, "[acceptFriendRequest] OK wx=$wxId")
                                        // 等待好友关系建立后发送欢迎语：
                                        // 候选目标 = rcontact 行 username(wxId)；加密键回退 rcontact 反查真实 wxid
                                        if (sendWelcome && welcomeText.isNotBlank()) {
                                            val key = if (!encUsername.isNullOrEmpty()) encUsername else wxId
                                            sendWelcomeWithRetry(key, wxId, wxId)
                                        }
                                    }.onFailure { WeLogger.e(TAG, "[acceptFriendRequest] FAIL wx=$wxId", it) }
                                }
                            }, delayMs)
                        }
                        1 -> {
                            WeLogger.i(TAG, "[autoAccept] NO-VERIFY someone add me wx=$wxId")
                            processedWxids.add(wxId)
                        }
                        else -> WeLogger.i(TAG, "[rcontactRow] type=$type ignored")
                    }
                }
            }
        }.onFailure { WeLogger.e(TAG, "[rcontactObs error] " + (it.message ?: it.toString())) }
    }

    /** 轮询 message 表：捕获 type=37 好友验证消息（自动同意触发） */
    private fun handleMessageInsert() {
        runCatching {
            if (lastMsgRowid < 0) {
                WeDatabaseApi.rawQuery("SELECT MAX(rowid) FROM message").use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) lastMsgRowid = cursor.getLong(0)
                }
                WeLogger.i(TAG, "[msg] baseline rowid=$lastMsgRowid")
                return
            }
            WeDatabaseApi.rawQuery(
                "SELECT rowid, content, talker FROM message WHERE rowid > " + lastMsgRowid +
                    " AND type=37 ORDER BY rowid ASC LIMIT 20"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val rowid = cursor.getLong(0)
                    val content = cursor.getString(1)
                    val talker = cursor.getString(2)
                    if (rowid > lastMsgRowid) lastMsgRowid = rowid
                    if (content.isNullOrEmpty()) continue
                    WeLogger.i(TAG, "[msgRow] rowid=$rowid talker=$talker len=${content.length}")
                    handleFriendVerifyContent(content, talker)
                }
            }
        }.onFailure { WeLogger.e(TAG, "[msgObs error] " + (it.message ?: it.toString())) }
    }

    private fun fallbackAccept(encryptUsername: String, ticket: String, scene: String) {
        // 回退方案：使用 WeChat 的 AddContact 或 VerifyUser 相关的 NetScene
        // 由于无法确定具体的 DexKit 签名，这里记录日志供用户参考
        WeLogger.w(TAG, "fallback accept not fully implemented, " +
                "request: encryptUsername=$encryptUsername, ticket=$ticket, scene=$scene")
        // 尝试通过 WeChat 的数据库操作来标记好友请求为已处理
        // 实际的自动接受功能需要 DexKit 成功解析 WeChat 的验证方法
    }

    /** 尝试通过 encryptUsername / 原始 wxid 查找新好友在 rcontact 中的真实 username */
    private fun findNewFriendWxId(encryptUsername: String, fromUser: String? = null): String {
        val escaped = listOfNotNull(encryptUsername, fromUser)
            .filter { it.isNotBlank() }
            .joinToString("','") { it.replace("'", "''") }
        if (escaped.isBlank()) return ""
        return runCatching {
            WeDatabaseApi.rawQuery(
                "SELECT username FROM rcontact WHERE " +
                    "(encryptUsername IN ('$escaped') OR username IN ('$escaped') OR alias IN ('$escaped')) " +
                    "LIMIT 1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
            }
        }.getOrDefault("")
    }

    /**
     * 轮询等待好友关系建立后发送欢迎语。
     * 候选目标按可靠度排序：message 表 talker / XML fromusername（真实 wxid）
     * → rcontact 按 encryptUsername/username/alias 反查。
     * 接受请求是异步网络往返，rcontact 落库可能晚于 accept 返回，故重试 ~15s。
     */
    private suspend fun sendWelcomeWithRetry(encryptUsername: String, fromUser: String?, messageTalker: String?) {
        val directCandidates = linkedSetOf<String>()
        messageTalker?.takeIf { it.isNotBlank() }?.let(directCandidates::add)
        fromUser?.takeIf { it.isNotBlank() }?.let(directCandidates::add)
        // 排除加密占位 ID（v2_/v3_ 开头），它们不是可发送的真实 wxid
        val realDirect = directCandidates.filter { !it.startsWith("v2_") && !it.startsWith("v3_") }

        repeat(6) { attempt ->
            // 1) 先试已知真实目标
            for (candidate in realDirect) {
                if (sendWelcomeMessage(candidate)) return
            }
            // 2) 再查 rcontact（接受成功后 encryptUsername/username 已落库）
            val resolved = findNewFriendWxId(encryptUsername, fromUser)
            if (resolved.isNotEmpty() && sendWelcomeMessage(resolved)) return
            if (attempt < 5) delay(2500)
        }
        WeLogger.w(
            TAG,
            "welcome message not sent: no resolvable target within retry window, " +
                "encryptUsername=$encryptUsername fromUser=$fromUser talker=$messageTalker"
        )
    }

    /** 发送欢迎语，成功返回 true（sendWelcome/文本非空已在调用方保证） */
    private fun sendWelcomeMessage(targetWxId: String): Boolean {
        val ok = WeMessageApi.sendText(targetWxId, welcomeText)
        WeLogger.i(TAG, "welcome text sent to $targetWxId, ok=$ok")
        return ok
    }

    private fun extractWxIdFromVerifyContent(verifyContent: String): String {
        // 从 verifyContent 中提取 wxId
        // 格式通常是 "v2_encryptUsername@ticket@scene"
        return runCatching {
            val parts = verifyContent.split("@")
            if (parts.size >= 2) {
                val encryptUsername = parts[0].removePrefix("v2_")
                findNewFriendWxId(encryptUsername)
            } else ""
        }.getOrDefault("")
    }

    private fun extractXmlValue(xml: String, tag: String): String? {
        val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim()
    }

    /** 微信 8.0.77 验证消息 XML 中 encryptusername/ticket 等位于 <msg> 属性上，需属性提取 */
    private fun extractAttr(xml: String, name: String): String? {
        val regex = Regex("""$name\s*=\s*"([^"]*)"""")
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    // ==================== UI ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localMasterEnabled by remember { mutableStateOf(masterEnabled) }
            var localDelayMode by remember { mutableStateOf(delayMode) }
            var localFixedDelayMs by remember { mutableStateOf(fixedDelayMs) }
            var localRandomMinMs by remember { mutableStateOf(randomDelayMinMs) }
            var localRandomMaxMs by remember { mutableStateOf(randomDelayMaxMs) }
            var localWelcomeText by remember { mutableStateOf(welcomeText) }
            var localSendWelcome by remember { mutableStateOf(sendWelcome) }
            var localBlacklistJson by remember { mutableStateOf(blacklistJson) }
            var localBlacklist by remember { mutableStateOf(getBlacklist().joinToString("\n")) }
            var localRemarkEnabled by remember { mutableStateOf(remarkEnabled) }
            var localRemarkTextFormat by remember { mutableStateOf(TextFieldValue(remarkTextFormat)) }
            var localRemarkTimeFormat by remember { mutableStateOf(remarkTimeFormat) }
            var isRemarkFormatFocused by remember { mutableStateOf(false) }

            AlertDialogContent(
                title = { Text("自动同意好友申请/自动备注") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        // 总开关
                        ListItem(
                            modifier = Modifier.clickable { localMasterEnabled = !localMasterEnabled },
                            trailingContent = {
                                Switch(checked = localMasterEnabled, onCheckedChange = null)
                            },
                            headlineContent = { Text("启用自动同意", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("开启后自动同意收到的所有好友申请") }
                        )

                        if (localMasterEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 延迟设置
                            Text("延迟设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                            DelayMode.values().forEach { mode ->
                                ListItem(
                                    modifier = Modifier.clickable { localDelayMode = mode.value },
                                    trailingContent = {
                                        Text(if (localDelayMode == mode.value) "✓" else "")
                                    },
                                    headlineContent = { Text(mode.description) }
                                )
                            }

                            when (localDelayMode) {
                                DelayMode.FIXED.value -> {
                                    val presets = listOf(0 to "立即", 500 to "0.5秒", 1000 to "1秒", 2000 to "2秒", 3000 to "3秒", 5000 to "5秒")
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        presets.forEach { (ms, label) ->
                                            FilterChip(
                                                selected = localFixedDelayMs == ms,
                                                onClick = { localFixedDelayMs = ms },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = localFixedDelayMs.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { localFixedDelayMs = it.coerceIn(0, 60000) }
                                        },
                                        label = { Text("自定义延迟（毫秒）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                }
                                DelayMode.RANDOM.value -> {
                                    OutlinedTextField(
                                        value = localRandomMinMs.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { localRandomMinMs = it.coerceIn(0, 60000) }
                                        },
                                        label = { Text("最小延迟（毫秒）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                    Spacer(Modifier.padding(top = 4.dp))
                                    OutlinedTextField(
                                        value = localRandomMaxMs.toString(),
                                        onValueChange = { v ->
                                            v.toIntOrNull()?.let { localRandomMaxMs = it.coerceIn(0, 60000) }
                                        },
                                        label = { Text("最大延迟（毫秒）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 欢迎语
                            ListItem(
                                modifier = Modifier.clickable { localSendWelcome = !localSendWelcome },
                                trailingContent = {
                                    Switch(checked = localSendWelcome, onCheckedChange = null)
                                },
                                headlineContent = { Text("自动发送欢迎语") },
                                supportingContent = { Text("通过好友申请后自动发送一条消息") }
                            )

                            if (localSendWelcome) {
                                OutlinedTextField(
                                    value = localWelcomeText,
                                    onValueChange = { localWelcomeText = it },
                                    label = { Text("欢迎语内容") },
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            // 黑名单
                            Text("黑名单管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "黑名单中的用户不会自动同意（每行一个 wxId）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            OutlinedTextField(
                                value = localBlacklist,
                                onValueChange = { localBlacklist = it },
                                label = { Text("黑名单 wxId") },
                                minLines = 3,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // 自动备注
                        ListItem(
                            modifier = Modifier.clickable { localRemarkEnabled = !localRemarkEnabled },
                            trailingContent = {
                                Switch(checked = localRemarkEnabled, onCheckedChange = null)
                            },
                            headlineContent = { Text("自动备注", fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("添加好友时自动设置备注名") }
                        )

                        if (localRemarkEnabled) {
                            Text("备注格式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                $$"可插入占位符：$nickname=对方昵称，$time=当前时间",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            OutlinedTextField(
                                value = localRemarkTextFormat,
                                onValueChange = { localRemarkTextFormat = it },
                                label = { Text("备注格式") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .onFocusChanged { isRemarkFormatFocused = it.isFocused }
                            )
                            PlaceholderChips(
                                placeholders = listOf(PLACEHOLDER_NICKNAME, PLACEHOLDER_TIME),
                                value = localRemarkTextFormat,
                                isFieldFocused = isRemarkFormatFocused,
                                onValueChange = { localRemarkTextFormat = it },
                            )
                            OutlinedTextField(
                                value = localRemarkTimeFormat,
                                onValueChange = { localRemarkTimeFormat = it },
                                label = { Text("时间格式") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val newBlacklist = localBlacklist.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()

                        masterEnabled = localMasterEnabled
                        delayMode = localDelayMode
                        fixedDelayMs = localFixedDelayMs
                        randomDelayMinMs = localRandomMinMs
                        randomDelayMaxMs = localRandomMaxMs
                        welcomeText = localWelcomeText
                        sendWelcome = localSendWelcome
                        blacklistJson = json.encodeToString(newBlacklist)
                        remarkEnabled = localRemarkEnabled
                        remarkTextFormat = localRemarkTextFormat.text
                        remarkTimeFormat = localRemarkTimeFormat
                        showToast("设置已保存")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }
}