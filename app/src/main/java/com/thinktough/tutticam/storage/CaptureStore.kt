package com.thinktough.tutticam.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * 把照片和 JSON 存进 Documents/TuttiCam/<场景文件夹>/（Pictures 不允许放 JSON），所有写入在一条串行线程上。
 * 强制同步时先写 .pending/，主机确认后再移入；Android 10 起走 MediaStore，Android 9 直接写文件。
 */
class CaptureStore(private val context: Context) {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "tutticam.store").apply { isDaemon = true } }

    /** 记录当前场景文件夹名，调用方会为已开拍的那组记下旧文件夹。 */
    @Volatile var folder: String? = null
        private set

    fun begin(folder: String) {
        this.folder = folder
    }

    fun end() {
        folder = null
    }

    /** 保存一组照片和 JSON，done 在存储线程回调，参数为空表示成功。 */
    fun save(files: List<Pair<String, ByteArray>>, metadata: List<Pair<String, String>>, pending: Boolean, folder: String?,
             done: (String?) -> Unit) {
        if (folder == null) return done("场景文件夹不存在")
        io.execute {
            val problem = runCatching {
                for ((name, bytes) in files) put(folder, name, bytes, pending)
                for ((name, text) in metadata) put(folder, name, text.toByteArray(), pending)
            }.exceptionOrNull()
            if (problem != null) Log.w(TAG, "保存失败", problem)
            done(problem?.let { it.message ?: it.javaClass.simpleName })
        }
    }

    /** 结算强制同步的一组：保留就移出 .pending，否则删除。 */
    fun resolve(round: Int, keep: Boolean, folder: String?, done: (() -> Unit)? = null) {
        if (folder == null) return
        val prefix = "r%04d_".format(java.util.Locale.US, round)
        io.execute {
            runCatching {
                for (entry in list(folder, pending = true).filter { it.name.startsWith(prefix) }) {
                    if (keep) move(entry, folder) else delete(entry)
                }
            }.onFailure { Log.w(TAG, "结算第 $round 组失败", it) }
            done?.invoke()
        }
    }

    /** 重命名场景文件夹。 */
    fun renameFolder(old: String, new: String) {
        if (folder == old) folder = new
        io.execute {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val oldPath = relative(old, false)
                    val newPath = relative(new, false)
                    query("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$oldPath%")).forEach { entry ->
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, newPath + entry.relativePath.removePrefix(oldPath))
                        }
                        context.contentResolver.update(entry.uri!!, values, null, null)
                    }
                } else {
                    val source = legacyDir(old, false)
                    if (source.exists()) source.renameTo(legacyDir(new, false))
                }
            }.onFailure { Log.w(TAG, "重命名 $old → $new 失败", it) }
        }
    }

    /** 把文本文件写进场景文件夹，同名覆盖。 */
    fun writeText(name: String, text: String, folder: String?) {
        if (folder == null) return
        io.execute { runCatching { put(folder, name, text.toByteArray(), false) }.onFailure { Log.w(TAG, "写 $name 失败", it) } }
    }

    /** 返回可用空间（字节）。 */
    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0)

    // MARK: - 实现

    private class Entry(val name: String, val relativePath: String, val uri: Uri?, val file: File?)

    private fun relative(folder: String, pending: Boolean) =
        "${Environment.DIRECTORY_DOCUMENTS}/$ROOT/$folder/" + if (pending) ".pending/" else ""

    @Suppress("DEPRECATION")
    private fun legacyDir(folder: String, pending: Boolean) =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "$ROOT/$folder" + if (pending) "/.pending" else "")

    private val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Files.getContentUri("external")

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "heic" -> "image/heic"
        "dng" -> "image/x-adobe-dng"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    /** 写入文件并覆盖同名文件，因为 MediaStore 默认会另起“名字 (1)”。 */
    private fun put(folder: String, name: String, bytes: ByteArray, pending: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val existing = query("${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(relative(folder, pending), name)).firstOrNull()?.uri
            if (existing != null) {
                resolver.openOutputStream(existing, "wt")!!.use { it.write(bytes) }
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative(folder, pending))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("系统拒绝创建 $name")
            try {
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = legacyDir(folder, pending)
            dir.mkdirs()
            val file = File(dir, name)
            val temp = File(dir, ".$name.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            if (!pending) MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
        }
    }

    private fun list(folder: String, pending: Boolean): List<Entry> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            query("${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relative(folder, pending)))
        } else {
            legacyDir(folder, pending).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
                ?.map { Entry(it.name, "", null, it) } ?: emptyList()
        }

    private fun query(selection: String, args: Array<String>): List<Entry> {
        val result = mutableListOf<Entry>()
        context.contentResolver.query(collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
            selection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result += Entry(cursor.getString(1), cursor.getString(2) ?: "",
                    Uri.withAppendedPath(collection, cursor.getLong(0).toString()), null)
            }
        }
        return result
    }

    private fun move(entry: Entry, folder: String) {
        if (entry.uri != null) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relative(folder, false)) }
            context.contentResolver.update(entry.uri, values, null, null)
        } else if (entry.file != null) {
            val target = File(legacyDir(folder, false), entry.name)
            entry.file.renameTo(target)
            MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
        }
    }

    private fun delete(entry: Entry) {
        if (entry.uri != null) context.contentResolver.delete(entry.uri, null, null) else entry.file?.delete()
    }

    companion object {
        private const val TAG = "TuttiCam.Store"
        const val ROOT = "TuttiCam"

        /** 生成场景文件夹名 <场景名>_<yyyyMMdd>_<HHmmss>。 */
        fun folderName(scene: String, time: java.util.Date = java.util.Date()): String =
            scene + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(time)

        /** 生成照片文件名主体，如 r0001_A01_w。 */
        fun photoBase(round: Int, code: String, lens: String): String = "r%04d_%s_%s".format(java.util.Locale.US, round, code, lens)

        /** 去掉文件夹名末尾的日期时间，用于显示。 */
        fun displayName(folder: String): String {
            if (folder.length <= 16) return folder
            return if (Regex("_\\d{8}_\\d{6}").matches(folder.takeLast(16))) folder.dropLast(16) else folder
        }
    }
}
