# 图片加载诊断包

用途为定位三星与一加在适屏清晰图、高清瓦片加载过程中的耗时差异。

## 构建配置

- 基线为当前 feature-3 的 4.3.6.8；变体参数为 `viewerTestVariant=nocachetrace`，不能与 `officialRelease=true` 同用。
- 独立包名为 `com.pixel.gallery.loadtrace.nocache`，名称为 Pixel Load Diagnostic。
- 使用 release 构建并启用 shell profiling，含 arm64-v8a、x86_64。
- 以编译常量关闭缓存，设置页调整缓存大小不能重新启用。

## 缓存处理

- Glide 默认跳过内存缓存；所有显式磁盘策略在诊断变体返回 NONE。底层内存容量为零，磁盘存储使用空实现，位图池及数组池关闭。
- 网格和查看器持久缩略图不读不写；跳过系统缩略图 API，直接读取源文件，仍允许原文件内嵌的 EXIF 预览。
- 瓦片磁盘缓存不读不写；不安排延迟缓存写入；离屏瓦片保留容量为零。当前屏幕绘制所需的预览、基础层和可见瓦片保留至该视图释放。
- 查看器不预建邻页，不在非当前页发起预览解码；不执行清晰预览的缓存探测。
- 区域解码直接使用原路径或 URI，不经 Glide 下载缓存文件；平台区域解码路径禁用 JPEG、PNG、WebP、HEIF 索引读取。TIFF、RAW 的索引读取及 RAW 预览字节 LRU 同样禁用；索引菜单隐藏。
- 系统文件页缓存不受此包控制，不清理系统或其他应用数据。

## 诊断事件

- 启动事件 NO_CACHE_POLICY 记录实际包名及编译策略。
- 默认 Glide 监听器通过 DIAGNOSTIC_IMAGE_READY 记录来源、尺寸、配置和分配字节数，缓存来源记为 NO_CACHE_POLICY_VIOLATION。页面自定义 `.listener()` 会替换默认监听器，这些预览以已有 PREVIEW_READY、INTERMEDIATE_PREVIEW_READY 中的 source 字段核对来源。
- FIT_SOURCE_SUBMIT、既有预览就绪及预绘制事件连接请求与绘制阶段。
- NATIVE_FIT_EXIF_DONE、NATIVE_FIT_DECODE_DONE、NATIVE_FIT_ROTATE_DONE 分别记录 EXIF、原生解码与方向处理；保留原生 header/alloc/setup/scan/finish 分项。
- 工作计时增加同一线程的 CPU 时间；跨线程完成的 cpuNs 为 -1，不作为 CPU 用时。
- 缩略图源读取记录调用数、字节数及底层读取用时；平台瓦片解码增加独立起止记录。
- 瓦片 TASK_ENQUEUE 与 TASK_START 记录排队时间，保留提交、完成及绘制记录。

## 验证

安装包为 `artifacts/PixelLoadDiagnostic-4.3.6.8-nocache-arm64-x86_64.apk`，31,057,654 字节，versionCode 4085。SHA256 为 `c8034fa0dde5d9130a81daf36b8282b72326f03e78303afd61b1811881d16abb`。模拟器安装文件回拉后的哈希与交付包一致；签名校验通过，清单中的独立包名、双 ABI、shell profiling 均已核对。

最终主程序已完成打包。合并构建在测试包的 L8 阶段遇到本机原生内存不足；降低内存配置后，单独的 `assembleReleaseAndroidTest` 构建成功，沿用同一主程序输出。未完成全量静态分析，保留失败及中断日志。

模拟器 `emulator-5554` 的 `no-cache-diagnostic` 专项检查通过，原始结果为 `scratch/load-diagnostic-device-checks.log`。

| 检查 | 实际结果 |
|---|---|
| 独立包名及诊断编译开关 | 通过 |
| 同一 Glide 模型重复加载，第一张仍保持活动，修改源像素 | 3 次均为 LOCAL，新像素可见 |
| 原生 JPEG 适屏重复读取，修改源像素 | 两次均取得当时的源像素 |
| 预置绿色网格缓存、源图为蓝色 | 两次返回蓝色源图 |
| 预置绿色适屏缓存、源图为蓝色 | 两次返回蓝色源图 |
| 两次区域解码会话 | 源像素正确，缓存探测为 false，缓存写入返回 false |
| 图片缓存目录文件核对 | 仅存在检查主动放入的两个夹具文件，无新增缓存文件；结束后删除这两个夹具 |
| 位图复用池 | maxSize 为 0 |

真实界面使用自己生成的 2400×1800 JPEG、PNG。初次外部打开的 4 次重启采集得到 151 次默认监听器成功回调，均为 LOCAL；该采集被首次启动的后台权限提示遮挡，未完成放大验收。

关闭提示后，从真实网格分别打开 PNG、JPEG 并双击放大，结果通过，适屏与放大截图均已查看。记录到 2 次 FIT_PREVIEW_PREDRAW、1 次原生 JPEG 适屏解码、2 次瓦片任务启动及 2 次平台区域解码完成；两次瓦片均直接使用原路径。此组预览由页面自定义监听器记录来源，不在默认监听器计数内。未发现缓存来源事件。

界面证据为 `scratch/image-load-nocache-20261114/ui/grid-*-fit.png`、`grid-*-zoom.png`；应用事件和约 30 秒的 Perfetto 记录位于同目录 `grid-capture/`。采集工具在模拟器完成实际采集与回拉。三星和一加的实际性能归因尚未执行。

采集工具为 `tools/capture_image_load_diagnostic.py`，必须显式传入设备序列号和新的输出目录；采集应用事件和 Perfetto，最长 60 秒。不清除设备日志、不自动操作用户照片。
