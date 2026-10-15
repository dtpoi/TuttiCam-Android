package com.thinktough.tutticam.ui.capture

import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import com.thinktough.tutticam.app.ScreenshotDemo
import com.thinktough.tutticam.camera.CameraEngine

/** 用 SurfaceView 显示一颗镜头的预览，按画面比例完整显示不裁切；displayPortrait 表示画面竖着显示。 */
@Composable
fun CameraPreview(camera: CameraEngine, lens: CameraEngine.LensInfo, displayPortrait: Boolean, modifier: Modifier = Modifier) {
    val ratio = lens.previewSize.width.toFloat() / lens.previewSize.height
    AndroidView(
        modifier = modifier.aspectRatio(if (displayPortrait) 1 / ratio else ratio),
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        camera.setPreviewSurface(lens.kind, holder.surface, Size(width, height))
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        camera.setPreviewSurface(lens.kind, null, null)
                    }
                })
            }
        },
        update = { view -> view.holder.setFixedSize(lens.previewSize.width, lens.previewSize.height) },
    )
    DisposableEffect(camera, lens.kind) { onDispose { camera.setPreviewSurface(lens.kind, null, null) } }
}

/** 在截图演示模式下用参考照片代替预览，竖幅 3:4。 */
@Composable
fun DemoPreview(lens: CameraEngine.LensInfo, all: List<CameraEngine.LensInfo>, modifier: Modifier = Modifier) {
    val image = remember(lens.kind) { ScreenshotDemo.image(lens, all) }
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.aspectRatio(3f / 4))
    } else {
        Box(modifier.aspectRatio(3f / 4).background(Color.DarkGray))
    }
}
