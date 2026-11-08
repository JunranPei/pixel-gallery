# 弹出层非线性动画审查

用户要求补齐已有动画，不改外观或操作。已有 `OverflowMotion` 是三点锚定菜单的阻尼响应，`GalleryMotion` 是页面和拖动释放的临界阻尼弹簧；优先复用。

| 入口 | 当前实现 | 缺项 |
| --- | --- | --- |
| 主 grid、选中后的三点 | GalleryOverflowButton / AnchoredOverflowPopup | 已有打开、关闭、中断衔接 |
| 共用确认、改名、群组设置、转移确认、Quick Sort 提醒 | GalleryDialog → 原生 Compose Dialog | 未使用项目非线性动画 |
| 排序 | SortDialog 的页面内遮罩 + Surface，visible=false 直接返回 | 打开、关闭均无动画 |
| 插画选择 | 直接 Dialog 包 SelectionBottomSheet | 整个弹窗的出现/消失未处理；半页拖动已有弹簧 |
| 备份缺失/歧义详情 | 直接 Dialog | 未使用项目非线性动画 |
| 大图索引三点菜单 | Material DropdownMenu | 尚未复用锚定菜单 |
| 视频倍速 | InlineSpeedChoices → Popup，if(showSpeed) 条件挂载 | 打开、关闭无动画 |
| 大图信息面板 | Material ModalBottomSheet | 核查现有版本的 motion spec，再决定接入方式 |

关闭动画必须覆盖点外部、返回键、确认操作和数据驱动关闭；只在 Dialog 内容加进入动画不能处理父级 if 删除节点。需保留退出期间的内容，结束后解除持有，避免新增窗口/图片引用滞留。持续动画仅在切换期间运行。测试需采集实际帧参数与窗口销毁，检查中断重开、减弱动画设置及退出后的空闲状态。

资料：Android [WindowManager.LayoutParams.windowAnimations](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#windowAnimations) 的资源限制不能作为任意应用自定义窗口曲线的可靠实现依据；本轮优先在 Compose 内容生命周期内处理。

已接入共用 PopupPresence：确认/改名/群组设置/转移确认/Quick Sort 提醒、备份预览与详情、排序、插画选择、倍速，以及标签/编组/Quick Sort/转移半页。关闭时保留当前内容至退出结束，随后释放；关闭期间拦截重复操作。动态层只更新透明度和缩放，布局与列表不逐帧重建。嵌套弹窗共享首次进入过程，在已展开父层里新打开的弹窗仍独立进入。

大图索引三点复用 AnchoredOverflowPopup；大图信息页沿用 ModalBottomSheet 布局与拖动，仅覆盖 MotionScheme 的空间和效果曲线，标签操作先完成 hide 再转入目标。原有主三点动画保持不变。

实际模拟器 `popup-lifecycle-final3`：原生窗口计数、渲染层参数采样、Compose 生命周期记录；没有截图或图像识别。通过非线性进入、返回退出期间窗口仍在、退出后原生窗口关闭且停止绘制、关闭中反向重开、排序/插画深色模式、12 次快速开关。测试中创建与释放的内容实例均为 4，快速切换沿用同一退出尚未完成的实例。APK SHA-256 为 ea5aab34ab9aec1638099d1baca54713fe0a6d23a9b0b13185874ebd712489e0。

采样修正记录：candidate1 用点击重开之前更早的一帧作对照，误报 0.77056 → 0.50458 的跳变；实际相邻帧为关闭 0.50458 → 重开 0.50458。candidate2 的设备用例通过，但主机误要求 am instrument 输出末尾一定含结果码；此 runner 仅打印 stream。final3 改为校验返回 JSON，主机和设备均通过。未将这两项主机检查失败登记成产品缺陷。

关闭系统动画补测 `popup-lifecycle-disabled1` 通过。只在隔离模拟器将 animator_duration_scale 临时置 0，确认普通对话框、排序和插画选择均能打开、退出、释放内容/原生窗口；排序用实际无障碍标题的出现和消失确认。两份弹出内容 attached=2、removed=2，退出后没有继续绘制。测试结束已恢复该设置原先未写入的状态（读取值为 null，使用系统默认值）。产品 APK 与 final3 及五十窗口压力测试完全相同，仅测试 APK 新增这一用例。

同产品 APK 的 `fifty-patched-state-popup5` 完整压力测试亦通过：51 窗全部保留、0 low-mem；Java 峰值 330.18 MiB，返回后的总 PSS 849.66 MiB，较加入状态/动画前 +6.17 MiB（0.73%）。详细对照见 fifty-window-memory-20261107.md。
