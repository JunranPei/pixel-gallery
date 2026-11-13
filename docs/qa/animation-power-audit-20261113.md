# 应用动画功耗相关测试

本次覆盖菜单、导航、搜索、选择、分组、重排、面板、设置、看图手势、视频控件和后台中断。可见模拟器为 GalleryAlbumSearchQA20261019:5554，隔离包 com.pixel.gallery.codexclean，版本 4.3.6.7-auto-multitask / 2084。

测试按当前实现采集进程 CPU 时间、绘制帧数、帧完成耗时和独立静置窗口。截图、界面树均在计数窗口之后采集；手柄底端截图为持续按住期间采集。59 项共享动画/手势单元测试通过；原生多指输入的 8 项静置检查均为零新增帧，功能检查为 7 项通过、1 项失败。

## 已定位的问题

三点菜单静止时没有新绘制帧，但仍有持续主线程帧回调。20 秒系统轨迹中，菜单打开的稳定窗口取 5.00 秒，共 300 次 Choreographer 回调，300 次 Recomposer:animation / Recomposer:recompose 片段，主线程运行 401.46 ms；关闭菜单后的 4.86 秒窗口为零帧回调。包含边界采样的进程 CPU 时间分别为 490 ms / 5.40 秒、60 ms / 5.26 秒。轨迹见 motion-held-menu.perfetto-trace、held-menu-windows.json、held-menu-summary.csv。

当前 APK 内 Compose UI 为 1.11.0-beta02。对应 classes.jar 的 Popup 协程调用 withInfiniteAnimationFrameNanos 与 pollForLocationOnScreenChange，字节码保存在 compose-popup-poll-bytecode.txt。此实现与测得的持续回调吻合。倍速弹层同样使用 Popup，其静置 5.114 秒进程 CPU 为 380 ms，关闭后 3.113 秒为 20 ms。后续优化优先处理弹窗的位置检查，改动后用相同轨迹复测。本次未改动该组件。

外部打开的 600 x 1200 图片在原始媒体尺寸为零时，双击缩放到 fit 的 0.01 倍，截图中只剩中心小点；该图片原始像素比例应约为 fit 的 0.556 倍。MainScaffold 的 External 条目宽高为零，SimpleSubsamplingImageView 将其按 1 x 1 计算，最终触及 0.01 的下限。gesture-final 保留原始采样；gesture-adjudicated.json 纠正了首次仅检查“缩放值改变”的过宽断言。严格断言的再次运行见 gesture-regression-confirmed.log。本次未修改图片缩放实现。

## 实测范围

| 交互 | 动画结束后的静置结果 | 实际确认 |
| --- | --- | --- |
| 设置选项与主题 | 0 帧 / 3.106 秒，CPU 30 ms | Display 选项、Dark/System；无动画/淡入缩放/缩略图三选项 |
| 三点菜单 | 0 帧 / 3.116 秒，CPU 210 ms | 三次打开/关闭；静止仍有帧回调，见下文 |
| 排序弹层 | 0 帧 / 3.128 秒，CPU 10 ms | 打开、退出 |
| 主页 pager | 0 帧 / 3.138 秒，CPU 30 ms | Photos / Albums 双向切换 |
| 导航横拖 | 0 帧 / 3.146 秒，CPU 20 ms | 展开、收起，Lock 入口出现/消失 |
| 搜索工具栏 | 0 帧 / 3.103 秒，CPU 20 ms | 短拖回弹、进入搜索、退出；聚焦时另有光标绘制 |
| 照片选择 | 0 帧 / 3.123 秒，CPU 30 ms | 长按、选中工具栏出现、取消 |
| 相册选择 | 0 帧 / 3.114 秒，CPU 30 ms | 长按、选中、取消 |
| 照片列表滚动 | 0 帧 / 3.124 秒，CPU 20 ms | 惯性滚动、返回 |
| 相册分组 | 0 帧 / 3.123 秒，CPU 30 ms | Motion Audit 的两个自有相册展开、折叠 |
| 相册重排 | 0 帧 / 3.121 秒，CPU 20 ms | 同一分区内两个置顶相册实际交换位置 |
| 快速整理目标重排 | 0 帧 / 5.109 秒，CPU 50 ms | Pictures / Movies 实际交换顺序，5 秒静置 |
| 标签/分组/移动目标面板 | 0 帧 / 5.135 秒，CPU 50 ms | 三类面板打开、收起、退出；未执行文件移动 |
| 相册设置对话框 | 0 帧 / 3.104 秒，CPU 20 ms | 打开、取消；未提交改名 |
| 详情无动画 | 0 帧 / 3.104 秒，CPU 30 ms | 等待 8 秒后测独立静置窗口 |
| 详情缩放淡入 | 0 帧 / 3.107 秒，CPU 30 ms | 等待 8 秒后测独立静置窗口 |
| 详情缩略图展开 | 0 帧 / 3.133 秒，CPU 20 ms | 等待 8 秒后测独立静置窗口 |
| 看图工具栏/底部操作 | 0 帧 / 3.133 秒，CPU 30 ms | 点击显隐、纵向展开，额外操作出现 |
| 看图 pager | 0 帧 / 3.18 秒，CPU 20 ms | 双向滑动、停止 |
| 双击/捏合/旋转/取消 | 8 项均 0 帧 / 3 秒 | 8 项原生输入的静置均 0 帧；双击原始尺寸功能失败，其余 7 项通过 |
| 拖动关闭 | 0 帧 / 3.116 秒，CPU 50 ms | 短拖回弹、长拖关闭；原生 ACTION_CANCEL 另测 |
| 信息半页 | 0 帧 / 3.245 秒，CPU 30 ms | Details 和 600 x 1200 文件信息已确认，返回关闭 |
| 视频工具条/倍速 | 0 帧 / 5.114 秒，CPU 380 ms | 真实视频暂停；展开、倍速弹层、关闭、收起；弹层仍有 CPU 唤醒 |
| 后台中断/恢复 | 0 帧 / 3.115 秒，CPU 40 ms | 打开过程中进入桌面，再恢复 |
| 系统动画关闭 | 0 帧 / 3.101 秒，CPU 50 ms | 菜单、详情打开；结束后恢复原系统值 |
| 菜单中断/重开 | 0 帧 / 3.104 秒，CPU 210 ms | 连续点按，最终菜单可用 |

