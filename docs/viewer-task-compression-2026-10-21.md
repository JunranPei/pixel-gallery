# 查看器处理时间压缩

用户要求：把一轮工作集中在一起短时间内完成，避免拖长。JPEG实验已提交b36af23b，本轮只调整任务调度。图片编码、画质、尺寸、缓存身份、预加载范围与线程优先级均不改。

## 独立清单

- ~~对照本地Simple的任务提交方式、默认并发和已有compressed实验基线。~~
- ~~移除逐请求自定义Engine分流，改为整个查看器统一的并发窗口：源解码至少4路、缓存3路。~~
- ~~支持查看器先于Glide初始化、多窗口和重复关闭；退出最后一个查看器后恢复用户源线程设置及缓存1路。~~
- ~~验证整个批次的并发与结束时间：两设备源任务和缓存任务均重叠推进，记录最后一项完成时间。~~
- ~~验证取消、合并、失败恢复、两设备回归及14次真实相册显示；227项单测通过。~~
- ~~构建并安装到模拟器/三星，实际APK哈希一致；三星直接清测试缓存并重开监听。~~
- 真实能耗：未测，不以受控延迟或瞬时整机电流代替。

## 依据与实现

本地Simple的PhotoFragment.loadImage先读方向，随后各页面发出适屏Glide请求，ViewPager保留两侧各2页；未找到专用Glide调度器配置，采用其依赖Glide的默认并发。它不是逐请求自定义Engine来实现集中处理。历史compressed实验使用源解码至少4线程、缓存3线程，见docs_archive/viewer_compressed_power_test_conclusions_20260801.md。

本轮按整个ViewerScreen生命周期建立集中处理窗口：源池至少4线程（用户原设置更大则保留），cache池3线程；现有Viewer请求不再绕到无上限source pool。所有既有Glide加载工作共用标准Glide Engine、缓存及请求去重，让源读取、解码、变换与缓存阶段在同一个窗口内尽早完成。无新增预览、不改变页面预加载和瓦片启动条件，不改CPU线程优先级。

关闭最后一个查看器时恢复源池的持久设置和cache单线程；正在进行的任务继续完成，不打断图片解码。缩小core和max后，多余worker在当前任务结束后退出，后续网格工作回到原配置。窗口可先于Glide初始化，多个查看器引用计数，重复关闭幂等。

保留标准Glide Engine，不复制或替换它。仅用一个窄Java桥接类将可调ThreadPoolExecutor交给GlideExecutor；Comparable队列、背景线程nice值、磁盘线程网络StrictMode与Glide4.16保持一致。以后升级Glide须核对该包内构造接口。线程池控制集中在ViewerGlideTaskCompression.kt，ViewerScreen只登记进入/退出，GlideModule只登记线程池。

当前页面、200级过渡、512/适屏、元数据与按需高清层的功能和条件均保留。改变调度不会让必需串行步骤凭空并行，也不能保证每张巨型JPEG在任意时间内完成。

## 验收矩阵

| 编号 | 操作 | 独立预期 | 结果 |
| --- | --- | --- | --- |
| C01 | 三个真实缓存文件并发读解码 | 常态峰值1，窗口内峰值3；零原件读取 | 两设备通过；三星458→154ms，模拟器463→159ms |
| C02 | 一批5个source和3个cache受控任务 | 窗口source至少4、cache3同时推进，整批更早结束 | 两设备通过；三星旧策略460→314ms，模拟器462→307ms |
| C03 | 多窗口、晚初始化、重复关闭和异常退出 | 最后一个窗口关闭才恢复，用户较大线程设置不被降低 | 3项专用单测及两设备多窗口/恢复检查通过 |
| C04 | 相同模型/尺寸同时请求 | 一个解码及共享结果 | 两设备通过，实测仅一个decoder调用、两target复用同一Bitmap |
| C05 | 排队取消、缓存失败后重试 | 取消不执行解码，后续任务正常完成 | 两设备通过，零原件读取 |
| C06 | 原生/平台回退、缓存版本失效 | 原输出、取消及缓存语义保留 | 两设备NativeFit及13组缩略桥接回归通过；管道测试保活修正见下 |
| C07 | 真实相册7张各开两轮 | 比例、旋转、显示和缓存重开无回归 | 14次通过，最大边缘误差0.582px，全部7次重开资源缓存命中 |

