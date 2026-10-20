# 本次任务清单

按用户确认范围执行。概念图不能代替实际组件截图；不在用户回复前替用户选设计方案。

| 顺序 | 工作 | 当前状态 | 验收条件 |
| --- | --- | --- | --- |
| 1 | ~~选中计数按 A08 局部处理，加 selected~~ | 已完成：实际相册选择截图已检查，原六个图标与间距保留，1 selected 同排完整 | 只修改原计数区域；关闭、全选、分享、移动、删除、更多不变；单词不被拆开 |
| 2 | ~~Quick Sort 默认收起~~ | 已完成：默认收起、上滑展开、下滑跟随与取消恢复已通过触控检查 | 证据：quicksort-collapsed/light/mid-drag 截图与 quick-sort-header-shared-groups-result.log；整组最终触控测试通过 |
| 3 | ~~B02 头部：Choose albums + 右侧计数清空胶囊~~ | 已完成：标题/计数同排、新建相册、清空与四列均已核验 | 保持 20dp 拖动区 + 48dp 标题行；无 Done/Expand；新建相册按钮点击打开对话框；清空立即应用；浅色/深色截图已保存 |
| 4 | ~~三处二/三选项统一 Theme mode~~ | 已完成：实际触控与排序回调通过；未选矩形与底色对比已修正并查看截图 | 直接复用 SettingsOptionControl；选中变圆胶囊、主色、勾；未选浅底圆角矩形；原排序行为保留 |
| 5 | ~~Hand preference 图标颜色~~ | 已完成：实际 Display 截图已查看，主题 primary 色 | 与其他 Settings 图标同为 theme primary |
| 6 | ~~Settings 编组~~ | 已完成：最新 APK 的实际导航与 library-final.png 已核验，连接处为小圆角 | Settings → Display → Themes；Library 只有 Label backup 和 Excluded Folders 两行。Label backup 上侧大圆角、下侧小圆角；Excluded 上侧小圆角、下侧大圆角；中间 4dp；没有小标题；Label backup 点击打开操作弹窗 |
| 7 | ~~Performance 色块圆角~~ | 已完成：performance-final.png 首尾大圆角与三个滑条已查看 | 第一项上侧 24dp、中间连接 6dp、最后一项下侧 24dp；值与滑条不变 |
| 8 | ~~标签 JSON 导出、匹配、导入逻辑~~ | 已完成：单元与设备逻辑测试通过 | 保留空标签/收藏；SHA-256 + 字节数匹配；重复副本严格消歧；重复导入无重复；预览后变化/损坏 JSON 不写入。最终入口和操作弹窗已按最新决定完成 |
| 9 | ~~共享色块、间距与多选项组件~~ | 已完成：一级 SettingsGroupSpec 与二级 SettingsBlockSpec 独立封装，所有相关页面已接入；最终构建、触控与截图验收通过 | 一级传入数量自动创建二级块；二级封装圆角/底色/内容；传 1 四周大圆角，传 3 自动首/中/尾；组内 4dp、组间 8dp；About 间距 8dp；任意选项数复用 Theme mode 组件 |
| 10 | ~~制作并展示标签备份 UI 十案~~ | 已完成：历史原生组件十案已展示；选择页已撤下 | 最终界面以第 6、11 项的最新用户决定为准；不再展示已定方案的选择图 |
| 11 | ~~应用用户最终选定的标签备份方案~~ | 已完成：实际入口和 Export/Import 操作弹窗已截图并验证；旧的 C01–C10 选择页已撤下 | Library 内保留一个 Label backup 入口；点击弹窗内操作 Export / Import；Excluded 是相邻的独立入口，间距 4dp |
| 12 | ~~最终构建、验证并安装模拟器~~ | 已完成：Debug 与 AndroidTest 构建成功；两组最终 UI 回归通过；最新 APK 已安装至 emulator-5554 并打开 Library；设备 APK 与构建产物 SHA-256 一致 | 所有已定项通过；最新 APK 安装到模拟器并打开实际界面 |

**完成标记规则：每项通过对应验收后，将该项标题划线，保留结果与证据；仅编码或仅构建通过不划线。**

## 新增任务（2026-10-20）

