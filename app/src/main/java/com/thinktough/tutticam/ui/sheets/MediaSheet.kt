package com.thinktough.tutticam.ui.sheets

import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thinktough.tutticam.R
import com.thinktough.tutticam.storage.MediaLibrary
import com.thinktough.tutticam.ui.Format
import com.thinktough.tutticam.ui.components.SheetPadding
import com.thinktough.tutticam.ui.components.TcSheet
import com.thinktough.tutticam.ui.components.sheetVisibleArea
import com.thinktough.tutticam.ui.theme.TcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 显示已拍摄媒体：按场景列出本机照片，点进去看缩略图，再点全屏翻看、分享和删除。
 * activeFolder 是正在拍摄的场景文件夹，不允许删除。
 */
@Composable
fun MediaSheet(activeFolder: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val folders by produceState<List<MediaLibrary.Folder>?>(null, reload) {
        value = withContext(Dispatchers.IO) { MediaLibrary.folders(context) }
    }
    var open by remember { mutableStateOf<MediaLibrary.Folder?>(null) }
    var pendingDelete by remember { mutableStateOf<MediaLibrary.Folder?>(null) }

    TcSheet(onDismiss) {
        val folder = open
        if (folder != null) {
            FolderGrid(folder) { open = null; reload++ }
        } else {
            FolderList(folders, activeFolder, onOpen = { open = it }, onDelete = { pendingDelete = it })
        }
    }
    pendingDelete?.let { folder ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.media_delete_scene_title)) },
            text = { Text(stringResource(R.string.media_delete_scene_note)) },
            confirmButton = {
                TextButton({
                    MediaLibrary.delete(context, folder.files, folder.name)
                    pendingDelete = null
                    reload++
                }) { Text(stringResource(R.string.media_delete_scene, folder.title, folder.photoCount), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ pendingDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun FolderList(folders: List<MediaLibrary.Folder>?, activeFolder: String?, onOpen: (MediaLibrary.Folder) -> Unit,
                       onDelete: (MediaLibrary.Folder) -> Unit) {
    val context = LocalContext.current
    Text(stringResource(R.string.media_title), style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = SheetPadding, end = SheetPadding, top = 16.dp, bottom = 8.dp))
    when {
        // 空状态只在弹层露出的部分里居中，半屏时不会掉到屏幕外。
        folders == null -> Box(Modifier.fillMaxSize().sheetVisibleArea(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        folders.isEmpty() -> Column(Modifier.fillMaxSize().sheetVisibleArea().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.media_empty), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.media_empty_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            // 磁盘上有场景文件夹却一张都读不到，多半是重装前拍的，指去“文件”App 看。
            if (MediaLibrary.hasFoldersOnDisk()) {
                SavedFolderLink(R.string.media_orphan_note, Modifier.padding(top = 8.dp), TextAlign.Center)
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(folders, key = { it.name }) { folder ->
                val active = folder.name == activeFolder
                Row(Modifier.fillMaxWidth().clickable { onOpen(folder) }.padding(start = SheetPadding, top = 8.dp, bottom = 8.dp, end = SheetPadding - 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Thumbnail(folder.cover, 160, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(folder.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            if (active) {
                                Text(stringResource(R.string.media_current_scene), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        val date = DateUtils.formatDateTime(context, folder.modified,
                            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
                        Text(stringResource(R.string.media_folder_summary, folder.photoCount, Format.bytes(context, folder.bytes), date),
                            style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum")),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (active) {
                        Spacer(Modifier.size(48.dp))
                    } else {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton({ menu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.media_delete)) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                    onClick = { menu = false; onDelete(folder) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderGrid(folder: MediaLibrary.Folder, onBack: () -> Unit) {
    val items = remember(folder) { mutableStateListOf<MediaLibrary.Item>().apply { addAll(MediaLibrary.items(folder.files)) } }
    var selected by remember { mutableStateOf<Int?>(null) }
    BackHandler(onBack = onBack)
    Row(Modifier.fillMaxWidth().padding(start = SheetPadding - 12.dp, end = SheetPadding, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
        Text(folder.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize().navigationBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(items, key = { _, item -> item.name }) { index, item ->
            Thumbnail(item.main.uri, GRID_PIXEL, Modifier.aspectRatio(1f).clickable { selected = index })
        }
    }
    selected?.let { start ->
        PhotoPager(items, start) { selected = null }
    }
}

/** 全屏翻看照片，底部显示拍摄信息、进度条、分享和删除。 */
@Composable
private fun PhotoPager(items: MutableList<MediaLibrary.Item>, start: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(start) { items.size }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            val current = items.getOrNull(pager.currentPage)
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text(stringResource(R.string.done)) }
                    Text(current?.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Spacer(Modifier.size(72.dp, 1.dp))
                }
                HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { items.getOrNull(it)?.name ?: it }) { page ->
                    items.getOrNull(page)?.let {
                        Thumbnail(it.main.uri, 2400, Modifier.fillMaxSize(), ContentScale.Fit, Color.Black, preview = GRID_PIXEL)
                    }
                }
                current?.let { MetadataStrip(it) }
                // 拖动进度条快速翻看：最左是这个场景的第一张，最右是最后一张。
                if (items.size > 1) {
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Slider(value = dragging ?: pager.currentPage.toFloat(),
                            onValueChange = { value ->
                                dragging = value
                                val page = value.roundToInt()
                                if (page != pager.currentPage) scope.launch { pager.scrollToPage(page) }
                            },
                            onValueChangeFinished = { dragging = null },
                            valueRange = 0f..(items.size - 1).toFloat(),
                            // 滑块在最左时贴着屏幕边缘，要挡掉系统的返回手势，不然一拖就关掉了。
                            modifier = Modifier.weight(1f).systemGestureExclusion())
                        Text("${pager.currentPage + 1} / ${items.size}",
                            style = MaterialTheme.typography.labelLarge.merge(TextStyle(fontFeatureSettings = "tnum")))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ current?.let { MediaLibrary.share(context, it) } }) {
                        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.media_share))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton({ confirmDelete = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.media_delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.media_delete_photo_title)) },
                text = { Text(stringResource(R.string.media_delete_photo_note)) },
                confirmButton = {
                    TextButton({
                        confirmDelete = false
                        val index = pager.currentPage
                        items.getOrNull(index)?.let { MediaLibrary.delete(context, it.files) }
                        if (index < items.size) items.removeAt(index)
                        if (items.isEmpty()) onDismiss()
                    }) { Text(stringResource(R.string.media_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
        }
    }
}

@Composable
private fun MetadataStrip(item: MediaLibrary.Item) {
    val context = LocalContext.current
    val meta by produceState<MediaLibrary.Metadata?>(null, item) {
        value = withContext(Dispatchers.IO) { MediaLibrary.metadata(context, item) }
    }
    Row(Modifier.fillMaxWidth().background(Color(0xFF1C1C1E)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        val style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum"))
        val m = meta
        if (m == null) {
            Text(stringResource(R.string.media_no_metadata), style = style, color = Color.White.copy(alpha = 0.6f))
        } else {
            m.deviceCode?.let { Text(it, style = style) }
            m.lens?.let { Text(it.uppercase(Locale.US), style = style) }
            m.shutter?.let { Text(Format.shutterShort(it), style = style) }
            m.iso?.let { Text("ISO ${it.toInt()}", style = style) }
            m.offsetMs?.let { Text(stringResource(R.string.media_offset, "%+.1f".format(Locale.US, it)), style = style, maxLines = 1) }
            m.blurPx?.let { Text(stringResource(R.string.blur_px, "%.2f".format(Locale.US, it)), style = style, maxLines = 1) }
            if (m.moving) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(Icons.Rounded.Vibration, contentDescription = null, tint = TcColors.MovingOrange, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.moving), style = style, color = TcColors.MovingOrange)
                }
            }
        }
    }
}

/** 网格缩略图的长边像素，全屏翻看时先拿它垫底。 */
private const val GRID_PIXEL = 320

/**
 * 显示一张图，后台解码；解不出来时显示破图图标。
 * 给了 preview 时先显示这个尺寸的小图，再换成大图，拖进度条快速翻时不会一片空白。
 */
@Composable
private fun Thumbnail(uri: Uri?, maxPixel: Int, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop,
                      background: Color = MaterialTheme.colorScheme.surfaceVariant, preview: Int? = null) {
    val context = LocalContext.current
    var failed by remember(uri, maxPixel) { mutableStateOf(uri == null) }
    val bitmap by produceState(uri?.let { MediaLibrary.cached(it, maxPixel) ?: preview?.let { p -> MediaLibrary.cached(it, p) } }, uri, maxPixel) {
        if (uri == null || MediaLibrary.cached(uri, maxPixel) != null) return@produceState
        if (value == null && preview != null) value = withContext(MediaLibrary.decoder) { MediaLibrary.thumbnail(context, uri, preview) }
        val full = withContext(MediaLibrary.decoder) { MediaLibrary.thumbnail(context, uri, maxPixel) }
        if (full != null) value = full
        failed = value == null
    }
    Box(modifier.background(background), contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null -> Image(image.asImageBitmap(), contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
            failed -> Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** 显示一句带保存位置链接的话，text 里的 %1$s 换成可点的 Documents/TuttiCam，点开系统“文件”App。 */
@Composable
internal fun SavedFolderLink(text: Int, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    val context = LocalContext.current
    val noApp = stringResource(R.string.no_file_manager)
    val marker = "\u0000"
    val (before, after) = stringResource(text, marker).split(marker).let { it[0] to it.getOrElse(1) { "" } }
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val annotated = buildAnnotatedString {
        append(before)
        withLink(LinkAnnotation.Clickable("folder", link) {
            if (!MediaLibrary.openFolder(context)) Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
        }) { append(MediaLibrary.folderLabel.replace("/", "\u2060/\u2060")) }  // 用连接符把路径连成一整段，不在斜杠处换行。
        append(after)
    }
    Text(annotated, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = textAlign,
        modifier = modifier)
}
