# 4.3.6.9 release

版本为 4.3.6.9-auto-multitask，arm64 versionCode 4086。正式包名 com.pixel.gallery.multitask，保留正常图片缓存，诊断监听和无缓存策略关闭。合入 codex/animation-fixes 的 4b6e6f50a，以及动画结束后释放 seed 引用的未提交修正。

正式图片路径启用 decodeFitExact：顺序读 JPEG 行并直接输出适屏位图，省去粗采样位图后的单独 CPU 缩放和大中间位图。旧 decodeFit 仍仅供无缓存诊断基线对照使用。保留既有 EXIF、取消、HDR／色彩回退和区域放大逻辑。

相册多选改为点击查重后在后台收集媒体。长按选择和开图不再提前构造所选相册的全量照片集合；普通相册、标签并集和隐藏相册范围均保留。

## 验证及安装

- 在正式构建配置下运行 88 项详情页、动画、手势、窗口及查重选取范围单元测试，失败与错误均为 0；assembleRelease 成功，日志 scratch/release-4.3.6.9/build.log。
- 原生逐行缩放的 25 组像素检查、18 次内存对照，以及指定 11 张原图的 99 次路径对照，见 image-load-oneplus-speed-20261114.md。正式版本次未另行执行 50 窗口内存压力测试。
- APK 为 artifacts/PixelGallery-4.3.6.9-arm64-v8a-release.apk，28,023,343 字节，SHA256 baa0953ef626794beb4ff54e56a3582f33e698e4a74f25d6a78d64c8bbbdafdb。
- 新包为不可调试的 release，签名与三星原正式版一致，证书 SHA256 为 2f1be29e2d55ae2f4040dfbaeb64f16ae78ab9cfd0ae4c6afe79c52dae4a7fdf。
- 已用 install -r 覆盖安装三星 SM-S9380（R5CY13T5Z3J），未清应用数据。设备 versionName／versionCode 和安装文件 SHA256 均与产物匹配。
- 在三星正式版中检查启动、网格、指定原图 09／01／11 的打开和返回，三张完成截图已查看。证据保存在 scratch/release-4.3.6.9/，未把完成截图当作逐帧黑帧或性能测量。

本次提交只包含应用／原生源码、测试、诊断工具和 QA 记录，未加入 APK、截图、轨迹、缓存、备份或其他工作目录。
