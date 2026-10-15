# 参与贡献

**简体中文** | [English](CONTRIBUTING.en.md)

欢迎报告问题、提建议和提交代码。较大的改动请先开 Issue 说明想法，确认方向后再动手。

## 报告问题

请在 Issue 里写明：

- 手机型号和 Android 版本，App 版本；
- 是否和 iPhone 混拍，几台设备，谁当主机；
- 复现步骤、期望结果和实际结果；
- 日志：`adb logcat -d | grep TuttiCam`（日志里可能有设备名，发出前请检查）。

## 开发环境

- JDK 21、Android Studio，Android SDK 需要 compileSdk 37.2。
- 编译、测试、lint：

  ```bash
  ./gradlew :app:assembleDebug
  ./gradlew :protocol:test :app:testDebugUnitTest
  ./gradlew :app:lintDebug
  ```

- 相机、对时、外接相机要用真机测；模拟器只适合看界面。

## 提交代码

1. 从 `main` 拉一个分支，一个 PR 只做一件事。
2. 提交前跑通单元测试和 lint；PR 会自动跑 CI（`.github/workflows/ci.yml`），必须通过才能合并。
3. 界面改动请附截图，相机或网络改动请写明在哪些机型上测过。
4. 提交信息用中文：第一行概括改了什么，空一行后按需列要点。

## 代码约定

- 跟周围的代码保持一致：命名、写法、注释密度。
- 注释用中文，动词开头，一句话说清；只写代码现在做什么和为什么，不写改动历史和未实现的计划。
- 不在主线程做时间敏感的事；`AppModel`、`HostCoordinator`、`CaptureAgent` 的状态只在主线程修改。
- 界面文字不要直接改 `strings.xml`：改 `tools/strings/strings.py` 里的表，三种语言一起补齐，再运行：

  ```bash
  python3 tools/strings/strings.py
  ```

## 与 iOS 兼容

安卓和 iPhone 在同一个会话里混拍，网络协议必须两端一致：

- 改消息、设置字段或编码方式时，同步更新 [docs/PROTOCOL.md](docs/PROTOCOL.md)。
- 新增字段一律做成可选，不改已有字段的含义和类型。
- 用 `tools/ios-golden` 重新生成 iOS 报文并反向解码安卓报文（需要 macOS、Xcode 和 iOS 工程，见 `tools/ios-golden/README.md`）。

## 外接相机

- 协议常量和纯数据处理放在 `protocol` 模块，并补单元测试；蓝牙收发放在 `app/.../accessory/Drivers.kt`。
- 用真相机测通后，在 PR 里写明型号和固件版本。
- 移植自其他开源项目的代码，在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 里写明来源和许可证。

## 许可证

提交的贡献按本项目的 [GPL-3.0](LICENSE) 许可证授权。
