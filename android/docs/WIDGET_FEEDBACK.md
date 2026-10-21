# 桌面小组件与快捷磁贴反馈

调研日期：2026-10-21。适用于版本 0.3.4。

六种小组件和快捷磁贴共用 `recordFromOutside` → `QuickPunchFeedback`，不启动 Activity。保存成功后显示实际保存时间并尝试轻震一次；3 秒内重复点击或写入失败只显示文字。Toast 使用系统样式，新的反馈替换尚未消失的旧提示。先提交记录并反馈，再刷新小组件，避免桌面刷新拖慢确认。

设置 → 桌面上的小帮手 → **快捷打卡震动**，默认开启，仅控制小组件与快捷磁贴，与滚轮震动独立。新增 `android.permission.VIBRATE` 普通权限，无运行时授权弹窗；不申请通知或悬浮窗权限。

## 厂商资料与适配选择

| 品牌 | 查阅的官方资料 | 本项目的处理 |
| --- | --- | --- |
| 小米／Redmi／POCO | [MiHaptic](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1526) 提供复杂波形，案例为音乐和游戏；[Android 16 适配指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2035)介绍安卓新触感 API | 使用系统预设轻触效果，不额外集成 MiHaptic，不反射私有接口 |
| 华为 Android／EMUI | [Haptics Engine](https://developer.huawei.com/consumer/cn/haptics-kit/)支持 Android 应用内波形，[接入示例](https://developer.huawei.com/consumer/cn/codelab/HUAWEIHapticsKit/)需要额外 SDK 与接入准备 | 保留标准 Android 实现，不为单次轻震接入整套 SDK；HarmonyOS 原生卡片不属于本 APK 的适配范围 |
| OPPO／ColorOS | [HyperBoost 振感](https://open.oppomobile.com/new/developmentDoc/info?id=10744)提供游戏／普通应用振感接口，需要接入 SDK | 使用公开系统 API，由系统处理机型马达差异 |
| vivo／iQOO | [4D 游戏振感](https://developers.vivo.com/product/d/gamingVibration)主要面向游戏；[Android 12 适配文档](https://developers.vivo.com/doc/d/4b46442a615849c3924aedb567ca3ad1)提醒非 SDK 接口限制 | 不调用游戏接口或隐藏接口，按安卓版本降级 |
| 三星／One UI | [Haptic 设计规范](https://developer.samsung.com/one-ui/sound-and-haptic/haptic.html)建议清脆、克制的反馈与视觉结果同步 | 使用短暂的系统点击效果，不使用长震动 |
| 荣耀、Pixel 及其他可运行本 APK 的安卓设备 | [Android 公开触感 API](https://developer.android.com/develop/ui/views/haptics/haptics-apis) | 共用相同的版本、能力与设置检查，不依赖品牌字符串 |

在上述查阅范围内，没有找到厂商公开承诺“普通 Android AppWidget 后台触感可无条件播放”的接口说明。复杂波形 SDK 的存在不代表能突破系统后台限制，因此没有为不同品牌添加未经验证的私有分支。

## 系统版本与降级

| Android | 处理 |
| --- | --- |
| 8–9（API 26–28） | `Vibrator` + 18 毫秒单次脉冲，有幅度控制时使用较轻强度；无幅度控制时用系统默认强度 |
| 10–11（API 29–30） | 系统 `EFFECT_CLICK`，效果由系统和硬件降级处理 |
| 12（API 31–32） | 优先 `VibratorManager.defaultVibrator`，缺失时回退 `Vibrator`；使用触感音频属性 |
| 13+（API 33+） | 相同设备选择，明确使用 `VibrationAttributes.USAGE_TOUCH` |

所有版本都先检查应用开关、振动权限、系统触感开关、静音／勿扰和马达存在性。厂商服务抛出异常时仅跳过震动，不影响 Toast、已保存的数据或小组件更新。不会用更强或更长的震动重试。

**后台限制仍然有效。** [Android Vibrator 文档](https://developer.android.com/reference/android/os/Vibrator)指出振动的前台与后台用途限制；系统可能静默抑制后台的触摸反馈，API 也不提供“用户确实感受到震动”的可靠回执。这里保持真实的触感用途，不改标闹钟／通知来绕过限制，不拉起透明页面、常驻前台服务或改变勿扰设置。系统禁止震动时保留 Toast 与记录刷新，不能宣称所有品牌后台必震。

[标准 Toast](https://developer.android.com/guide/topics/ui/notifiers/toasts)在 Android 12+ 显示应用图标且最多两行，系统也可能对后台高频提示限流；因此消息保持简短，提示是否显示不作为保存成功与否的依据。

## 验证范围

自动化在 Android 9 与 Android 15 的 Robolectric 环境检查成功／重复／失败消息、触感用途、旧版本脉冲、独立开关、系统触感、静音／勿扰、缺少马达、服务异常和 Toast 替换。设置交互回归检查开关持久化；原有广播测试检查实际保存及共用误触保护。

这些测试验证代码路径，不模拟各品牌真实的后台振动策略，也不代表真机适配认证。未使用 ADB；安装后按[设备验收清单](DEVICE_CHECKLIST.md)确认实际效果。
