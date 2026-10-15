# 网页来源与许可

网页结构、交互、计算与玻璃渲染代码由本项目维护，采用 GNU GPL-3.0；许可全文随静态产物保存在 [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt)。项目源码及说明见 [calculate_overtime](https://github.com/NiceXHZ/calculate_overtime)。

## 设计与技术参考

参考 [cocolayuan/liquid-glass](https://github.com/cocolayuan/liquid-glass) 介绍的边缘折射、镜面高光及场景对齐思路。网页的轻量渲染器为独立实现，未引入该仓库源码或 React 组件依赖；只在顶部导航和底部操作栏生成有界位移贴图，普通统计面板使用半透明材质。

## holiday-cn

Copyright (c) 2019 NateScarlet. 内置 2025、2026 年节假日安排来自 [NateScarlet/holiday-cn](https://github.com/NateScarlet/holiday-cn)，采用 MIT License，全文见 [licenses/holiday-cn-MIT.txt](licenses/holiday-cn-MIT.txt)。

`holidays.js` 将仓库现有 Android 内置 JSON 无内容改动地打包为 JavaScript，保留上游 `papers` 中的官方通知来源。网络更新还会尝试 [china-holiday-calender](https://github.com/shuyz/china-holiday-calender) 与 [Timor](https://timor.tech/api/holiday) 的公开接口。

## 开发与测试依赖

Playwright、axe-core 及其包装用于开发环境的浏览器与无障碍测试，版本由根目录 `package-lock.json` 固定。它们不进入网页静态产物，也不会在用户浏览器中运行。
