package dev.ujhhgtg.wekit.features.items.beautify.home_page_cards

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Keyboard_double_arrow_down
import com.composables.icons.materialsymbols.outlined.Keyboard_double_arrow_up
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.ColorPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.nul

@Feature(
    id = "首页三卡",
    nameRes = "feature_beautify_home_page_cards_name",
    categoryIds = [FeatureCategoryIds.BEAUTIFY],
    descriptionRes = "feature_beautify_home_page_cards_description",
)
object HomePageCards : ClickableFeature() {

    private const val TAG = "HomePageCards"

    override val defaultEnabled: Boolean = true

    var calendarCardEnabled by prefOption("home_calendar_card", true)
    var imageCardEnabled by prefOption("home_image_card", true)
    var musicCardEnabled by prefOption("home_music_card", true)
    var cardsOrder by prefOption("home_cards_order", "calendar,image,music")

    var calendarBgColor by prefOption("home_calendar_bg_color", "#FFFFFFFF")
    var calendarBgImage by prefOption("home_calendar_bg_image", nul<String>())
    var imageCardBgImage by prefOption("home_image_card_bg_image", nul<String>())

    var calendarTitleColor by prefOption("home_calendar_title_color", "#FFFFFF")
    var calendarSubtitleColor by prefOption("home_calendar_subtitle_color", "#E0E0E0")
    var calendarYiColor by prefOption("home_calendar_yi_color", "#4CAF50")
    var calendarJiColor by prefOption("home_calendar_ji_color", "#FF5252")
    var calendarDateBgColor by prefOption("home_calendar_date_bg_color", "#000000")

    override fun onEnable() {
        WeLogger.i(TAG, "首页三卡已启用")
        HpcMediaNotification.release()

        Activity::class.java.reflekt().firstMethod { name = "onResume" }.hookAfter {
            val act = thisObject as? Activity ?: return@hookAfter
            if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return@hookAfter
            onLauncherResume(act)
        }
        WeLogger.i(TAG, "首页三卡 hook 已注册")
    }

    override fun onDisable() {
        WeLogger.i(TAG, "首页三卡已禁用")
        HpcCalendarCard.clearCache()
        HpcImageCard.clearCache()
        HpcMusicCard.clearCache()
        HpcMediaNotification.release()
    }

    fun onLauncherResume(act: Activity) {
        HpcMusicCard.bindActivity(act)
        HpcCardManager.insertCards(act)
    }

