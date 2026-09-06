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

## 品牌视觉（v1.0.3）
- 启动图标：自适应图标重绘为"新月 + 四芒星光"（深夜渐变底 + 柔金月光 + 微光辉光），
  含 monochrome（Android 13+ 主题图标）；移除上游 19 色变体与全部 activity-alias
- 界面调色板：覆盖 `color_primary / color_primary_dark / color_accent`
  为星光色系（深星蓝紫 #4E57A5 × 柔金 #E7C97F，夜间更深一档）
- 桌面长按快捷方式（秒表）改为固定品牌金，不再跟随旧的多色图标系统

## v1.0.7 界面升级
- **单次闹钟**：闹钟编辑弹窗顶部新增"单次 / 重复"类型切换（单次 = 现有 dayless
  语义：今天/明天响一次后自动停用；切回"重复"默认预选每周明天）
- **助眠 tab 重构**：改为三张入口大卡片（我的收藏 / 精选推荐 / 睡眠报告），
  点进才展开对应列表（收藏夹与精选页内切换、返回键回 hub；报告进独立页），
  报告卡副标题实时显示最近 30 次起床摘要
- **整体卡片化**：自绘 bg_card_rounded（星光紫 6% 底 + 同色描边，明暗主题都柔和），
  应用于助眠卡片、收藏/报告列表行、睡眠报告摘要块；新增文件夹/星光/报告矢量图标

## v1.0.8 可读性 / 权限 / 推荐接口
- **助眠板块文字提亮**：卡片标题纯白、副标题 72% 白，不再依赖主题灰字，
  也去掉了会把它们刷回主题色的 updateTextColors
- **一键权限向导**：设置 → 闹钟可靠性 → "一键开启所需权限"。逐项检测
  （通知 / 精确闹钟 / 电池优化 / 悬浮窗 / 厂商自启动），已授权的自动跳过，
  每完成一项自动进入下一项
- **精选推荐接口**（两用）：
  1. *运营自定义*：编辑 `app/src/main/assets/relax_picks.json`
     （`[{"title":"...","url":"..."}]` 数组）即可整体替换精选内容，无需改代码；
     文件缺失/为空时回退到内置默认。实现见 `helpers/PicksRepository.kt`
  2. *社区推荐*：`RelaxStore` 新增 CommunityPick 模型（本地 SharedPreferences 存储），
     用户可在"精选推荐 → 大家的推荐"提交自己的推荐、长按打分（1-5 助眠评分），
     列表按平均分×评价数聚合排序。接后端时只需替换 PicksRepository 的数据源
     与 RelaxStore 社区方法的同步逻辑，UI 无需改动

## v1.1.0 功能裁剪与振动修复
- **删除秒表**：tab、Fragment/Adapter/Service、桌面快捷方式、设置项、横屏布局全部移除，
  现在只有 助眠 / 闹钟 / 计时器 三个板块
- **删除日出渐亮**：SunriseService/Activity/Receiver、编辑弹窗里的渐亮时长选择、
  提前调度全部移除；光唤醒保留"响铃时手电筒常亮 + 仅光唤醒"两种形态，
  响铃页屏幕亮度 30 秒爬坡保留
- **振动修复**：Android 12+ 改用 VibratorManager.defaultVibrator 标准路径
  （旧 VIBRATOR_SERVICE 在部分新系统上失效），整个振动块独立 try/catch，
  出错也不会拖垮声音和手电筒
- **手电筒可靠性**：响铃期间每 2 秒重申一次 torch 开关——闪光灯被系统/相机
  短暂抢占后能自动恢复，亮灯与振动互不影响

## v1.1.1 响铃引擎重做（小米 15 实测振动失效后）
- **根因**：HyperOS 会压制"应用进程自己发起"的媒体播放与振动；VibratorManager
  是官方唯一振动器 API（没有更底层接口），问题不在 API 而在执行主体
- **新引擎**：铃声与首段振动改由**系统通知渠道**承载——每条闹钟按
  (铃声, 振动) 组合建独立渠道（IMPORTANCE_HIGH、免打扰穿透、USAGE_ALARM 铃声、
  振动 pattern），通知带 FLAG_INSISTENT 循环铃音，由系统本体执行，
  应用进程被限制时依然响；应用内 VibratorManager 振动保留作即时补充
- 音量渐增设置随 MediaPlayer 移除而不再生效（渠道铃声按闹钟音量播放）

## v1.1.5 振动三通路自检 + 闹钟级振动（小米 15 实测跟进）
- 触感自检与通知振动自检在小米 15 上均无体感（勿扰已排除、通知权限已开），
  而系统自带闹钟正常——差别在特权标签。新增第三条通路：
  **闹钟级振动**（`VibratorManager.vibrate` + `CombinedVibration` +
  `VibrationAttributes.USAGE_ALARM`），与系统时钟同一特权层
