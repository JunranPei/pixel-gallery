# 按需JPEG解码的原生优化调查

用户明确否决点击前准备预览。约束：继续按需加载，不扩大预加载，不重编码或修改原照片，不以回调提前几毫秒当作清晰等待成功；先调查原生/汇编层与网上已有方法。

## 本轮清单

- ~~核对当前应用语言和实际解码路径~~：应用Kotlin/Compose，加载Glide 4.16；当前两张地图日志为PLATFORM适屏，512为自定义Fetcher调用BitmapFactory，最终走系统原生解码。仓库另有C/C++索引JPEG模块，不是这次首开预览路径。
- ~~只读检查两张原JPEG的编码与内嵌预览~~：每张读取头部262144字节，SOF0/8-bit、非渐进；EXIF及Photoshop APP13内嵌JPEG分别145×160、160×157，没有找到头部内的更大JPEG。没有修改原图，也没有以没有找到头部预览推断全文件绝无其他图像。
- ~~核对上游解码/缩放/SIMD及替代接口~~：见来源与候选表。
- ~~按用户补充纳入第四项：修改Skia/JPEG缩放路径~~；已核对底层最小输出限制，后续底层实验已完成，见下。
- ~~隔离原生对照~~：2026-10-21已完成底层DC直出与合并AC查表实验，三星同图重复对照见 [实验记录](viewer-native-jpeg-experiment-2026-10-21.md)。函数级CPU采样仍未开展，不能将算法对照冒充采样归因。

## libjpeg-turbo底层实验（用户已授权实施）

- ~~确认修改范围和测试约束~~：在应用自带的索引版Turbo增加独立DC预览入口，不更换手机系统库，不建立预索引，不修改原图；现有标准/索引入口保持原实现。
- ~~实现直接从MCU的DC系数累加到1/16、1/32输出~~，色彩转换仅在最终尺寸进行，不建立完整1/8 RGB图；追加12位合并AC跳过查表。实际承载使用隔离的Turbo 3.1.3，保留旧索引核心的状态ABI。
- ~~构建独立设备工具并记录标准1/8后缩图、新算法与系统NDK对照~~：同尺寸，交错各6轮；CPU、总耗时和原生输出尺寸已记录。系统NDK使用位置独立读取，其FD位置为0不能当作读取量0。
- ~~完成边缘矩阵、原有索引回归和三星原图只读对照~~；测试包接入仅在trace实验开关启用，正式构建默认关闭；真实相册打开效果仍待用户复测。

| 编号 | 输入/故障 | 预期 | 层级 | 结果 |
| --- | --- | --- | --- | --- |
| N01 | 灰度、YCbCr 444/422/420、RGB | 正确颜色和ceil尺寸，误差对照独立的像素平均参考 | 原生设备 | 通过；含独立常量颜色/棋盘面积平均，噪声样本对标准均值参考MAE最高3、通道最大18 |
| N02 | 1×1、17×19、奇数边、横竖图、16/32采样 | 末端块按实际覆盖权重，不越界、不拉伸 | 原生设备 | 通过 |
| N03 | restart间隔、重复解码 | 无跨MCU状态错乱和污染，输出一致 | 原生设备 | 通过；0/1/2/3/31/65535，优化Huffman表，系数63无EOB等72项新增边界 |
| N04 | progressive/多扫描/CMYK/不支持参数 | 明确拒绝，允许上层回退；不能冒充成功 | 原生设备 | 通过；Android端另测progressive、PNG、小图和不足下限 |
| N05 | 空、截断、坏Huffman输入 | 受控失败，不发布部分图 | 原生设备 | 通过；1000份定点变异输入794份拒绝，206份仍有效/可解码，不将修改输入一律当成必然损坏 |
| N06 | 起始前和解码中取消 | 停止读取、不发布部分图、之后重试成功 | 原生设备 | 通过；原生回调边界确定性取消及重试，JNI另测进入前取消 |
| N07 | 现有顺序与渐进索引 | 标准索引/区域解码行为不变 | Android设备 | 三星ARM64、模拟器x86_64各5组JNI及索引JUnit通过 |
| N08 | 两张原地图，交错重复解码 | 只读，实际输出≥512；同图同尺寸比较，记录限制 | 三星真机 | 通过；原生工具各6轮，不计作真实查看页验收 |
| N09 | 原生内存访问检查 | 无越界/释放后访问 | 三星工具 | 1402项AddressSanitizer通过；未启用泄漏检测 |
| N10 | 真实Fetcher的原生入口、旋转、管道回退 | 常规1次原图打开、管道2次；512下限/缓存/取消不退化 | 模拟器集成 | 16组通过；原生513×5、旋转5×513，管道平台回退512×4；证据 `emulator-native-bridge-final.log` |