| 顺序 | 工作 | 当前状态 | 验收条件 |
| --- | --- | --- | --- |
| 13 | ~~扩充模拟器文件和相册~~ | 已完成：新增 24 个相册、318 个文件并扫描入媒体库，保留作后续手动测试 | JPEG、PNG、WebP、动图 GIF、MP4；4 文件小相册、98 文件混合大相册；横竖比例、中文/长名称、旧日期、同名不同内容、相同内容副本；原有数据保留 |
| 14 | ~~退出相册自动关闭 Quick Sort~~ | 已完成：真实相册的收起/展开退出及重进回归均通过，退出后无残留面板 | 收起/展开时退出相册均关闭面板和排序状态；重新进入不残留；正常查看器往返不受影响 |
| 15 | ~~再点全选保留空选择状态~~ | 已完成：4 文件和 98 文件相册均通过；空选择时保留工具栏与选择圆圈，无对象的操作置灰 | 全选后再点全选显示 0 selected，保留选择工具栏；再点文件可继续选择；再点全选可重新全选；关闭/返回可正常退出 |
| 16 | ~~新增任务构建与可见模拟器验收~~ | 已完成：构建成功、153 单元测试和新增设备回归通过；新 APK 已安装，可见模拟器打开新增相册页 | 有针对性的设备回归通过；最新 APK 安装在可见模拟器；新数据和截图可供检查 |

## 已有证据

- `scratch/ui-label-migration-verified-build.log`：一次完整 Debug、AndroidTest、JVM 测试构建成功；之后新增的 UI 改动仍需最终构建。
- `scratch/label-backup-device-result.log`：真实 MediaStore 文件、JSON I/O 和 Room 数据库迁移检查通过；测试只读写本次创建的临时文件。
- 设备测试覆盖中文标签、空标签、收藏、改名换目录/新 MediaStore ID、同名不同内容不匹配、重复导入、相同内容重复副本歧义、预览后文件变化、损坏 JSON。

## 已拒绝或撤回

- 未经回复擅自采用 B01：已撤回，按用户确认 B02 执行。
- 整栏重画的 A08 概念：不照搬，只修改实际计数。
- 未忠实于既有 MD3 的标签备份概念图：不作为正式界面方案，改为项目组件的真实预览。

## 最终界面证据

- `scratch/settings-selection-device-result.log`：实际 MainScaffold 导航、原工具栏操作和选中计数验证通过。
- `scratch/quick-sort-header-delivery-device-result.log`：底部默认收起、四列、滑动、新建相册、清空，以及排序二/三选项触控和回调验证通过。
- `scratch/label-backup-final-device-result.log`：真实 JSON 迁移回归通过。
- `scratch/ui-label-migration-qa/`：最终 selection-count-final、settings-main、performance-final、library-final、label-actions-dialog、sort-photos-name 等截图。
- `docs/settings-components.md`：共享组件复用说明。
- `scratch/ui-label-migration-shared-groups-build.log`：一级/二级类及全部页面接入后的最终 Debug、AndroidTest 构建成功。
- `scratch/settings-selection-shared-groups-result.log`：最终设置导航、标签操作弹窗、原工具栏与计数通过。
- `scratch/quick-sort-header-shared-groups-result.log`：最终 Quick Sort、排序选项、清空与新建相册回归通过。
- `scratch/ui-label-migration-qa/final-install-verification.json`：设备安装 APK 与最终构建 SHA-256 一致。
- `scratch/ui-label-migration-qa/label-ui-roundtrip-result.txt`：真实文件选择器导入、恢复关联、再次导出核验通过。

## 首轮持续目标复核（新增 13–16 项之前，2026-10-20）

~~逐项复核全部 12 项任务并补齐验收证据~~：12/12 完成，无待办项。

- 逐项对照当前实现、构建日志、单元测试报告和设备测试报告；没有仅凭划线认定完成。
- 19 份单元测试报告合计 153 项，失败 0、错误 0、跳过 0。最终设置界面、Quick Sort 与标签迁移设备测试报告均通过；真实文件选择器的导入、恢复和再次导出结果亦通过。
- 当前所有产品源码均早于最终 APK，设备实际安装 APK 的 SHA-256 与该构建产物一致：`fdac3962a6dd5940f4ee0f680a4337a21e0b06dcbcd7f1f6f71ba4bc9d991a9d`。
- 本次重新打开实际 Library 页面并保存 `scratch/ui-label-migration-qa/library-goal-audit.png`；确认只有两个入口、外侧大圆角、连接处小圆角，实测中间间距 11 像素（设备密度 2.625，4dp 渲染取整）。
- 本次结构化复核结果：`scratch/ui-label-migration-qa/goal-acceptance.json`。模拟器当前保留在 Library 实际页面。

## 新增任务最终验收（2026-10-20）

~~完成新增 13–16 项并安装可见模拟器~~：目前 16/16 项完成。

