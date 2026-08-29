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
 * 将群聊分析报告（Markdown 文本）渲染为白底长图并保存。
 */
object GroupReportImage {

    private const val IMAGE_WIDTH_PX = 1080
    private const val HORIZONTAL_PADDING_PX = 56
    private const val VERTICAL_PADDING_PX = 64
    private const val BLOCK_SPACING_PX = 18
    private const val LIST_INDENT_PX = 56
    private const val QUOTE_INDENT_PX = 24
    private const val LINE_HEIGHT_RATIO = 1.4f
    private const val BACKGROUND_COLOR = Color.WHITE
    private val TEXT_COLOR = Color.parseColor("#2A2A2A")
    private val MUTED_COLOR = Color.parseColor("#8A8A8A")
    private val ACCENT_COLOR = Color.parseColor("#07C160")
    private val CODE_BG_COLOR = Color.parseColor("#F2F3F5")
    private val BULLET_COLOR = Color.parseColor("#3C3C3C")

    /** 生成长图并写入模块缓存目录，返回文件路径 */
    fun renderToFile(markdown: String): Path {
        val bitmap = renderMarkdown(markdown)
        val file = (KnownPaths.moduleCache / "group_report_${System.currentTimeMillis()}.png")
        file.parent?.createDirsSafe()
        FileOutputStream(file.toFile()).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    /** 渲染长图并保存到系统相册，返回相册 Uri（失败返回 null） */
    fun saveToGallery(context: Context, markdown: String): Uri? {
        val bitmap = renderMarkdown(markdown)
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

    private fun renderMarkdown(markdown: String): Bitmap {
        val blocks = parseBlocks(markdown)

        // 第一遍测量总高度（用与绘制一致的块专用 paint）
        val contentWidth = IMAGE_WIDTH_PX - HORIZONTAL_PADDING_PX * 2
        var totalHeight = VERTICAL_PADDING_PX * 2
        val measuredHeights = ArrayList<Int>(blocks.size)
        for (block in blocks) {
            val layout = buildLayout(block, buildPaintFor(block), contentWidth)
            measuredHeights.add(layout.height)
            totalHeight += layout.height
            totalHeight += if (block is Block.Code) 16 else 0
            if (block !is Block.Blank) totalHeight += BLOCK_SPACING_PX
        }

        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH_PX, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND_COLOR)

        // 第二遍绘制
        var y = VERTICAL_PADDING_PX
        for ((index, block) in blocks.withIndex()) {
            if (block is Block.Blank) continue
            val blockPaint = buildPaintFor(block)
            val layout = buildLayout(block, blockPaint, contentWidth)
            canvas.save()
            canvas.translate(HORIZONTAL_PADDING_PX.toFloat(), y.toFloat())
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
                    -HORIZONTAL_PADDING_PX / 2f,
                    -8f,
                    contentWidth + HORIZONTAL_PADDING_PX / 2f,
                    layout.height + 8f,
                    bgPaint
                )
            }
            layout.draw(canvas)
            canvas.restore()
            y += measuredHeights[index]
            y += if (block is Block.Code) 16 else 0
            if (block !is Block.Blank) y += BLOCK_SPACING_PX
        }
        return bitmap
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