## 查明的限制（研究记录）

1. 当前Skia上游的SkJpegCodec原生缩放档位为n/8，最小1/8。宋政和源17008×18826，1/8约2126×2354；东汉13071×12829，1/8约1634×1604。512及手机适屏输出都低于该档位。依据源码推断，两者可能共享相近的基础JPEG解码工作，再做额外采样，因此不能按最终输出像素比例估算耗时。三星系统实际构建版本和热点占比尚未从函数采样证实。
2. 两张都是顺序JPEG，因此利用progressive首扫描产生完整画面的办法不适用于这两张，不能凭换支持渐进显示的库就实现。
3. AOSP ThumbnailUtils对EXIF内嵌JPEG可直接接受再缩放，没有检查必须达到所请求的像素下限；与当前系统返回小图现象一致，但不是对三星MediaProvider内部的直接源码证明。
4. libjpeg-turbo本身已有ARM64 NEON优化。换Kotlin为C++、或声称新增SIMD，不能直接当作提升；要核对实际编译配置与热点。
5. 仓库indexed-jpeg使用带索引补丁的libjpeg-turbo 1.3.0，CMake编译jsimd_none.c，jconfig.h显式禁用WITH_SIMD。这个模块有优化空间，但首开两张地图并未走此索引路径，不能把当前等待归因于它或简单启用它就宣称修复。

## 可检验的候选

| 候选 | 具体方法 | 价值和限制 |
| --- | --- | --- |
| 原生libjpeg-turbo对照 | 应用内隔离JNI库、现代ARM64 NEON构建、直接FD/内存输入、目标512与适屏分别测 | 能控制缩放、颜色输出、快速色度上采样及IDCT选项；系统本身已有Turbo/SIMD，收益未知 |
| ImageDecoder / NDK AImageDecoder | 同文件setTargetSize，软件分配为基准，比较FD输入与当前流输入 | 官方受支持，集成风险较低；同属系统解码，不是全新算法，不能先承诺明显提速；HARDWARE是像素存储分配选项，不等同开启JPEG硬件解码 |
| 针对热点的ARM64优化 | Simpleperf分析具体函数，必要时NEON内建/汇编、编译器PGO/LTO、反汇编检查 | 应先判断熵解码、IDCT、颜色转换还是复制占主要时间；已有SIMD的步骤不应重复优化。需要同图、同画质对照 |
| 第四项：修改Skia/JPEG缩放路径 | 应用内实验分支评估1/16、1/32等低分辨率直出；联合检查Skia尺寸选择、JPEG块输出及颜色转换/采样融合 | 不能只改Skia的档位常量：底层libjpeg-turbo对≤1/8的请求仍按每8×8块至少一个输出样本计算尺寸。需改变实际像素生成/采样路径，不能用1/8解出后普通缩图冒充新解码档位；压缩流熵解析仍可能占主导 |
| jpegli | 另一个可用C/C++JPEG编解码实现 | 官方主要说明编码画质/压缩及高精度解码能力，不能把压缩率提升当作解码速度提升；作为次级对照，不优先押注 |
| zune-jpeg | Rust候选，支持增量输入/部分输出 | 当前README主要强调AVX2/SSE；不能把x86成绩用于三星ARM64。顺序JPEG的部分扫描行不是完整低分辨率预览，不能偷换目标 |
| GPU/专有硬件 | 进一步确认手机可用的公开接口后再评估 | 查到的GPU高倍加速论文多为NVIDIA A100/V100/CUDA，不能移植其成绩到Adreno手机。尚未找到适合本项目、已验证可用的通用Android JPEG硬件解码方案 |

