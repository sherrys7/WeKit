package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.FileOutputStream
import java.nio.file.Path
import kotlin.io.path.div

/**
 * 将群聊分析报告（Markdown 文本）渲染为长图并保存。
 * 布局：#F4F9F5 底色上绘制圆角 50、2px #D5E7DF 边框的白底卡片，
 * 卡片内为标题 + 绿色副标题（群名 · 统计周期） + #C1D7CF 横线 + 正文；
 * 生成时间以灰色小字显示在卡片外、图片底部。高度完全由内容决定，无固定留白。
 * [title]/[subtitle]/[footer] 均为已按当前 Locale 排好的字符串，本对象不做文案拼接。
 */
object GroupReportImage {

    private const val IMAGE_WIDTH_PX = 1080
    private const val OUTER_MARGIN_PX = 24          // 图片边缘到圆角卡片的外边距
    private const val CARD_PADDING_H_PX = 40        // 卡片内左右内边距
    private const val CARD_PADDING_TOP_PX = 44      // 卡片内顶部内边距
    private const val CARD_PADDING_BOTTOM_PX = 40   // 卡片内底部内边距
    private const val CARD_CORNER_RADIUS_PX = 50f   // 卡片圆角半径 50
    private const val CARD_BORDER_WIDTH_PX = 2f     // 卡片边框 2px
    private const val BLOCK_SPACING_PX = 18
    private const val LIST_INDENT_PX = 56
    private const val QUOTE_INDENT_PX = 24
    private const val LINE_HEIGHT_RATIO = 1.4f
    private const val HEADER_TITLE_SIZE_PX = 56f
    private const val HEADER_SUBTITLE_SIZE_PX = 32f
    private const val FOOTER_SIZE_PX = 26f
    private const val HEADER_GAP_PX = 12            // 标题与副标题间距
    private const val DIVIDER_GAP_PX = 20           // 横线与副标题/正文的间距
    private const val DIVIDER_HEIGHT_PX = 2f        // 横线高度
    private const val FOOTER_GAP_PX = 24            // 卡片底部到生成时间的间距
    private val PAGE_BACKGROUND_COLOR = Color.parseColor("#F4F9F5")
    private val CARD_BACKGROUND_COLOR = Color.WHITE
    private val CARD_BORDER_COLOR = Color.parseColor("#D5E7DF")
    private val TEXT_COLOR = Color.parseColor("#2A2A2A")
    private val MUTED_COLOR = Color.parseColor("#8A8A8A")
    private val ACCENT_COLOR = Color.parseColor("#07C160")
    private val CODE_BG_COLOR = Color.parseColor("#F2F3F5")
    private val BULLET_COLOR = Color.parseColor("#3C3C3C")
    private val DIVIDER_COLOR = Color.parseColor("#C1D7CF")

    /** 生成长图并写入模块缓存目录，返回文件路径 */
    fun renderToFile(title: String, subtitle: String, footer: String, markdown: String): Path {
        val bitmap = renderMarkdown(title, subtitle, footer, markdown)
        val file = (KnownPaths.moduleCache / "group_report_${System.currentTimeMillis()}.png")
        file.parent?.createDirsSafe()
        FileOutputStream(file.toFile()).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    /** 渲染长图并保存到系统相册，返回相册 Uri（失败返回 null） */
    fun saveToGallery(context: Context, title: String, subtitle: String, footer: String, markdown: String): Uri? {
        val bitmap = renderMarkdown(title, subtitle, footer, markdown)
        return try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "群聊分析报告_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WeKit")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(collection, contentValues) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderMarkdown(title: String, subtitle: String, footer: String, markdown: String): Bitmap {
        val blocks = parseBlocks(markdown)

        // 卡片内可用宽度：图片宽度 - 两侧外边距 - 卡片内左右内边距
        val contentWidth = IMAGE_WIDTH_PX - OUTER_MARGIN_PX * 2 - CARD_PADDING_H_PX * 2

        val titlePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            textSize = HEADER_TITLE_SIZE_PX
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_COLOR
            textSize = HEADER_SUBTITLE_SIZE_PX
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val footerPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED_COLOR
            textSize = FOOTER_SIZE_PX
        }

        // 第一遍测量总高度（与绘制使用一致的 paint 与 includePad=false，避免顶部/底部空白）
        val titleLayout = buildLayoutText(title, titlePaint, contentWidth)
        val subtitleLayout = buildLayoutText(subtitle, subtitlePaint, contentWidth)
        val footerLayout = buildLayoutText(footer, footerPaint, contentWidth)
        val headerHeight = titleLayout.height + HEADER_GAP_PX + subtitleLayout.height

        val measuredHeights = ArrayList<Int>(blocks.size)
        var bodyHeight = 0
        for (block in blocks) {
            if (block is Block.Blank) {
                measuredHeights.add(0)
                continue
            }
            val layout = buildLayout(block, buildPaintFor(block), contentWidth)
            val h = layout.height + (if (block is Block.Code) 16 else 0) + BLOCK_SPACING_PX
            measuredHeights.add(h)
            bodyHeight += h
        }

        val cardHeight = CARD_PADDING_TOP_PX + headerHeight +
            DIVIDER_GAP_PX + DIVIDER_HEIGHT_PX.toInt() + DIVIDER_GAP_PX + bodyHeight +
            CARD_PADDING_BOTTOM_PX

        // 页脚位于圆角框外、图片底部
        val totalHeight = OUTER_MARGIN_PX + cardHeight + FOOTER_GAP_PX + footerLayout.height + OUTER_MARGIN_PX

        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH_PX, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAGE_BACKGROUND_COLOR)

