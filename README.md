# 睡眠站台 Sleep Station 🌙🔦

到点自动亮起手电筒、用"光"把你叫醒的安卓闹钟——同时完整兼容传统铃声闹钟。
手机秒变"唤醒灯"，宿舍早起不吵室友，冬天夜里不用摸黑找灯。

> 全新月亮星星图标 + 柔和星光色系（深星蓝紫 × 柔金），整体氛围为夜晚而生。

> 基于 [Fossify Clock](https://github.com/FossifyOrg/Clock)（GPL-3.0）二次开发，
> 亮灯思路参考 [Flashy Alarm](https://github.com/yahyatinani/flashy-alarm)（GPL-3.0）。

## ✨ 功能

### 🔦 光唤醒（核心差异化）
- **响铃时亮手电筒**：闹钟一响闪光灯常亮，与传统铃声自由组合
- **仅光唤醒**：不响铃、只亮灯，安静叫醒（勾选自动联动开灯）
- **日出渐亮**：响铃前 1 / 5 / 10 / 20 / 30 分钟，手电筒以"占空比渐亮"从微光爬到全亮，
  配合响铃页屏幕亮度爬坡，模拟自然日出；LED 无亮度档位也能做出柔和的 sunrise
- 精确闹钟调度（`setExactAndAllowWhileIdle`），省电打盹模式也会准时渐亮
- 手电筒被相机占用时自动降级为屏幕光，不影响叫醒

### 📊 睡眠报告（个人节律）
- 自动记录每次闹钟：几点响、几点真正起、赖床多久、是否贪睡
- 汇总最近 30 次起床：**平均起床时间 / 按时率 / 平均赖床时长** + 逐条明细
- 数据全部来自真实的"关闹钟"动作，无需佩戴任何设备

### 🌙 睡前助眠收藏夹
- 内置精选：myNoise 声景、Noisli 白噪音、LibriVox 中文公版有声书、雨声/睡前故事
- 支持收藏自己喜欢的小说、博客、音乐链接，一键外部打开，长按删除
- 支持收藏**本地文件**（小说 txt、音频 mp3 等），系统文件选择器选取，打开时交给本地应用

### ⏰ 传统闹钟全功能（来自 Fossify Clock）
重复闹钟、自定义铃声、贪睡、振动、音量渐增、时钟/秒表/计时器、桌面小部件等全部保留。

## 📲 安装

克隆本仓库自行构建（见下），或从 [Releases](../../releases) 下载 APK（如有）。

> 1.0.1 起修复了闹钟列表在 commons 6.1.6 上的必现崩溃（`getSelectedDaysString`
> 内部对 `Arrays$ArrayList` 的非法强转），应用内已用安全实现替代。
> 1.0.2 修复了"新增/编辑闹钟"弹窗里的同类崩溃（星期字母行构建），并新增本地文件收藏。

## 🔨 构建

- Android Studio：直接打开项目根目录，选 `foss` 变体运行
- 命令行：`./gradlew :app:assembleFossDebug`（需 JDK 17+ 与 Android SDK 36）
- 国内网络：Gradle 发行版可用 `https://mirrors.cloud.tencent.com/gradle/` 镜像，
  Maven 依赖可用 `https://maven.aliyun.com/repository/google` 镜像（init 脚本注入 settings 仓库）

## 🗺️ Roadmap

- [ ] 独立应用图标与配色（当前沿用上游资源）
- [ ] 真正的 AI 助眠推荐（当前为内置精选清单）
- [ ] 睡眠报告趋势图
- [ ] 独立包名与签名发布

## 📄 许可

本项目沿用 **GPL-3.0** 许可证（见 [LICENSE](LICENSE)）。
基于 Fossify Clock 的改动与新增功能说明见 [FORK_NOTES.md](FORK_NOTES.md)。

- 上游：[FossifyOrg/Clock](https://github.com/FossifyOrg/Clock) · GPL-3.0
- 参考：[yahyatinani/flashy-alarm](https://github.com/yahyatinani/flashy-alarm) · GPL-3.0
