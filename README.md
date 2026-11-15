# DozeGuard

[![Build APK](https://github.com/Kosakanao/DozeGuard/actions/workflows/build.yml/badge.svg)](https://github.com/Kosakanao/DozeGuard/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

让 Doze 豁免清单由机主决定，而不是由 ColorOS 的云控决定。

全文按四块讲清楚：**为什么要干掉云控 → 使用环境 → 编译环境 → 编译方法**。

## 1. 为什么要干掉云控

**现象**：在 ColorOS / OPPO 上，把某个应用从电池优化白名单里移除，过一阵它又回来了。不用等到重启，设备进出一次 light doze 就回来。

**机制**：云控下发一份白名单到 `/data/oplus/os/battery/doze_wl_local.xml`。`com.oplus.nas` 在每次进出 light doze 时调用 `DeviceIdleController.addPowerSaveWhitelistAppsInternal(List)`，把整份清单重新灌进系统。系统设置里的用户选择不是这份清单的权威来源，所以怎么改都会被覆盖。

**后果**：

- 一批应用长期豁免低电耗，后台常驻、唤醒、耗电；
- 机主没有真正生效的开关，「省电」和「及时收消息」之间的取舍被厂商云控替你做了；
- 改配置文件、改设置项都没用，因为写回发生在框架层且反复发生。

**DozeGuard 的做法**：作为 libxposed API 102 模块，钩子跑在 `system_server`，在上述调用把白名单交给框架之前，先按用户配置剔除不该放行的包。只做内存态过滤，不改任何系统文件、不写系统分区。停用模块并重启，系统行为与安装前完全一致。

一句话：不是改掉云控清单，而是让云控清单不再能单方面生效。

## 2. 使用环境

| 项 | 要求 |
| --- | --- |
| 系统 | ColorOS / OPPO，且确实存在上述云控写回行为。其他 ROM 上没有作用对象 |
| Root | 需要。界面用 `su` 写 `/data/system/dozeguard.conf`、同步系统白名单 |
| 框架 | 支持 libxposed API 102 的管理器（开发实测用 Vector），作用域能设为 `system` |
| Android | 8.0+（minSdk 26），targetSdk 36 |
| 权限 | `QUERY_ALL_PACKAGES`，用于列出全部应用 |

钩子注入 `system_server`。虽然只做过滤、不落盘，仍是系统级改动。不满足条件，或不想承担风险，就不要装。

### 安装

1. 取 APK：到 [Releases](https://github.com/Kosakanao/DozeGuard/releases) 下载 tag 对应的包（推荐），或 [Actions](https://github.com/Kosakanao/DozeGuard/actions) 最新一次成功运行的 Artifacts → `DozeGuard-debug-apk`。
2. **在手机上直接点 APK 安装**：下载完用文件管理器点开，走系统安装器，按提示允许「安装未知来源应用」。这是推荐路径，不需要电脑、不需要 ADB。系统安装器会把完整 APK 写入后再落盘，框架能正常读到模块。
3. 在框架管理器里启用模块，作用域选 `system`，重启手机。
4. 确认钩子挂上了。有 ADB 时最直观：

   ```bash
   adb shell su -c 'cat /data/system/dozeguard.log'
   ```

   看到 `### installed on 2 method(s)` 即可。没有 ADB 时：看框架管理器里模块已启用且作用域包含 `system`，再看 App 底部状态栏的 `root ✓`；能正常勾选保存并看到白名单变化，钩子就是在工作。

**用 ADB 安装（可选）**

```bash
adb install -r --no-incremental app-debug.apk
```

`--no-incremental` 只用于排除 ADB 的 incfs 流式安装：这条路径是 ADB 特有的，且只有 APK 旁边存在 `.apk.idsig`（v4 签名）或显式指定 `--incremental` 时才会启用；本仓库产物不带 idsig，普通 `adb install -r` 会回退到完整安装。加这个参数只是把变量彻底排除。**手机上点安装不涉及 incfs，不需要这个参数。**

注意签名：CI 产物用临时 debug 签名、每次都不同，只能全新安装。若手机上装过别的签名的版本，先长按图标卸载旧包（或 `adb uninstall com.dozeguard`）再装。

### 使用与撤回

打开 App 默认停在「当前白名单」页，条目默认全勾选 —— 它们正是当前被豁免的应用。取消勾选、点「保存并应用」，对应应用就被移出白名单，之后云控再写回也会被拦掉。

- 白名单模式（默认）：只有勾选的能留在白名单，其余全部拦截；`android` 与 `com.android.*` 永不拦截，避免破坏系统。
- 黑名单模式：只拦勾选的应用，其余照常放行。
- 配置按文件 mtime 热加载，界面保存后不用重启。

撤回：界面勾回并保存 → 恢复单个应用；`adb shell su -c 'rm -f /data/system/dozeguard.conf'` → 回落到模块内置默认；框架里停用模块并重启 → 彻底恢复，系统行为与安装前一致。

## 3. 编译环境

**clone 下来不需要编译就能用**：APK 由 GitHub Actions 产出。只有改代码、复现 CI 或排查构建失败时，才需要搭本地环境。仓库没有 Gradle Wrapper，也没有 `gradlew`。

### 版本矩阵

| 组件 | 版本 | CI 里由谁提供 | 本地要准备什么 |
| --- | --- | --- | --- |
| JDK | Temurin **21** | `actions/setup-java@v4` | 装 JDK 21，设 `JAVA_HOME` |
| Gradle | **8.13** | `gradle/actions/setup-gradle@v4` 的 `gradle-version` | 下载解压 8.13，加入 `PATH` |
| Android Gradle Plugin | **8.9.2** | `build.gradle` 声明，Gradle 自动下载 | 同左，需能访问 Google Maven |
| SDK Platform | `android-36` | `sdkmanager` | `sdkmanager` 安装 |
| SDK Build-Tools | **36.0.0** | `sdkmanager` | `sdkmanager` 安装；`build.gradle` 里显式写死 |
| Java 源/目标兼容级别 | 17 | `compileOptions` | 无需额外操作 |
| libxposed API | 102 | 仓库内 `app/libs/libxposed-api-102.jar` | 同左，`compileOnly`，不打进 APK |

两条别动的约束：

- **不用 JDK 25**：Gradle 8.13 的脚本编译器读不懂 class file major 69，连 `build.gradle` 都加载不了。
- **不用 Gradle 9.x 配 AGP 8.x**：不在官方兼容矩阵里，报错会落在 AGP 内部，排查成本很高。

### 本地从零准备

下列路径都是占位示例，换成你自己的安装位置。

**JDK 21** — 从 [Adoptium](https://adoptium.net/temurin/releases/?version=21) 下载 JDK（不是 JRE，构建需要 `javac`）：

```powershell
# Windows
$env:JAVA_HOME = 'C:\Java\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version   # 必须显示 21
```

```bash
# Linux / macOS
export JAVA_HOME=/opt/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
```

**Android SDK** — 下载 [Command line tools only](https://developer.android.com/studio#command-tools)，解压到 `<SDK>/cmdline-tools/latest/`，保证 `latest/bin/sdkmanager` 存在：

```powershell
# Windows
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
sdkmanager.bat --licenses
sdkmanager.bat "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

```bash
# Linux / macOS
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
yes | sdkmanager --licenses > /dev/null
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

两个 SDK 包缺一不可。`build.gradle` 里写死 `buildToolsVersion '36.0.0'`，就是为了避免 AGP 自己跑去装 35.0.0 再卡在 license 未接受上。

**Gradle 8.13** — 下载 `https://services.gradle.org/distributions/gradle-8.13-bin.zip` 并解压：

```powershell
$env:PATH = "C:\Gradle\gradle-8.13\bin;$env:PATH"
gradle.bat --version   # 应显示 Gradle 8.13 且 JVM 为 21
```

```bash
export PATH="/opt/gradle-8.13/bin:$PATH"
gradle --version
```

**让 Gradle 找到 SDK** — 二选一：设好上面的 `ANDROID_HOME` / `ANDROID_SDK_ROOT`（推荐），或在仓库根目录写 `local.properties`（已被忽略）：

```properties
# Windows，冒号与反斜杠都要转义
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

Linux / macOS 写 `sdk.dir=/home/you/Android/Sdk`。这个文件只属于你的机器，不要提交。

**签名（可选）** — 仓库不含私钥：`key/`、`signing.properties`、`*.keystore` 全部被忽略。`app/build.gradle` 只在 `signing.properties` 存在时读取它，否则回落到 AGP 默认 debug 签名。要固定签名就执行 `cp signing.properties.example signing.properties`，按文件内注释填四项，并用里面附的 `keytool` 命令生成 keystore。

注意：CI 产物用临时 debug 签名、每次都不同，只能全新安装。若手机上装过固定签名的版本，先在手机上卸载旧包（或 `adb uninstall com.dozeguard`）；需要可覆盖安装的包，用固定签名在本地构建。

## 4. 编译方法

### 4.1 GitHub Actions（权威路径）

Workflow 在 `.github/workflows/build.yml`，跑在 `ubuntu-latest`，环境全部由 workflow 固定：

| 触发 | 结果 |
| --- | --- |
| push / PR 到 `main` | 构建 debug APK，上传 artifact `DozeGuard-debug-apk` |
| `v*` tag | 上述 + 自动创建 Release 并附上 APK |
| `workflow_dispatch` | 手动触发 |

流程：`checkout` → 装 JDK 21 → `setup-android` 并接受 licenses、安装 `platforms;android-36` 与 `build-tools;36.0.0` → `setup-gradle@v4` 装 Gradle 8.13 → `gradle :app:assembleDebug --no-daemon --console=plain` → 门禁校验 APK 内含 `classes.dex` 与 `META-INF/xposed/` 三个元数据文件 → 上传 artifact → tag 时发 Release。

取包：Actions → 对应 run → Artifacts → `DozeGuard-debug-apk`，或

```bash
gh run download --name DozeGuard-debug-apk
```

Artifact 有保留期，长期分发请打 tag 走 Release。CI 不需要 `local.properties`（AGP 读 runner 的 `ANDROID_HOME`），也没有 `signing.properties`。

### 4.2 命令行本地构建

```powershell
# Windows，在仓库根目录执行
gradle.bat :app:assembleDebug --console=plain
```

```bash
# Linux / macOS
gradle :app:assembleDebug --console=plain
```

首次构建会下载 AGP 8.9.2 及依赖，需要能访问 Google Maven 与 Maven Central。`gradle.properties` 里 `org.gradle.daemon=false`，构建结束前 fork 一个单用途 daemon 属正常现象。想彻底重来：`gradle :app:clean :app:assembleDebug --console=plain`。

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 26 KB）。

### 4.3 Android Studio

SDK Manager 装好 Platform 36 与 Build-Tools 36.0.0 → `File > Open` 选仓库根目录 → 在 Gradle 设置里指定本地 Gradle 8.13（仓库没有 wrapper，别选 9.x）→ `Build > Build Bundle(s) / APK(s) > Build APK(s)`。

### 4.4 产物校验

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E 'classes.dex|META-INF/xposed/'
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

APK 根目录 `META-INF/xposed/` 下必须有 `module.prop`（`minApiVersion=101` / `targetApiVersion=102`）、`java_init.list`（`com.dozeguard.DozeGuard`）、`scope.list`（`system`）。三者缺一，框架就不会把这个 APK 当模块加载；CI 的门禁正是检查这件事。装到设备的命令见第 2 节。

### 4.5 排错

| 报错 / 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Unsupported class file major version 69` | 用 JDK 25 跑 Gradle 8.13 | `JAVA_HOME` 指向 JDK 21，重开终端 |
| `build-tools;35.0.0 ... licences have not been accepted` | AGP 想自动装 35 | 确认 `buildToolsVersion '36.0.0'` 在，并装好 36.0.0 |
| `gradle: command not found` | 没装 Gradle 或没加 `PATH` | 见 3 节；CI 里检查 `setup-gradle` 的 `gradle-version` |
| `SDK location not found` | 找不到 SDK | 设 `ANDROID_HOME`，或写 `local.properties` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名与已装版本不一致 | `adb uninstall com.dozeguard` 后重装 |
| 装了但框架不加载模块 | 多数是：模块未在框架里启用 / 作用域没选 `system` / 装完没重启 / 签名冲突留下旧包；少数是 ADB 走了 incfs 增量安装 | 按顺序排查前四项；ADB 安装时加 `--no-incremental`。手机上点 APK 安装不走 incfs，可排除这一项 |
| AGP / Maven 依赖下载失败 | JVM 的网络/TLS 被本地环境拦截 | 换网络或配代理；CI 走 GitHub runner 网络不受影响 |

## 贡献与许可

欢迎 issue / PR。改 Java 代码后跑一次 `assembleDebug`，并确认 APK 里的模块元数据仍在。不要提交 `local.properties`、`key/` 或任何密钥。

[MIT](LICENSE) © 2026 Kosakanao。本项目会影响后台消息送达与耗电，作者不对设备异常或数据丢失负责，请保留回滚手段。