- 响铃路径（服务 + 接收器应急）全部改用闹钟级振动
- 自检触感改用显式 255 振幅三脉冲（排除 DEFAULT_AMPLITUDE 被忽略的可能）
- 诊断新增响铃模式（静音会压制通知振动）；勿扰开着时诊断给出
  "勿扰访问授权"入口，授权后响铃自动切正常模式、关闭后还原

## v1.2.4 悬浮窗响铃兜底（HyperOS 拦截 startActivity 的最终解法）
- 实测（小米 15）：到点亮屏 ✓ 手电 ✓ 闹钟级振动 ✓，日志记到"已尝试拉起响铃页"
  但界面不出——HyperOS 在全屏意图 app-op 之外，还有独立于"显示悬浮窗"的
  **"后台弹出界面"**与**"锁屏显示"**两个 MIUI 私有 app-op，会静默吞掉后台
  startActivity（无异常、无系统日志，诊断只能看到"尝试过"）
- **兜底 UI**：`RingOverlayHelper` 用 `TYPE_APPLICATION_OVERLAY` 直接画全屏响铃页
  （完全不走 Activity，锁屏也盖得住）。响铃路径 startActivity 后 2 秒检查：
  真响铃页（AlarmActivity / TimerAlarmActivity）没到前台就弹兜底页；
  真页一进来（onCreate）自动撤掉兜底，避免双界面
- 兜底页功能：闹钟版（当前时间 + 停止 + 按配置分钟数贪睡）、计时器版（标签 + 停止）；
  停止复用 `alarmController.stopAlarm` / 计时器熄灯-停振-撤通知链路；
  响铃服务清理与计时器自动清理时同步撤页
- **MIUI 权限可视化**：`MiuiHelper` 反射读 op 10018/10020/10021，诊断面板新增
  "后台弹出界面（小米）/ 锁屏显示（小米）/ 自启动（小米）"三行真实状态
- **向导升级**：一键权限向导（首启 + 设置页两份）新增直达 MIUI"其他权限"页步骤
  （PermissionsEditorActivity，失败回退应用信息页），两项权限都已开启则自动跳过；
  SettingsActivity 向导补齐悬浮窗/全屏意图步骤，并修掉诊断函数里错位的向导步骤注入

## 可靠性说明（重要）
- **完全关机（长按电源键关机）后，任何第三方 APP 都无法被唤醒**——RTC 硬件闹钟只有
  厂商系统级时钟可用，这是硬件/系统层限制。可用的替代：部分机型自带"定时开机"；
  或保持充电 + 熄屏（锁屏状态闹钟正常响铃，应用有全屏意图 + 亮度爬坡）。
- **重启后恢复**：BOOT_COMPLETED 等广播触发 `RescheduleAlarmsReceiver` 重排全部
  闹钟与日出调度（含 USE_EXACT_ALARM 精确闹钟）。前提是该 ROM 允许应用开机自启——
  应用会在首次启动时引导用户打开厂商自启动页（小米/华为/OPPO/vivo/魅族组件直达）。
- **后台稳定**：精确闹钟在应用未被强杀时必然触发；国产 ROM 滑动清理等于强杀（会取消
  全部闹钟），因此每次冷启动自检电池优化白名单（未通过就再次弹引导），
  引导顺序：省电白名单 → 厂商自启动。用户侧口诀：白名单 + 自启动 + 最近任务卡片锁定。

## v1.0.5 行为升级
- **日出渐亮重做**：不再用占空比闪烁（LED 无亮度档，调制即闪烁）；手电筒全程稳亮，
  新增全屏"日出页"（锁屏可见）把屏幕亮度与天空色从深夜平滑爬坡到暖昼，轻触可隐藏画面、灯光继续
- **助眠 tab**：睡前助眠 + 睡眠报告合并为第一个 tab，"时钟" tab 移除
  （ClockFragment 代码保留但不再挂载）；完整睡眠报告从卡片进入
- **后台保活**：新增电池优化白名单一次性引导（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）；
  重启后由 BOOT_COMPLETED 接收器重排全部闹钟与日出调度（上游已有链路）

## 已知上游兼容问题（已修复）
- commons 6.1.6 的 `Context.getSelectedDaysString` 会把 `getStringArray().toList()`
  （`Arrays$ArrayList`）强转为 `java.util.ArrayList`，闹钟列表一渲染必崩。
  已在 `extensions/Context.kt` 用 `getSelectedDaysStringSafe` 替代，
  并有 Robolectric 回归测试（`LaunchSmokeTest`）覆盖列表绑定。
- 同款强转也在上游 main 的 `EditAlarmDialog` 里（星期字母行构建），
  "新增/编辑闹钟"一打开就崩；已改为 `toCollection(ArrayList())`，
  回归测试 `editAlarmDialog_opens_withoutCrash` 覆盖。
