# 新窗口相册 grid 起始位置

用户确认入口是新建独立窗口，不是旧窗口恢复，也不要求跨窗口同步滚动。

## 代码路径

- MainScaffold 的 albumsGridState 默认从 0/0 开始。
- PhotosViewModel.albums 的初始值为空，标签、群组也是异步加载。
- groupedAlbumRows 即使相册为空仍会提供固定 key 的回收站；已加载的群组也会先提供空群组标题。
- 后到的相册插入旧卡片之前，Compose 按旧卡片 key 保持可见位置。
- 官方说明：https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/grid/LazyGridScope

## 修复

GroupedAlbumsScreen 在行数据变更提交、下一次布局前，如果 grid 原位置是 0/0 且没有正在滚动，则 requestScrollToItem(0)，覆盖本次 key 锚定。不使用延时回顶，不重设已有非顶部位置，不缓存额外媒体列表。

## 验证矩阵

隔离模拟器包 com.pixel.gallery.memorypatched；真实 GroupedAlbumsScreen，测试宿主控制数据到达时间，不读写用户媒体或设置。通过 LazyGridState 读数验证，不使用图像识别。

| 用例 | 预期 | 状态 |
| --- | --- | --- |
| 先显示回收站，再加入 80 个相册 | 0/0 | 通过 |
| 顶部插入新相册 | 0/0 | 通过 |
| 滚动至第 24 项、偏移 17，再在前方插入 4 项 | 原 key 和偏移不变 | 通过 |

测试：app/src/androidTest/kotlin/com/pixel/gallery/integration/AlbumStartupInstrumentation.kt。
手机未安装。此前保留的 release 安装包未覆盖。

## 实测读数

- 修复前延迟加载：trash-only 为 0/0；加入 80 个相册后变为 index=56、offset=337。
- 修复后同一加载流程：index=0、offset=0、key=album:/test/0。
- 在顶部新增名称排序最靠前的相册：index=0、offset=0、key=album:/test/new。
- 中途浏览后在前方插入 4 个相册：index 24→28，key 始终 album:/test/3，offset 始终 17。
- 构建成功，运行结果 passed=true。
- 修复前的插入回归夹具名称排序没有实际插到前面，因此只用修复前首个延迟加载用例作失败对照；修复后的插入回归已改为明确排在前面的名称。

证据：scratch/album-startup-baseline-result.txt、scratch/album-startup-fixed-result.txt；构建日志为 scratch/album-startup-baseline-build.log、scratch/album-startup-fixed-build.log。

此前保留 APK 的 SHA256 仍为 eb9db26d06bcd7b034d1d54e2d723fd5b724d2cde3d81046b00bed1ae8153852，本次修复尚未重新打入 release。