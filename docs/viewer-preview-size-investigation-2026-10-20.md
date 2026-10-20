# 照片过渡画面偏小调查

用户样本：`D:\Quick Share\新建文件夹 (2)`，七张 JPEG。范围是调查、安装监听并观察，不修改显示逻辑。原始文件只读，使用副本。

## 清单

- ~~检查七张原图尺寸、方向、编码和内嵌缩略图，记录 SHA-256~~。
- ~~安装独立 trace 包，在可见模拟器逐张从真实相册打开两轮，录制加载过程~~。
- ~~对照系统缩略图、实际 ImageView drawable/matrix、最终图片和录屏，定位缩小阶段~~。
- ~~核验安装产物与原图副本，报告结论及限制~~。

## 资料与当前线索

- 上游 [SSIV preview images](https://github.com/davemorrissey/subsampling-scale-image-view/wiki/03.-Preview-images) 说明预览图应与原图保持相同比例和方向；[Glide targets](https://sjudd.github.io/glide/doc/targets.html) 与 [transformations](https://sjudd.github.io/glide/doc/transformations.html) 用于核对视图目标与缩放。
- 七张图 EXIF orientation 均为 8。Canon 三张原图 5808×3872，Sony 四张 7952×5304；均包含 160×120 EXIF JPEG 缩略图。
- 缩略图自带上下黑边：Canon 内容约 160×108、Sony 约 160×106。旋转为竖图后黑边在左右。实际平台返回、绘制监听和录屏已共同确认：适屏计算包含了这部分黑边，导致照片内容比清晰图小约一成。
- [Android ThumbnailUtils 上游源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/ThumbnailUtils.java) 的 `createImageThumbnail` 会优先尝试 EXIF 内嵌预览，失败后才解码原图。本轮还直接调用模拟器的平台 API，实际返回结果见下表，未仅凭上游源码推定设备行为。
- 文件、摘要和嵌入预览的原始提取结果：`scratch/viewer-preview-size-20261020/photo-metadata.json` 及同目录 `*-embedded.jpg`。

## 用例矩阵

| 编号 | 前置/操作 | 预期/调查项 | 层级 | 状态 |
| --- | --- | --- | --- | --- |
| P01 | 七个原始文件和副本 | 原件不变，副本字节一致 | 文件摘要 | 通过：设备逐字节核对，结束后原件 SHA-256 再核对 |
| P02 | 第一轮七张从相册点击 | 观察过渡与清晰图的尺寸、位置、像素来源 | 实际 UI、录屏、绘制观察 | 完成：7/7 复现偏小，随后转为正确尺寸 |
| P03 | 第二轮重新打开七张 | 比较缓存命中时是否还出现缩小 | 实际 UI、录屏、绘制观察 | 完成：7/7 内层命中清晰图磁盘缓存，跳过 512 请求；不代表最前面的 200 cover 不会短暂出现 |
| P04 | 读取平台 200/512 缩略图 | 核对是否保留 EXIF 黑边及方向 | 平台真实调用 | 完成：14/14 返回 120×160，均保留左右黑边 |
| P05 | 可见模拟器安装 trace 包 | 与主包并存，安装产物一致，保留手动查看 | 设备 | 通过：两个安装 APK 与产物逐字节一致；原主包 SHA-256 未变 |

监听使用现有 trace 构建开关，加上 AndroidTest 绘制观察器。观察器不设置图片、矩阵、alpha 或加载策略；保存小尺寸 drawable 会带来诊断开销，本轮时间不作为正常性能指标。

## 实际打开过程与结论

1. 网格进入查看器时，最前面的 200px cover 命中网格缓存。日志显示它实际上是 120×160 的 `MediaStoreThumbnail`，带有左右黑边。
2. 第一轮内层清晰图缓存未命中，开始原图解码，并请求名义为 512px 的过渡预览。平台日志明确记录 `target=512x512 result=120x160`；绘制观察器捕获的实际 bitmap 也是 120×160。
3. 120×160 整张预览在 1080×2400 视图中按 9 倍适屏，drawable 范围为 `[0,480]–[1080,1920]`。但里面真正的照片内容只有约 108×160 或 106×160，显示宽度约 972 或 954，高度 1440。因此看到四周空得更多，比例仍大致正确。
4. 原图解码完成后，替换为 1080×1620（Canon）或 1080×1619（Sony）的清晰预览，范围变为 `[0,390]–[1080,2010]` 或 `[0,390.5]–[1080,2009.5]`。这一步同时改变清晰度与照片的可见大小。
5. 两阶段 ImageView 均为 `alpha=1`、`scaleX=scaleY=1`、平移为 0，矩阵符合 FIT_CENTER 计算。没有证据显示是手势缩放或记忆缩放状态造成了这一现象。

系统缩略图与提取出的 EXIF 预览旋转后尺寸相同；像素对比相关系数为 0.9978–0.9986（系统缓存重编码产生差异）。这一对照与平台实现、实际加载日志相符。

| 首轮顺序 | 文件 | 内层小图首次绘制/点击后 ms | 清晰图首次绘制/点击后 ms | 清晰图尺寸 |
| --- | --- | --- | --- | --- |
| 1 | 2020-12-10 185722(3).jpg | 652 | 1017 | 1080×1620 |
| 2 | 2020-12-10 185702(1).jpg | 531 | 685 | 1080×1620 |
| 3 | 2020-12-10 185701(1).jpg | 402 | 646 | 1080×1620 |
| 4 | DSC01113.JPG | 293 | 562 | 1080×1619 |
| 5 | DSC01110.JPG | 351 | 678 | 1080×1619 |
| 6 | DSC01108.JPG | 338 | 655 | 1080×1619 |
| 7 | DSC01107.JPG | 306 | 658 | 1080×1619 |

以上是开启 trace 和录屏时的单轮观察时间，仅用于对应阶段。第二轮七张内层直接绘制正确尺寸的清晰图，点击后 228–302ms；最前面的 Compose cover 仍可能在清晰图就绪前短暂出现，不能把“内层跳过缩略图”描述为“完全没有任何过渡图”。

## 修复方向（尚未实施）

应在过渡预览的比例处理处，根据原图的真实宽高和旋转信息处理内嵌预览的填充区域，使 200 cover 和 512 中间预览都与最终适屏范围一致。不能只修内层 512 请求，也不能按“看到黑像素就裁掉”的规则处理所有图片，以免裁掉照片本身的暗部。另一个可比较的方向是对比例不一致的内嵌缩略图使用直接从原图生成的低清预览，并检查解码开销。

本轮仅调查，未选择或实施修复方案，未更改产品显示代码。新增内容限于测试观察器、测试入口、采集脚本和报告。为让 trace 测试包与旧测试包并存，将两个仅用于故障注入的测试 Provider authority 改为按测试包名生成，并同步其测试调用 URI。初次安装发生的 authority 冲突是测试组件安装问题，已解决，不计作图片加载缺陷。

## 设备和证据

- 设备：可见模拟器 `GalleryAlbumSearchQA20261019` / `emulator-5554`，Android API 36，1080×2400。
- 原始字节副本保留在 `/sdcard/Pictures/PreviewSizeQA_20261020/`。从真实 `MainScaffold` 的样本相册点击进入实际查看器，执行 7 张×2 轮。测试只将启动页定位到该相册，没有绕过查看器组件或更改加载逻辑。
- `scratch/viewer-preview-size-20261020/loading.mp4`：100.11 秒原始录屏。
- `scratch/viewer-preview-size-20261020/first-photo-transition.mp4`：从原始录屏截取的 3.3 秒首张打开片段。
- `transition-frame.png`（11.65s）与 `clear-frame.png`（12.6s）：同一首张打开过程的原始帧。
- `load-logcat.txt`、`preview-geometry/events.jsonl`、`geometry-summary.json`：各阶段来源、bitmap 尺寸、矩阵和显示范围。
- `preview-geometry/`：七张中间预览的实际像素、14 次平台缩略图调用结果、14 张最终截图。
- `embedded-system-comparison.json`：EXIF 与平台预览的像素对照。
- `device-copies.json`、`final-verification.json`：原件/副本核验及安装 APK 核验。
- Trace 主包 `com.pixel.gallery.codextrace` SHA-256：`cba1c8478d1289df77b8133a077c7e038a99344cfcc6ec2aa9ac364183d51acd`。测试包：`990535e789fcc0c23516ffa1e97958524b6a2a3ceb8028ea06ea960cce15f8b9`。
- 原主包仍为 `com.pixel.gallery.multitask.test`，SHA-256 `882203abd51a7897ea8ec39223a520251401a0266d8b00ed3add44e60d170ccd`，未覆盖。
- 测试结束后以正常 Activity 打开首张样本，关闭通知访问提示，保留可见模拟器供手动查看。后台本地日志采集仍在运行，进程与日志路径见 `live-listener.json`，日志为 `live-load-logcat.txt`。测试观察器只在本轮设备测试中生效，后续手动操作由 trace 内置加载日志记录。

本次未在真实手机或其他 Android 版本复测，不将模拟器的时间作为手机性能结论。
