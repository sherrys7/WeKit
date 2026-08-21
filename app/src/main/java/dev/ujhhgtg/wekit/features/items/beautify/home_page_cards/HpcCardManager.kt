package dev.ujhhgtg.wekit.features.items.beautify.home_page_cards

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Method

object HpcCardManager {

    private const val TAG_INSERTED = "wekit_home_cards"
    private const val TAG = "HpcCardManager"

    private var cachedRoot: LinearLayout? = null

    fun insertCards(act: Activity) {
        val decor = act.window?.decorView as? ViewGroup ?: return
        decor.post {
            val listView = findListView(decor) ?: return@post
            if (listView.tag == TAG_INSERTED) return@post

            val d = act.resources.displayMetrics.density
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            }

            var added = 0
            if (WePrefs.getBoolOrDef("home_calendar_card", true)) {
                addChild(root, HpcCalendarCard.getCard(act), d, added == 0)
                added++
            }
            if (WePrefs.getBoolOrDef("home_image_card", true)) {
                addChild(root, HpcImageCard.getCard(act), d, added == 0)
                added++
            }
            if (WePrefs.getBoolOrDef("home_music_card", true)) {
                addChild(root, HpcMusicCard.getCard(act), d, added == 0)
                added++
            }

            if (root.childCount == 0) return@post

            try {
                val addHeader = listView.javaClass.getMethod("addHeaderView", View::class.java, Any::class.java, Boolean::class.javaPrimitiveType)
                addHeader.invoke(listView, root, null, false)
                listView.tag = TAG_INSERTED
                WeLogger.i(TAG, "首页卡片插入成功（${root.childCount}张）")
            } catch (e: Exception) {
                WeLogger.w(TAG, "首页卡片插入失败: ${e.message}")
            }
        }
    }

    private fun addChild(root: LinearLayout, card: View?, d: Float, isFirst: Boolean) {
        if (card == null) return
        (card.parent as? ViewGroup)?.removeView(card)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (!isFirst) lp.topMargin = (10 * d).toInt()
        root.addView(card, lp)
    }

    private fun findListView(root: ViewGroup): ListView? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is ListView) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    v.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }
}