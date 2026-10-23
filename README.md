# PhotoSync — 相册局域网同步工具

把 Android 手机相册里的照片和视频，在**家庭 WiFi 局域网内**一键备份到电脑。不依赖云盘、不占外网流量、不限速不限量。

## 特性

- **一键同步**：主界面一个「开始」按钮，检查 WiFi 和电脑在线后自动开始；通知栏常驻进度，随时可停
- **增量续传**：手机本地数据库记录每个文件的指纹（mediaId + 大小 + 修改时间），中途停止、断网、电脑关机都安全——已完成的文件不丢，下次只传剩余，不重复不遗漏
- **流式传输**：512KB 分块边读边发，1GB 以上的大视频也稳定传输、不占内存
- **后台运行**：前台服务 + WiFi 锁，切应用、锁屏都不中断
- **自动发现配对**：手机一键搜索局域网内的电脑，选择身份即完成配对，无需手动填 IP
- **多设备多电脑**：按设备分目录保存互不覆盖；同一台电脑两部手机可同时同步
- **完整性保证**：`.part` 临时文件 + 原子改名 + 启动清理，任何时刻强制停止（断电、杀进程）都不会在正式目录留下半截文件
- **磁盘保护**：可设置保留空间（默认 5GB），空间不足自动停止并提示

非目标（v1 不做）：云同步、定时自动同步、电脑→手机反向同步、同步删除、iOS。

## 组成

| 目录 | 说明 |
|---|---|
| `desktop/` | 电脑端接收软件：Python + tkinter，Win10/Win11，可打包免安装单文件 exe |
| `android/` | 手机端 App：Kotlin 原生，Android 10+ |
| `docs/PROTOCOL.md` | 两端通信协议契约（HTTP 接口、UDP 自动发现、文件命名/去重规则） |

## 快速上手

### 电脑端

```bash
cd desktop
python server.py          # 带界面运行（需 Python 3.10+，仅用标准库）
python server.py --cli    # 无界面运行（调试）
python test_client.py     # 自动化验收：18 项用例
build_exe.bat             # PyInstaller 打包单文件 exe（dist\PhotoSyncReceiver.exe）
```

exe 拷到任意 Windows 电脑双击即用，首次运行自动生成 `config.json`（端口、保存目录、保留空间、两台手机的配对令牌）。

### 手机端

```bash
cd android
./gradlew.bat assembleRelease     # 需 JDK 17 + Android SDK 34；签名密钥自行配置
adb install -r app/build/outputs/apk/release/app-release.apk
```

（`local.properties` 指向本机 SDK 路径，自行创建；release 签名密钥不入库，见 `.gitignore`）

### 配对

1. 电脑运行接收软件（多台电脑可同时运行，手机都能搜到）
2. 手机 App →「设置」→「搜索电脑」→ 从列表选择 → 选择本机身份（手机A/手机B）→ 地址和令牌自动填入
   - 也支持手动填 IP + 端口 + 令牌
3. 「选择要同步的相册目录」勾选 Camera / Screenshots 等
4. 主界面点「开始同步」

## 同步规则摘要

- 手机端维护本地数据库（Room），比对「相册清单 − 已完成记录」得到待传列表，无需询问电脑
- 照片在手机上被编辑后视为新版本，电脑端另存（文件名加 `_m<mediaId>` 后缀），不覆盖原备份
- 手机删除照片不影响电脑端备份
- 电脑磁盘剩余空间低于保留值时停止接收（HTTP 507），手机端停止同步并给出提示
- 卸载重装手机 App 后，首次同步会向电脑逐个查询已有文件（`/status`），已存在的自动跳过

## 开发环境

- 电脑端：Python 3.10+（标准库，无第三方运行时依赖；打包用 PyInstaller）
- 手机端：JDK 17、Android SDK Platform 34、Gradle 8.2.1 / AGP 8.2.2 / Kotlin 2.0.21、Room + OkHttp
- 通信：局域网 HTTP（明文，见 PROTOCOL.md 的安全说明）+ UDP 广播自动发现
