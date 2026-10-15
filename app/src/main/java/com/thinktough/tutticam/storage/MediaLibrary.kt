package com.thinktough.tutticam.storage

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.roundToInt

/**
 * 列出本机拍的照片：按场景文件夹分组，同名的 JPEG/HEIC、DNG、JSON 归为一张。
 * 通过 MediaStore 读取，只看得到本 App 写入的文件。
 */
object MediaLibrary {
    /** 表示场景文件夹里的一个文件，pending 表示还在 .pending/ 里等主机确认。 */
    class MediaFile(val name: String, val uri: Uri, val bytes: Long, val modified: Long, val pending: Boolean, val path: String?) {
        val extension get() = name.substringAfterLast('.', "").lowercase()
    }

    /** 表示一个场景文件夹，cover 是按文件名排序的第一张。 */
    class Folder(val name: String, val modified: Long, val photoCount: Int, val bytes: Long, val cover: Uri?, val files: List<MediaFile>) {
        val title get() = CaptureStore.displayName(name)
    }

    /** 表示一张照片：主图优先用 HEIC/JPEG，只有 RAW 时用 DNG；related 是同名的 DNG 和 JSON。 */
    class Item(val main: MediaFile, val related: List<MediaFile>) {
        val name get() = main.name
        val files get() = listOf(main) + related
    }

    /** 记录照片同名 JSON 里在全屏页显示的字段。 */
    class Metadata(val deviceCode: String?, val lens: String?, val shutter: Double?, val iso: Double?, val offsetMs: Double?,
                   val blurPx: Double?, val moving: Boolean)

    private val imageExtensions = setOf("heic", "jpg", "jpeg", "dng")
    private val rootRelative = "${Environment.DIRECTORY_DOCUMENTS}/${CaptureStore.ROOT}/"

    @Suppress("DEPRECATION")
    private val rootDir get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), CaptureStore.ROOT)

    private val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Files.getContentUri("external")

    /** 限制同时解码的缩略图数量，避免网格一次解几十张大图。 */
    val decoder = Dispatchers.IO.limitedParallelism(3)

    // MARK: 扫描

    /** 返回全部场景文件夹，最近改动的在前。 */
    fun folders(context: Context): List<Folder> =
        scan(context).groupBy({ it.first }, { it.second }).map { (name, files) ->
            val items = items(files)
            Folder(name, files.maxOf { it.modified }, items.size, files.sumOf { it.bytes }, items.firstOrNull()?.main?.uri, files)
        }.sortedByDescending { it.modified }

    /** 把文件归成照片，按文件名排序；.pending/ 里的不算。 */
    fun items(files: List<MediaFile>): List<Item> =
        files.filter { !it.pending }.groupBy { it.name.substringBeforeLast('.') }.mapNotNull { (_, group) ->
            val images = group.filter { it.extension in imageExtensions }
            val main = images.firstOrNull { it.extension != "dng" } ?: images.firstOrNull() ?: return@mapNotNull null
            Item(main, group.filter { it !== main })
        }.sortedBy { it.name }

    /** 查询 Documents/TuttiCam/ 下的文件，返回（场景文件夹名，文件）。 */
    private fun scan(context: Context): List<Pair<String, MediaFile>> {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val root = if (modern) rootRelative else rootDir.path + "/"
        @Suppress("DEPRECATION")
        val location = if (modern) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, location,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val result = mutableListOf<Pair<String, MediaFile>>()
        runCatching {
            context.contentResolver.query(collection, projection, "$location LIKE ?", arrayOf("$root%"), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val where = cursor.getString(2) ?: continue
                    // 新系统给的是“Documents/TuttiCam/场景/”，Android 9 给的是完整路径，都取根目录之后的部分。
                    val rest = where.removePrefix(root)
                    val parts = (if (modern) rest.trimEnd('/') else rest.substringBeforeLast('/', "")).split('/')
                    val folder = parts.first().takeIf { it.isNotEmpty() } ?: continue
                    val name = cursor.getString(1) ?: where.substringAfterLast('/')
                    if (name.endsWith(".tmp")) continue
                    val file = MediaFile(name, ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getLong(3),
                        cursor.getLong(4) * 1000, pending = parts.getOrNull(1) == ".pending", path = if (modern) null else where)
                    result += folder to file
                }
            }
        }
        return result
    }

    // MARK: 读取

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** 返回已缓存的缩略图。 */
    fun cached(uri: Uri, maxPixel: Int): Bitmap? = cache.get("$uri@$maxPixel")

    /** 解码长边不超过 maxPixel 的图，按 EXIF 方向转正；失败时返回 null。 */
    fun thumbnail(context: Context, uri: Uri, maxPixel: Int): Bitmap? {
        cached(uri, maxPixel)?.let { return it }
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = maxPixel.toDouble() / maxOf(info.size.width, info.size.height)
                if (scale < 1) decoder.setTargetSize(maxOf(1, (info.size.width * scale).roundToInt()), maxOf(1, (info.size.height * scale).roundToInt()))
            }
        }.getOrNull()?.also { cache.put("$uri@$maxPixel", it) }
    }

    /** 读照片同名 JSON；没开“输出照片陀螺仪信息”时没有 JSON，返回 null。 */
    fun metadata(context: Context, item: Item): Metadata? {
        val json = item.related.firstOrNull { it.extension == "json" } ?: return null
        val o = runCatching {
            context.contentResolver.openInputStream(json.uri)!!.use { Json.parseToJsonElement(it.reader().readText()).jsonObject }
        }.getOrNull() ?: return null
        fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content
        fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.doubleOrNull
        return Metadata(o.text("device_code"), o.text("lens"), o.number("shutter"), o.number("iso"), o.number("offset_ms"),
            o.number("blur_px"), o["moving"]?.jsonPrimitive?.booleanOrNull == true)
    }

    // MARK: 操作

    /** 删除文件；Android 9 上同时删磁盘文件。整个文件夹删完后顺手删掉空目录。 */
    fun delete(context: Context, files: List<MediaFile>, folder: String? = null) {
        for (file in files) {
            runCatching { context.contentResolver.delete(file.uri, null, null) }
            file.path?.let { runCatching { File(it).delete() } }
        }
        if (folder != null) runCatching {
            File(rootDir, "$folder/.pending").delete()
            File(rootDir, folder).delete()
        }
    }

    /** 用系统分享面板分享照片，带上同名的 DNG。 */
    fun share(context: Context, item: Item) {
        val uris = listOf(item.main.uri) + item.related.filter { it.extension == "dng" }.map { it.uri }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mime(item.main))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("image/*")
        }
        intent.clipData = ClipData.newRawUri(item.name, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** 在系统“文件”App 里打开 Documents/TuttiCam；还没拍过时打开 Documents。没有能打开的应用时返回 false。 */
    fun openFolder(context: Context): Boolean {
        val path = if (rootDir.exists()) rootRelative.trimEnd('/') else Environment.DIRECTORY_DOCUMENTS
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:$path")
        val root = DocumentsContract.buildRootUri(EXTERNAL_STORAGE, "primary")
        for ((uri, type) in listOf(folder to DocumentsContract.Document.MIME_TYPE_DIR, root to "vnd.android.document/root")) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            }
        }
        return false
    }

    /** 返回 Documents/TuttiCam 的显示路径。 */
    val folderLabel = rootRelative.trimEnd('/')

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    private fun mime(file: MediaFile) = when (file.extension) {
        "heic" -> "image/heic"
        "dng" -> "image/x-adobe-dng"
        else -> "image/jpeg"
    }
}
