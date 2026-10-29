# 扫描页十案与无重复页十案

最终选定扫描 02 + 结果 03，打开 index.html?final=1，或访问本机 http://127.0.0.1:8768/index.html?final=1 。修订与检查见 FINAL.md。原始 index.html 仍保留全部二十案，可切换扫描页、浅深色、编号放大；扫描页可切换阶段及进度。

已按最后确认的要求统一：扫描后没有重复和清理后没有重复共用同一个页面，只显示 MD3 风格图形与「没有重复」。没有清理完成、结果数字、说明小字或额外按钮。

总览：

- empty-ten-light.png / empty-ten-dark.png：无重复页十案。
- scan-ten-light.png / scan-ten-dark.png：扫描页十案。
- empty-01.png 至 empty-10.png、scan-01.png 至 scan-10.png：各方案单独大图。
- DESIGN.md：设计约束与两遍复核。
- checks.json：预览交互与尺寸检查结果。

使用 frontend-design skill。分支 codex/dedup-scan-result-design，基于已经合并前一轮工作的 feature-3 / 6ea4c557。最终方案现已接入原生扫描和结果页面，标签相册取消标签改用带斜杠的 LabelOff 图标。扫描、选择、授权和回收站处理逻辑保持原样；原生验证见 native/verification.md。

验证：执行 node docs/dedup-scan-empty-2026-10-29/render.cjs。全部二十案的手机宽度、返回演示、浅深色、结果入口一致性、加载准备阶段不显示伪造计数、进度交互均通过；浏览器无页面错误。已查看扫描浅色总览、结果浅深色总览和手机深色大图。图片和进度都是方案数据，不连接用户文件。
