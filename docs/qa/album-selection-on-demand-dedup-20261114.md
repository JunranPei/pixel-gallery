# 相册选择后按需收集查重照片

用户要求只在点击查重时收集所选相册里的照片。此前 selectedAlbumEntries 在公共 Compose 页面中计算，以 currentScreen 为 remember key，长按选择和开图都可能触发全库过滤；之后的空选择提前返回只能覆盖没有选择项的情况。

## 修改

- 移除主界面里的 selectedAlbumEntries 与 selectedLabelMediaIds 预计算。
- 相册多选工具栏的查重菜单不再依靠扫描后的照片数量决定是否可用。选中相册即可进入查重，空相册和单照片相册也按正常查重流程展示结果。
- 只在查重菜单的点击回调里冻结当前相册路径、标签选择、成员表与媒体列表引用。调用 DuplicateViewModel.startResolved 后立即进入 SCANNING，在 Dispatchers.Default 收集选中照片，再进入原有权限检查与扫描流程。
- 收集操作支持取消，重复点击由原有 IDLE 状态限制挡住。普通相册仍匹配直接父目录；标签与普通相册取并集且保持原媒体列表顺序；隐藏相册仍匹配目录及其子目录，不把同名前缀的其他目录并入。
- 保留原有 start(entries, scope, title) 供其他查重入口使用，权限授权、结果复核与清理流程保持原调用逻辑。

## 核验

新增 SelectedAlbumMediaTest，覆盖空选择不读取媒体列表、普通相册精确父目录、多相册及标签重叠范围、隐藏目录子目录与前缀碰撞、取消处理，共五项。构建与测试日志位于 scratch/image-blackframe-oneplus-speed-20261114/on-demand-dedup-build.log。

首次测试进程启动时申请 526,385,152 字节失败。将测试堆降为 128m 后五项测试全部通过（failures=0，errors=0）；对应 XML 为 app/build/test-results/testReleaseUnitTest/TEST-com.pixel.gallery.model.SelectedAlbumMediaTest.xml。该次后续打包守护进程退出，因此测试通过与 APK 打包分别核验。安装构建使用 on-demand-samsung-package.log，降低打包堆至 512m 并使用 SerialGC。

最终打包任务在 on-demand-samsung-package-final.log 中成功（5m 7s）。产物 artifacts/PixelLoadDiagnostic-on-demand-dedup-nocache.apk 已安装到三星 SM-S9380 的独立包 com.pixel.gallery.loadtrace.nocache，手机与本地 SHA256 一致：f283acb217e890b7f2fee25a76cd2c6cf371278c973df2b35500f3da83f4cc76。

三星上已检查启动、相册页、长按选择以及多选查重菜单。对显示 9 项的 AdobeLightroom 相册点击查重，实机返回 No duplicates；没有执行文件删除。普通相册的这一条界面路径已经验证，标签混选、隐藏相册和取消由上述单元测试覆盖，尚未逐项进行实机复测。截图、XML 和安装校验位于 scratch/image-blackframe-samsung-20261114/on-demand-*。

三星正式版仍为 4.3.6.6，lastUpdateTime=2026-11-10 16:06:21，未替换。原有 USB 保持亮屏值 15 未修改。USB 调试为 1，保存电脑公钥已匹配，授权过期时间为 0；主动 reconnect 后无需重新授权即可调用 shell，已核验自动连接。
