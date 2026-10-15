# 发布

推送 `v*` 标签时，GitHub Actions（`.github/workflows/release.yml`）会测试、编出签名的 APK 和 AAB，并创建 GitHub Release。

## 第一次：准备签名密钥

1. 生成发布密钥（只做一次，**妥善保管，丢了就不能再更新同一个 App**）：

   ```bash
   keytool -genkeypair -keystore tutticam-release.jks -alias tutticam -keyalg RSA -keysize 4096 -validity 10000
   ```

2. 在 GitHub 仓库的 Settings → Secrets and variables → Actions 里添加 4 个 Secrets：

   | 名称 | 内容 |
   | --- | --- |
   | `TUTTICAM_KEYSTORE_BASE64` | `base64 -i tutticam-release.jks` 的输出 |
   | `TUTTICAM_KEYSTORE_PASSWORD` | 密钥库密码 |
   | `TUTTICAM_KEY_ALIAS` | 密钥别名，如 `tutticam` |
   | `TUTTICAM_KEY_PASSWORD` | 密钥密码 |

密钥文件不要提交到仓库（`.gitignore` 已忽略 `*.jks`、`*.keystore`）。上架 Google Play 时建议开启 Play 应用签名，这样上传密钥丢了还能重置。

## 每次发布

1. 在 `app/build.gradle.kts` 里把 `versionCode` 加一，并改 `versionName`（如 `1.1`）。
2. 在 `CHANGELOG.md` 最上面加一节 `## v1.1 · 日期`，写这一版的更新说明；Release 页面的说明就取自这一节（标题和 `v1.1` 之间要有空格）。
3. 提交到 `main`，等 CI 通过。
4. 打标签并推送，标签必须是 `v` 加 `versionName`：

   ```bash
   git tag v1.1
   git push origin v1.1
   ```

5. 等 Release 流程跑完：
   - GitHub Release 里附带签名的 `TuttiCam-v1.1.apk`，说明取自 `CHANGELOG.md`，找不到这一节时按提交记录自动生成。
   - 这次运行的构建产物（Artifacts）里有 `TuttiCam-v1.1.aab`（上传应用商店用）和 `mapping-v1.1.txt`（还原混淆后的崩溃堆栈用）。

## 本机编签名包

```bash
TUTTICAM_KEYSTORE_FILE=/路径/tutticam-release.jks \
TUTTICAM_KEYSTORE_PASSWORD=… TUTTICAM_KEY_ALIAS=tutticam TUTTICAM_KEY_PASSWORD=… \
./gradlew :app:assembleRelease :app:bundleRelease
```

不设这些环境变量时，`assembleRelease` 编出未签名的 `app-release-unsigned.apk`。

发布前至少在一台真机上装一次 release 包试拍：release 包开启了 R8 混淆，和调试包不完全一样。
