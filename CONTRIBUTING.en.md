# Contributing

[简体中文](CONTRIBUTING.md) | **English**

Bug reports, ideas and pull requests are welcome. For larger changes, please open an issue first so we can agree on the direction.

## Reporting Bugs

Please include:

- Phone model, Android version and app version;
- Whether iPhones were in the session, how many devices, and which one was the host;
- Steps to reproduce, expected result and actual result;
- Logs: `adb logcat -d | grep TuttiCam` (logs may contain device names, so check them before posting).

## Development Setup

- JDK 21 and Android Studio; the Android SDK needs compileSdk 37.2.
- Build, test and lint:

  ```bash
  ./gradlew :app:assembleDebug
  ./gradlew :protocol:test :app:testDebugUnitTest
  ./gradlew :app:lintDebug
  ```

- Test camera, clock sync and external cameras on real phones; the emulator is only good for checking the UI.

## Pull Requests

1. Branch off `main`; keep one change per pull request.
2. Run the unit tests and lint before pushing. CI (`.github/workflows/ci.yml`) runs on every pull request and must pass before merging.
3. Add screenshots for UI changes, and list the phones you tested on for camera or network changes.
4. Commit messages are written in Chinese: a one-line summary, then a blank line and bullet points if needed. English is fine if you can't write Chinese.

## Code Conventions

- Match the surrounding code: naming, style and comment density.
- Comments are in Chinese, start with a verb and fit in one sentence. Describe what the code does now and why; don't describe change history or unbuilt plans.
- Don't do timing-sensitive work on the main thread; change the state of `AppModel`, `HostCoordinator` and `CaptureAgent` only on the main thread.
- Don't edit `strings.xml` directly. Edit the table in `tools/strings/strings.py`, fill in all three languages, then run:

  ```bash
  python3 tools/strings/strings.py
  ```

## Compatibility with iOS

Android phones and iPhones shoot in the same session, so the network protocol must match on both sides:

- When you change messages, settings fields or the encoding, update [docs/PROTOCOL.md](docs/PROTOCOL.md) (in Chinese).
- New fields must be optional; don't change the meaning or type of existing fields.
- Regenerate the iOS messages and decode the Android messages with `tools/ios-golden` (needs macOS, Xcode and the iOS project; see `tools/ios-golden/README.md`).

## External Cameras

- Put protocol constants and pure data handling in the `protocol` module with unit tests; put the Bluetooth logic in `app/.../accessory/Drivers.kt`.
- Once tested with a real camera, mention the model and firmware version in the pull request.
- For code ported from other open-source projects, add the source and license to [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

By contributing, you agree that your contributions are licensed under this project's [GPL-3.0](LICENSE) license.
