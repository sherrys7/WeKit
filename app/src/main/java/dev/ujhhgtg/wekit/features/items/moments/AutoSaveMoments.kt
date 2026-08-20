package dev.ujhhgtg.wekit.features.items.moments

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.DocumentsContract
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi.MomentContent
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi.ResolvedVideo
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import kotlinx.coroutines.runBlocking
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@Feature(
    id = "自动保存",
    nameRes = "feature_auto_save_moments_name",
    categoryIds = [FeatureCategoryIds.MOMENTS],
    descriptionRes = "feature_auto_save_moments_description",
)
object AutoSaveMoments : AutoMomentsBase(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    AutoRefresh.IRefreshListener {

    override val TAG = "AutoSaveMoments"

    private const val RETRY_INTERVAL_MS = 30_000L
    private const val MAX_SAVED_RECORDS = 2000

    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()

    private var savedSnsIds by WePrefs.prefOption(
        "moments_auto_save_saved_ids",
        emptySet()
    )

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        AutoRefresh.addListener(this)
        installTimelineHooks()
        if (AutoSaveMomentsSettings.hasAllLoadedTargets()) scanCachedTargetMoments()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        AutoRefresh.removeListener(this)
    }

    override fun onRefresh() {
        if (AutoSaveMomentsSettings.hasAllLoadedTargets()) scanCachedTargetMoments()
    }

    override fun onClick(context: ComponentActivity) {
        AutoSaveMomentsSettings.showMainDialog(context) {
            handledSnsIds.clear()
            lastAttemptAt.clear()
            if (AutoSaveMomentsSettings.hasAllLoadedTargets()) scanCachedTargetMoments()
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        processSnsInfoValues(table, values)
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        processSnsInfoValues(table, values)
    }

    override fun processVisibleItems(list: ViewGroup) {
        for (index in 0 until list.childCount) {
            runCatching {
                locateSnsInfo(list.getChildAt(index))?.let { processSnsInfoAsync(it, "visible") }
            }.onFailure {
                WeLogger.w(TAG, "failed to process visible Moments item", it)
            }
        }
    }

    // ==================== 触发入口 ====================

    private fun processSnsInfoValues(table: String, values: ContentValues) {
        if (table != "SnsInfo") return
        val owner = values.getAsString("userName")?.trim().orEmpty()
        if (!AutoSaveMomentsSettings.isWhitelisted(owner)) return
        if (AutoSaveMomentsSettings.modeFor(owner) != MomentAutomationMode.ALL_LOADED) return

        val sourceType = values.getAsInteger("sourceType") ?: 0
        if (sourceType != 0) return

        val snsId = values.getAsLong("snsId") ?: return
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        processSnsInfoAsync(snsInfo, "database")
    }

    private fun scanCachedTargetMoments() {
        if (!AutoSaveMomentsSettings.hasAllLoadedTargets()) return
        thread(name = "ScanMomentsToAutoSaveThread") {
            val snsIds = runCatching { queryCachedTargetSnsIds() }
                .onFailure { WeLogger.w(TAG, "failed to query cached target moments", it) }
                .getOrDefault(emptyList())

            WeLogger.d(TAG, "scanCachedTargetMoments: found ${snsIds.size} cached moments")
            for (snsId in snsIds) {
                val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: continue
                runCatching { processSnsInfo(snsInfo, "cached") }
                    .onFailure { WeLogger.w(TAG, "auto-save processing failed", it) }
            }
        }
    }

    private fun queryCachedTargetSnsIds(): List<Long> {
        if (!WeDatabaseApi.isReady) return emptyList()
        val sql = """
            SELECT snsId, userName
            FROM SnsInfo
            WHERE snsId != 0
              AND sourceType = 0
            ORDER BY createTime DESC
        """.trimIndent()

        val result = mutableListOf<Long>()
        WeDatabaseApi.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val snsId = cursor.getLong(0)
                val owner = cursor.getString(1).orEmpty()
                if (!AutoSaveMomentsSettings.isWhitelisted(owner)) continue
                if (AutoSaveMomentsSettings.modeFor(owner) != MomentAutomationMode.ALL_LOADED) continue
                result += snsId
            }
        }
        return result
    }

    private fun processSnsInfo(snsInfo: Any, source: String) {
        val owner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
        if (owner.isBlank() || owner == WeApi.selfWxId) return
        if (!AutoSaveMomentsSettings.isWhitelisted(owner)) return
        if (source != "visible" && AutoSaveMomentsSettings.modeFor(owner) != MomentAutomationMode.ALL_LOADED) return
        if (WeMomentsApi.isDeleted(snsInfo)) return

        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: return
        if (snsTableId in handledSnsIds) return
        if (isAlreadySaved(snsTableId)) {
            handledSnsIds.add(snsTableId)
            return
        }
        if (!canAttempt(snsTableId)) return

        val result = saveMoment(snsInfo, owner)
        handledSnsIds.add(snsTableId)
        if (result) {
            markSaved(snsTableId)
            WeLogger.i(TAG, "auto-save $source done, owner=$owner, sns=$snsTableId")
        } else {
            WeLogger.w(TAG, "auto-save $source failed, owner=$owner, sns=$snsTableId")
        }
    }

    private fun processSnsInfoAsync(snsInfo: Any, source: String) {
        submitItemWork {
            runCatching { processSnsInfo(snsInfo, source) }
                .onFailure { WeLogger.w(TAG, "auto-save processing failed", it) }
        }
    }

    private fun canAttempt(snsTableId: String): Boolean = synchronized(lastAttemptAt) {
        val now = System.currentTimeMillis()
        val last = lastAttemptAt[snsTableId] ?: 0L
        if (now - last < RETRY_INTERVAL_MS) return@synchronized false
        lastAttemptAt[snsTableId] = now
        true
    }

    // ==================== 保存逻辑 ====================

    private fun saveMoment(snsInfo: Any, owner: String): Boolean {
        val content = WeMomentsApi.getMomentContent(snsInfo) ?: return false
        val target = buildSaveTarget() ?: return false
        val relativeDir = momentRelativeDir(owner, content)
        val types = AutoSaveMomentsSettings.saveTypes()

        val type = content.type
        val isLiveContent = content.hasLivePhoto
        val isVideoContent = !isLiveContent &&
            (type == MomentsContentType.VIDEO.typeId || type == MomentsContentType.LITTLE_VIDEO.typeId)
        val isImageContent = !isLiveContent && !isVideoContent && type == MomentsContentType.IMG.typeId
        val hasMedia = isLiveContent || isVideoContent || isImageContent
        val isLive = isLiveContent && types.livePhotos
        val isVideo = isVideoContent && types.videos
        val isImage = isImageContent && types.images
        val isText = !hasMedia && type == MomentsContentType.TEXT.typeId && types.text && content.contentText.isNotBlank()

        var saved = when {
            isLive -> saveLivePhoto(target, relativeDir, content)
            isVideo -> saveVideo(target, relativeDir, content)
            isImage -> saveImages(target, relativeDir, content)
            isText -> saveText(target, relativeDir, content.contentText)
            else -> false
        }
        if (hasMedia && types.text && content.contentText.isNotBlank()) {
            saved = saveText(target, relativeDir, content.contentText) || saved
        }
        return saved
    }

    private fun saveLivePhoto(target: SaveTarget, relativeDir: String, content: MomentContent): Boolean {
        val images = runBlocking {
            WeMomentsApi.ensureImagePathsCached(content.mediaList, content.nativeMediaList)
        } ?: return false

        val videosReady = runBlocking { WeMomentsApi.ensureLivePhotoVideosCached(content) }
        val resolved = WeMomentsApi.resolveMediaItems(content)
        if (resolved == null) return false

        var ok = false
        for (index in images.indices) {
            val media = resolved.items.getOrNull(index) ?: continue
            if (target.saveFromPath(relativeDir, "image_${index + 1}${extensionOf(media.imagePath)}", media.imagePath)) {
                ok = true
            }
            val videoPath = media.videoPath
            if (videosReady && !videoPath.isNullOrBlank()) {
                if (target.saveFromPath(relativeDir, "image_${index + 1}${extensionOf(videoPath)}", videoPath)) {
                    ok = true
                }
            }
        }
        return ok
    }

    private fun saveVideo(target: SaveTarget, relativeDir: String, content: MomentContent): Boolean {
        val resolved: ResolvedVideo = runBlocking {
            WeMomentsApi.ensureVideoPaths(HostInfo.application, content)
        } ?: return false
        return target.saveFromPath(relativeDir, "video${extensionOf(resolved.videoPath)}", resolved.videoPath)
    }

    private fun saveImages(target: SaveTarget, relativeDir: String, content: MomentContent): Boolean {
        val images = runBlocking {
            WeMomentsApi.ensureImagePathsCached(content.mediaList, content.nativeMediaList)
        } ?: return false
        var ok = false
        for (index in images.indices) {
            if (target.saveFromPath(relativeDir, "image_${index + 1}${extensionOf(images[index])}", images[index])) {
                ok = true
            }
        }
        return ok
    }

    private fun saveText(target: SaveTarget, relativeDir: String, text: String): Boolean {
        return target.saveText(relativeDir, "text.txt", text)
    }

    private fun momentRelativeDir(owner: String, content: MomentContent): String {
        val displayName = displayNameByWxId[owner]?.takeIf { it.isNotBlank() } ?: owner
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(content.createTime * 1000L))
        val snsTableId = content.snsTableId ?: "unknown"
        return "${sanitizeFileName(displayName)}/$date/$snsTableId"
    }

    private val displayNameByWxId: Map<String, String> by lazy {
        runCatching {
            WeDatabaseApi.getFriends().associate { it.wxId to it.displayName }
        }.getOrDefault(emptyMap())
    }

    private fun sanitizeFileName(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_")

    private fun extensionOf(path: String): String {
        val lower = path.substringAfterLast('.', "").lowercase()
        return if (lower in SAVE_EXTENSIONS) ".$lower" else ""
    }

    private fun buildSaveTarget(): SaveTarget? {
        val treeUri = AutoSaveMomentsSettings.treeUri()
        if (treeUri.isNotBlank()) {
            val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
            return SafSaveTarget(uri, HostInfo.application.contentResolver)
        }
        return FsSaveTarget(AutoSaveMomentsSettings.resolveSaveRoot())
    }

    private fun isAlreadySaved(snsTableId: String): Boolean = snsTableId in savedSnsIds

    @Synchronized
    private fun markSaved(snsTableId: String) {
        val updated = LinkedHashSet(savedSnsIds)
        updated.add(snsTableId)
        if (updated.size > MAX_SAVED_RECORDS) {
            val iterator = updated.iterator()
            repeat(updated.size - MAX_SAVED_RECORDS) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        savedSnsIds = updated
    }

    // ==================== 保存目标 ====================

    private sealed interface SaveTarget {
        fun saveFromPath(relativeDir: String, fileName: String, sourcePath: String): Boolean
        fun saveText(relativeDir: String, fileName: String, text: String): Boolean
    }

    private class FsSaveTarget(val root: Path) : SaveTarget {
        override fun saveFromPath(relativeDir: String, fileName: String, sourcePath: String): Boolean {
            val dest = root.resolve(relativeDir).createDirsSafe().resolve(fileName)
            return WeMomentsApi.copyExistingFile(sourcePath, dest.absolutePathString())
        }

        override fun saveText(relativeDir: String, fileName: String, text: String): Boolean {
            return runCatching {
                root.resolve(relativeDir).createDirsSafe().resolve(fileName).writeText(text)
                true
            }.getOrDefault(false)
        }
    }

    private class SafSaveTarget(
        private val treeUri: Uri,
        private val resolver: ContentResolver,
    ) : SaveTarget {

        override fun saveFromPath(relativeDir: String, fileName: String, sourcePath: String): Boolean {
            return runCatching {
                val localSource = if (sourcePath.asPath.isRegularFile()) {
                    sourcePath
                } else {
                    val dest = KnownPaths.moduleCache / "auto_save_moments_${System.currentTimeMillis()}.bin"
                    if (!WeMomentsApi.copyExistingFile(sourcePath, dest.absolutePathString())) return false
                    dest.absolutePathString()
                }
                val dirUri = ensureDirectory(relativeDir) ?: return false
                val docUri = createFile(dirUri, fileName) ?: return false
                java.io.FileInputStream(localSource).use { input ->
                    resolver.openOutputStream(docUri, "w")!!.use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }.getOrDefault(false)
        }

        override fun saveText(relativeDir: String, fileName: String, text: String): Boolean {
            return runCatching {
                val dirUri = ensureDirectory(relativeDir) ?: return false
                val docUri = createFile(dirUri, fileName) ?: return false
                resolver.openOutputStream(docUri, "w")!!.use { it.write(text.toByteArray()) }
                true
            }.getOrDefault(false)
        }

        private fun ensureDirectory(relativeDir: String): Uri? {
            var current = treeUri
            for (segment in relativeDir.split('/')) {
                if (segment.isBlank()) continue
                current = findChildDirectory(current, segment)
                    ?: DocumentsContract.createDocument(resolver, current, DocumentsContract.Document.MIME_TYPE_DIR, segment)
                    ?: return null
            }
            return current
        }

        private fun findChildDirectory(parent: Uri, name: String): Uri? {
            val docId = DocumentsContract.getTreeDocumentId(parent)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, docId)
            return resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val typeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (cursor.getString(typeIndex) == DocumentsContract.Document.MIME_TYPE_DIR &&
                        cursor.getString(nameIndex) == name
                    ) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idIndex))
                    }
                }
                null
            }
        }

        private fun createFile(parent: Uri, fileName: String): Uri? {
            val mime = mimeFromName(fileName)
            return DocumentsContract.createDocument(resolver, parent, mime, fileName)
        }

        private fun mimeFromName(fileName: String): String {
            return when (fileName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "heic" -> "image/heic"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
        }
    }

    private val SAVE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "mp4", "mov")
}
