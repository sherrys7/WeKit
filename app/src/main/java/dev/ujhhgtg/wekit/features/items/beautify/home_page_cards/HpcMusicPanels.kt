package dev.ujhhgtg.wekit.features.items.beautify.home_page_cards

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

object HpcMusicPanels {

    private const val TAG = "HpcMusicPanels"
    private val M = HpcMusicCard
    private val mh = Handler(Looper.getMainLooper())

    private var coverRotation: ObjectAnimator? = null
    private var detailsCurrentLyricTv: TextView? = null
    private var detailsLyricTvExpanded: TextView? = null
    private var lyricsExpanded = false
    private var currentDetailsDialog: Dialog? = null

    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null

    private fun topAct(act: Activity?): Context? = act ?: M.getActivity()

    fun showDetails(act: Activity?) {
        val currentSong = M.getCurrentSong()
        if (currentSong == null) { showSearch(act); return }

        try {
            try { currentDetailsDialog?.let { if (it.isShowing) it.dismiss() } } catch (_: Exception) {}

            val ctx = topAct(act) ?: run { M.toast("无法打开详情页"); return }
            val res = ctx.resources.displayMetrics
            val sw = res.widthPixels
            val sh = res.heightPixels
            val d = res.density

            val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.window?.let { w ->
                val wp = w.attributes
                wp.gravity = Gravity.BOTTOM
                wp.width = -1
                wp.height = -1
                w.attributes = wp
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            dialog.setCanceledOnTouchOutside(true)

            val rootFrame = FrameLayout(ctx).apply { setPadding(0, (sh * 0.06).toInt(), 0, 0) }

            val blurBg = FrameLayout(ctx).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
            val bgCover = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            loadCover(bgCover, currentSong.coverUrl)
            blurBg.addView(bgCover)
            blurBg.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#AAEEF8F0"))
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
            rootFrame.addView(blurBg)

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, 40 * d)
                    }
                }
                setBackgroundColor(Color.parseColor("#AAFFFFFF"))
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }

            val topBar = RelativeLayout(ctx).apply {
                setPadding((sw * 0.04).toInt(), (sh * 0.015).toInt(), (sw * 0.04).toInt(), (sh * 0.01).toInt())
            }
            val iconSize = (36 * d).toInt()

            topBar.addView(TextView(ctx).apply {
                text = "\u2715"
                textSize = 22f
                setTextColor(Color.parseColor("#333333"))
                gravity = Gravity.CENTER
                layoutParams = RelativeLayout.LayoutParams(iconSize, iconSize).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                }
                setOnClickListener { dialog.dismiss() }
            })

            val titleBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = RelativeLayout.LayoutParams(-2, -2).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                }
            }
            titleBlock.addView(TextView(ctx).apply {
                text = currentSong.title
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1A1A1A"))
                gravity = Gravity.CENTER
            })
            titleBlock.addView(TextView(ctx).apply {
                text = currentSong.artist + " · " + platLabel(currentSong.platform)
                textSize = 13f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER
            })
            topBar.addView(titleBlock)

            topBar.addView(TextView(ctx).apply {
                text = "\uD83D\uDD0D"
                textSize = 20f
                layoutParams = RelativeLayout.LayoutParams(iconSize, iconSize).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                }
                setOnClickListener { showSearch(act) }
            })
            card.addView(topBar)

            val tabRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins((sw * 0.04).toInt(), 0, (sw * 0.04).toInt(), 0)
                }
            }
            tabRow.addView(createTabBtn(ctx, "收藏", true).apply { setOnClickListener { showFavList(act) } })
            tabRow.addView(createTabBtn(ctx, "缓存", false).apply { setOnClickListener { showCacheManager(act) } })
            card.addView(tabRow)

            val coverSize = (sw * 0.65).toInt()
            val coverContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(coverSize, coverSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = (sh * 0.03).toInt()
                    bottomMargin = (sh * 0.04).toInt()
                }
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setOval(0, 0, v.width, v.height)
                    }
                }
            }
            val coverView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            loadCover(coverView, currentSong.coverUrl)
            coverContainer.addView(coverView)
            card.addView(coverContainer)

            val funcRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins((sw * 0.04).toInt(), 0, (sw * 0.04).toInt(), (sh * 0.02).toInt())
                }
            }
            funcRow.addView(createFuncBtn(ctx, "HQ 音质").apply { setOnClickListener { M.toast("HQ 音质开发中") } })
            funcRow.addView(createFuncBtn(ctx, "定时").apply { setOnClickListener { showTimerDialog(act) } })
            funcRow.addView(createFuncBtn(ctx, "悬浮词").apply { setOnClickListener { showFloatLyricPanel(act) } })
            card.addView(funcRow)

            val lyricOuter = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
                setPadding((sw * 0.04).toInt(), (sh * 0.02).toInt(), (sw * 0.04).toInt(), 0)
            }
            val curLyricTv = TextView(ctx).apply {
                text = getDisplayLyric(M.getCurrentPos())
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.CENTER }
                setOnClickListener { showExpandedLyrics(act) }
            }
            detailsCurrentLyricTv = curLyricTv
            lyricOuter.addView(curLyricTv)
            card.addView(lyricOuter)

            val bottomSection = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((sw * 0.04).toInt(), (sh * 0.015).toInt(), (sw * 0.04).toInt(), (sh * 0.015).toInt())
            }

            val progressRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val startTime = TextView(ctx).apply {
                text = formatTime(M.getCurrentPos())
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }
            val seekBar = SeekBar(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                max = M.getDuration()
                progress = M.getCurrentPos()
                setPadding((sw * 0.03).toInt(), 0, (sw * 0.03).toInt(), 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) M.seekTo(p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            val endTime = TextView(ctx).apply {
                text = formatTime(M.getDuration())
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
            }
            progressRow.addView(startTime)
            progressRow.addView(seekBar)
            progressRow.addView(endTime)
            bottomSection.addView(progressRow)

            val ctrlRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, (sh * 0.02).toInt(), 0, (sh * 0.02).toInt())
            }
            val detailBtnSize = (48 * d).toInt()
            val detailPlaySize = (60 * d).toInt()
            fun fp() = LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.CENTER }

            val favBtn = TextView(ctx).apply {
                text = if (currentSong.isFavorite) "\u2665" else "\u2661"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FF4444"))
            }
            favBtn.setOnClickListener {
                val s = M.getCurrentSong() ?: return@setOnClickListener
                s.isFavorite = !s.isFavorite
                favBtn.text = if (s.isFavorite) "\u2665" else "\u2661"
                M.saveFav()
                M.refreshMiniFav()
            }
            ctrlRow.addView(favBtn, fp())

            ctrlRow.addView(TextView(ctx).apply {
                text = "\u23EE"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#333333"))
                setOnClickListener { M.playPrev() }
            }, fp())

            val playBtn = TextView(ctx).apply {
                text = if (M.isPlaying()) "\u23F8" else "\u25B6"
                textSize = 36f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1A73E8"))
            }
            playBtn.setOnClickListener {
                if (M.getCurrentSong() == null) { M.toast("请先添加歌曲"); return@setOnClickListener }
                M.toggle()
                playBtn.text = if (M.isPlaying()) "\u23F8" else "\u25B6"
                if (M.isPlaying()) startCoverAnimation(coverView) else pauseCoverAnimation()
            }
            ctrlRow.addView(playBtn, fp())

            ctrlRow.addView(TextView(ctx).apply {
                text = "\u23ED"
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#333333"))
                setOnClickListener { M.playNext() }
            }, fp())

            ctrlRow.addView(TextView(ctx).apply {
                text = "\u2630"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#333333"))
                setOnClickListener { showHistoryDialog(act) }
            }, fp())

            bottomSection.addView(ctrlRow)
            card.addView(bottomSection)

            rootFrame.addView(card)
            dialog.setContentView(rootFrame)
            dialog.show()
            currentDetailsDialog = dialog

            var lastSongKey = currentSong.let { it.title + "|" + it.coverUrl }
            var lastPlaying = M.isPlaying()
            val progressTask = object : Runnable {
                override fun run() {
                    val song = M.getCurrentSong()
                    if (song != null) {
                        val key = song.title + "|" + song.coverUrl
                        if (key != lastSongKey) {
                            lastSongKey = key
                            titleBlock.removeAllViews()
                            titleBlock.addView(TextView(ctx).apply {
                                text = song.title
                                textSize = 18f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.parseColor("#1A1A1A"))
                                gravity = Gravity.CENTER
                            })
                            titleBlock.addView(TextView(ctx).apply {
                                text = song.artist + " · " + platLabel(song.platform)
                                textSize = 13f
                                setTextColor(Color.parseColor("#666666"))
                                gravity = Gravity.CENTER
                            })
                            loadCover(coverView, song.coverUrl)
                            favBtn.text = if (song.isFavorite) "\u2665" else "\u2661"
                            if (M.isPlaying()) restartCoverAnimation(coverView)
                        }
                    }
                    if (M.isPlaying()) {
                        try {
                            val pos = M.getCurrentPos()
                            seekBar.progress = pos
                            startTime.text = formatTime(pos)
                            endTime.text = formatTime(M.getDuration())
                            seekBar.max = M.getDuration()
                        } catch (_: Exception) {}
                    }
                    val playing = M.isPlaying()
                    if (playing != lastPlaying) {
                        lastPlaying = playing
                        playBtn.text = if (playing) "\u23F8" else "\u25B6"
                        if (playing) startCoverAnimation(coverView) else pauseCoverAnimation()
                    }
                    if (detailsCurrentLyricTv != null && song != null && !lyricsExpanded) {
                        detailsCurrentLyricTv?.text = getDisplayLyric(M.getCurrentPos())
                    }
                    mh.postDelayed(this, 1000)
                }
            }
            mh.post(progressTask)

            dialog.setOnDismissListener {
                mh.removeCallbacks(progressTask)
                pauseCoverAnimation()
                detailsCurrentLyricTv = null
                currentDetailsDialog = null
            }
            if (M.isPlaying()) startCoverAnimation(coverView)
        } catch (e: Exception) {
            WeLogger.w(TAG, "showDetails 异常: $e")
            M.toast("详情页加载失败")
        }
    }

    private fun getDisplayLyric(currentMs: Int): String {
        val s = M.getCurrentSong() ?: return "暂无歌词"
        if (s.lrctxt.isEmpty()) return "暂无歌词"
        val lyric = M.getLyricAt(currentMs)
        return lyric ?: "即将播放..."
    }

    private fun showExpandedLyrics(act: Activity?) {
        val currentSong = M.getCurrentSong()
        if (currentSong == null || currentSong.lrctxt.isEmpty()) { M.toast("暂无歌词"); return }
        lyricsExpanded = true

        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels
        val sh = res.heightPixels
        val d = res.density

        val lyricDialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        lyricDialog.window?.let { w ->
            val wp = w.attributes
            wp.gravity = Gravity.BOTTOM
            wp.width = -1
            wp.height = (sh * 0.75).toInt()
            w.attributes = wp
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        lyricDialog.setCanceledOnTouchOutside(true)

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.06).toInt(), (sw * 0.05).toInt(), (sw * 0.06).toInt(), (sw * 0.05).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 30 * d)
                }
            }
        }
        panel.addView(TextView(ctx).apply {
            text = "全部歌词 · " + currentSong.title
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.03).toInt())
        })

        val lyricScroll = ScrollView(ctx)
        val expanded = TextView(ctx).apply {
            text = currentSong.lrctxt
            textSize = 14f
            setTextColor(Color.parseColor("#444444"))
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(12f, 1.5f)
            setPadding(0, (sw * 0.04).toInt(), 0, (sw * 0.08).toInt())
        }
        detailsLyricTvExpanded = expanded
        lyricScroll.addView(expanded)
        panel.addView(lyricScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        lyricDialog.setContentView(panel)
        lyricDialog.setOnDismissListener { lyricsExpanded = false }
        lyricDialog.show()

        mh.postDelayed({ scrollExpandedLyricToCurrent() }, 300)
    }

    private fun scrollExpandedLyricToCurrent() {
        val tv = detailsLyricTvExpanded ?: return
        if (M.getCurrentSong() == null) return
        try {
            var lineHeight = tv.lineHeight
            if (lineHeight == 0) lineHeight = 48
            val currentLine = M.getLyricLine(M.getCurrentPos())
            if (currentLine >= 0) {
                var scrollY = currentLine * lineHeight - 200
                if (scrollY < 0) scrollY = 0
                (tv.parent as? ScrollView)?.smoothScrollTo(0, scrollY)
            }
        } catch (_: Exception) {}
    }

    private fun showFavList(act: Activity?) {
        val favList = M.getFavList()
        if (favList.isEmpty()) { M.toast("暂无收藏歌曲"); return }

        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels
        val sh = res.heightPixels
        val d = res.density

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt())
            setBackgroundColor(Color.parseColor("#FFFFFF"))
        }
        val scroll = ScrollView(ctx)
        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        for (favItem in favList) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(ctx).apply {
                text = favItem.title + " - " + favItem.artist
                textSize = 15f
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = "\u25B6"
                textSize = 18f
                setTextColor(Color.parseColor("#1677FF"))
                setPadding((sw * 0.04).toInt(), (8 * d).toInt(), (sw * 0.04).toInt(), (8 * d).toInt())
                setOnClickListener {
                    val idx = M.indexOf(favItem)
                    if (idx >= 0) M.setCurrentAndPlay(idx)
                }
            })
            listLayout.addView(row)
        }
        scroll.addView(listLayout)
        layout.addView(scroll)

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.window?.let { w ->
            val wp = w.attributes
            wp.gravity = Gravity.BOTTOM
            wp.width = -1
            wp.height = (sh * 0.6).toInt()
            w.attributes = wp
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.setContentView(layout)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    fun showSearch(act: Activity?) {
        val ctx = topAct(act) ?: run { M.toast("无法打开搜索"); return }
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels
        val sh = res.heightPixels
        val d = res.density

        val searchDialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        searchDialog.window?.let { w ->
            val wp = w.attributes
            wp.gravity = Gravity.BOTTOM
            wp.width = -1
            wp.height = (sh * 0.8).toInt()
            w.attributes = wp
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        searchDialog.setCanceledOnTouchOutside(true)

        val rootPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 30 * d)
                }
            }
        }

        val platRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((sw * 0.04).toInt(), (sh * 0.03).toInt(), (sw * 0.04).toInt(), (sh * 0.01).toInt())
        }
        rootPanel.addView(platRow)

        val currentPlat = arrayOf("wy")
        val qqBtn = createTabBtn(ctx, "QQ音乐", false)
        val wyBtn = createTabBtn(ctx, "网易云", true)
        qqBtn.setOnClickListener {
            currentPlat[0] = "qq"
            qqBtn.setTextColor(Color.parseColor("#FFFFFF"))
            qqBtn.background = createTagBg(Color.parseColor("#333333"), 20)
            wyBtn.setTextColor(Color.parseColor("#333333"))
            wyBtn.background = createTagBg(Color.parseColor("#EEEEEE"), 20)
        }
        wyBtn.setOnClickListener {
            currentPlat[0] = "wy"
            wyBtn.setTextColor(Color.parseColor("#FFFFFF"))
            wyBtn.background = createTagBg(Color.parseColor("#333333"), 20)
            qqBtn.setTextColor(Color.parseColor("#333333"))
            qqBtn.background = createTagBg(Color.parseColor("#EEEEEE"), 20)
        }
        platRow.addView(qqBtn)
        platRow.addView(wyBtn)

        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((sw * 0.04).toInt(), 0, (sw * 0.04).toInt(), (sh * 0.02).toInt())
        }
        val inputField = EditText(ctx).apply {
            hint = "搜索音乐..."
            textSize = 15f
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.04).toInt(), (sh * 0.01).toInt(), (sw * 0.04).toInt(), (sh * 0.01).toInt())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(0, 0, (sw * 0.02).toInt(), 0)
            }
        }
        searchRow.addView(inputField)
        val searchAction = TextView(ctx).apply {
            text = "搜索"
            textSize = 15f
            setTextColor(Color.parseColor("#1677FF"))
        }
        searchRow.addView(searchAction)
        rootPanel.addView(searchRow)

        val resultContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scrollView = ScrollView(ctx).apply { addView(resultContainer) }
        rootPanel.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        searchDialog.setContentView(rootPanel)
        searchDialog.show()

        val isSearching = booleanArrayOf(false)
        searchAction.setOnClickListener {
            if (isSearching[0]) { M.toast("正在搜索中..."); return@setOnClickListener }
            val keyword = inputField.text.toString().trim()
            if (keyword.isEmpty()) { M.toast("请输入关键词"); return@setOnClickListener }
            isSearching[0] = true
            resultContainer.removeAllViews()
            resultContainer.addView(TextView(ctx).apply {
                text = "搜索中..."
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 40)
                setTextColor(Color.parseColor("#999999"))
            })

            M.search(keyword, currentPlat[0]) { jsonResult ->
                isSearching[0] = false
                resultContainer.removeAllViews()
                if (jsonResult.isEmpty() || jsonResult == "[]") {
                    resultContainer.addView(TextView(ctx).apply {
                        text = "未找到结果"
                        gravity = Gravity.CENTER
                        setPadding(0, 40, 0, 40)
                        setTextColor(Color.parseColor("#999999"))
                    })
                    return@search
                }
                if (jsonResult == "ERROR") {
                    resultContainer.addView(TextView(ctx).apply {
                        text = "服务连接失败"
                        gravity = Gravity.CENTER
                        setPadding(0, 40, 0, 40)
                        setTextColor(Color.parseColor("#FF4444"))
                    })
                    return@search
                }
                try {
                    val items = JSONArray(jsonResult)
                    for (i in 0 until items.length()) {
                        val obj = items.getJSONObject(i)
                        val n = obj.optInt("n")
                        val name = obj.optString("name")
                        val singer = obj.optString("singer")
                        val plat = obj.optString("platform")

                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding((sw * 0.04).toInt(), (12 * d).toInt(), (sw * 0.04).toInt(), (12 * d).toInt())
                            setBackgroundColor(Color.parseColor("#FFFFFF"))
                        }
                        val info = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        }
                        info.addView(TextView(ctx).apply {
                            text = name
                            textSize = 14f
                            setTextColor(Color.parseColor("#333333"))
                        })
                        info.addView(TextView(ctx).apply {
                            text = singer
                            textSize = 11f
                            setTextColor(Color.parseColor("#999999"))
                        })
                        row.addView(info)

                        val addBtn = TextView(ctx).apply {
                            text = "\u2795"
                            textSize = 18f
                            setTextColor(Color.parseColor("#1677FF"))
                            setPadding((sw * 0.04).toInt(), (8 * d).toInt(), (sw * 0.04).toInt(), (8 * d).toInt())
                        }
                        addBtn.setOnClickListener {
                            searchDialog.dismiss()
                            M.toast("正在获取歌曲...")
                            M.getTrackDetail(keyword, obj.optString("mid"), plat) { item ->
                                if (item == null) { M.toast("获取失败"); return@getTrackDetail }
                                if (item.playUrl.isEmpty()) { M.toast("该歌曲暂无可用播放链接"); return@getTrackDetail }
                                M.addToPlaylistAndPlay(item)
                                M.toast("已添加: " + item.title)
                            }
                        }
                        row.addView(addBtn)
                        resultContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                            setMargins(0, 0, 0, (2 * d).toInt())
                        })
                    }
                } catch (_: Exception) { M.toast("解析失败") }
            }
        }
    }

    fun showHistoryDialog(act: Activity?) {
        try {
            val history = M.getHistoryItems()
            if (history.isEmpty()) { M.toast("暂无播放历史"); return }

            val ctx = topAct(act) ?: return
            val res = ctx.resources.displayMetrics
            val sw = res.widthPixels
            val sh = res.heightPixels
            val d = res.density

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt())
                setBackgroundColor(Color.parseColor("#FFFFFF"))
            }
            val scroll = ScrollView(ctx)
            val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

            for (historyItem in history) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(ctx).apply {
                    text = historyItem.title + " - " + historyItem.artist
                    textSize = 14f
                    setTextColor(Color.parseColor("#333333"))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                row.addView(TextView(ctx).apply {
                    text = "\u25B6"
                    textSize = 18f
                    setTextColor(Color.parseColor("#1677FF"))
                    setPadding((sw * 0.04).toInt(), (8 * d).toInt(), (sw * 0.04).toInt(), (8 * d).toInt())
                    setOnClickListener {
                        val idx = M.indexOf(historyItem)
                        if (idx >= 0) M.setCurrentAndPlay(idx)
                        else { M.addToPlaylist(historyItem); M.setCurrentAndPlay(M.getPlaylist().size - 1) }
                    }
                })
                listLayout.addView(row)
            }
            scroll.addView(listLayout)
            layout.addView(scroll)

            val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.window?.let { w ->
                val wp = w.attributes
                wp.gravity = Gravity.BOTTOM
                wp.width = -1
                wp.height = (sh * 0.6).toInt()
                w.attributes = wp
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            dialog.setContentView(layout)
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
        } catch (_: Exception) { M.toast("历史功能异常") }
    }

    private fun showCacheManager(act: Activity?) {
        val playlist = M.getPlaylist()
        if (playlist.isEmpty()) { M.toast("当前没有缓存歌曲"); return }

        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels
        val sh = res.heightPixels
        val d = res.density

        val cardPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt(), (sw * 0.04).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 20 * d)
                }
            }
        }
        cardPanel.addView(TextView(ctx).apply {
            text = "缓存管理 (" + playlist.size + " 首)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.03).toInt())
        })

        val scroll = ScrollView(ctx)
        val listLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val copyList = ArrayList(playlist)
        for (i in copyList.indices) {
            val cacheItem = copyList[i]
            val idx = i
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(ctx).apply {
                text = "${idx + 1}. " + cacheItem.title + " - " + cacheItem.artist
                textSize = 13f
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = "移除"
                textSize = 13f
                setTextColor(Color.parseColor("#FF4444"))
                setPadding((sw * 0.04).toInt(), (6 * d).toInt(), (sw * 0.04).toInt(), (6 * d).toInt())
                setOnClickListener {
                    M.removeItem(idx)
                    M.toast("已移除: " + cacheItem.title)
                    showCacheManager(act)
                }
            })
            listLayout.addView(row)
            listLayout.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#EEEEEE")) },
                LinearLayout.LayoutParams(-1, (1 * d).toInt()))
        }
        scroll.addView(listLayout)
        cardPanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        cardPanel.addView(TextView(ctx).apply {
            text = "一键清理全部"
            textSize = 15f
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#FF4444"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (sw * 0.03).toInt() }
            setOnClickListener { M.clearAllCache(); M.toast("缓存已全部清理") }
        })

        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.window?.let { w ->
            val wp = w.attributes
            wp.gravity = Gravity.CENTER
            wp.width = (sw * 0.9).toInt()
            wp.height = (sh * 0.65).toInt()
            w.attributes = wp
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.setContentView(cardPanel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun showFloatLyricPanel(act: Activity?) {
        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels
        val d = res.density

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.06).toInt(), (sw * 0.05).toInt(), (sw * 0.06).toInt(), (sw * 0.05).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 20 * d)
                }
            }
        }
        panel.addView(TextView(ctx).apply {
            text = "桌面悬浮歌词"
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.04).toInt())
        })

        val options = arrayOf("关闭悬浮词", "开启(自由拖动)", "开启(锁定防误触)")
        val btnH = (48 * d).toInt()

        val dialog = android.app.AlertDialog.Builder(ctx).create()

        for (i in options.indices) {
            val idx = i
            panel.addView(TextView(ctx).apply {
                text = options[i]
                textSize = 16f
                setTextColor(Color.parseColor("#333333"))
                setBackgroundColor(Color.parseColor("#F5F5F5"))
                gravity = Gravity.CENTER
                setPadding(0, btnH / 2, 0, btnH / 2)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
                setOnClickListener {
                    try {
                        when (idx) {
                            0 -> { HpcFloatLyric.hideFloatLyric(); M.toast("悬浮词已关闭") }
                            1 -> { HpcFloatLyric.setLocked(false); HpcFloatLyric.showFloatLyric(ctx); M.toast("悬浮词已开启(自由拖动)") }
                            2 -> { HpcFloatLyric.setLocked(true); HpcFloatLyric.showFloatLyric(ctx); M.toast("悬浮词已开启(锁定防误触)") }
                        }
                    } catch (e: Exception) { WeLogger.w(TAG, "悬浮词切换失败: $e") }
                    dialog.dismiss()
                }
            })
        }

        dialog.setView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showTimerDialog(act: Activity?) {
        val ctx = topAct(act) ?: return
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels
        val d = res.density

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.06).toInt(), (sw * 0.05).toInt(), (sw * 0.06).toInt(), (sw * 0.05).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 24 * d)
                }
            }
        }
        panel.addView(TextView(ctx).apply {
            text = "定时关闭播放"
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.04).toInt())
        })

        val labels = arrayOf("15 分钟", "30 分钟", "自定义", "关闭定时")
        val btnH = (48 * d).toInt()

        val dialog = android.app.AlertDialog.Builder(ctx).create()

        for (i in labels.indices) {
            val idx = i
            val label = labels[i]
            panel.addView(TextView(ctx).apply {
                text = label
                textSize = 16f
                setTextColor(Color.parseColor("#333333"))
                background = createTagBg(Color.parseColor("#F5F5F5"), 16)
                gravity = Gravity.CENTER
                setPadding(0, btnH / 2, 0, btnH / 2)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() }
                setOnClickListener {
                    when (idx) {
                        0 -> startSleepTimer(15)
                        1 -> startSleepTimer(30)
                        2 -> { dialog.dismiss(); showCustomTimerInput(ctx); return@setOnClickListener }
                        3 -> { timerRunnable?.let { timerHandler?.removeCallbacks(it) }; M.toast("定时已取消") }
                    }
                    dialog.dismiss()
                }
            })
        }

        dialog.setView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showCustomTimerInput(ctx: Context) {
        val res = ctx.resources.displayMetrics
        val sw = res.widthPixels
        val d = res.density

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding((sw * 0.06).toInt(), (sw * 0.05).toInt(), (sw * 0.06).toInt(), (sw * 0.05).toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 24 * d)
                }
            }
        }
        panel.addView(TextView(ctx).apply {
            text = "自定义定时（分钟）"
            textSize = 18f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, (sw * 0.04).toInt())
        })

        val input = EditText(ctx).apply {
            hint = "请输入整数分钟，如 45"
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setHintTextColor(Color.parseColor("#AAAAAA"))
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F7F7F7"))
                cornerRadius = 16 * d
                setStroke((1.5f * d).toInt(), Color.parseColor("#1A73E8"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        panel.addView(input)

        val btnH = (48 * d).toInt()
        val dialog = android.app.AlertDialog.Builder(ctx).create()

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * d).toInt() }
        }
        btnRow.addView(TextView(ctx).apply {
            text = "取消"
            textSize = 16f
            setTextColor(Color.parseColor("#666666"))
            background = createTagBg(Color.parseColor("#F5F5F5"), 16)
            gravity = Gravity.CENTER
            setPadding(0, btnH / 3, 0, btnH / 3)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = (6 * d).toInt() }
            setOnClickListener { dialog.dismiss() }
        })
        btnRow.addView(TextView(ctx).apply {
            text = "确定"
            textSize = 16f
            setTextColor(Color.parseColor("#FFFFFF"))
            background = createTagBg(Color.parseColor("#1A73E8"), 16)
            gravity = Gravity.CENTER
            setPadding(0, btnH / 3, 0, btnH / 3)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = (6 * d).toInt() }
            setOnClickListener {
                val txt = input.text.toString().trim()
                val minutes = txt.toIntOrNull()
                if (minutes == null || minutes <= 0) { M.toast("请输入大于 0 的整数分钟"); return@setOnClickListener }
                startSleepTimer(minutes)
                dialog.dismiss()
            }
        })
        panel.addView(btnRow)

        dialog.setView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun startSleepTimer(minutes: Int) {
        if (timerHandler == null) timerHandler = Handler(Looper.getMainLooper())
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
        timerRunnable = Runnable {
            if (M.isPlaying()) { M.toggle(); M.toast("定时关闭") }
        }
        timerHandler?.postDelayed(timerRunnable!!, minutes * 60 * 1000L)
        M.toast("将在 $minutes 分钟后关闭")
    }

    private fun startCoverAnimation(view: ImageView?) {
        if (view == null) return
        try {
            if (coverRotation == null || coverRotation?.target !== view) {
                coverRotation?.cancel()
                coverRotation = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                    duration = 15000
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = LinearInterpolator()
                }
            }
            view.post {
                coverRotation?.let { if (!it.isStarted) it.start() else it.resume() }
            }
        } catch (_: Exception) {}
    }

    private fun restartCoverAnimation(view: ImageView?) {
        if (view == null) return
        try {
            coverRotation?.cancel()
            coverRotation = null
            view.rotation = 0f
            startCoverAnimation(view)
        } catch (_: Exception) {}
    }

    private fun pauseCoverAnimation() {
        coverRotation?.let { if (it.isStarted) it.pause() }
    }

    private fun createFuncBtn(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(8, 0, 8, 0) }
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#555555"))
            background = createTagBg(Color.parseColor("#33FFFFFF"), 20)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }

    private fun createTabBtn(ctx: Context, text: String, selected: Boolean): TextView =
        TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) }
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#333333"))
            background = createTagBg(Color.parseColor(if (selected) "#1A73E8" else "#EEEEEE"), 20)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 10)
        }

    private fun createTagBg(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusDp.toFloat() }

    private fun loadCover(iv: ImageView, url: String) {
        if (url.isEmpty()) return
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.connect()
                if (conn.responseCode == 200) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null) mh.post { iv.setImageBitmap(bmp) }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun formatTime(millis: Int): String {
        val ts = millis / 1000
        return "%02d:%02d".format(ts / 60, ts % 60)
    }

    private fun platLabel(platform: String): String = when (platform) {
        "qq" -> "QQ音乐"
        "wy" -> "网易云音乐"
        else -> "音乐"
    }
}