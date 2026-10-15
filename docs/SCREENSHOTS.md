# 截图

README 里的截图用调试版的**截图演示模式**截取：不开相机，取景框显示参考照片；主机模拟 3 台从机、外接相机，并显示拍到第 12 组的状态。演示数据写在 `app/.../app/ScreenshotDemo.kt`，正式版里不会生效。

## 参考照片

把照片放进 App 的外部文件目录 `/sdcard/Android/data/com.thinktough.tutticam/files/screenshot/`：

| 文件名 | 用在 |
| --- | --- |
| `scene.jpg` | 后置镜头的公共底图，当作最广那颗镜头拍到的画面；其他后置镜头按倍率从中间裁一块 |
| `ultrawide.jpg`、`wide.jpg`、`telephoto.jpg` | 对应镜头单独的照片（可选，优先于 scene） |
| `front.jpg` | 前置（可选，没有时前置画面是灰色） |

照片会从中间裁成竖幅 3:4，最好用竖拍、长边 3000 像素以上的照片。

当前截图用的照片来自 Unsplash（[Unsplash License](https://unsplash.com/license)，可免费商用，不要求署名）：

- `scene.jpg`：feey 拍摄的「A living room filled with furniture and a plant」<https://unsplash.com/photos/a-living-room-filled-with-furniture-and-a-plant-G-8dNxsBJP8>
- `front.jpg`：feey 拍摄的「A living room with a white couch and a green plant」<https://unsplash.com/photos/a-living-room-with-a-white-couch-and-a-green-plant-sMOqtkOwsJA>

```bash
adb push scene.jpg front.jpg /sdcard/Android/data/com.thinktough.tutticam/files/screenshot/
```

## 截图命令

用 Android Studio 的模拟器或真机都可以。先装调试版，再选 App 语言（Android 13 起）：

```bash
adb shell cmd locale set-app-locales com.thinktough.tutticam --locales zh-CN
```

按演示模式启动，`-S` 先停掉正在运行的 App：

```bash
adb shell am start -S -n com.thinktough.tutticam/.MainActivity --ez screenshot true --es lens w
```

```bash
adb exec-out screencap -p > 截图.png
```

| 启动参数 | 画面 |
| --- | --- |
| `--es lens w` | 单镜头 1x（`uw` 超广角、`w` 主摄、`t` 长焦、`f` 前置） |
| `--es lens w,f` | 多镜头，主镜头在前；只能选本机支持的组合 |
| `--es sheet settings` | 打开设置页，滚到“静止判定” |
| `--es sheet devices` | 打开设备页；上拉成全屏能看到全部 4 台设备 |
| `--es sheet accessories` | 打开外接相机页 |

截好的图缩到 600 像素宽，按 `docs/screenshots/<语言>/` 存放（`zh`、`en`），各语言文件名一致。