    private fun selectCalendarImage(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult
                val cr = HostInfo.application.contentResolver
                runCatching {
                    cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure {
                    WeLogger.w(TAG, "failed to take persistable uri permission", it)
                }
                calendarBgImage = uri.toString()
            }
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun selectImageCardImage(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult
                val cr = HostInfo.application.contentResolver
                runCatching {
                    cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure {
                    WeLogger.w(TAG, "failed to take persistable uri permission", it)
                }
                imageCardBgImage = uri.toString()
            }
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var calEnabled by remember { mutableStateOf(calendarCardEnabled) }
            var imgEnabled by remember { mutableStateOf(imageCardEnabled) }
            var musEnabled by remember { mutableStateOf(musicCardEnabled) }
            var order by remember { mutableStateOf(cardsOrder) }
            var calBgColor by remember { mutableStateOf(calendarBgColor) }
            var calHasImage by remember { mutableStateOf(calendarBgImage != null) }
            var imgHasImage by remember { mutableStateOf(imageCardBgImage != null) }
            var titleColor by remember { mutableStateOf(calendarTitleColor) }
            var subtitleColor by remember { mutableStateOf(calendarSubtitleColor) }
            var yiColor by remember { mutableStateOf(calendarYiColor) }
            var jiColor by remember { mutableStateOf(calendarJiColor) }
            var dateBgColor by remember { mutableStateOf(calendarDateBgColor) }

            AlertDialogContent(
                title = { Text("首页三卡设置") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(title = "子卡片开关") {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "日历卡",
                                    description = "农历、黄历宜忌、天气、一言",
                                    checked = calEnabled,
                                    onCheckedChange = {
                                        calEnabled = it
                                        calendarCardEnabled = it
                                    },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "图片卡",
                                    description = "自定义背景图",
                                    checked = imgEnabled,
                                    onCheckedChange = {
                                        imgEnabled = it
                                        imageCardEnabled = it
                                    },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "音乐播放器",
                                    description = "音乐搜索、播放、收藏、悬浮歌词",
                                    checked = musEnabled,
                                    onCheckedChange = {
                                        musEnabled = it
                                        musicCardEnabled = it
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = "日历卡背景") {
                            item {
                                ColorPickerWidget(
                                    title = "背景颜色",
                                    value = calBgColor,
                                    onValueChange = {
                                        calBgColor = it
                                        calendarBgColor = it
                                    },
                                )
                            }
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = "背景图片",
                                    description = if (calHasImage) "已选择图片" else "未设置（使用纯色背景）",
                                    onClick = { selectCalendarImage(context) },
                                    trailingContent = {
                                        if (calHasImage) {
                                            IconButton(onClick = {
                                                calendarBgImage = null
                                                calHasImage = false
                                            }) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Delete,
                                                    contentDescription = "清除图片",
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = "日历卡字体颜色") {
                            item {
                                ColorPickerWidget(
                                    title = "年月标题 / 日期数字 / 天气",
                                    value = titleColor,
                                    onValueChange = {
                                        titleColor = it
                                        calendarTitleColor = it
                                    },
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = "星期 / 农历 / 进度 / 一言",
                                    value = subtitleColor,
                                    onValueChange = {
                                        subtitleColor = it
                                        calendarSubtitleColor = it
                                    },
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = "宜 文字颜色",
                                    value = yiColor,
                                    onValueChange = {
                                        yiColor = it
                                        calendarYiColor = it
                                    },
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = "忌 文字颜色",
                                    value = jiColor,
                                    onValueChange = {
                                        jiColor = it
                                        calendarJiColor = it
                                    },
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = "日期数字背景色",
                                    value = dateBgColor,
                                    onValueChange = {
                                        dateBgColor = it
                                        calendarDateBgColor = it
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = "图片卡背景") {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = "背景图片",
                                    description = if (imgHasImage) "已选择图片" else "未设置（使用纯色背景）",
                                    onClick = { selectImageCardImage(context) },
                                    trailingContent = {
                                        if (imgHasImage) {
                                            IconButton(onClick = {
                                                imageCardBgImage = null
                                                imgHasImage = false
                                            }) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Delete,
                                                    contentDescription = "清除图片",
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = "卡片顺序") {
                            val ids = order.split(",").filter { it.isNotEmpty() }
                            val labels = mapOf(
                                "calendar" to "日历卡",
                                "image" to "图片卡",
                                "music" to "音乐播放器",
                            )
                            ids.forEachIndexed { index, id ->
                                val label = labels[id] ?: id
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "${index + 1}. $label",
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            enabled = index > 0,
                                            onClick = {
                                                val list = ids.toMutableList()
                                                val tmp = list[index]
                                                list[index] = list[index - 1]
                                                list[index - 1] = tmp
                                                order = list.joinToString(",")
                                                cardsOrder = order
                                            },
                                        ) {
                                            Icon(
                                                MaterialSymbols.Outlined.Keyboard_double_arrow_up,
                                                contentDescription = "上移",
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        IconButton(
                                            enabled = index < ids.size - 1,
                                            onClick = {
                                                val list = ids.toMutableList()
                                                val tmp = list[index]
                                                list[index] = list[index + 1]
                                                list[index + 1] = tmp
                                                order = list.joinToString(",")
                                                cardsOrder = order
                                            },
                                        ) {
                                            Icon(
                                                MaterialSymbols.Outlined.Keyboard_double_arrow_down,
                                                contentDescription = "下移",
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}