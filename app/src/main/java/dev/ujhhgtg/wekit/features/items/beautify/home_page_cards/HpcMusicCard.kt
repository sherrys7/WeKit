package dev.ujhhgtg.wekit.features.items.beautify.home_page_cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder


object HpcMusicCard {

    class MusicItem(
        var title: String, var artist: String, var coverUrl: String,
        var playUrl: String, var lrctxt: String, var mid: String, var platform: String = ""
    ) { var isFavorite = false }

    private const val AURORA_WY = "http://api3.andeer.top/API/music_wy"
    private const val AURORA_LRC = "http://api3.andeer.top/API/music_wy_geci"
    private const val URL_QQ = "https://api.ygking.top/api"

    private const val MODE_SEQUENCE = 0
    private const val MODE_RANDOM = 1
    private const val MODE_LOOP = 2
    private const val TAG = "HpcMusicCard"

    private var cachedCard: View? = null
    private val playlist = ArrayList<MusicItem>()
    private var currentIndex = -1
    private var isPlaying = false
    private var isPreparing = false
    private var player: MediaPlayer? = null
    private var duration = 0
    private var currentPos = 0
    private var currentSong: MusicItem? = null
    private var miniCard: View? = null
    private var currentMode = MODE_SEQUENCE
    private val shuffledOrder = ArrayList<Int>()
    private var shuffleIndex = 0

    val coverSharedCache = HashMap<String, Bitmap?>()

    private val mh = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private var _act: Context? = null

    fun getActivity(): Context? = _act

    fun clearCache() {
        cachedCard = null
    }

    fun getCard(ctx: Context): View? {
        _act = ctx
        if (playlist.isEmpty()) loadSavedPlaylist()
        cachedCard?.let {
            miniCard = it
            currentSong?.let { s -> updateMiniCardUI(s) }
            refreshMiniFavIcon()
            return it
        }
        return buildCard(ctx)
    }

