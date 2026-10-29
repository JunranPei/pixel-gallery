# 设置页共享组件

所有新设置项直接复用这些组件和几何值，避免在各页面重新定义圆角、间距或选项按钮。

## 色块与间距

`SettingsGroupSpec`（一级编组）和 `SettingsBlockSpec`（二级色块）是两个独立的不可变类，位于 `ui/settings/SettingsBlocks.kt`。对应界面组件为 `SettingsGroup` 和 `SettingsBlock`。

页面使用 `SettingsGroup(itemCount = 3) { index -> … }`，只根据 index 填入三项内容。一级组件自动创建三个二级实例，设置圆角与 4dp 间距，不需要页面手动指定首、中、尾。传入 1 时生成四周大圆角的独立块；数量必须大于零。

二级类封装位置与底色，`SettingsBlock` 统一绘制背景和内容。一级类通过 `blockAt(index)` 分配以下位置：

- `Single`：独立设置块，四角均为 24dp。
- `First`：编组首项，上侧 24dp，下侧 6dp。
- `Middle`：编组内部，四角 6dp。
- `Last`：编组末项，上侧 6dp，下侧 24dp。

`SettingsLayout.ItemSpacing` 是 4dp，`GroupSpacing` 定义为它的两倍（8dp）。一级组件内部统一使用前者；页面列表以整个一级编组为条目，统一使用后者。About 和 Clear Cache 各为数量 1 的独立组，不再手工添加额外间距。

所有设置页的列表边距复用 `SettingsLayout.ContentPadding`。

Library 的 Label backup 与 Excluded Folders 使用 `SettingsGroup(itemCount = 2)`，自动分配 First / Last，中间 4dp，连接面为小圆角。Settings 主页面、Display、Themes、Performance 与标签操作弹窗也已改用一级组件。

## 两级底色

`SettingsBlockTone.Standard` 使用主题的 surfaceContainer；`Emphasized` 使用 surfaceContainerHighest。一级组件统一将底色配置传给各二级实例，二级组件负责绘制。默认普通层级，About 与 Clear Cache 选择 Emphasized。页面不再重复指定色值，动态主题与深色模式统一由组件获取颜色。

## 任意数量的单选项

`SettingsOptionControl<T>` 位于 `ui/components/SettingsOptionControl.kt`，接受 `(value, label)` 列表、当前选值与变更回调。不限于三个选项；二选、三选和更多选项使用同一组件。

选中项使用主题主色、圆胶囊和勾号；未选项使用浅色容器与圆角矩形。点击改变选中值时，同一组件负责形状和颜色变化。不要重新复制 FilterChip 或一组始终为胶囊形状的按钮来代替。

Theme mode、Hand preference、媒体类型、Quick Sort 相册筛选，以及 Sort Photos / Sort Albums 的条件和方向均复用这个控件。