        val cardLeft = OUTER_MARGIN_PX.toFloat()
        val cardTop = OUTER_MARGIN_PX.toFloat()
        val cardRight = (IMAGE_WIDTH_PX - OUTER_MARGIN_PX).toFloat()
        val cardBottom = cardTop + cardHeight

        // 圆角框：白底 + 2px 边框（#D5E7DF）
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD_BACKGROUND_COLOR }
        canvas.drawRoundRect(cardLeft, cardTop, cardRight, cardBottom, CARD_CORNER_RADIUS_PX, CARD_CORNER_RADIUS_PX, cardPaint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CARD_BORDER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = CARD_BORDER_WIDTH_PX
        }
        canvas.drawRoundRect(cardLeft, cardTop, cardRight, cardBottom, CARD_CORNER_RADIUS_PX, CARD_CORNER_RADIUS_PX, borderPaint)

        // 页眉：标题 + 副标题
        var y = cardTop + CARD_PADDING_TOP_PX
        canvas.save()
        canvas.translate(cardLeft + CARD_PADDING_H_PX, y)
        titleLayout.draw(canvas)
        canvas.translate(0f, titleLayout.height + HEADER_GAP_PX.toFloat())
        subtitleLayout.draw(canvas)
        canvas.restore()
        y += headerHeight + DIVIDER_GAP_PX

        // 横线（群名称与总结之间，#C1D7CF）
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DIVIDER_COLOR }
        canvas.drawRect(
            cardLeft + CARD_PADDING_H_PX, y.toFloat(),
            cardRight - CARD_PADDING_H_PX, y + DIVIDER_HEIGHT_PX,
            dividerPaint,
        )
        y += DIVIDER_HEIGHT_PX.toInt() + DIVIDER_GAP_PX

        // 正文块
        for ((index, block) in blocks.withIndex()) {
            if (block is Block.Blank) continue
            val blockPaint = buildPaintFor(block)
            val layout = buildLayout(block, blockPaint, contentWidth)
            canvas.save()
            canvas.translate(cardLeft + CARD_PADDING_H_PX, y.toFloat())
            if (block is Block.Bullet || block is Block.Ordered) {
                // 绘制项目符号
                val bulletPaint = Paint(blockPaint).apply {
                    color = if (block is Block.Ordered) TEXT_COLOR else BULLET_COLOR
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                canvas.drawText(block.marker, 0f, blockPaint.fontMetrics.ascent * -1 + blockPaint.textSize / 2, bulletPaint)
                canvas.translate(LIST_INDENT_PX.toFloat(), 0f)
            }
            if (block is Block.Quote) {
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_COLOR }
                canvas.drawRect(0f, 0f, 6f, layout.height.toFloat(), barPaint)
                canvas.translate(QUOTE_INDENT_PX.toFloat(), 0f)
            }
            if (block is Block.Code) {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CODE_BG_COLOR }
                canvas.drawRect(
                    -CARD_PADDING_H_PX / 2f,
                    -8f,
                    contentWidth + CARD_PADDING_H_PX / 2f,
                    layout.height + 8f,
                    bgPaint,
                )
            }
            layout.draw(canvas)
            canvas.restore()
            y += measuredHeights[index]
        }

        // 页脚（圆角框外，紧贴卡片底部留白后绘制）
        canvas.drawText(
            footer,
            cardLeft + CARD_PADDING_H_PX,
            cardBottom + FOOTER_GAP_PX + (-footerPaint.fontMetrics.ascent),
            footerPaint,
        )
        return bitmap
    }

    private fun buildLayoutText(text: String, paint: android.text.TextPaint, contentWidth: Int): android.text.StaticLayout {
        return android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidth)
            .setIncludePad(false)
            .setLineSpacing(0f, LINE_HEIGHT_RATIO)
            .build()
    }

    private fun layoutWidthFor(block: Block, contentWidth: Int): Int = when (block) {
        is Block.Bullet, is Block.Ordered -> contentWidth - LIST_INDENT_PX
        is Block.Quote -> contentWidth - QUOTE_INDENT_PX
        else -> contentWidth
    }

    private fun buildLayout(block: Block, paint: android.text.TextPaint, contentWidth: Int): android.text.StaticLayout {
        val text = when (block) {
            is Block.Heading -> block.text
            is Block.Bullet -> block.content
            is Block.Ordered -> block.content
            is Block.Quote -> block.content
            is Block.Code -> block.code
            is Block.Paragraph -> block.text
            is Block.Blank -> ""
        }
        return android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, layoutWidthFor(block, contentWidth))
            .setIncludePad(false)
            .setLineSpacing(0f, LINE_HEIGHT_RATIO)
            .build()
    }

    private fun buildPaintFor(block: Block): android.text.TextPaint {
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        when (block) {
            is Block.Heading -> {
                paint.textSize = when (block.level) {
                    1 -> 52f
                    2 -> 44f
                    else -> 38f
                }
                paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            is Block.Quote -> {
                paint.color = MUTED_COLOR
            }
            is Block.Code -> {
                paint.color = TEXT_COLOR
                paint.typeface = Typeface.MONOSPACE
                paint.textSize = 30f
            }
            else -> {}
        }
        return paint
    }

    // ==================== Markdown 块解析 ====================

    private sealed interface Block {
        val marker: String
        data class Heading(val level: Int, val text: String) : Block { override val marker: String get() = "" }
        data class Paragraph(val text: String) : Block { override val marker: String get() = "" }
        data class Bullet(val content: String) : Block { override val marker: String get() = "•" }
        data class Ordered(val number: String, val content: String) : Block { override val marker: String get() = "$number. " }
        data class Quote(val content: String) : Block { override val marker: String get() = "" }
        data class Code(val code: String) : Block { override val marker: String get() = "" }
        data object Blank : Block { override val marker: String get() = "" }
    }

    private fun parseBlocks(markdown: String): List<Block> {
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val result = ArrayList<Block>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> {
                    result.add(Block.Blank)
                    i++
                }
                trimmed.startsWith("```") -> {
                    val codeLines = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        codeLines.add(lines[i])
                        i++
                    }
                    i++ // 跳过闭合围栏
                    result.add(Block.Code(codeLines.joinToString("\n")))
                }
                trimmed.startsWith(">") -> {
                    result.add(Block.Quote(trimmed.removePrefix(">").trimStart()))
                    i++
                }
                trimmed.startsWith("#") -> {
                    val match = Regex("""^(#{1,6})\s+(.*)$""").matchEntire(trimmed)
                    if (match != null) {
                        result.add(Block.Heading(match.groupValues[1].length, match.groupValues[2].trim()))
                    } else {
                        result.add(Block.Paragraph(trimmed))
                    }
                    i++
                }
                trimmed.matches(Regex("""^[-*+]\s+.+""")) -> {
                    result.add(Block.Bullet(trimmed.replaceFirst(Regex("""^[-*+]\s+"""), "")))
                    i++
                }
                trimmed.matches(Regex("""^\d+[.)]\s+.+""")) -> {
                    val match = Regex("""^(\d+)[.)]\s+(.*)$""").matchEntire(trimmed)!!
                    result.add(Block.Ordered(match.groupValues[1], match.groupValues[2]))
                    i++
                }
                else -> {
                    // 普通段落：合并直到空行
                    val para = StringBuilder(trimmed)
                    i++
                    while (i < lines.size && lines[i].trim().isNotEmpty()) {
                        para.append('\n').append(lines[i].trim())
                        i++
                    }
                    result.add(Block.Paragraph(para.toString()))
                }
            }
        }
        return result
    }
}
