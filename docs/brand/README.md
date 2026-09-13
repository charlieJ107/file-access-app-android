# FileAccess 图标

原创几何标志：薄荷绿色文件夹内的向右连接箭头，表达访问远端文件；深蓝绿色底色。无文字，在小尺寸保持辨识度。采用代码原生矢量绘制，不使用 AI 图像生成或第三方素材。

- `fileaccess-logo.svg`：带圆角背景的可编辑主图。
- `fileaccess-mark.svg`：透明背景标志。
- `fileaccess-logo.png`：512×512 PNG，适用于项目介绍和发布页。
- `app/src/main/res/drawable/ic_launcher_*`：108dp 自适应背景、前景、单色版本。
- `app/src/main/res/mipmap-anydpi/`：普通/圆形自适应图标定义。
- `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/`：48、72、96、144、192px 普通/圆形 WebP。

设计几何集中于 `scripts/generate-icons.cjs`，使用 Node.js 与 sharp 重建：`node scripts/generate-icons.cjs`。前景保持在 Android 自适应图标中心安全区，单色版本通过镂空箭头保留特征；Manifest 的 icon / roundIcon 引用这些资产。图标规范参考 [Android Adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。
