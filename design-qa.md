# 媒体分类设计验收

日期：2026-10-17。分支：`codex/media-type-filter`。

## 对照目标与证据

用户选择更多菜单方案，并明确要求复用现有 Theme mode 三选一控件。随后指出菜单按钮过大、不协调；本轮以缩小菜单内尺寸为验收目标。对照范围是该控件的外观和交互；设置卡片与媒体菜单属于不同容器，不要求两页整体布局相同。

- 原始视觉资产：`scratch/emulator-review/themes-colored.png`。
- 当前实现截图：`scratch/media-type-compact/home-menu-all-light.png`、`scratch/media-type-compact/album-menu-videos-dark.png`；上轮原尺寸截图保留在 `scratch/media-type-qa/`。
- 全屏并排对照：`scratch/media-type-qa/theme-full-comparison.png`。
- 控件局部对照：`scratch/media-type-qa/theme-control-comparison.png`。两个控件按原始像素排列，未拉伸来匹配不同的容器宽度。
- 大字体实际系统截图：`scratch/media-type-qa/menu-system-font-150.png`。通过 Android 系统 `font_scale=1.5` 启动原生 MainActivity，选择 Videos 后截取；检查结束恢复为 1.0。
- 空状态：`scratch/media-type-qa/album-no-videos-dark.png`。

原图与实现截图均为 1080 × 2400 像素。实现设备为 SelectAllShot 独立模拟器，420 dpi，密度 2.625，屏幕约 411 × 914 dp；这是 Android 原生界面，没有 CSS 视口。原图的设备密度未另行记录；原 Theme mode 按钮约 126 像素（48 dp），本轮菜单紧凑按钮约 84 像素（32 dp）；这是按用户反馈进行的尺寸调整。对照板仅排列原截图，原始截图保持不变。

## 视觉检查

- 字体：原设置页沿用 `labelLarge`，菜单紧凑版本使用 `labelMedium`；均保留选中粗体与未选中中等字重，没有新增字体。菜单勾选图标、字号同步缩小，保持原控件的形状与配色关系。系统 150% 字号截图中三项文字完整显示。
- 间距与布局：保留原有选中圆角胶囊、未选中圆角矩形和 4 dp 间距，菜单使用紧凑尺寸：按钮高 32 dp、文字 labelMedium、勾选 14 dp、图文间距 4 dp；设置页继续使用原来 48 dp 的默认尺寸。默认菜单宽 236 dp；大字体时增宽，并受屏幕宽度约束。按钮只出现在更多菜单，页面没有新增常驻工具栏。
- 颜色：继续使用 MaterialTheme 的 primary/onPrimary 与 surfaceContainerHigh/onSurface。参考图使用 Material You，固定主题测试图为紫色，这属于主题差异；实际 MainActivity 的动态配色截图与原资产的蓝色风格一致。明暗主题均检查了前景与背景。
- 图像与资产：直接复用原 Compose 控件与 Material 勾选图标，没有替换成图片按钮。背景媒体为测试创建的纯色 JPEG 和可播放 MP4，仅用于隔离测试；测试结束已删除媒体。
- 文案：菜单为 Media type、All、Images、Videos；中文资源为“媒体类型、全部、图片、视频”。相册标题下沿用数量位置，按类型显示 images/videos；空视频分类显示 No videos。中文资源已通过构建，中文系统界面未单独截图。

已修复：[P2] 菜单内直接套用设置页尺寸，按钮和留白过大，分类控件抢过主要菜单操作。已增加仅供菜单使用的紧凑尺寸，并以相同相册、相同视频选择、相同暗色主题的新旧截图复核。按钮从 126 像素高收至 84 像素，整组减少约 28 dp，分类控件低于普通菜单项的圆形图标。没有剩余 P0/P1/P2 视觉问题。

## 操作与回归检查

`scratch/media-types-emulator-final.log` 中记录完整媒体分类套件通过：

- 主页与相册列表共享分类；仅显示包含所选类型的相册。
- 相册内进入时继承父级，此后独立；退出再进入重新继承。
- 筛选后的相册数量、封面与日期使用同一批媒体；数量和日期排序有单元测试覆盖。
- 视频分类下全选仅选中两个可见视频，排除两张图片。
- 搜索与分类取交集；只有一项的视频搜索结果在大图左右滑动后仍保持同一文件。
- 返回大图前的相册搜索与分类得到保留。验证期间发现并修复了底层保留的相册搜索返回处理拦截大图返回的问题，现仅在相册位于前台时处理搜索返回。
- 视频空状态、明暗菜单、增大字体下的菜单宽度均已检查；另以真实 Android 系统 150% 字号截图复核文字。

功能实现阶段全部 115 项单元测试通过。本轮仅调整控件尺寸，重新构建并重跑完整媒体分类 UI 套件通过，记录为 `scratch/media-types-compact-build.log` 与 `scratch/media-types-compact-emulator.log`。另在 Android 系统真实 150% 字号下复核新菜单，文字完整。测试仅在独立模拟器执行，未进行物理设备验收。

## 比较历史与交付检查

上一轮保留了原控件尺寸，用户反馈按钮过大。本轮将菜单按钮从 48 dp 调整为 32 dp，字号从 labelLarge 调整为 labelMedium，勾选图标从 18 dp 调整为 14 dp，同时缩小标题与分组留白。原设置页使用默认尺寸不变。

用户反馈截图：`scratch/media-type-compact/user-feedback.png`。修改前同状态原生截图：`scratch/media-type-compact/before-dark.png`、`before-light.png`。

本轮修改后原生截图：`scratch/media-type-compact/album-menu-videos-dark.png`、`home-menu-all-light.png`。全屏对照：`scratch/media-type-compact/full-comparison.png`；局部对照：`scratch/media-type-compact/menu-comparison.png`。两侧均使用原始像素、未拉伸截图；原图与新图同为 1080 × 2400 像素。

系统大字体证据：`scratch/media-type-compact/menu-system-font-150.png`，实际 MainActivity，Android `font_scale=1.5`，Videos 选中；截图后恢复为 1.0。以上新截图均已打开复核。

- [x] 复用原 Theme mode 控件。
- [x] 仅增加菜单内的分类选项。
- [x] 父级联动与相册独立行为通过完整模拟器流程。
- [x] 全选、搜索、大图浏览、返回流程通过。
- [x] 明暗主题、空状态和系统大字体经过截图检查。
- [x] 构建和单元测试通过。

final result: passed
