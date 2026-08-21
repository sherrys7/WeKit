package dev.ujhhgtg.wekit.features.items.beautify.home_page_cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

object HpcCalendarCard {

    private const val TAG = "HpcCalendarCard"

    private var cachedWrapper: LinearLayout? = null

    fun clearCache() {
        cachedWrapper = null
    }

    fun getCard(ctx: Context): View? {
        cachedWrapper?.let { return it }
        return buildCard(ctx)
    }

    private fun buildCard(ctx: Context): LinearLayout? {
        try {
            val d = ctx.resources.displayMetrics.density
            val cw = (352 * d).toInt()
            val ch = (215 * d).toInt()
            val r = 20 * d
            val pad = (16 * d).toInt()

            val wrapper = LinearLayout(ctx).apply { gravity = Gravity.CENTER }

            val card = FrameLayout(ctx).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, r)
                    }
                }
            }

            card.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF1A237E"))
                    cornerRadius = r
                }
            }, FrameLayout.LayoutParams(-1, -1))

            card.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#40000000")) },
                FrameLayout.LayoutParams(-1, -1))

            val ct = RelativeLayout(ctx).apply {
                setPadding(pad, pad, pad, pad)
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            card.addView(ct)

            val cal = Calendar.getInstance()
            val yr = cal.get(Calendar.YEAR)
            val mo = cal.get(Calendar.MONTH) + 1
            val dy = cal.get(Calendar.DAY_OF_MONTH)
            val wk = cal.get(Calendar.WEEK_OF_YEAR)
            val mx = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val pct = (dy.toDouble() / mx * 100).toInt()
            val wds = arrayOf("星期日","星期一","星期二","星期三","星期四","星期五","星期六")
            var wi = cal.get(Calendar.DAY_OF_WEEK) - 1
            if (wi < 0) wi = 0

            val cl = WePrefs.getStringOrDef("lunar_date", "四月十三")
            val cy = WePrefs.getStringOrDef("yi", "疗病 结婚 交易 入仓 求职")
            val cj = WePrefs.getStringOrDef("ji", "安葬 动土 针灸")
            val cw2 = WePrefs.getStringOrDef("weather_display", "深圳 31°C 多云")
            val chk = WePrefs.getStringOrDef("hitokoto", "愿你走出半生，归来仍是少年")

            val lb = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
            ct.addView(lb, RelativeLayout.LayoutParams(-2, -2).apply {
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                topMargin = (8 * d).toInt()
            })
            lb.addView(tv(ctx, "${yr}年${mo}月", 16, "#FFFFFF", true))
            lb.addView(tv(ctx, wds[wi], 14, "#E0E0E0", false))
            val td = tv(ctx, dy.toString(), 36, "#FFFFFF", true).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#000000"))
                    cornerRadius = 8 * d
                }
                gravity = Gravity.CENTER
            }
            val ds = (50 * d).toInt()
            lb.addView(td, LinearLayout.LayoutParams(ds, ds))
            val lr = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            lr.addView(tv(ctx, "$cl 第${wk}周", 14, "#E0E0E0", false).apply { tag = "lunar_tv" })
            lr.addView(tv(ctx, "本月进度 $pct%", 12, "#E0E0E0", false).apply { setPadding((12 * d).toInt(), 0, 0, 0) })
            lb.addView(lr, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (4 * d).toInt() })

            val rb = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            ct.addView(rb, RelativeLayout.LayoutParams(-2, -2).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                topMargin = (8 * d).toInt()
            })
            rb.addView(tv(ctx, cw2, 12, "#FFFFFF", false).apply {
                tag = "weather_tv"
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4CAF50"))
                    cornerRadius = 12 * d
                }
                setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
            })
            rb.addView(tv(ctx, "宜 $cy", 12, "#4CAF50", false).apply { tag = "yi_tv" },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = (28 * d).toInt() })
            rb.addView(tv(ctx, "忌 $cj", 12, "#FF5252", false).apply { tag = "ji_tv" },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = (4 * d).toInt() })

            ct.addView(tv(ctx, chk, 12, "#E0E0E0", false).apply {
                tag = "hitokoto_tv"
                gravity = Gravity.CENTER
                maxWidth = cw - pad * 2
            }, RelativeLayout.LayoutParams(-2, -2).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.CENTER_HORIZONTAL)
                bottomMargin = (8 * d).toInt()
            })

            val rf = ImageView(ctx).apply {
                setImageResource(android.R.drawable.ic_popup_sync)
                setColorFilter(Color.parseColor("#80FFFFFF"))
                isClickable = true
                isFocusable = true
            }
            ct.addView(rf, RelativeLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                bottomMargin = (32 * d).toInt()
                rightMargin = (18 * d).toInt()
            })
            rf.setOnClickListener { v ->
                v.isEnabled = false
                Thread {
                    refreshData(wrapper)
                    Handler(Looper.getMainLooper()).post { v.isEnabled = true }
                }.start()
            }

            wrapper.addView(card, LinearLayout.LayoutParams(cw, ch))
            cachedWrapper = wrapper

            Thread { refreshData(wrapper) }.start()
            return wrapper
        } catch (e: Exception) {
            WeLogger.w(TAG, "日历卡创建失败: ${e.message}")
            return null
        }
    }

    private fun tv(ctx: Context, t: String, sz: Int, clr: String, bold: Boolean): TextView =
        TextView(ctx).apply {
            text = t
            textSize = sz.toFloat()
            setTextColor(Color.parseColor(clr))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun refreshData(wrapper: LinearLayout?) {
        if (wrapper == null) return
        val c = Calendar.getInstance()
        val yr = c.get(Calendar.YEAR)
        val mo = c.get(Calendar.MONTH) + 1
        val dy = c.get(Calendar.DAY_OF_MONTH)
        val wk = c.get(Calendar.WEEK_OF_YEAR)

        val lj = httpGet("https://www.36jxs.com/api/Commonweal/almanac?sun=$yr-$mo-$dy")
        if (lj.isNotEmpty()) {
            try {
                val root = JSONObject(lj)
                if (root.optInt("code") == 1) {
                    val data = root.optJSONObject("data")
                    if (data != null) {
                        val ld = data.optString("LMonth","四月") + data.optString("LDay","十三")
                        val yi = data.optString("Yi","").replace("."," ")
                        val ji = data.optString("Ji","").replace("."," ")
                        WePrefs.putString("lunar_date", ld)
                        WePrefs.putString("yi", yi)
                        WePrefs.putString("ji", ji)
                        Handler(Looper.getMainLooper()).post {
                            (wrapper.findViewWithTag<TextView>("lunar_tv"))?.text = "$ld 第${wk}周"
                            (wrapper.findViewWithTag<TextView>("yi_tv"))?.text = "宜 $yi"
                            (wrapper.findViewWithTag<TextView>("ji_tv"))?.text = "忌 $ji"
                        }
                    }
                }
            } catch (e: Exception) {
                WeLogger.w(TAG, "黄历解析失败: ${e.message}")
            }
        }

        try {
            val lat = WePrefs.getStringOrDef("wp_lat", "")
            val lon = WePrefs.getStringOrDef("wp_lon", "")
            if (lat.isNotEmpty() && lon.isNotEmpty()) {
                val lastTs = WePrefs.getStringOrDef("weather_time", "0").toLongOrNull() ?: 0L
                val now = System.currentTimeMillis()
                val expired = now - lastTs > 31 * 60 * 1000L
                if (expired) {
                    val wj = httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto")
                    if (wj.isNotEmpty()) {
                        val root = JSONObject(wj)
                        val cur = root.optJSONObject("current")
                        if (cur != null) {
                            val temp = cur.optDouble("temperature_2m", 0.0)
                            val code = cur.optInt("weather_code", 0)
                            val city = WePrefs.getStringOrDef("weather_city", "当前位置")
                            val wd = "$city ${Math.round(temp)}°C ${wmoToCn(code)}"
                            WePrefs.putString("weather_display", wd)
                            WePrefs.putString("weather_time", now.toString())
                            Handler(Looper.getMainLooper()).post {
                                (wrapper.findViewWithTag<TextView>("weather_tv"))?.text = wd
                            }
                        }
                    }
                } else {
                    val wd = WePrefs.getStringOrDef("weather_display", "")
                    if (wd.isNotEmpty()) Handler(Looper.getMainLooper()).post {
                        (wrapper.findViewWithTag<TextView>("weather_tv"))?.text = wd
                    }
                }
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "天气刷新失败: ${e.message}")
        }

        val hk = httpGet("https://api.bi71t5.cn/api/juhe.php")
        if (hk.isNotEmpty()) {
            WePrefs.putString("hitokoto", hk)
            Handler(Looper.getMainLooper()).post {
                (wrapper.findViewWithTag<TextView>("hitokoto_tv"))?.text = hk
            }
        }
    }

    private fun httpGet(urlStr: String): String {
        return try {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            c.connect()
            if (c.responseCode == 200) {
                c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else ""
        } catch (e: Exception) {
            WeLogger.w(TAG, "HTTP失败: ${e.message}")
            ""
        }
    }

    private fun wmoToCn(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "少云"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51 -> "小毛毛雨"
        53 -> "毛毛雨"
        55 -> "大毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "小阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85 -> "小阵雪"
        86 -> "阵雪"
        95 -> "雷阵雨"
        96 -> "雷阵雨伴冰雹"
        99 -> "强雷暴伴冰雹"
        else -> "未知"
    }
}