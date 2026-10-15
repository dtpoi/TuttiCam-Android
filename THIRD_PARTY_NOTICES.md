# 第三方声明 / Third-Party Notices

本项目用到或参考了下列第三方项目和资料。

## 运行时依赖

| 项目 | 许可证 |
| --- | --- |
| [Android Jetpack](https://github.com/androidx/androidx)（Compose、Material 3、Activity、Lifecycle、Core、ExifInterface、HeifWriter、WindowManager） | Apache License 2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) | Apache License 2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache License 2.0 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache License 2.0 |
| [Material Design Icons](https://github.com/google/material-design-icons) | Apache License 2.0 |

Apache License 2.0 全文：<https://www.apache.org/licenses/LICENSE-2.0>

## 外接相机协议

### furble

索尼、佳能（遥控器方式和智能手机方式）、富士（新旧两种固件）、尼康（蓝牙遥控器 ML-L7 方式）、理光 GR 系列的蓝牙遥控协议
移植自 [gkoh/furble](https://github.com/gkoh/furble)，见 `protocol/.../accessory/Cameras.kt` 和 `app/.../accessory/Drivers.kt`。
furble 的理光实现参考了 [dm-zharov/ricoh-gr-bluetooth-api](https://github.com/dm-zharov/ricoh-gr-bluetooth-api)。

```
MIT License

Copyright (c) 2020 Guo-Rong Koh

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### DJI R SDK 与 Osmo GPS Controller Demo

DJI 设备的蓝牙遥控依据 DJI 公开的 DJI R SDK 协议文档和
[Osmo GPS Controller Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo) 示例实现，见 `protocol/.../accessory/Dji.kt`。
按 DJI 的声明，DJI R SDK 协议文档和 Osmo GPS Controller Demo 依
[DJI 最终用户许可协议](http://developer.dji.com/policies/eula/) 提供，示例代码同时以 MIT 许可证发布：

```
The MIT License (MIT)
Copyright (c) 2025 SZ DJI Technology Co., Ltd.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Open GoPro

GoPro 相机的蓝牙遥控依据 GoPro 公开的 [Open GoPro](https://gopro.github.io/OpenGoPro/) 蓝牙接口规范实现，
接口常量参照 [Open GoPro Python SDK](https://github.com/gopro/OpenGoPro/tree/main/demos/python/sdk_wireless_camera_control)：

```
The OpenGoPro Python SDK is licensed under the MIT License

Copyright © 2021-2024 GoPro, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

=============================================================================

The SDK is comprised of third-party libraries made available by their respective
copyright holders under the licenses specified in the thirdPartyDependencies.csv file.
```

## 商标

DJI、Osmo 是深圳市大疆创新科技有限公司的商标；Sony 是索尼集团公司的商标；Canon、EOS 是佳能株式会社的商标；
FUJIFILM 是富士胶片株式会社的商标；Nikon 是株式会社尼康的商标；RICOH、GR 是株式会社理光的商标；GoPro、HERO 是 GoPro, Inc. 的商标。
本项目与上述公司没有关联，也未获得它们的认可。
