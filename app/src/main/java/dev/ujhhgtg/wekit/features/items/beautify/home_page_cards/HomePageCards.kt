package dev.ujhhgtg.wekit.features.items.beautify.home_page_cards

import android.app.Activity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(
    id = "首页三卡",
    nameRes = "feature_beautify_home_page_cards_name",
    categoryIds = [FeatureCategoryIds.BEAUTIFY],
    descriptionRes = "feature_beautify_home_page_cards_description",
)
object HomePageCards : SwitchFeature() {

    private const val TAG = "HomePageCards"

    override val defaultEnabled: Boolean = true

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
}