## 下一步实验的验收方式

独立工具先比较现有系统BitmapFactory、ImageDecoder/NDK及原生Turbo，不先替换产品逻辑。输入为同一原图，只读；输出统一尺寸、方向和可比画质，分别记录总耗时、线程CPU时间、内存、读取量和有效输出。对照单次解码，避免同时解两路把争用混入解码器比较。真实能耗需另设合适测量条件，不把CPU时间缩短直接当作省电。

用户新增第四项纳入同一对照：先以单改请求比例为控制组，记录底层实际output_width/output_height，确认是否仍停在1/8；再实验解码/采样融合，逐项记录熵解析、IDCT、颜色转换/输出与拷贝时间。1/16/1/32只是候选档位，最终保持原图比例和既定512像素下限，不能通过低于验收尺寸或明显降低画质制造提速。修改范围为应用内隔离原生库，不替换三星系统Skia；需要改的是实际算法路径而非只改返回的尺寸数字。

只有在同一三星设备的重复对照中有实际改善，再接入有收益的路径；原生函数热点明确后，才选择手写ARM64/NEON或编译优化。上文为研究阶段约束；新增实验已完成并接入隔离trace测试包，结果及未测范围见 [实验记录](viewer-native-jpeg-experiment-2026-10-21.md)。

## 来源及本地证据

- 当前Skia JPEG代码： https://github.com/google/skia/blob/main/src/codec/SkJpegCodec.cpp （本地快照`scratch/viewer-512-decode-fix-20261020/SkJpegCodec.cpp`）。
- Android 16 ThumbnailUtils源码： https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/media/java/android/media/ThumbnailUtils.java （快照同目录`ThumbnailUtils.java`）。
- libjpeg-turbo SIMD覆盖： https://libjpeg-turbo.org/About/SIMDCoverage 。
- libjpeg-turbo参数性能说明： https://libjpeg-turbo.org/About/Performance 。
- libjpeg-turbo底层缩放尺寸计算： https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/src/jdmaster.c （jpeg_core_output_dimensions对scale_num*8<=scale_denom仍输出ceil(image_dimension/8)；与本地旧库对应逻辑一致）。
- 渐进JPEG与buffered-image说明： https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/doc/libjpeg.txt 。
- Android ImageDecoder： https://developer.android.com/reference/android/graphics/ImageDecoder 。
- NDK解码接口： https://developer.android.com/ndk/guides/image-decoder 、https://developer.android.com/ndk/reference/group/image-decoder 。
- 原生热点分析： https://developer.android.com/ndk/guides/simpleperf ；PGO： https://developer.android.com/ndk/guides/pgo 。
- jpegli： https://github.com/google/jpegli （快照`jpegli-README.md`）；zune-jpeg： https://github.com/etemesi254/zune-image/tree/dev/crates/zune-jpeg （快照`zune-jpeg-README.md`）。
- GPU研究原文： https://arxiv.org/abs/2111.09219 。
- 原图头部只读检查结果：`scratch/viewer-512-decode-fix-20261020/jpeg-header-analysis.json`，仅保存结构/尺寸，不保存头部原始字节。
- 自带索引库构建：`indexed-jpeg/src/main/cpp/CMakeLists.txt`、`vendor/libjpeg-turbo-indexed/jconfig.h`，仅核对未修改。
