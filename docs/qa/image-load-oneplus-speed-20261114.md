# 一加普通模式的加载加速排查

用户要求一加达到三星旧版的响应与清晰图加载体验，拒绝延后详情页首帧、放大网格缩略图和增加预览位图内存。这两项显示实验已从源码删除，保留逐行适屏解码。高性能模式保持关闭。

## 解码路径对照

一加 CPH2745，1272×2772，独立无缓存包。11 张授权原图、三个模式、三轮，轮换模式顺序，共 99 次。两条 Glide 路径均禁用内存和磁盘缓存，并在每次计时前清空池。采样 native heap 增量，输出位图在每轮后释放；不是整应用峰值或正式界面完成时间。

三种模式依次为原生粗采样加 Bitmap.createScaledBitmap、低内存原生适屏路径经 Glide 交付、Glide 系统标准路径。最终尺寸、ARGB_8888 格式及 allocationByteCount 逐图一致。

| 文件编号 | 原生后单独缩放 ms | 低内存原生 Glide ms | 标准 Glide ms | 两条 Glide 的采样 heap 增量 MB |
|---|---:|---:|---:|---:|
| 01 | 176.6 | 164.7 | 168.4 | 10.76 / 20.98 |
| 02 | 151.3 | 104.8 | 111.5 | 5.41 / 17.89 |
| 03 | 205.7 | 189.4 | 188.1 | 10.76 / 23.09 |
| 04 | 157.6 | 136.1 | 136.4 | 10.76 / 23.16 |
| 05 | 148.6 | 135.9 | 140.8 | 10.63 / 23.08 |
| 06 | 164.7 | 130.2 | 140.5 | 10.63 / 23.08 |
| 07 | 133.1 | 130.6 | 134.1 | 10.63 / 20.98 |
| 08 | 259.4 | 220.1 | 218.8 | 10.76 / 23.08 |
| 09 | 266.5 | 245.5 | 245.6 | 10.63 / 23.08 |
| 10 | 224.4 | 184.3 | 182.8 | 5.41 / 19.98 |
| 11 | 123.4 | 108.1 | 112.8 | 10.85 / 25.25 |

系统与低内存原生路径的逐图中位时间接近，标准/原生逐图时间比的中位数为 1.022。未据此将一加切换到系统解码。低内存原生比原先粗采样后缩放的逐图耗时下降中位数为 12.4%，最终位图字节数未增加。

## 开图前全库遍历

对一加实际打开 09 图三次采样 Java 方法栈。`selectedAlbumEntries` 的 remember 以 currentScreen 为 key；Home 与 Viewer 切换会使其重新执行，即使 selectedAlbumPaths 与 selectedLabelMediaIds 都为空，仍遍历 131,372 条 allPhotos，执行 mediaParentPath、路径字符串处理、Long 装箱与集合查找。

在三次点击至清晰图请求提交的窗口内，采样主线程 CPU 合计约 806.6 ms，MainScaffoldContent 包含约 624.0 ms，其中 mediaParentPath 包含约 235.3 ms。此采样开启方法分析，有额外开销，仅用于定位热点。

另录制不启用方法采样的 22 次无缓存基线，逐次核对 MediaStore ID。中位时间为详情页 attach 212 ms、提交清晰图请求 243.5 ms、完整请求 158.5 ms、点击至清晰预览预绘制 406 ms。

当前修改只为无相册选择且无标签媒体选择的情况增加 emptyList 快速返回，跳过整库扫描；非空选择继续走原有分支。没有增加缓存或图像分配，没有更改显示时序。修复后的同组界面对照见下一节。

## 调试授权

已确认电脑公钥在一加保存的授权中。保留 adb_enabled=1，将 adb_allowed_connection_time 从系统默认改为 0（已授权电脑不自动过期），主动 reconnect 后设备自动恢复为已授权 device，shell 身份可用。此设置不自动开关 USB 调试开关；开关保持开启。

## 原始材料

`scratch/image-blackframe-oneplus-speed-20261114/` 保存 decoder-path-probe.json（99 次）、decoder-path-summary.json、ui-method-profile.trace、ui-method-profile-summary.json、speed-before-11-pairs.mp4、speed-before-summary.json、授权前后记录与构建日志。方法轨迹本地保存字节数与设备临时文件核对一致后，已删除设备临时副本。

## 空选择快速返回的实机对照结果

同一一加普通模式、同一独立诊断包策略、同一组 11 张、每图连续两次。两个构建都已经删除开页等待与网格像素交接，均保留原生逐行适屏解码；两者唯一源码差异是 selectedAlbumEntries 的空选择快速返回。所有 44 次完整预览来源均为 LOCAL。每次输入前检查前台包名，测试后按全部 MediaStore ID 核对顺序。

