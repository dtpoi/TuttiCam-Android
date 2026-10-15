# 应用图标

- `TuttiCamIcon.svg`：图标原稿（512 × 512，透明底），不放进仓库，由维护者单独保管，生成图标时放到这个目录
- `svg2adaptive.py`：生成 Android 自适应图标的前景与单色图层（`app/src/main/res/drawable/ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`），背景为白色（`values/colors.xml`）
- `fit_icon.py`、`optical_center.swift`：按“模糊后看重心”检查图标是否视觉居中，可选用来微调缩放和中心

```bash
python3 tools/icon/svg2adaptive.py tools/icon/TuttiCamIcon.svg app/src/main/res
```

前景沿用原稿的留白与几何居中：512 × 512 的原稿画框对应 108dp 画布中间可见的 72dp，再按目测缩小 5%、往右下挪 1dp；单色图层（Android 13 主题图标）把深浅灰换成不同的不透明度。
