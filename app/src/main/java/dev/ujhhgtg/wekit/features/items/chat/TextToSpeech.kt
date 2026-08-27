package dev.ujhhgtg.wekit.features.items.chat

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Graphic_eq
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import com.composables.icons.materialsymbols.outlined.Settings
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.m3.DropDownMenuWidget
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.TextFieldDialogWidget
import dev.ujhhgtg.wekit.ui.utils.MicIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object TextToSpeech : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "文字转语音"
    override val nameRes = R.string.feature_text_to_speech_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_text_to_speech_description

    private const val TAG = "TextToSpeech"
    private const val API_BASE = "https://peiyinmofang.com"

    var apiKey by prefOption("tts_api_key", "")
    var selectedVoice by prefOption("tts_voice_id", "琅琊榜-梅长苏")
    var selectedEmotion by prefOption("tts_emotion", "平静")

    private val mh = Handler(Looper.getMainLooper())
    private var lastWavPath: String? = null

    data class TtsVoice(val voiceId: String, val label: String)

    private val DEFAULT_VOICES = listOf(
        TtsVoice("琅琊榜-梅长苏", "琅琊榜-梅长苏"),
        TtsVoice("琅琊榜-靖王", "琅琊榜-靖王"),
        TtsVoice("甄嬛传-甄嬛", "甄嬛传-甄嬛"),
    )

    private val EMOTIONS = listOf(
        "平静" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "开心" to floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "悲伤" to floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
        "愤怒" to floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f),
        "惊讶" to floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
        "害怕" to floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f),
        "温柔" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f),
    )

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    @Suppress("DEPRECATION")
    fun isSupported(msgInfo: MessageInfo): Boolean =
        msgInfo.type == MessageType.TEXT || msgInfo.type == MessageType.QUOTE

    private val micImageVector = MaterialSymbols.Outlined.Graphic_eq

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777025,
                localizedChatString(R.string.chat_tts_menu),
                MicIcon,
                micImageVector,
                isSupported = ::isSupported,
                onClick = { view, _, msgInfo ->
                    val text = msgInfo.humanReadableRepr.trim()
                    if (text.isEmpty()) {
                        showToast(view.context, "该消息没有可转语音的文本")
                        return@MenuItem
                    }
                    showMainDialog(view.context, msgInfo.talker, text)
                }
            )
        )
    }

    private fun showMainDialog(context: android.content.Context, talker: String, initialText: String) {
        showComposeDialog(context) {
            var inputText by remember { mutableStateOf(initialText) }
            var voiceId by remember { mutableStateOf(selectedVoice) }
            var emotion by remember {
                mutableStateOf(
                    selectedEmotion.takeIf { e -> EMOTIONS.any { it.first == e } }
                        ?: EMOTIONS.first().first
                )
            }
            var voices by remember {
                mutableStateOf(
                    if (DEFAULT_VOICES.any { it.voiceId == selectedVoice }) DEFAULT_VOICES
                    else DEFAULT_VOICES + TtsVoice(selectedVoice, selectedVoice)
                )
            }
            var customVoices by remember { mutableStateOf(emptyList<TtsVoice>()) }
            var generating by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                Thread {
                    val fetched = fetchVoices()
                    val custom = fetchUserVoices()
                    mh.post {
                        voices = fetched
                        customVoices = custom
                        val inSystem = fetched.any { it.voiceId == voiceId }
                        val inCustom = custom.any { it.voiceId == voiceId }
                        if (!inSystem && !inCustom && fetched.isNotEmpty()) {
                            voiceId = fetched.first().voiceId
                            selectedVoice = voiceId
                        }
                    }
                }.start()
            }

            AlertDialogContent(
                title = { Text("文字转语音") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            placeholder = { Text("输入要转成语音的文本") },
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(Modifier.fillMaxWidth()) {
                            DropDownMenuWidget(
                                modifier = Modifier.weight(1f),
                                title = "系统音色",
                                description = null,
                                value = voiceId,
                                options = voices.map { DropdownOption(it.voiceId, it.label) },
                                onValueChange = { voiceId = it; selectedVoice = it },
                                maxVisibleItems = 6,
                            )
                            Spacer(Modifier.width(8.dp))
                            DropDownMenuWidget(
                                modifier = Modifier.weight(1f),
                                title = "自定义音色",
                                description = null,
                                value = voiceId,
                                options = customVoices.map { DropdownOption(it.voiceId, it.label) },
                                onValueChange = { voiceId = it; selectedVoice = it },
                                enabled = customVoices.isNotEmpty(),
                                maxVisibleItems = 6,
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        DropDownMenuWidget(
                            title = "语气",
                            description = null,
                            value = emotion,
                            options = EMOTIONS.map { DropdownOption(it.first, it.first) },
                            onValueChange = { emotion = it; selectedEmotion = it },
                        )

                        Spacer(Modifier.height(12.dp))

                        if (generating) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }

                        Row(Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    if (inputText.trim().isEmpty()) {
                                        showToast(context, "请输入文本")
                                        return@Button
                                    }
                                    if (apiKey.isBlank()) {
                                        showToast(context, "请先在设置中填写 API Key")
                                        showApiKeyDialog(context)
                                        return@Button
                                    }
                                    generating = true
                                    generateVoice(
                                        inputText.trim(),
                                        voiceId,
                                        EMOTIONS.first { it.first == emotion }.second,
                                    ) { wavPath ->
                                        generating = false
                                        if (wavPath != null) {
                                            showPreviewDialog(context, talker, wavPath)
                                        } else {
                                            showToast(context, "生成失败，请检查 API Key 与网络")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("生成")
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    val wav = lastWavPath
                                    if (wav == null || !File(wav).exists()) {
                                        showToast(context, "请先生成语音")
                                        return@Button
                                    }
                                    sendVoiceTo(talker, wav) { ok ->
                                        if (ok) {
                                            showToast(context, "语音已发送")
                                            onDismiss()
                                        } else {
                                            showToast(context, "发送失败")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("发送语音")
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { showApiKeyDialog(context) }) {
                                Icon(
                                    MaterialSymbols.Outlined.Settings,
                                    contentDescription = "设置",
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("打开设置")
                            }
                            TextButton(onClick = { onDismiss() }) {
                                Text("关闭")
                            }
                        }
                    }
                }
            )
        }
    }

    private fun showApiKeyDialog(context: android.content.Context) {
        showComposeDialog(context) {
            var apiKeyState by remember { mutableStateOf(apiKey) }
            AlertDialogContent(
                title = { Text("API Key 设置") },
                text = {
                    Column {
                        SegmentedColumn(title = "配音魔方") {
                            item {
                                TextFieldDialogWidget(
                                    title = "API Key",
                                    value = apiKeyState,
                                    onValueChange = {
                                        apiKeyState = it
                                        apiKey = it
                                    },
                                    dialogTitle = "设置 API Key",
                                    confirmLabel = "确认",
                                    dismissLabel = "取消",
                                    valueHint = "未设置，请前往 peiyinmofang.com 获取",
                                    password = true,
                                )
                            }
                        }
                        Text(
                            "用于调用配音魔方 TTS 接口，在官网「API Key 管理」中创建",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = { Button(onDismiss) { Text("完成") } }
            )
        }
    }

    private fun showPreviewDialog(context: android.content.Context, talker: String, wavPath: String) {
        showComposeDialog(context, directlyDismissable = false) {
            var playing by remember { mutableStateOf(false) }
            var durationMs by remember { mutableIntStateOf(0) }
            var positionMs by remember { mutableIntStateOf(0) }
            var player by remember { mutableStateOf<MediaPlayer?>(null) }

            DisposableEffect(Unit) {
                val p = MediaPlayer().apply {
                    setDataSource(wavPath)
                    prepare()
                    durationMs = duration
                    setOnCompletionListener { mh.post { playing = false } }
                }
                player = p
                onDispose {
                    runCatching { p.stop() }
                    p.release()
                }
            }

            LaunchedEffect(playing) {
                while (playing) {
                    player?.let { positionMs = it.currentPosition }
                    delay(200)
                }
            }

            AlertDialogContent(
                title = { Text("试听语音") },
                text = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Slider(
                            value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                            onValueChange = {
                                positionMs = it.toInt()
                                player?.seekTo(it.toInt())
                            },
                            valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(positionMs))
                            Text(formatDuration(durationMs))
                        }
                        IconButton(
                            onClick = {
                                val p = player ?: return@IconButton
                                if (playing) {
                                    p.pause()
                                    playing = false
                                } else {
                                    p.seekTo(positionMs)
                                    p.start()
                                    playing = true
                                }
                            },
                        ) {
                            Icon(
                                if (playing) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                                contentDescription = if (playing) "暂停" else "播放",
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        player?.let { runCatching { it.stop() }.onFailure { } }
                        player?.release()
                        player = null
                        sendVoiceTo(talker, wavPath) { ok ->
                            if (ok) {
                                showToast(context, "语音已发送")
                                onDismiss()
                            } else {
                                showToast(context, "发送失败")
                            }
                        }
                    }) { Text("发送语音") }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss() }) { Text("关闭") }
                }
            )
        }
    }

    private fun formatDuration(ms: Int): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun generateVoice(text: String, voiceId: String, emoVec: FloatArray, cb: (String?) -> Unit) {
        Thread {
            var wavPath: String? = null
            try {
                val body = JSONObject().apply {
                    put("voiceId", voiceId)
                    put("text", text)
                    put("emoVec", JSONArray(emoVec.map { it.toDouble() }))
                }.toString()
                val resp = httpPostJson("$API_BASE/api/open/v1/tts/simple-generate", body)
                if (resp.isNotEmpty()) {
                    val root = JSONObject(resp)
                    val audioUrl = root.optJSONObject("data")?.optString("audio").orEmpty()
                    if (audioUrl.isNotEmpty()) {
                        val dir = File(HostInfo.application.cacheDir, "wekit_tts").apply { mkdirs() }
                        val dest = File(dir, "tts_${System.currentTimeMillis()}.wav")
                        if (download(audioUrl, dest)) {
                            wavPath = dest.absolutePath
                            lastWavPath = wavPath
                        }
                    }
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "generate voice failed: ${e.message}")
            }
            val result = wavPath
            mh.post { cb(result) }
        }.start()
    }

    private fun sendVoiceTo(talker: String, wavPath: String, cb: (Boolean) -> Unit) {
        Thread {
            val ok = runCatching {
                val silkPath = wavPath.substringBeforeLast('.') + ".silk"
                if (!AudioUtils.anyToSilk(wavPath, silkPath)) return@runCatching false
                val durationMs = AudioUtils.getDurationMs(silkPath).toInt()
                WeMessageApi.sendVoice(talker, silkPath, durationMs)
            }.getOrDefault(false)
            mh.post { cb(ok) }
        }.start()
    }

    private fun fetchVoices(): List<TtsVoice> {
        if (apiKey.isBlank()) return DEFAULT_VOICES
        return try {
            val resp = httpGet("$API_BASE/api/open/v1/voices")
            val root = JSONObject(resp)
            val data = root.optJSONArray("data") ?: return DEFAULT_VOICES
            val list = DEFAULT_VOICES.toMutableList()
            for (i in 0 until data.length()) {
                val group = data.getJSONObject(i)
                val title = group.optString("title")
                val chars = group.optJSONArray("characters") ?: continue
                for (j in 0 until chars.length()) {
                    val c = chars.getJSONObject(j)
                    val vid = c.optString("voice_id")
                    if (vid.isEmpty()) continue
                    if (list.any { it.voiceId == vid }) continue
                    val name = c.optString("name")
                    val actor = c.optString("actor")
                    val label = if (title.isNotEmpty()) "$title-$name" else name
                    list.add(TtsVoice(vid, if (actor.isNotEmpty()) "$label($actor)" else label))
                }
            }
            list
        } catch (e: Exception) {
            WeLogger.w(TAG, "fetch voices failed: ${e.message}")
            DEFAULT_VOICES
        }
    }

    private fun fetchUserVoices(): List<TtsVoice> {
        if (apiKey.isBlank()) return emptyList()
        return try {
            val resp = httpGet("$API_BASE/api/open/v1/user-voices")
            val root = JSONObject(resp)
            val data = root.optJSONArray("data") ?: return emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val vid = item.optString("voice_id")
                    if (vid.isEmpty()) continue
                    val name = item.optString("name")
                    add(TtsVoice(vid, if (name.isEmpty()) vid else name))
                }
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "fetch user voices failed: ${e.message}")
            emptyList()
        }
    }

    private fun httpGet(urlStr: String): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer $apiKey")
        return if (c.responseCode == 200) {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            ""
        }
    }

    private fun httpPostJson(urlStr: String, jsonBody: String): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $apiKey")
        c.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        return if (c.responseCode == 200) {
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            ""
        }
    }

    private fun download(urlStr: String, dest: File): Boolean {
        return runCatching {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            c.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            c.disconnect()
            true
        }.getOrElse {
            WeLogger.w(TAG, "download wav failed: ${it.message}")
            false
        }
    }
}
