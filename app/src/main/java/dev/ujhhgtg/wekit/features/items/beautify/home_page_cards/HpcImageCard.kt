package dev.ujhhgtg.wekit.features.items.beautify.home_page_cards

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import coil3.load
import coil3.request.crossfade
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger

object HpcImageCard {

    private const val TAG = "HpcImageCard"

    private var cachedCard: View? = null

    fun clearCache() {
        cachedCard = null
    }

    fun getCard(ctx: Context): View? {
        cachedCard?.let { return it }
        return buildCard(ctx)
    }

    private fun buildCard(ctx: Context): View? {
        try {
            val d = ctx.resources.displayMetrics.density
            val cw = (365 * d).toInt()
            val ch = (145 * d).toInt()
            val r = 20 * d

            val wrapper = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }
            val card = FrameLayout(ctx).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, r)
                    }
                }
            }

            val bgImage = WePrefs.getString("home_image_card_bg_image")
            if (bgImage != null) {
                val iv = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                card.addView(iv, FrameLayout.LayoutParams(-1, -1))
                iv.load(bgImage) { crossfade(true) }
            } else {
                val iv = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.parseColor("#FFFFFFFF"))
                }
                card.addView(iv, FrameLayout.LayoutParams(-1, -1))
            }

            wrapper.addView(card, LinearLayout.LayoutParams(cw, ch))
            cachedCard = wrapper
            return wrapper
        } catch (e: Exception) {
            WeLogger.w(TAG, "图片卡创建失败: ${e.message}")
            return null
        }
    }
}