- `scratch/gallery-regression-media-manifest.json`：24 个新增相册、318 个测试文件的清单，文件保留在模拟器 `/sdcard/Pictures/GalleryQA20261020`。
- `scratch/album-regression-build.log`：最终 Debug、AndroidTest 与单元测试构建成功；153 项单元测试失败 0、错误 0、跳过 0。
- `scratch/album-regression-device-result.log`：真实 MainScaffold 中完成“全选→清空→继续选择→再次全选”，验证 Back/Close 退出空选择；验证 Quick Sort 收起/展开退出相册、空白点击、重进相册与正常查看器返回；98 文件混合相册全选/清空均通过。
- `scratch/album-regression-qa/`：保留 0 selected、98 selected、退出无面板和新增相册等实际截图，已查看核对。
- `scratch/album-regression-qa/final-verification.json`：最新构建 APK 与安装 APK 的 SHA-256 一致，产品源码无晚于 APK 的改动。新校验值为 `59990c242d92d09c9a1053121a38b78a01bf0faf044fc510741bef5d1f992aae`。
- 可见模拟器已置于前台并打开新增相册列表，供继续手动测试。

## Label backup 边缘测试任务

| 顺序 | 工作 | 当前状态 | 验收条件 |
| --- | --- | --- | --- |
| 17 | ~~搜索并建立边缘情况清单~~ | 已完成：官方资料及完整矩阵已记录 | 记录官方来源；明确文件匹配、编码、损坏、合并、取消、I/O、生命周期和环境限制 |
| 18 | ~~JSON 与匹配单元测试~~ | 已完成：209 项单测通过，标签备份 67 项，保留修复前失败 | 正常/异常/边界输入与歧义匹配通过；保留失败复现与修复后的结果 |
| 19 | ~~隔离数据库及真实文件测试~~ | 已完成：23 个真实文件/数据库用例组通过，原迁移回归通过 | 合并、收藏、过期预览、原子性、中断、I/O 和数量边界；已有数据保留 |
| 20 | ~~实际弹窗与文件选择器验收~~ | 已完成：系统选择器、产品弹窗、取消/错误/部分匹配/重试通过 | 正常往返、取消、损坏备份、部分匹配与详情、重试等实际交互通过 |
| 21 | ~~结果报告与最终安装~~ | 已完成：结果与未测环境已记录；最新 APK 已安装，实际 Library 页面可检查 | 报告已测与未测项；已发现缺陷最小修复后复测；最新 APK 安装到可见模拟器 |
| 22 | ~~将功能边缘测试流程制成 skill~~ | 已完成：functional-edge-testing 已安装、UTF-8 格式校验通过；当前用于复制/移动专项测试 | 遵循 skill-creator；通用功能测试可复用；先搜索、建矩阵、测试前后对照、真实操作与故障注入、数据保留、证据和未测范围；完成校验 |
| 23 | ~~补测文件复制与转移~~ | 已完成：32 个设备用例组、真实界面及既有回归通过；修复 3 类缺陷，209 项单测通过，已安装可见模拟器 | 按新 skill 检索和建立边界矩阵，审查已有覆盖，补足真实文件及设备测试，复现问题后最小修复并复测，报告风险与未测环境 |

## 本轮最终记录

17–23 项均完成；详见 `docs/label-backup-edge-cases-2026-10-20.md`、`docs/copy-move-edge-cases-2026-10-20.md` 与 `scratch/copy-move-final-verification.json`。未测真实硬件/云环境按报告保留，不计作已通过。

## 用户复测新增问题

| 顺序 | 工作 | 当前状态 | 验收条件 |
| --- | --- | --- | --- |
| 24 | ~~调查视频首试失败并修复已复现分支~~ | 已完成：MOV 命名规范化分支已复现并修复；22 场景、5 轮冷进程、209 单测及相关回归通过；用户原次是否同因尚未确认 | 保留首次失败证据，查明原因后最小修复；真实视频 Quick Sort 首试与文件/标签/时间核验，失败恢复及相关回归通过；安装可见模拟器 |
| 25 | ~~调查七张照片打开时过渡图偏小~~ | 调查完成：7 张×2 轮真实相册打开、录屏及 14 次平台缩略图调用；确认内嵌预览黑边导致内容小约一成；监听版已安装并持续记录，显示逻辑未改 | 原始文件保持不动；安装可见模拟器，逐张录屏并记录实际加载阶段、图片尺寸与显示范围；先报告原因，不修改显示逻辑 |
| 26 | ~~修复过渡预览尺寸并安装复测~~ | 已完成：215 单测、37 设备加载检查、32 次像素对照；trace 与主包共 28 次真实打开，过渡/清晰图最大边缘误差 0.093px；两个包均更新安装，7 张原件与副本不变 | 七张原样本冷/热打开、旧缓存升级、旋转/横竖方图及真实黑边回归；实际画面和绘制范围一致；安装可见模拟器，原图保持不变 |