| 中位时间 | 修改前 | 修改后 |
|---|---:|---:|
| 点击至 Viewer attach | 212 ms | 25.5 ms |
| 点击至清晰图请求提交 | 243.5 ms | 52 ms |
| 完整预览请求 | 158.5 ms | 157.5 ms |
| 原生 JPEG 解码 | 152.98 ms | 152.75 ms |
| 点击至清晰预览就绪 | 401 ms | 217 ms |
| 点击至清晰预览预绘制 | 406 ms | 224.5 ms |

完整 22 次对照显示改善来自请求开始前的主线程工作，原生耗时基本不变。逐图最终位图尺寸和 allocationByteCount 与修改前相同（4,314,624～9,707,904 字节），没有新增位图或缓存。先前低内存解码的 25 组像素检查与 18 次内存采样记录仍适用于未改动的原生实现。

修改前录屏 1,484 帧、修改后 1,553 帧，中央照片区域均未检出全黑帧，检测参数 blackframe=amount=98:threshold=32，并查看修改后首轮联系图。两侧原有 opening-ready listener 均已修为 addListener，避免诊断跟踪 listener 覆盖它。零黑帧不是本次空选择修复独有的变化；本次修复缩短的是点击至开始图片工作及清晰图呈现的时间。

两份视频为变帧率录屏，最大 120 fps 参数不作为恒定采样频率。没有测量触摸发生前的硬件延迟或以预绘制代替系统实际扫描呈现。

三星旧无缓存诊断包在同组照片、1440×3120 下清晰预览预绘制中位数为 177.5 ms，一加修改后为 224.5 ms。逐图将各两次 ready 的中位数配对，一加与三星的就绪差值中位数为 36.5 ms。两机输出分辨率不同；这项结果记录剩余差距，尚未完成一加正式缓存路径的修改后体验验证。

首次修改后录制进程在 13 次操作后中断，未生成完整 actions.json；speed-after-* 文件只留作中断记录。最终使用 speed-after-complete-* 的完整 22 次结果。

## 安装与设置核验

当前一加诊断包为 artifacts/PixelLoadDiagnostic-exact-fit-speed-nocache.apk，31,061,790 字节，SHA256 ffdf122d05adc153194ec3e85b19ed803aa4d6c36f4d9488c77b4cff027a7e83。已核对手机安装文件哈希一致。MainScaffoldContent 空选择修复的打包任务成功，日志 selection-fast-path-build.log。

正式版仍为 4.3.6.6（4083），lastUpdateTime=2026-11-10 16:05:46，未安装替换。USB 保持亮屏已恢复为 0；高性能模式仍为 0。USB 调试保持开启，已授权电脑的过期时间保持 0，已实测 reconnect 自动授权。

一加原先安装的延后首帧实验包已被当前诊断包替换。三星在用户改接一加前已安装过一次网格像素交接实验，仅涉及独立诊断包，正式版未变；三星诊断包尚需在其再次接入时恢复，不能把该试验包作为后续基线。

原始材料包括 speed-before-summary.json、speed-after-complete-summary.json、selection-speed-comparison.json、video-comparison.json、samsung-cross-device-speed.json、final-artifact.json 和两份完整录屏。源图保持原状。

## selectedAlbumEntries 功能依赖核查

后续已按用户要求改为点击查重后才收集媒体，删除本节所述的主界面预计算；最新实现和测试见 [相册选择后按需收集查重照片](album-selection-on-demand-dedup-20261114.md)。本节保留当时依赖核查依据。

按用户要求逐一追查引用。该集合属于“选中的相册／标签中查找重复文件”功能，不能整段删除。

- MainScaffold.kt 中 selectedAlbumEntries 只被相册选择工具栏使用两次：以 size > 1 决定查重菜单是否可用，将实际条目交给 DuplicateViewModel.start(entries, ALL_FILES, duplicate_scope_selected_albums)。
- 普通相册通过 mediaParentPath 精确匹配父目录；标签通过 selectedLabelMediaIds 并入；隐藏相册仍使用 isInsideMediaFolder 判定其媒体。非空选择的这些原有分支均未改变。
- AlbumSelectionTopBar.kt 在用户点击查重菜单后执行 onFindDuplicates。DuplicateViewModel.kt 将传入条目交给 DuplicateRepository；ALL_FILES 范围以该传入集合为扫描范围。
- 隐藏、取消隐藏、排除、置顶、分组、封面等操作分别直接使用 selectedAlbumPaths／selectedLabelAlbumIds，没有读取 selectedAlbumEntries。照片详情页的图片模型也没有读取该集合。

当前只在所选相册路径和所选标签媒体 ID 都为空时直接返回 emptyList。原判定在同样条件下逐条检查后也只能产生空列表，所以这次修改省掉无结果的遍历，保留了查重的数据收集机制。此次为源码引用与调用链核查；未执行实际查重或批量文件操作，未删除该功能代码。
