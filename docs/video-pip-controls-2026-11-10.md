# PiP 控制按钮验收

本次约定底部从左到右为后台听、播放／暂停、静音／取消静音，沿用系统放大和关闭入口。后台听只影响当前会话，不写入用户保存的后台策略。保留当前 PiP 画面缩放、布局锁定和视频输出实现。

当前分支为 feature-3。测试使用 emulator-5562（API 36）、独立包 com.pixel.gallery.viewertrace；只创建并删除测试自身导入的视频。现有媒体和其他应用数据保留。构建不提升版本号。

参考 Android 官方 [PiP 控制说明](https://developer.android.com/develop/ui/views/picture-in-picture#add_controls) 和 [自定义操作接口](https://developer.android.com/reference/android/app/PictureInPictureParams.Builder#setActions(java.util.List))，通过 RemoteAction 配置顺序；图标位置和菜单外观由 SystemUI 绘制。后台切换采用系统 moveTaskToBack，参考 [AOSP Task](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/Task.java) 中 pinned 任务移入后台的处理。

| 用例 | 操作与预期 | 验证方式 | 状态 |
| --- | --- | --- | --- |
| 手动进入 | 原 enterPip 入口；三按钮从左到右排列且位于同一行 | 仪器测试调用入口；最后使用实际触摸展开原工具栏并点击 PiP 按钮另行验证 | 通过 |
| 暂停和恢复 | 点系统暂停，进度停止；点播放恢复 | 系统按钮、实际播放器进度 | 通过 |
| 静音切换 | 声音音量为 0 后恢复到 1；播放不断，图标切换 | 系统按钮、音量与状态 | 通过 |
| 放大返回 | 回到同一播放器，视频输出恢复 | 系统放大按钮、会话与输出检查 | 通过 |
| 双击缩放 | 画面保持可见，视频输出尺寸不变且继续播放 | 实际双击手势与输出检查 | 通过 |
| HOME 自动进入 | 原自动 PiP 行为保留；后台听返回后也能重新进入 | HOME 操作、窗口状态 | 通过 |
| 后台听 | 小窗收起，音频继续，禁用视频轨道，设置不变 | 系统按钮与实际播放器 | 通过 |
| 后台通知 | 暂停后能恢复；返回原播放会话 | 通知 UI、播放器状态；恢复原任务入口修复后验证 | 通过 |
| 关闭 | 从后台返回后再进入 PiP，点系统关闭停止声音 | 系统关闭按钮与播放器 | 通过 |
| 源文件 | 导入的测试视频字节保持不变 | 完整字节比对 | 通过 |
| 相关单元回归 | VideoOutputReadinessTest 9 项、VideoWindowStateTest 4 项 | 单元测试 XML | 13 项通过 |
| 旧整套播放器测试 | 自动播放、速度、截图、进度、结束策略 | 旧 VideoPlayerChecks 依赖旧版工具栏及速度弹窗；此次未执行完整套件 | 未测 |

## 构建记录

首次构建因 PowerShell 参数拆分而未进入编译，已给属性参数加引号。后续增量 Kotlin 编译出现跨文件顶层声明无法解析，改用完整 Kotlin 编译后通过。记录保存在 scratch/pip-controls-build-20261110.log。

完整 Kotlin 编译通过。指定模拟器 ABI 的本次 APK 实际位于 app/build/intermediates/apk，app/build/outputs/apk 中仍有旧产物，安装前已核对本次包名和版本。首次误取旧目录仅安装了模拟器中的旧测试包，没有清除数据；此后均安装 com.pixel.gallery.viewertrace。

## 实际发现和处理

- 测试工具原先使用旧工具栏入口和不稳定的窗口坐标。早期工具栏展开测试不计通过；最后通过实际触摸操作验证了工具栏入口。新按钮测试使用真实系统 PiP；读取 WMShell 的 PiP 边界并避开系统放大按钮展开菜单，暂停、播放、静音、后台听、通知和关闭通过实际无障碍点击操作。
- 原后台通知的 Intent 受 documentLaunchMode=always 影响，会创建相册首页。新增不显示界面的通知返回入口，以原 taskId 恢复已有任务；保留 MainActivity 的多文档配置。修复后实际点击通知已恢复同一 VideoPlaybackSession。
- 新增临时后台状态的清除判断原先位于可见性赋值之后，导致条件永远不成立，返回视频后再次 HOME 不进入 PiP。已将判断移到赋值之前；最终重跑原失败场景通过，随后系统关闭停止播放。

阶段记录保存在 scratch/pip-controls-evidence-v10-20261110、scratch/pip-controls-return-fix-evidence-20261110，失败记录保留。

## 最终结果

最终构建日志为 scratch/pip-controls-state-fix-build-20261110.log（BUILD SUCCESSFUL），模拟器完整结果为 scratch/pip-controls-final-device-20261110.log。最后一次相关单元回归为 scratch/pip-controls-final-unit-20261110.log，13 项、0 失败、0 错误。

最终截图、系统节点、测试结果和本次 APK 保存在 scratch/pip-controls-evidence-20261110/final。04-home-system-controls.png 显示 HOME 后的真实系统菜单；showcase.png 是最后通过原工具栏按钮进入 PiP 的实际画面。用于展示的一份测试视频 controls-preview.mp4 留在模拟器 Movies/PixelPipControlsQA；仪器测试自身导入的视频已删除，设置已恢复。

首个完成验收版 APK 的 SHA-256 为 f66a413f88f08de590df8683ee1cf40dd7d09576fc8bad02edd4ba0354760677。版本仍为 2082 / 4.3.6.5-auto-multitask，独立测试包 com.pixel.gallery.viewertrace；没有改动版本号、提交或推送。

用户随后提出按钮显小，已减少图标留白：耳机和音量图形放大 20%，播放和暂停重新绘制为更饱满的图形，保持系统提供的点击区域。新版构建记录为 scratch/pip-controls-larger-icons-build-20261110.log，完整系统按钮及后台往返复测记录为 scratch/pip-controls-larger-icons-device-20261110.log，全部检查再次通过。

最新 APK、实测截图和报告保存在 scratch/pip-controls-evidence-20261110/larger-icons；showcase.png 为原工具栏进入后的实际显示。最新归档 APK 与模拟器安装包 SHA-256 相同，均为 54f37bbf69ff95c13fe5d2621885d3b810a956437295d3aa15b300a4c9855eba。模拟器保留在 PiP 界面。

本次实测环境为 API 36 模拟器。旧整套播放器测试、其他 Android 版本和真机未在本次完整执行；没有以本次结果替代那些环境的验收。

## 用户确认后的打包

用户确认界面后授权提升版本号、打包与推送。版本提升为 4.3.6.6-auto-multitask，基础 versionCode 2083；沿用 arm64 的 +2000 规则，安装包实际 versionCode 为 4083。

Release 构建及 lintVitalRelease 通过，记录为 scratch/pip-controls-release-4.3.6.6-build.log。安装包为 released_apks/4.3.6.6-auto-multitask/PixelGallery-4.3.6.6-auto-multitask-arm64-v8a.apk；包名 com.pixel.gallery.multitask，仅含 arm64-v8a，未设置 testOnly 或 debuggable。BuildConfig 的诊断监听开关均为 false。

APK SHA-256 为 f1642f5afd41fb5a9c62f2892f4dc6abd5997e2ca98b4b3e02b5a964a384eefd。apksigner 验证通过，证书 SHA-256 为 2f1be29e2d55ae2f4040dfbaeb64f16ae78ab9cfd0ae4c6afe79c52dae4a7fdf，与上一发布包一致。该安装包为本次交付的 arm64 Release；此前模拟器证据来自独立的 x86_64 测试包。
