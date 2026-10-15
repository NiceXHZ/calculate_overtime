# 加班时长计算器 / 加班打卡

记录出勤时间，按工作日、周末、法定节假日与调休规则计算加班时长。

网页端适合导入记录、计算和编辑数据；Android 应用「加班打卡」适合日常打卡、桌面快捷记录和按月查看。记录保存在本机，无需账号，通过 JSON 文件在两端之间导入导出。

| 使用方式 | 入口 | 要求 |
| --- | --- | --- |
| Android 应用 | [从 GitHub Releases 下载 APK](https://github.com/NiceXHZ/calculate_overtime/releases) | Android 8.0 及以上 |
| 网页工具 | [在线使用](https://calculate-overtime.vercel.app/) | 现代浏览器，无需安装 |
| iOS 快捷指令 | [安装打卡快捷指令](https://www.icloud.com/shortcuts/1d4ad7c1eae14dbb964682359b14b8fc) | 运行后将记录文件导入网页 |

当前 Android 源码版本为 **0.3.3**。安装包以 Releases 中实际发布的版本和附件为准；发布页暂无 APK 时，可等待发布或[自行构建](android/README.md#从源码构建)。

[下载与安装](#下载与安装) · [主要功能](#主要功能) · [开始使用](#开始使用) · [计算与数据](#计算与数据) · [开发与验证](#开发与验证)

## 下载与安装

### Android

1. 打开 [Releases 发布页](https://github.com/NiceXHZ/calculate_overtime/releases)，选择最新正式版本。
2. 展开该版本的 **Assets**，下载以 `.apk` 结尾的正式安装包，例如 `overtime-checkin-0.3.3-arm64-v8a.apk`。`Source code (zip)` 和 `Source code (tar.gz)` 是源码，不能直接安装到手机。
   常见手机选 `arm64-v8a`；模拟器按其 Android 系统架构选 `x86_64` / `x86`；不确定时选 `universal`。每个 APK 都可单独安装，详细对照见 [安装包平台](android/README.md#安装包平台)。
3. 在手机打开 APK。若系统提示安装权限，按提示允许当前浏览器或文件管理器安装此应用，再完成安装。
4. 打开「加班打卡」，即可直接记录，无需先创建文件或注册账号。

同一正式签名的版本可以覆盖升级并保留数据。若此前安装的是 debug 调试版，切换正式版前需要先**导出完整 JSON 备份**，再卸载调试版、安装正式版并导入。签名不同的安装包不能直接覆盖。

源码提交不包含 APK 或签名密钥，正式安装包通过 Release 附件提供。更多使用方法见 [Android 说明](android/README.md)。

### 网页与 iOS

网页可直接[在线打开](https://calculate-overtime.vercel.app/)，也可下载源码后用浏览器打开 `index.html`。

iOS [打卡快捷指令](https://www.icloud.com/shortcuts/1d4ad7c1eae14dbb964682359b14b8fc)每次运行会将当前时间追加到 `iCloud Drive/Shortcuts/overtime_log.json`，随后可将该文件导入网页查看。

## 主要功能

- **日常记录**：Android 一键打卡、今日时间线、打卡后撤销、日期与时间滚轮补卡。应用、组件和快捷磁贴共用 **3 秒**误触保护。
- **桌面快捷操作**：提供 **2×1、2×2、3×2、4×1、4×2、4×3** 六种小组件，以及系统快捷设置打卡磁贴。只有打卡按钮保存记录，点击时间查看今日记录。
- **按月统计**：识别周末、法定节假日和调休上班日。Android 支持跨月查看、自定义下班时间、月历热力图与 CSV 导出；网页查看设备当前月份。
- **备份与互通**：两端均可导入导出 JSON。Android 支持合并导入、完整备份与最近修改前副本恢复。
- **原生界面**：Android 支持深浅色、统一玻璃弹层和自适应排版；日期与时间滚轮带惯性吸附，可分别关闭刻度音效和轻震动。

## 开始使用

### Android：记录与查看

1. 在「今天」轻点「记录打卡」，当天时间线会自动更新。
2. 漏记时，在「今天」或「记录」中选择补卡，滚轮选好实际日期与时间后保存。
3. 在「统计」查看月度结果；需要时，在设置修改工作日下班时间，默认 **17:30**。
4. 长按桌面添加「加班打卡」小组件，选择适合的尺寸；也可在系统快捷设置中添加打卡磁贴。
5. 在「设置」定期导出完整 JSON，保存到应用之外的位置。

Android 首次启动会自动创建本地记录文件。完整操作说明、组件尺寸与反馈设置见 [Android 使用说明](android/README.md)。

### 网页：导入、计算与编辑

1. 打开[网页工具](https://calculate-overtime.vercel.app/)，导入 JSON，或先加载[示例数据](overtime_log.example.json)。
2. 等待「已加载」后，点击「计算加班」查看统计和每日明细。
3. 在「管理数据」中新增或删除本月打卡；导入空对象 `{}` 可从零开始录入。
4. 修改后点击「保存并导出新文件」。

网页不会覆盖原文件，未导出的修改会在刷新或关闭页面后丢失。iOS 快捷指令文件经网页编辑并导出后，也需要自行用新文件替换原文件。

## 计算与数据

| 日期类型 | 加班时长 |
| --- | --- |
| 工作日，包括周末调休上班日 | 最后一次打卡晚于下班时间时，计算下班时间至该次打卡的间隔 |
| 周末、法定节假日等休息日 | 至少两次打卡时取首末间隔，只有一次时为 0 |

- 节假日与调休规则优先于星期判断；不扣午休，不自动配对上下班，不合并跨午夜记录。
- 网页固定使用 **17:30** 和浏览器本地时区；Android 可修改下班时间，并固定按北京时间 `Asia/Shanghai` 分组。建议网页也在北京时间环境下使用。
- 打卡数据不上传服务器。两端会联网获取公开节假日数据，网络失败时使用已有数据；对应年份不可用时明确提示暂按星期规则计算。
- 两端通过手动导入导出 JSON 互通，**不会自动同步**。备份文件是导出时的数据快照，不会随以后的打卡自动更新。
- Android 的本地记录和自动副本会随卸载或清除数据而删除，换机或卸载前请先导出完整备份。

JSON 示例、兼容格式、导入合并与节假日缓存细节见 [数据格式与规则](docs/DATA_FORMAT.md)。

## 开发与验证

网页端无需构建，使用 Node.js 18 或更高版本运行测试：

```bash
node --test tests/overtime.test.cjs
```

Android 配置 JDK 21 与 Android SDK 36 后，在仓库根目录执行：

```bash
./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

签名、正式包构建与输出位置见 [Android 构建说明](android/README.md#从源码构建)，设备检查项见 [验收清单](android/docs/DEVICE_CHECKLIST.md)。自动化检查结果见 [代码与构建复查记录](android/docs/CODE_REVIEW.md)，覆盖网页、Android 逻辑与界面；真实设备上的 GPU 效果、触感与桌面行为仍需设备检查。

```text
index.html                 网页界面、样式与业务逻辑
overtime_log.example.json  公开示例数据
docs/                      通用数据格式与规则
tests/                     网页回归测试
android/                   Android 工程、资源、测试与详细说明
LICENSE                    GPL-3.0 许可证
```

## 许可证

项目采用 [GNU GPL-3.0](LICENSE)。第三方组件与节假日数据来源见 [第三方说明](android/THIRD_PARTY_NOTICES.md)。
