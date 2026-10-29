# 视频画面旋转切换闪烁

用户观察：开始双指旋转、转回原位两个节点闪烁；中间转动不闪。此次仅处理视频画面的显示层交接，保留 UI 方向的独立控制。

## 本地检查

- `VideoSurfaceTransition` 在这两个节点切换 SurfaceView 与 TextureView。
- 原实现只在 TextureView 返回 SurfaceView 时捕获遮盖帧，进入旋转时直接替换输出。
- 原返回流程等待首帧通知和两个 Compose 帧时钟，但没有验证遮盖绘制，也没有核实新 SurfaceView 已经有可读取的视频缓冲。
- 这些是与现象对应的代码缺口；尚未取得手机端帧记录，不能把本地检查称为真机根因验证。

## 处理

- 两个方向均在移除旧输出前捕获一帧，最长边限制 1440 像素。快速反向时复用尚在显示的遮盖，避免截取中间未就绪的输出。
- 等到遮盖执行绘制，再经过帧边界切换输出。捕获或绘制等待有界，失败不阻塞播放；该异常路径仍可能有视觉间隙。
- 进入旋转时，在遮盖捕获前保持旧 SurfaceView 的角度不变；捕获就绪后遮盖跟随手指旋转，避免旧显示层先收到任意角度变换。
- TextureView 仍以 `onSurfaceTextureUpdated` 作为缓冲更新信号。
- SurfaceView 在首帧事件后通过一次成功的 1×1 PixelCopy 核实视频缓冲存在；失败时只做有界重试，正常播放没有持续轮询。平台解码后备路径使用相同检查。
- 旧输出销毁时使就绪状态失效；异步读取携带代次，旧读取结果不能放行新输出。
- 保留现有 1.5 秒交接超时兜底，异常时记录日志并释放遮盖，避免静帧永远遮住播放。因此这不构成所有设备零闪烁保证。
- 未更改解码器数量、视频文件、索引、默认播放输出和旋转手势。普通播放仍使用 SurfaceView。

## 官方资料

- [Media3 Surface types](https://developer.android.com/media/media3/ui/surface)：SurfaceView 在许多设备上耗电更低；也说明两类显示层不同的生命周期。
- [Media3 issue 1994](https://github.com/androidx/media/issues/1994)：报告新 View 创建后立即转移播放输出有一两帧黑画面。属于相关案例，不是对本项目的证明。
- [PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)：读取最近提交的缓冲。成功说明缓冲存在，不等于显示器已经显示；仍保留帧边界等待。
- [TextureView.SurfaceTextureListener](https://developer.android.com/reference/android/view/TextureView.SurfaceTextureListener)：使用纹理更新回调确认新输出已更新。

## 验证边界

运行 VideoOutputReadinessTest，覆盖双向就绪条件、旧首帧回调、快速反向、销毁后迟到读取及旧 View 销毁。不做自动触摸操作；手机上的开始旋转、转回原位、暂停旋转、快速反向由用户测试。

构建结果：7 项测试通过；release 构建成功（`scratch/video-rotation-handoff-build.log`）。APK 的签名、对齐、包名、arm64-v8a 单架构及非 testOnly 属性均已核对。没有安装或运行设备 UI 验收。

- APK：`released_apks/4.3.5.5-auto-multitask/video-rotation-handoff-fix/PixelGallery-4.3.5.5-video-rotation-handoff-fix-arm64-v8a.apk`
- 包名：`com.pixel.gallery.multitask`；版本：`4.3.5.5-auto-multitask`，4072。
- SHA-256：`ab10691c8c182f0781e27ae7beb3a79a9e443a5aab564127d6d3ec768b718ad6`。
