# 标签页空状态闪现修复

分支：`codex/dedup-redesign-toast`。日期：2026-10-29。

原因：MainScaffold 在后台生成标签页分组时，以空列表作为初始值。LabelPhotosScreen 把这个尚未完成的状态当作真实空结果，短暂显示 `No files with this label`。

修改：分组未完成时传递 null，完成后才传递实际列表。标签、源照片、列数或排序变化时重新建立对应状态，避免沿用其他输入的结果。加载阶段不显示空提示或零文件计数；完成后确实为空时正常显示。搜索和媒体类型过滤仍沿用现有行为。

验证：

- 应用与测试 APK 构建通过：`scratch/label-loading-build-20261029.log`，BUILD SUCCESSFUL in 5m 1s。
- 测试启动等待调整后，测试 APK 构建通过：`scratch/label-loading-test-build-20261029.log`，BUILD SUCCESSFUL in 1m 17s。
- emulator-5554 上使用生产 LabelPhotosScreen 组件与缓存内生成图片，主动维持加载状态并采样检查；加载不显示空提示，有图不显示空提示，完成后的空结果显示提示，切换标签不继承上一个标签的空提示，最后一项消失后的空结果显示提示，全部通过。
- 原始结果：`scratch/label-loading-device-20261029.log`。界面截图：`loading.png`、`populated.png`、`empty.png`，均已查看。

测试直接控制标签页的数据状态，不修改用户标签或媒体；未将这些检查宣称为真实文件缺失检测或整个扫描流程的测试。