搜索框聚焦时首次 3.119 秒采到 24 个新增帧，退出搜索后为零。移动目标面板首次打开的早期窗口有 121 个新增帧，延长等待后的 5.135 秒窗口为零。看图工具栏有 3 秒自动隐藏及系统栏变化，早期窗口另采到 10–23 帧；三种打开效果最终统一等待 8 秒后再取静置窗口。上述早期采样均保留，未归为持续动画泄漏。系统动画比例设为零后，菜单打开/关闭的绘制分别降为 9/4 帧，菜单稳定窗口的额外 CPU 唤醒仍存在；原系统值 null 已恢复。

## 主页滚动条修改与验收

主页照片、相册各自传入独立的 scrollbarBottomPadding=0；网格的 contentBottomPadding、内容 PaddingValues 保留原值。选择面板和快速整理停靠栏仍保留自身占位。其他页面沿用原来的滚动条边距。

照片页旧手柄范围为 x=1044–1068、y=1980–2084。修改后照片页、相册页均为 x=1044–1068、y=2295–2399；屏幕高度 2400。拖动实际到达最后一项，照片页末尾三个日期的位置与修改前一致（March 12 为 y=318，February 21 为 y=723，February 1 为 y=1533），网格底部留白保持。截图和节点证据见 home-photos-scrollbar-after-held-bottom.png、home-albums-scrollbar-after-held-bottom.png、scrollbar-results.json。

构建 :app:assembleDebug 与 :app:assembleDebugAndroidTest 通过，安装成功。修改后的 APK 哈希为 fae8b724aa3ff4f8605f0d5e2630015c628ae6a6512466db0ab1e17cabfc63f7；旧采样 APK 哈希保留在 baseline.json，新增滚动条采样对应 scrollbar-build-identity.json。

## 数据与环境

原始 UI 采样共 84 条，results.json 不覆盖；adjudicated-results.json 逐条标注未命中预期动作、替代验证及不计入功能通过的输入。UI 层采样并非每条都有独立功能断言，功能判断结合界面状态、截图和顺序变化。帧完成耗时受模拟器宿主图形后端影响，报告不据此估算电池节省比例。

完整证据位于 scratch/animation-power-audit-20261113/。模拟器仅支持 60 Hz，hasArrSupport=false；系统轨迹无能量计数器，未测真机瓦数、耗电百分比及 120 Hz / ARR。正式应用和既有媒体未移动、删除，隔离应用设置在测试后恢复，恢复结果见 settings-restore.json。

依据 [Android 渲染检查](https://developer.android.com/topic/performance/rendering/inspect-gpu-rendering)、[Macrobenchmark 指标](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)、[Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline) 和 [AndroidX Popup 实现](https://android.googlesource.com/platform/frameworks/support/+/735a9b7b08f60c9fd9c17d20849f97af7a4362e3/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/window/AndroidPopup.android.kt)。
