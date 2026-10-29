# 去重页十案与全局 toast

分支：`codex/dedup-redesign-toast`，基于 `cb762664`。

使用 `frontend-design` skill。`index.html` 是离线交互设计稿，`ten-options-light.png`、`ten-options-dark.png` 为总览，`option-01.png` 至 `option-10.png` 为单案图。图中照片是内嵌的示例插画，文件与容量均为演示数据，不代表设备扫描结果。

## 共用设计基础

- 顶栏：复用应用唯一的 GalleryHeader，64dp 高，主题 surfaceContainerLow，返回箭头，22sp 粗体标题。
- 主体：surface，顶部 20dp 圆角边缘；内容左对齐，不另外建立顶栏或 Scaffold。
- 浅色示意：主体 `#FFFFFF`、顶栏 `#F3F0F7`、浅底 `#F7F5FA`、主色 `#675286`、正文 `#26232C`、次要文字 `#6B6574`。实际应用继续使用 MaterialTheme 与动态色，不硬编码这套示例色。
- 字体：应用沿用 EmphasizedTypography.TitleLarge 与 Material Typography；网页采用系统界面字体，正文与文件信息分层。
- 原则：照片、文件名、目录、容量与保留状态服务于核对；每组至少保留一项；选中表示准备移入回收站；不新增排序、筛选、删除策略或自动清理。

```text
共用顶栏      ← Duplicate review
共用主体      ╭──────────────────╮
              │ 扫描范围与结果   │
              │ 分组及文件核对   │
各案底栏      │ 选中容量 + 操作  │
              ╰──────────────────╯
```

设计复核：不以换颜色区分方案；保留同一顶栏、主题和数据，实际比较分组结构、照片大小、核对方式与底部操作。全部保留逐项选择、按组选择、全选副本与确认移入回收站；第 08 案额外需要组展开状态。设计稿简化了扫描、系统授权、失败和完成状态，实际接入时必须保留现有处理流程。

## 十案

| 编号 | 方向 | 主体与底栏 | 取舍 |
| --- | --- | --- | --- |
| 01 | 分组清单 | 细分隔线文件列表；单行数量与操作 | 稳妥，信息完整 |
| 02 | 双列照片 | 两列照片卡；柔和底托 | 照片更直观，显示文件较少 |
| 03 | 紧凑文件表 | 窄行、小缩略图；单行底栏 | 大批处理效率高 |
| 04 | 保留照片领衔 | 大保留照片，副本缩进；全宽按钮 | 保留关系清楚 |
| 05 | 左右核对 | 保留与副本分列；容量与操作并排 | 适合逐组核对 |
| 06 | 分组导线 | 导线连接组内文件；独立浮岛 | 分组与底栏边界明显 |
| 07 | 横向底片 | 一组一排照片；上下两行底栏 | 多副本需横滑查看 |
| 08 | 可展开分组 | 折叠照片摘要，展开文件；单行底托 | 总览简洁，增加展开操作 |
| 09 | 保留区与副本区 | 浅色保留行；数量与按钮分行 | 状态明显，底栏较高 |
| 10 | 轻量清理条 | 精简文件行；一枚整合数量容量的按钮 | 底部最简洁 |

用户已确认合并方案：07 横向照片主体，去掉组间横线；06 悬浮底栏，四周与后方透明，不铺白色背景。后续修订：未选中不显示标记，选中显示 26dp 主色实心圆与 onPrimary 勾，无外层底座、圈线或方框；全局全选操作移到胶囊内 Move to bin 左侧，顶部入口删除，阴影降为 1dp。全选键沿用主导航选中胶囊的 primaryContainer/onPrimaryContainer 配色并加粗文字，底栏使用半页面的 surfaceContainerLow。当前预览为 `index.html?final=1`，单案图为 `final-scheme-light.png` 和 `final-scheme-dark.png`。上述主体与底栏已写入生产页面；十案作为历史设计稿保留。

## 已写入应用的修改

- 去重页取消旧的 surface 顶栏覆盖，改用统一颜色和标题字重；关闭图标换成返回箭头；主体加入与其他页面一致的圆角边缘。
- 所有现有提示均通过 GalleryMessages/GalleryMessageHost。旧组件的 8dp 宿主边距加 12dp 内置边距共 20dp；新组件取消内置外边距，直接设置 36dp 下边距，提示实际约上移 16dp；左右边距 16dp。
- toast 从 inverseSurface 改为 surfaceContainerHigh/onSurface，使用淡 outlineVariant 边框，跟随明暗与动态主题。
- 所有时长均不显示叉号；采用 SwipeToDismissBox 支持双向滑动。每条消息独立保存手势状态，保留消息队列和自动消失；为辅助功能保留 dismiss 操作。
- 保留前台模态窗口的提示宿主选择。

手势实现参照 [Android Compose 官方文档](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/swipe-to-dismiss)。

## 验证

- `render.cjs` 生成预览并检查十案点选、确认弹窗、双向滑动演示与 412px 页面；结果见 `preview-checks.json`。
- 应用与测试 APK 首轮构建通过。原生 toast 的最终测试结果另见 `verification.md`。
- 原生测试仅使用模拟消息和独立测试包，不提交文件移动或删除。

复现网页预览截图：在当前工作站运行 `node docs/dedup-redesign-2026-10-28/render.cjs`。脚本的浏览器与 Playwright 路径对应本工作站。
