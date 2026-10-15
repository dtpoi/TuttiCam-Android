package com.thinktough.tutticam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/** 探测本机支持的多镜头组合并写日志（TuttiCam.Dual），只在调试版运行。 */
object DualProbe {
    private const val TAG = "TuttiCam.Dual"

    /** 打开逻辑多摄，询问两路物理子镜头组合是否可用。 */
    @android.annotation.SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun probeOpened(manager: CameraManager, preview: Surface, yuv: (Size) -> Surface, executor: Executor,
                            callback: android.hardware.camera2.CameraCaptureSession.StateCallback) {
        val thread = android.os.HandlerThread("tutticam.dual-probe-camera").apply { start() }
        val handler = android.os.Handler(thread.looper)
        val latch = java.util.concurrent.CountDownLatch(1)
        for (id in manager.cameraIdList) {
            val physical = runCatching { manager.getCameraCharacteristics(id).physicalCameraIds }.getOrDefault(emptySet()).toList()
            if (physical.size < 2) continue
            manager.openCamera(id, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(device: android.hardware.camera2.CameraDevice) {
                    try {
                        for (i in physical.indices) for (j in physical.indices) {
                            if (i >= j) continue
                            for (size in listOf(Size(4000, 3000), Size(1920, 1440))) {
                                val outputs = listOf(
                                    OutputConfiguration(preview),
                                    OutputConfiguration(yuv(size)).apply { setPhysicalCameraId(physical[i]) },
                                    OutputConfiguration(yuv(size)).apply { setPhysicalCameraId(physical[j]) },
                                )
                                val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
                                val ok = runCatching<Boolean> { device.isSessionConfigurationSupported(config) }
                                Log.i(TAG, "  （已打开 $id）物理 ${physical[i]} + ${physical[j]} 各出 ${size.width}x${size.height} YUV：" +
                                    ok.getOrElse { "出错 ${it.javaClass.simpleName}: ${it.message}" })
                            }
                        }
                    } finally {
                        device.close()
                        latch.countDown()
                    }
                }
                override fun onDisconnected(device: android.hardware.camera2.CameraDevice) { device.close(); latch.countDown() }
                override fun onError(device: android.hardware.camera2.CameraDevice, error: Int) {
                    Log.i(TAG, "打开 $id 出错 $error"); device.close(); latch.countDown()
                }
            }, handler)
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            break
        }
        thread.quitSafely()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun run(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.i(TAG, "系统低于 Android 15，跳过不开相机的探测")
            return
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val executor = Executor { it.run() }
        val callback = object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) = Unit
            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) = Unit
        }
        val previewTexture = SurfaceTexture(false).apply { setDefaultBufferSize(1920, 1440) }
        val preview = Surface(previewTexture)
        val readers = mutableListOf<ImageReader>()
        fun yuv(size: Size): Surface = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { readers += it }.surface

        try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                val physical = runCatching { c.physicalCameraIds }.getOrDefault(emptySet())
                if (physical.size < 2) continue
                val setupSupported = runCatching { manager.isCameraDeviceSetupSupported(id) }.getOrDefault(false)
                Log.i(TAG, "逻辑多摄 $id：物理子镜头 $physical，可不开相机查询：$setupSupported")
                if (!setupSupported) continue
                val setup = manager.getCameraDeviceSetup(id)
                val ids = physical.toList()
                for (i in ids.indices) for (j in ids.indices) {
                    if (i >= j) continue
                    for (size in listOf(Size(4000, 3000), Size(1920, 1440), Size(1280, 960))) {
                        val outputs = listOf(
                            OutputConfiguration(preview),
                            OutputConfiguration(yuv(size)).apply { setPhysicalCameraId(ids[i]) },
                            OutputConfiguration(yuv(size)).apply { setPhysicalCameraId(ids[j]) },
                        )
                        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
                        val ok = runCatching<Boolean> { setup.isSessionConfigurationSupported(config) }
                        Log.i(TAG, "  物理 ${ids[i]} + ${ids[j]} 各出 ${size.width}x${size.height} YUV：${ok.getOrElse { "出错 ${it.javaClass.simpleName}: ${it.message}" }}")
                    }
                }
            }
            val concurrent = manager.concurrentCameraIds
            Log.i(TAG, "并发相机组合：$concurrent")
            for (set in concurrent) {
                for (size in listOf(Size(4000, 3000), Size(1920, 1440), Size(1280, 960))) {
                    val map = set.associateWith { id ->
                        SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(Surface(SurfaceTexture(false).apply { setDefaultBufferSize(1280, 960) })),
                                OutputConfiguration(yuv(size))), executor, callback)
                    }
                    val ok = runCatching<Boolean> { manager.isConcurrentSessionConfigurationSupported(map) }
                    Log.i(TAG, "  并发 $set 各出预览 + ${size.width}x${size.height} YUV：${ok.getOrElse { "出错 ${it.javaClass.simpleName}: ${it.message}" }}")
                }
            }
            probeOpened(manager, preview, ::yuv, executor, callback)
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                Log.i(TAG, "相机 $id 硬件级别 $level")
            }
        } catch (e: Exception) {
            Log.w(TAG, "探测出错", e)
        } finally {
            readers.forEach { it.close() }
            preview.release()
            previewTexture.release()
        }
    }
}
