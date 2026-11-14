# 一加图片加载过程分析

设备为 CPH2745，Android 16，设备报告 SoC 为 SM8850。诊断包为 `com.pixel.gallery.loadtrace.nocache`，versionCode 4085，设备安装文件已核对 SHA256。

## 采集与保存

采集已按停止请求结束，状态文件为 stopped，requestedStop 为 true。8 段系统轨迹及连续应用日志已保存到：

`scratch/image-load-nocache-20261114/oneplus-live-20261113T155230Z/`

8 份设备端轨迹副本在逐个核对本地保存文件大小后删除，本地轨迹完整保留，共 266,458,828 字节。用户原始图片仅作读取，未修改。

连续日志包含 39 次 ENTRY_REQUEST、39 次 PREVIEW_START、39 次 PREVIEW_READY、39 次 FIT_PREVIEW_PREDRAW、39 次 ENTRY_END。39 次预览的 DataSource 均为 LOCAL。27 次原生 JPEG 适屏成功，11 次 JPEG 回退标准路径，1 次 PNG 标准路径。未记录到区域瓦片解码及 SSIV 瓦片任务启动。

## 加载各阶段

下表为本次不同文件的描述统计，各行样本范围不同，不能把各行中位数相加当作一张图片的过程。

| 阶段 | 样本数 | 中位数 | 范围 |
|---|---:|---:|---:|
| 点击至清晰预览请求发起 | 39 | 131 ms | 111–242 ms |
| 发出请求至原生任务开始 | 27 个成功请求 | 1 ms | 0–2 ms |
| 原生 JPEG 解码调用 | 27 | 175.122 ms | 108.224–1006.102 ms |
| 上述解码的线程 CPU 时间 | 27 | 171.568 ms | 105.535–1000.405 ms |
| 原生扫描行阶段 | 27 | 171 ms | 105–1004 ms |
| 原生工作结束至预览回调就绪 | 27 | 69 ms | 31–75 ms |
| 预览回调至预绘制 | 39 | 5 ms | 1–16 ms |
| 预绘制至关联的系统呈现 | 38 | 25.501 ms | 24.123–33.089 ms |

最后一项使用应用帧和 SurfaceFlinger display_frame_token 对应；它是系统轨迹的呈现时间，未使用外部摄像。应用日志保持 monotonic 时钟，系统轨迹以各段 clock_snapshot 的 MONOTONIC 映射转换后关联。

清晰图回调、预绘制、系统呈现分别保存，不把它们混用。缩略图回调也不直接当作模糊图出现在屏幕上的时间；例如一幅大地图的缩略图回调晚于清晰预览就绪。

## CPU 执行证据

原生解码时间大部分对应线程实际执行。解码返回后至 Glide 预览回调之间也有工作线程计算，尚未细分到具体后处理函数。

第 2 次打开为 5464×8192 JPEG，过程为：

- 点击后 154 ms 发起预览请求，155 ms 开始原生任务。
- 原生解码调用 237.566 ms，其中线程 CPU 时间 235.512 ms。
- 原生工作在点击后 395 ms 结束；预览在 466 ms 就绪。
- 系统调度记录显示，这 71 ms 内工作线程 Running 70.729 ms，Runnable 0.158 ms，Sleeping 0.113 ms。主线程同期 Running 1.765 ms。
- 预绘制在 468 ms；关联帧预绘制至系统呈现约 24.567 ms。

该样本的点击至请求阶段，主线程 Running 148.793 ms，阶段总长约 154 ms。前置主线程计算、解码、解码后工作均值得在三星上分别对照。

目前没有对 Glide 适屏缩放、资源初始化等具体函数逐一计时，因此不将解码后约 69 ms 全部归为某一个函数。

## 11 次标准路径回退

11 张 5683×8525 JPEG 的原生尝试耗时为 2–4 ms。随后标准路径的完整预览请求耗时中位数 397 ms，范围 343–477 ms。

对这 11 个原文件只读检查了 JPEG 头，均为 8-bit、3 分量、SOF0；全部携带同一份名为 Spyder4Express 的 ICC，ICC SHA256 为 `0ecf836a40e7f45368d40718122abc9ac2048e6b8a4af8f07106ae278216412f`。

在一加 Android 运行时对全部 11 张执行了与应用相同的 BitmapFactory 只读尺寸检查，全部报告 `color=Unknown (id=-1, model=RGB)`、`isSrgb=false`。对原生成功样本的对照检查报告 `sRGB IEC61966-2.1`、`isSrgb=true`。

`DcJpegPreview.decodeFit()` 在 `bounds.outColorSpace?.isSrgb != true` 时直接返回 null，因此这些文件通过颜色空间保护分支转至标准解码。未修改文件颜色配置，也未绕过颜色处理。

原始证据为 `fallback-jpeg-headers.json`、`fallback-icc-profiles.json`、`android-fallback-colors-all.txt` 和 `android-bounds-color-probe.txt`。

## 大地图样本

| 文件 | 尺寸 | 实际处理 |
|---|---|---|
| 01_东汉_十三刺史部20200717.jpg | 13071×12829 | 原生解码 1006.102 ms，CPU 1000.405 ms；点击至预绘制 1196 ms |
| 宋政和元年（公元1111年）地图1.jpg | 17008×18826 | 原生解码 865.642 ms，CPU 860.059 ms；点击至预绘制 1072 ms |
| 1605年日本诸大名之配置｜中海帝皇制作.PNG | 12102×6137 | 标准路径完整预览请求 1449 ms；点击至预绘制 1612 ms。未将这个完整请求时间写成单独 PNG 解码时间 |

## 证据范围与待处理项

- 应用日志覆盖 39 次完整打开；系统轨迹关联到 38 次清晰预览呈现。分段停止、回拉和下段启动之间存在间隙，部分阶段有缺项，不补算为正常。
- 轨迹 stats 中存在 info 级 traced_chunks_discarded；未发现本次查询范围内的 error 级解析统计。原始统计保存在各段 overview.txt。
- 本次没有瓦片解码记录，放大、拖动部分需要补采。
- 三星尚未执行同图采集，当前报告只描述一加实际执行的过程。
- 本次未修改产品加载算法及用户照片。

机器可读汇总为 `entry-summary.json`、`entry-summary.csv`、`analysis-summary.json`、`phase-state-summary.json`；阶段查询与原始结果均保存在采集目录。
