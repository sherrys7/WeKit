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
import org.json.JSONArray

object HpcImageCard {

    private const val TAG = "HpcImageCard"

    private var cachedCard: View? = null

    fun clearCache() {
        cachedCard = null
    }

    fun getCard(ctx: Context): View? {
        cachedCard?.let { return it }
        return if (HomePageCards.imageCardFormat == "five") buildFiveCard(ctx) else buildCard(ctx)
    }

    fun imageCardImagesList(): List<String> {
        val s = WePrefs.getString("home_image_card_images") ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildCard(ctx: Context): View? {
        try {
            val d = ctx.resources.displayMetrics.density
            val cw = (365 * d).toInt()
            val ch = (180 * d).toInt()
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

    private fun buildFiveCard(ctx: Context): View? {
        try {
            val d = ctx.resources.displayMetrics.density
            val cw = (365 * d).toInt()
            val ch = (180 * d).toInt()
            val r = 20 * d
            val gap = (2 * d).toInt()
            val uris = imageCardImagesList()

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
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F0F0F0")); cornerRadius = r
                }
            }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val rotations = floatArrayOf(330f, 320f, 0f, 40f, 30f)
            for (i in 0 until 5) {
                val iv = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    rotationX = rotations[i]
                    if (i >= uris.size) setBackgroundColor(Color.parseColor("#E0E0E0"))
                }
                if (i < uris.size) iv.load(uris[i]) { crossfade(true) }
                val lp = LinearLayout.LayoutParams(0, -1, 1f)
                if (i > 0) lp.marginStart = gap
                row.addView(iv, lp)
            }
            card.addView(row, FrameLayout.LayoutParams(-1, -1))
            wrapper.addView(card, LinearLayout.LayoutParams(cw, ch))
            cachedCard = wrapper
            return wrapper
        } catch (e: Exception) {
            WeLogger.w(TAG, "五图卡片创建失败: ${e.message}")
            return null
        }
    }
}