    private fun buildCard(ctx: Context): View? {
        try {
            initPlayMode()
            val d = ctx.resources.displayMetrics.density
            val sw = ctx.resources.displayMetrics.widthPixels
            val cw = (370 * d).toInt()
            val ch = (178 * d).toInt()
            val r = 24 * d
            val cr = 12 * d
            val pad = (10 * d).toInt()
            val cs = (54 * d).toInt()

            val wrapper = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, r)
                    }
                }
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#D9FFFFFF"))
                    cornerRadius = r
                }
            }

            val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val coverContainer = FrameLayout(ctx).apply {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, cr)
                    }
                }
            }
            val coverView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                tag = "mini_cover"
            }
            loadCover(coverView, currentSong?.coverUrl ?: "")
            coverContainer.addView(coverView, FrameLayout.LayoutParams(-1, -1))
            topRow.addView(coverContainer, LinearLayout.LayoutParams(cs, cs))

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            textCol.addView(TextView(ctx).apply {
                text = currentSong?.title ?: "暂无歌曲"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0D0D0D"))
                isSingleLine = true
                tag = "mini_title"
            }, LinearLayout.LayoutParams(-1, -2))
            textCol.addView(TextView(ctx).apply {
                text = currentSong?.artist ?: "搜索添加歌曲"
                textSize = 11f
                setTextColor(Color.parseColor("#666666"))
                isSingleLine = true
                tag = "mini_artist"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (3 * d).toInt() })
            topRow.addView(textCol, LinearLayout.LayoutParams(0, cs, 1f).apply { leftMargin = (10 * d).toInt() })
            card.addView(topRow, LinearLayout.LayoutParams(-1, cs))

            card.addView(TextView(ctx).apply {
                text = if (currentSong != null) "点击卡片进入播放器" else "点击卡片搜索歌曲"
                textSize = 12f
                setTextColor(Color.parseColor("#1677FF"))
                gravity = Gravity.CENTER
                isSingleLine = true
                tag = "mini_lyric_hint"
            }, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (4 * d).toInt()
                bottomMargin = (2 * d).toInt()
            })

            val seekRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
            }
            seekRow.addView(TextView(ctx).apply {
                text = "00:00"
                textSize = 10f
                setTextColor(Color.parseColor("#999999"))
                tag = "mini_start_time"
            }, LinearLayout.LayoutParams(-2, -2))
            seekRow.addView(SeekBar(ctx).apply {
                max = duration
                progress = currentPos
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
                tag = "mini_seekbar"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser && player != null) {
                            player?.seekTo(p)
                            currentPos = p
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            seekRow.addView(TextView(ctx).apply {
                text = "00:00"
                textSize = 10f
                setTextColor(Color.parseColor("#999999"))
                tag = "mini_end_time"
            }, LinearLayout.LayoutParams(-2, -2))
            card.addView(seekRow, LinearLayout.LayoutParams(-1, (26 * d).toInt()))

            card.addView(View(ctx), LinearLayout.LayoutParams(-1, (6 * d).toInt()))

            val ctrlRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val iconSize = (sw * 0.06).toInt()
            val playSize = (sw * 0.08).toInt()

            val favBtn = iconBtn(ctx, "未收藏", iconSize, "\u2661").apply { tag = "fav_btn" }
            favBtn.setOnClickListener {
                currentSong?.let { s ->
                    s.isFavorite = !s.isFavorite
                    favBtn.text = if (s.isFavorite) "\u2665" else "\u2661"
                    savePlaylist()
                }
            }
            ctrlRow.addView(favBtn, w1())
            ctrlRow.addView(iconBtn(ctx, "上一曲", iconSize, "\u23EE"), w1())
            val playBtn = iconBtn(ctx, "播放", playSize, "\u25B6").apply { tag = "mini_play_btn" }
            playBtn.setOnClickListener { handlePlayButtonClick() }
            ctrlRow.addView(playBtn, w1())
            ctrlRow.addView(iconBtn(ctx, "下一曲", iconSize, "\u23ED"), w1())
            ctrlRow.addView(iconBtn(ctx, "列表", iconSize, "\u2630").apply {
                setOnClickListener { HpcMusicPanels.showHistoryDialog(null) }
            }, w1())
            card.addView(ctrlRow, LinearLayout.LayoutParams(-1, -2))

            card.setOnClickListener {
                if (currentSong == null) HpcMusicPanels.showSearch(null)
                else HpcMusicPanels.showDetails(null)
            }

            wrapper.addView(card, LinearLayout.LayoutParams(cw, ch))
            cachedCard = wrapper
            miniCard = wrapper

            ensurePlayer()
            currentSong?.let { updateMiniCardUI(it) }
            return wrapper
        } catch (e: Exception) {
            WeLogger.w(TAG, "音乐卡创建失败: ${e.message}")
            return null
        }
    }

    private fun iconBtn(ctx: Context, name: String, size: Int, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = (size / 3).toFloat()
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#333333"))
            layoutParams = ViewGroup.LayoutParams(size, size)
        }

    private fun w1() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun ensurePlayer() {
        if (player == null) {
            val p = MediaPlayer()
            p.setOnCompletionListener {
                isPlaying = false
                updatePlayButtonUI()
                stopProgressUpdate()
                playNext()
            }
            player = p
        }
    }

    fun handlePlayButtonClick() {
        if (currentSong == null) { toast("请先搜索添加歌曲"); return }
        ensurePlayer()
        if (currentIndex < 0 || currentIndex >= playlist.size) currentIndex = 0
        if (!isPlaying) {
            if (currentPos == 0) loadAndPlay(currentIndex) else togglePlay()
        } else togglePlay()
    }

    fun loadAndPlay(index: Int) {
        if (isPreparing) return
        if (index < 0 || index >= playlist.size) return
        currentIndex = index
        currentSong = playlist[index]
        updateMiniCardUI(currentSong!!)
        addHistory("${currentSong!!.title}|${currentSong!!.artist}")
        val url = currentSong!!.playUrl
        if (url.isEmpty()) { toast("播放链接为空"); return }
        try {
            val p = player ?: MediaPlayer().also { player = it }
            try { p.reset() } catch (_: Exception) {
                p.release()
                player = MediaPlayer()
            }
            player!!.setOnPreparedListener { mp ->
                isPreparing = false
                duration = mp.duration
                mp.start()
                isPlaying = true
                updatePlayButtonUI()
                startProgressUpdate()
                HpcMediaNotification.onPlaybackStarted()
            }
            player!!.setOnCompletionListener {
                isPlaying = false
                updatePlayButtonUI()
                stopProgressUpdate()
                playNext()
            }
            player!!.setOnErrorListener { _, _, _ ->
                isPreparing = false
                toast("播放出错")
                isPlaying = false
                updatePlayButtonUI()
                stopProgressUpdate()
                true
            }
            isPreparing = true
            player!!.setDataSource(url)
            player!!.prepareAsync()
        } catch (e: Exception) {
            isPreparing = false
            toast("播放失败")
            isPlaying = false
            updatePlayButtonUI()
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        currentSong ?: return
        try {
            if (isPlaying) {
                p.pause()
                isPlaying = false
                stopProgressUpdate()
            } else {
                p.start()
                isPlaying = true
                startProgressUpdate()
            }
            updatePlayButtonUI()
        } catch (_: Exception) {}
    }

    fun playPrev() {
        if (playlist.isEmpty()) return
        currentIndex = getPrevIndex(currentIndex, playlist.size)
        loadAndPlay(currentIndex)
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        currentIndex = getNextIndex(currentIndex, playlist.size)
        loadAndPlay(currentIndex)
    }

    private fun startProgressUpdate() {
        if (progressRunnable != null) return
        progressRunnable = object : Runnable {
            override fun run() {
                if (player != null && isPlaying) {
                    try { currentPos = player!!.currentPosition; updateProgressUI() } catch (_: Exception) {}
                }
                updateMiniLyricHint()
                mh.postDelayed(this, 1000)
            }
        }
        mh.post(progressRunnable!!)
    }

    private fun stopProgressUpdate() {
        progressRunnable?.let { mh.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun updateMiniCardUI(item: MusicItem) {
        mh.post {
            val c = miniCard ?: return@post
            (c.findViewWithTag<TextView>("mini_title"))?.text = item.title
            (c.findViewWithTag<TextView>("mini_artist"))?.text = item.artist
            (c.findViewWithTag<ImageView>("mini_cover"))?.let { loadCover(it, item.coverUrl) }
        }
    }

    private fun updatePlayButtonUI() {
        mh.post {
            val c = miniCard ?: return@post
            (c.findViewWithTag<TextView>("mini_play_btn"))?.text = if (isPlaying) "\u23F8" else "\u25B6"
        }
    }

    private fun updateProgressUI() {
        mh.post {
            val c = miniCard ?: return@post
            (c.findViewWithTag<SeekBar>("mini_seekbar"))?.let { it.max = duration; it.progress = currentPos }
            (c.findViewWithTag<TextView>("mini_start_time"))?.text = formatTime(currentPos)
            (c.findViewWithTag<TextView>("mini_end_time"))?.text = formatTime(duration)
        }
    }

    private fun updateMiniLyricHint() {
        val c = miniCard ?: return
        val hint = c.findViewWithTag<TextView>("mini_lyric_hint") ?: return
        val lyric = getCurrentLyricText(currentPos)
        val show = if (lyric.isNullOrEmpty()) "暂无歌词" else lyric
        mh.post { hint.text = show }
    }

    private fun refreshMiniFavIcon() {
        mh.post {
            val c = miniCard ?: return@post
            val s = currentSong ?: return@post
            (c.findViewWithTag<TextView>("fav_btn"))?.text = if (s.isFavorite) "\u2665" else "\u2661"
        }
    }

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
                    if (bmp != null) {
                        mh.post { iv.setImageBitmap(bmp) }
                        coverSharedCache[url] = bmp
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }

    private fun formatTime(millis: Int): String {
        val ts = millis / 1000
        return "%02d:%02d".format(ts / 60, ts % 60)
    }

    private fun parseLyric(lrc: String?): List<Pair<Int, String>> {
        val list = ArrayList<Pair<Int, String>>()
        if (lrc.isNullOrEmpty()) return list
        for (raw in lrc.split("\n")) {
            val line = raw.trim()
            if (line.length < 10) continue
            val end = line.indexOf("]")
            if (end < 4) continue
            val timeStr = line.substring(1, end)
            val text = line.substring(end + 1).trim()
            if (text.isEmpty()) continue
            val parts = timeStr.split(":")
            if (parts.size < 2) continue
            try {
                val min = parts[0].toInt()
                val secParts = parts[1].split(".")
                val sec = secParts[0].toInt()
                var ms = 0
                if (secParts.size > 1) {
                    var msStr = secParts[1]
                    if (msStr.length == 1) msStr += "00"
                    else if (msStr.length == 2) msStr += "0"
                    ms = msStr.toInt()
                }
                list.add(((min * 60 + sec) * 1000 + ms) to text)
            } catch (_: Exception) {}
        }
        return list
    }

    private fun getCurrentLyricText(pos: Int): String? {
        val s = currentSong ?: return null
        val lrc = parseLyric(s.lrctxt)
        if (lrc.isEmpty()) return null
        var result: String? = null
        for ((t, text) in lrc) {
            if (t <= pos) result = text else break
        }
        return result
    }

    private fun initPlayMode() {
        setPlayMode(WePrefs.getStringOrDef("music_play_mode", "0").toIntOrNull() ?: MODE_SEQUENCE)
    }

    fun setPlayMode(mode: Int) {
        currentMode = mode
        if (mode == MODE_RANDOM) generateShuffledOrder()
        WePrefs.putString("music_play_mode", mode.toString())
    }

    private fun getNextIndex(cur: Int, total: Int): Int {
        if (total <= 0) return 0
        return when (currentMode) {
            MODE_RANDOM -> {
                if (shuffledOrder.isEmpty()) generateShuffledOrder()
                shuffleIndex++
                if (shuffleIndex >= shuffledOrder.size) {
                    shuffleIndex = 0
                    generateShuffledOrder()
                }
                shuffledOrder[shuffleIndex]
            }
            else -> {
                var n = cur + 1
                if (n >= total) n = 0
                n
            }
        }
    }

    private fun getPrevIndex(cur: Int, total: Int): Int {
        if (total <= 0) return 0
        if (currentMode == MODE_RANDOM) {
            shuffleIndex--
            if (shuffleIndex < 0) {
                generateShuffledOrder()
                shuffleIndex = shuffledOrder.size - 1
            }
            return shuffledOrder[shuffleIndex]
        }
        var p = cur - 1
        if (p < 0) p = if (currentMode == MODE_LOOP) total - 1 else 0
        return p
    }

    private fun generateShuffledOrder() {
        shuffledOrder.clear()
        val total = playlist.size
        if (total == 0) return
        for (i in 0 until total) shuffledOrder.add(i)
        for (i in total - 1 downTo 1) {
            val j = (Math.random() * (i + 1)).toInt()
            val t = shuffledOrder[i]
            shuffledOrder[i] = shuffledOrder[j]
            shuffledOrder[j] = t
        }
        shuffleIndex = 0
    }

    private fun savePlaylist() {
        try {
            val arr = JSONArray()
            for (m in playlist) {
                arr.put(JSONObject().apply {
                    put("title", m.title)
                    put("artist", m.artist)
                    put("coverUrl", m.coverUrl)
                    put("playUrl", m.playUrl)
                    put("lrctxt", m.lrctxt)
                    put("mid", m.mid)
                    put("platform", m.platform)
                    put("fav", m.isFavorite)
                })
            }
            WePrefs.putString("music_playlist", arr.toString())
        } catch (_: Exception) {}
    }

    private fun loadSavedPlaylist() {
        try {
            val s = WePrefs.getStringOrDef("music_playlist", "")
            if (s.isEmpty()) return
            val arr = JSONArray(s)
            playlist.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                playlist.add(MusicItem(
                    o.optString("title"), o.optString("artist"), o.optString("coverUrl"),
                    o.optString("playUrl"), o.optString("lrctxt"), o.optString("mid"),
                    o.optString("platform", "")
                ).apply { isFavorite = o.optBoolean("fav", false) })
            }
            if (playlist.isNotEmpty() && currentSong == null) {
                currentIndex = 0
                currentSong = playlist[0]
            }
        } catch (_: Exception) {}
    }

    fun addToPlaylistAndPlay(item: MusicItem) {
        playlist.add(0, item)
        savePlaylist()
        loadAndPlay(0)
    }

    private fun addHistory(key: String) {
        if (key.isEmpty()) return
        try {
            val s = WePrefs.getStringOrDef("music_history", "[]")
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                if (arr.getString(i) == key) return
            }
            val newArr = JSONArray()
            newArr.put(key)
            for (i in 0 until arr.length()) {
                if (newArr.length() >= 100) break
                newArr.put(arr.getString(i))
            }
            WePrefs.putString("music_history", newArr.toString())
        } catch (_: Exception) {}
    }

    fun getPlaylist(): List<MusicItem> = playlist

    fun search(keyword: String, platform: String, cb: (String) -> Unit) {
        if (keyword.trim().isEmpty()) { cb("[]"); return }
        Thread {
            var resultJson = "[]"
            try {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                if (platform == "qq") {
                    val resp = httpGet("$URL_QQ/search?keyword=$encoded&type=song&num=20")
                    if (resp.isNotEmpty()) {
                        val root = JSONObject(resp)
                        if (root.optInt("code") == 0) {
                            val list = root.optJSONObject("data")?.optJSONArray("list")
                            if (list != null) {
                                val out = JSONArray()
                                for (i in 0 until list.length()) {
                                    val song = list.getJSONObject(i)
                                    val singerArr = song.optJSONArray("singer")
                                    val singer = if (singerArr != null && singerArr.length() > 0)
                                        singerArr.getJSONObject(0).optString("name") else ""
                                    val album = song.optJSONObject("album")?.optString("name") ?: ""
                                    out.put(JSONObject().apply {
                                        put("n", i)
                                        put("name", song.optString("title"))
                                        put("singer", singer)
                                        put("album", album)
                                        put("platform", "qq")
                                        put("mid", song.optString("mid"))
                                    })
                                }
                                resultJson = out.toString()
                            }
                        }
                    }
                } else {
                    val resp = httpGet("$AURORA_WY?msg=$encoded")
                    if (resp.isNotEmpty()) {
                        val root = JSONObject(resp)
                        if (root.optInt("code") == 200) {
                            val list = root.optJSONObject("data")?.optJSONArray("list")
                            if (list != null) {
                                val out = JSONArray()
                                for (i in 0 until list.length()) {
                                    val song = list.getJSONObject(i)
                                    val display = song.optString("name")
                                    val dash = display.lastIndexOf("-")
                                    val name = if (dash > 0) display.substring(0, dash) else display
                                    val singer = if (dash > 0) display.substring(dash + 1) else ""
                                    out.put(JSONObject().apply {
                                        put("n", i + 1)
                                        put("name", name)
                                        put("singer", singer)
                                        put("platform", "wy")
                                        put("mid", song.optString("index", (i + 1).toString()))
                                    })
                                }
                                resultJson = out.toString()
                            }
                        }
                    }
                }
            } catch (e: Exception) { WeLogger.w(TAG, "搜索失败: ${e.message}") }
            val fj = resultJson
            mh.post { cb(fj) }
        }.start()
    }

    fun getTrackDetail(keyword: String, mid: String, platform: String, cb: (MusicItem?) -> Unit) {
        Thread {
            var item: MusicItem? = null
            try {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                if (platform == "qq") {
                    val urlResp = httpGet("$URL_QQ/song/url?mid=$mid&quality=320")
                    var musicUrl = ""
                    if (urlResp.isNotEmpty()) {
                        val root = JSONObject(urlResp)
                        if (root.optInt("code") == 0) {
                            musicUrl = root.optJSONObject("data")?.optString(mid, "") ?: ""
                        }
                    }
                    var lrc = ""
                    val lrcResp = httpGet("$URL_QQ/lyric?mid=$mid")
                    if (lrcResp.isNotEmpty()) {
                        val lrcRoot = JSONObject(lrcResp)
                        if (lrcRoot.optInt("code") == 0) {
                            lrc = lrcRoot.optJSONObject("data")?.optString("lyric", "") ?: ""
                        }
                    }
                    var name = ""
                    var singer = ""
                    var coverUrl = ""
                    val detailResp = httpGet("$URL_QQ/song/detail?mid=$mid")
                    if (detailResp.isNotEmpty()) {
                        val detailRoot = JSONObject(detailResp)
                        if (detailRoot.optInt("code") == 0) {
                            val detail = detailRoot.optJSONObject("data")
                            if (detail != null) {
                                name = detail.optString("title")
                                val singerArr = detail.optJSONArray("singer")
                                if (singerArr != null && singerArr.length() > 0) {
                                    singer = singerArr.getJSONObject(0).optString("name")
                                }
                            }
                        }
                    }
                    val coverResp = httpGet("$URL_QQ/song/cover?mid=$mid&size=300")
                    if (coverResp.isNotEmpty()) {
                        try {
                            val coverRoot = JSONObject(coverResp)
                            if (coverRoot.optInt("code") == 0) {
                                coverUrl = coverRoot.optJSONObject("data")?.optString("url", "") ?: ""
                            }
                        } catch (_: Exception) {}
                    }
                    item = MusicItem(name, singer, coverUrl, musicUrl, lrc, mid, "qq")
                } else {
                    val resp = httpGet("$AURORA_WY?msg=$encoded&b=$mid")
                    if (resp.isNotEmpty()) {
                        val root = JSONObject(resp)
                        if (root.optInt("code") == 200) {
                            val data = root.optJSONObject("data")
                            if (data != null) {
                                val musicUrl = data.optString("music", "")
                                val id = data.optInt("id", 0)
                                var lrc = ""
                                if (id > 0) {
                                    val lrcResp = httpGet("$AURORA_LRC?id=$id")
                                    if (lrcResp.isNotEmpty()) {
                                        val lrcRoot = JSONObject(lrcResp)
                                        if (lrcRoot.optInt("code") == 200) {
                                            lrc = lrcRoot.optJSONObject("data")?.optJSONObject("lrc")?.optString("lyric", "") ?: ""
                                        }
                                    }
                                }
                                item = MusicItem(
                                    data.optString("song", ""),
                                    data.optString("singer", ""),
                                    data.optString("picture", ""),
                                    musicUrl, lrc, id.toString(), "wy"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) { WeLogger.w(TAG, "获取详情失败: ${e.message}") }
            val fi = item
            mh.post { cb(fi) }
        }.start()
    }

    private fun httpGet(urlStr: String): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        return if (c.responseCode == 200) c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else ""
    }

    fun bindActivity(act: Context) { _act = act }

    fun toast(msg: String) {
        _act?.let { a ->
            mh.post { android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    fun getCurrentSong(): MusicItem? = currentSong
    fun getCurrentPos(): Int = currentPos
    fun getDuration(): Int = duration
    fun isPlaying(): Boolean = isPlaying
    fun seekTo(pos: Int) { currentPos = pos; player?.seekTo(pos) }
    fun saveFav() { savePlaylist() }
    fun refreshMiniFav() { refreshMiniFavIcon() }

    fun addToPlaylist(item: MusicItem) {
        playlist.add(item)
        currentSong = item
        currentIndex = playlist.size - 1
        savePlaylist()
        updateMiniCardUI(item)
    }

    fun removeItem(index: Int) {
        if (index in 0 until playlist.size) {
            playlist.removeAt(index)
            savePlaylist()
        }
    }

    fun getLyricAt(pos: Int): String? = getCurrentLyricText(pos)

    fun getLyricLine(pos: Int): Int {
        val lrc = parseLyric(currentSong?.lrctxt ?: return -1)
        for ((i, p) in lrc.withIndex()) {
            if (p.first <= pos) return i
        }
        return -1
    }

    fun getFavList(): List<MusicItem> = playlist.filter { it.isFavorite }

    fun getHistoryItems(): List<MusicItem> {
        try {
            val s = WePrefs.getStringOrDef("music_history", "[]")
            val arr = JSONArray(s)
            val items = ArrayList<MusicItem>()
            for (i in 0 until arr.length()) {
                val key = arr.getString(i)
                val parts = key.split("|")
                val title = if (parts.size >= 2) parts[0] else key
                val artist = if (parts.size >= 2) parts[1] else ""
                val found = playlist.firstOrNull { it.title == title && (artist.isEmpty() || it.artist == artist) }
                if (found != null) items.add(found)
            }
            return items
        } catch (_: Exception) { return emptyList() }
    }

    fun indexOf(item: MusicItem): Int {
        return playlist.indexOfFirst { it.title == item.title && it.playUrl == item.playUrl }
    }

    fun setCurrentAndPlay(index: Int) {
        if (index in 0 until playlist.size) loadAndPlay(index)
    }

    fun toggle() {
        if (currentSong == null) return
        togglePlay()
    }

    fun clearAllCache() {
        playlist.clear()
        currentIndex = -1
        currentSong = null
        player?.apply { stop() }
        player?.apply { release() }
        player = null
        isPlaying = false
        stopProgressUpdate()
        updatePlayButtonUI()
        WePrefs.putString("music_playlist", "[]")
        WePrefs.putString("music_history", "[]")
        val emptyItem = MusicItem("暂无歌曲", "搜索添加歌曲", "", "", "", "", "")
        updateMiniCardUI(emptyItem)
    }
}