受控任务每项150ms只是制造可重复的工作窗口，不能把等待时间差当成真实JPEG算法提速或省电。实际能耗及巨图整批耗时仍须另行测量。

官方依据：[GlideExecutor4.16](https://github.com/bumptech/glide/blob/v4.16.0/library/src/main/java/com/bumptech/glide/load/engine/executor/GlideExecutor.java)、[EngineJob4.16](https://github.com/bumptech/glide/blob/v4.16.0/library/src/main/java/com/bumptech/glide/load/engine/EngineJob.java)。本地Simple源文件：scratch/simple-gallery-powertrace/app/src/main/kotlin/com/simplemobiletools/gallery/pro/fragments/PhotoFragment.kt与activities/ViewPagerActivity.kt。未安装此前复杂分流候选，已撤回其代码。


## 结果与证据

构建window-build.log成功，227项单测无失败；其中ViewerTaskWindowTest的3项专测覆盖晚初始化、多窗口/重复关闭、较大用户线程设置、异常和已关闭池的退出。两设备实际Glide读取3个已写入的资源文件，并执行5个源任务+3个缓存任务的整批对照，每任务150ms受控停留。旧查看器策略是无上限source+单线程cache，新策略4source+3cache；三星整批460→314ms、模拟器462→307ms。这里没有重新压缩真实照片，也不能把该数值称作真实JPEG加载时延下降或焦耳节省。

两设备窗口测试、NativeFit及缩略图回归和14次UI记录：scratch/viewer-task-compression-20261021下的*-window-tests.log、*-native-final.log、*-thumbnail-regression.log、emulator-real-viewer.log、ui-geometry/analysis.json及截图。真实相册7张×2轮比例均正确，5个支持项首开使用新窗口的source-configured队列，7次重开资源缓存命中；已查看旋转图实际最终截图。原照片和QA原件均保持不动。

三星最初NativeFit回归曾两次在慢管道回退等待30秒超时，原始失败保留在R5CY13T5Z3J-native-regression.log和samsung-native-recheck-1.log。该夹具由独立测试包的异步线程写入管道，进程状态记录显示测试提供器会进入isFrozen=true的缓存态；没有在首次超时瞬间取得全部线程栈，不能把冻结写成已证明的唯一原因。现测试端持有稳定ContentProviderClient覆盖完整异步读取，三星最终连续三次、模拟器一次回退/方向/缓存测试通过，未改产品回退逻辑。此项是夹具生命周期修正，不算产品缺陷修复，失败证据未丢弃。

## 安装

APK：scratch/viewer-task-compression-20261021/trace-window-debug.apk；SHA256为85bbb6619f12c583256d28d4af688fc1334afc9998d04e48d8efaaf7dd370351。三星R5CY13T5Z3J与emulator-5554实际安装哈希一致，见window-installed-verification.json。

三星监听会话samsung-window-20261020T173457Z，应用PID10351、监听PID116880。只清测试包cache：700个文件、63567KiB，启动前cache子项0、4KiB；files/databases/shared_prefs大小前后相同。保留生产包、用户设置及所有照片，系统缓存未清。未自动打开用户照片。模拟器应用已重新打开供检查。

本轮策略实现和功能验证完成；真实图像整批耗时及受控整机能耗仍未验收，不宣称省电已经得到证明。此前JPEG保存点为b36af23b；本轮随4.3.4.15提交。正式构建默认启用已验证的JPEG自适应适屏路径，同时带上整轮调度；旧DC-only实验仍保持隔离。正式包关闭测试监听，arm64-v8a版本码4065，沿用现有签名。
