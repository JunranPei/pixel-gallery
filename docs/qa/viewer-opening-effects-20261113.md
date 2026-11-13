# 详情页打开动画

入口位于 Settings → Display，与主题、图片等板块并列的“动画效果”板块。三种设置为无动画、缩略图展开、放大淡入，默认无动画，使用现有 Settings DataStore 保存。

展开使用现有缩略图位置登记和返回手势的裁切几何；没有可用锚点、图片尺寸未知或需要恢复缩放/方向时使用放大淡入。展开 280 ms，放大淡入 220 ms。复用现有预览的就绪通知，最长等待 180 ms，不增加图片请求。视频过渡期间沿用已有封面，完成后交给播放表面，不为打开动画截取视频帧。

逐帧状态只在绘制/graphicsLayer 中读取，不改变原图布局、解码器和分块几何。完成后移除打开动画图层；暂停、退出、窗口尺寸变化会取消任务并恢复正常显示。省电模式、系统禁用动画或已恢复的导航条目跳过动画。此处记录实现和验证情况，没有进行设备耗电量测量。

实现依据为 Android 官方的 [Compose 动画指南](https://developer.android.com/develop/ui/compose/animation/quick-guide) 和 [绘图修饰符说明](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)，使用 graphicsLayer lambda 延后状态读取，避免逐帧重组与布局。

| 检查 | 层级 | 状态 | 证据 |
| --- | --- | --- | --- |
| 初版 Debug、Release Kotlin 编译；修复后 Debug APK 构建 | 构建 | 通过 | scratch/viewer-opening-build.log、scratch/viewer-opening-apk-fixed.log |
| 横图、竖图、方图的首帧位置与不拉伸裁切 | 单元 | 通过 | ViewerOpeningGeometryTest |
| 结束后正常比例、不透明、无裁切 | 单元 | 通过 | ViewerOpeningGeometryTest |
| 无锚点、尺寸未知、设置非法值 | 单元 | 通过 | ViewerOpeningGeometryTest |
| 动画完成后和取消后不再请求帧、无动画不请求帧 | 单元 | 通过 | ViewerOpeningGeometryTest |
| 缺失预览有界等待后恢复操作 | 单元 | 通过 | ViewerOpeningGeometryTest |
| 原有关闭手势几何回归 | 单元 | 通过 | ViewerDismissGeometryTest |
| Display 独立板块与三项设置选择、重启保存 | 应用界面 | 通过 | settings.png、settings-none-verified.png、settings-scale-fade-fixed.png、persisted-thumbnail-verified.png |
| 三种打开方式的实际画面 | 应用界面 | 通过 | none-fixed-verified.mp4、thumbnail-fixed.mp4、scale-fade-fixed.mp4 |
| 横图、竖图、方图展开和视频封面到播放表面交接 | 应用界面 | 通过 | thumbnail-landscape-fixed.mp4、thumbnail-fixed.mp4、thumbnail-square-fixed.mp4、thumbnail-video-verified.mp4 |
| 动画结束后的静止状态 | 应用绘制计数 | 通过 | idle-fixed.json，3 秒前后均为 502 帧 |
| 省电模式跳过动画 | 应用界面 | 通过 | power-saver-fixed.mp4、power-saver-state.txt；测试后恢复关闭省电模式 |
| 打开瞬间背景网格空白修复 | 录屏前后对照 | 通过 | frame-analysis.json；修复前存在白屏采样，修复后展开、淡入、无动画及不同宽高比均无白屏采样 |
| 设备耗电量对照 | 实测 | 未测 | 无设备耗电量数据 |

单元测试共 19 项，失败 0、错误 0。实际结果在 `app/build/test-results/testDebugUnitTest/TEST-com.pixel.gallery.ui.viewer.ViewerOpeningGeometryTest.xml`（12 项）和 `TEST-com.pixel.gallery.ui.viewer.ViewerDismissGeometryTest.xml`（7 项）；日志为 `scratch/viewer-opening-tests.log`。

界面证据统一在 `scratch/viewer-opening-qa/`。使用可见的 `GalleryAlbumSearchQA20261019:5554` 模拟器，安装本次构建的隔离包 `com.pixel.gallery.codexclean`，版本 4.3.6.7-auto-multitask / 2084。既有正式应用未安装覆盖。

录屏发现初次打开时，过渡登记前网格被取消放置，造成背景短暂空白。修复将网格保留到打开过渡登记，随后沿用原有隐藏逻辑；动画结束后不放置底层网格。`thumbnail-portrait-final.mp4` 是修复前证据；`thumbnail-fixed.mp4` 是同一竖图修复后证据。按 60 fps 重采样、将画面主体白色像素占比超过 90% 作为该夹具的空白判据，修复前有 6 个连续采样，修复后为 0。视频仅用于该夹具的前后对照，没有用模拟器帧耗时推算真机耗电。

模拟器保持打开，停留在 Display 的动画效果板块，测试包选择缩略图展开。代码默认值仍为无动画。
