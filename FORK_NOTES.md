# 睡眠站台 Sleep Station · 改造说明

基于 **Fossify Clock**（GPL-3.0，https://github.com/FossifyOrg/Clock）二次开发，
亮灯逻辑参考 **Flashy Alarm**（GPL-3.0，https://github.com/yahyatinani/flashy-alarm）。

按 GPL-3.0 要求，本仓库沿用 GPL-3.0 许可证（见 LICENSE）。

## 新增差异化功能

### 1. 光唤醒（手电筒 + 屏幕光，渐亮日出）
- `helpers/TorchHelper.kt`：`CameraManager.setTorchMode` 封装（API 23+，无需相机权限），
  自动寻找带闪光灯的相机（优先后置），相机被占用时安全返回 false。
- `services/SunriseService.kt`：前台服务，闹钟响铃前做 **占空比 PWM 渐亮**
  （LED 无亮度档位，用亮/灭比例按 ease-in 曲线爬坡模拟日出），持有 PARTIAL_WAKE_LOCK。
- `receivers/SunriseReceiver.kt`：精确闹钟（`setExactAndAllowWhileIdle`）提前触发渐亮，
  调度逻辑在 `extensions/Context.kt` 的 `setupAlarmClock` / `cancelAlarmClock`。
- 闹钟编辑弹窗新增三项：
  - **响铃时亮手电筒**（`Alarm.enableTorch`）
  - **仅光唤醒（不响铃）**（`Alarm.lightOnly`，勾选自动开启手电筒）
  - **日出渐亮**（`Alarm.sunriseMinutes`，0/1/5/10/20/30 分钟）
- 响铃时 `AlarmService` 开灯（lightOnly 时跳过铃声与震动）；
  `AlarmActivity` 响铃页屏幕亮度 30 秒爬坡到最亮，配合手电筒形成"日出"。
- 手电筒被其他 APP（相机）占用时自动放弃闪灯，响铃页屏幕光仍然生效。

### 2. 个人节律 · 睡眠报告
- `DBHelper` 新表 `sleep_records`（DB 版本 2→3 自动迁移）：闹钟开响时开记录、
  最终关闭时闭合，贪睡打标记。
- `activities/SleepReportActivity.kt`（主菜单入口）：最近 30 次起床的平均起床时间、
  按时率（≤5 分钟）、平均赖床时长与逐条记录。

### 3. 睡前助眠收藏夹
- `helpers/RelaxStore.kt` + `activities/RelaxActivity.kt`（主菜单入口）：
  内置"助眠精选推荐"（免费合法内容：myNoise、Noisli、LibriVox 中文公版有声书、
  雨声/睡前故事视频搜索），支持添加自定义收藏（标题 + 网址，存 SharedPreferences），
  点击用外部应用打开，长按删除。

## 数据库迁移
`DB_VERSION 2 → 3`：alarms 表新增 `enable_torch` / `light_only` / `sunrise_minutes` 三列，
新建 `sleep_records` 表；旧版本逐级 `ALTER TABLE`，不丢数据。

## 构建
Android Studio 直接打开本目录即可（minSdk 26）。应用名：睡眠站台（Sleep Station）。
包名保持 `org.fossify.clock`，如需独立包名请全局重构后再上架。

## 已知上游兼容问题（已修复）
- commons 6.1.6 的 `Context.getSelectedDaysString` 会把 `getStringArray().toList()`
  （`Arrays$ArrayList`）强转为 `java.util.ArrayList`，闹钟列表一渲染必崩。
  已在 `extensions/Context.kt` 用 `getSelectedDaysStringSafe` 替代，
  并有 Robolectric 回归测试（`LaunchSmokeTest`）覆盖列表绑定。
- 同款强转也在上游 main 的 `EditAlarmDialog` 里（星期字母行构建），
  "新增/编辑闹钟"一打开就崩；已改为 `toCollection(ArrayList())`，
  回归测试 `editAlarmDialog_opens_withoutCrash` 覆盖。
