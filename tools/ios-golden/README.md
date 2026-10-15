# iOS golden 报文工具

用 iOS 工程里的**原始模型代码**（`Models/Messages.swift`、`Models/CaptureSettings.swift`）编译一个小工具，
保证 Android 的协议实现与 iOS 逐字节兼容。

```bash
tools/ios-golden/build.sh                                   # 编译（需要 macOS + Xcode，iOS 工程在同级目录）
tools/ios-golden/.build/golden encode > protocol/src/test/resources/ios-golden.tsv   # 重新生成 iOS 报文
./gradlew :protocol:test                                    # Android 解码 iOS 报文，并写出 Android 编码的报文
tools/ios-golden/.build/golden decode protocol/build/android-encoded.tsv            # iOS 模型反向解码
```

iOS 协议有改动时：重新生成 golden、跑一遍以上命令，并同步更新 docs/PROTOCOL.md。
