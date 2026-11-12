# WtfNelJava

把 [WtfNel](https://github.com/caolvchong0721/WtfNel)（C# / .NET 9 的网易版《我的世界》布吉岛服启动器）逐类移植成的**纯 Java** 项目。
移植目标是逻辑等价：命令行、协议字节、加密算法、NBT、PE 解析、注入流程都与 C# 原版对齐，
并有金标准测试（用 C# 原版跑出来的产物做断言）钉住关键输出。

不依赖 Minecraft、不依赖 Bukkit —— 只是一套可被任何 Java 程序调用（或独立运行）的启动器库。

## 环境

* JDK 21（Gradle toolchain 会强制 21）
* Windows x64（注入 / 伪造父进程 / 注册表 / 终端探测这几块是 Win32 专属）
* 首次构建需要联网拉 Maven Central 依赖
* 构建原生产物需要 MSVC（VS 2019/2022/2026 的「C++ 桌面开发」）+ CMake + Windows SDK
  —— `native/build.ps1` 会自己用 vswhere 定位，不要求它们进 PATH

```
gradlew.bat build          # 编译 + 原生 DLL/替身 + 三个 jar，一条命令走完
gradlew.bat jar            # build/libs/WTFNelJava-1.0-SNAPSHOT.jar（本体，只有本项目）
gradlew.bat libsJar        # build/libs/WTFNelJava-1.0-SNAPSHOT-libs.jar（只有第三方依赖）
gradlew.bat fatJar         # build/libs/WTFNelJava-1.0-SNAPSHOT-all.jar（本体 + 依赖，可直接 java -jar）
gradlew.bat installDist    # build/install/WTFNelJava/bin/WTFNelJava.bat + lib/
gradlew.bat build -PskipNative   # 没有 C++ 工具链时跳过原生部分，只出 jar
```

三个 jar 的分工：`-SNAPSHOT.jar` 是本体，`-libs.jar` 只有第三方依赖（不含本项目任何
class），`-all.jar` 是两者合体。

瘦 jar 与 `-all.jar` 内部都带 `native/`（`WPFLauncher.exe` + 两个 payload DLL），所以**单独一个
jar 丢进空目录也能跑**：首次运行 `NativeAssets` 会把它们释放到程序目录，见下面「工作目录与自带原生产物」。
`-libs.jar` 里没有原生产物，它只是给「自己管 classpath」的场景配瘦 jar 用的。

`build` 结束后 `build/libs` 就是可以直接跑的一整套（jar 一启动就按
`AppPaths.baseDirectory()` = jar 所在目录找 payload 和替身）：

```
build/libs/WTFNelJava-1.0-SNAPSHOT-all.jar      本体 + 全部依赖（可直接 java -jar）
build/libs/WTFNelJava-1.0-SNAPSHOT.jar          本体（只有本项目 class + native/）
build/libs/WTFNelJava-1.0-SNAPSHOT-libs.jar     只有第三方依赖（配合上面的瘦 jar 用）
build/libs/WPFLauncher.exe                      启动器替身（版本资源已对上网易的 1.15.34.21425）
build/libs/payloads/WtfNel.Payload.Proxy.dll    代理 payload
build/libs/payloads/WtfNel.Payload.HwidSpoof.dll HWID 伪装 payload
```

原生产物由 `nativeBuild` 任务（调 `native/build.ps1` → CMake + Ninja）编到 `build/native`，
再由 `nativeLauncher` / `nativePayloads` 两个任务拷到上面的位置，同时由 `jar` / `fatJar`
以 `native/` 为前缀打进 jar。任务声明了输入（`native` 下的 c / cpp / h / hpp / rc、
`CMakeLists.txt`、`build.ps1`）和输出目录，改过源码才会重编。

> 替身是给 `PROC_THREAD_ATTRIBUTE_PARENT_PROCESS` 当父进程用的常驻程序：`LauncherIdentity.isAligned`
> 要求它的版本资源是 `NetEase / 我的世界启动器 / WPFLauncher.exe / 1.15.34.21425` 四项全中，
> 而且它必须一直活到客户端退出（`ParentedLauncher.ensureLauncher` 起完会等它站住）。
> 手工单独编一次：`powershell -NoProfile -ExecutionPolicy Bypass -File native\build.ps1`，
> 产物落 `native/build`；加 `-BuildDir <目录>` 可改位置，`-Configuration Debug` 换配置。

> 注：PowerShell 里 `gradlew.bat` 的 WARNING 输出走 stderr，会被标成 `NativeCommandError` 且退出码 1，
> 这不代表失败；判断成败看有没有 `BUILD SUCCESSFUL` 或 `错误:`。

## 跑起来

```
java -jar WTFNelJava-1.0-SNAPSHOT-all.jar help
java -jar WTFNelJava-1.0-SNAPSHOT-all.jar config     # 打印当前生效配置
java -jar WTFNelJava-1.0-SNAPSHOT-all.jar login netease 账号 密码
java -jar WTFNelJava-1.0-SNAPSHOT-all.jar run        # 完整启动流程
java -jar WTFNelJava-1.0-SNAPSHOT-all.jar inject <pid> <payload.dll>
java -jar WTFNelJava-1.0-SNAPSHOT-all.jar stub --spawn X.exe --args "..."   # 替身启动器
```

配置、日志、缓存都相对**程序所在目录**（`AppPaths.baseDirectory()`）：
可以用 `-Dwtfnel.home=<目录>` 显式指定，从 jar 启动时取 jar 目录（installDist 布局下退到 `<dist>/bin`），
其它情况（IDE / `gradle run`）退回当前工作目录。

```
<home>/shadow.json          主配置（缺省即全部默认值，PascalCase 键，支持注释与尾逗号）
<home>/ui.json              CLI 不读（原 WPF 界面的偏好）
<home>/accounts.json        保存的账号
<home>/logs/shadow-*.log    运行日志
<home>/logs/launch-cmdline.txt  完整 java 命令行
<home>/cache/...            下载缓存 / 登录态 / modInfo
<home>/payloads/*.dll       两个原生 payload
<home>/LocalRuntime/<ver>/  本地兜底的 natives/runtime 与 .dat
```

## 作为库用

宿主程序（比如以后要塞进 Minecraft 里的那个「内置 nel」）面向下面四个类，不用碰内部包
（前三个在根包 `org.wyx6669036.wtfnel`，静态层在 `org.wyx6669036.wtfnel.nel`）：

| 类 | 角色 |
| --- | --- |
| [`WtfNel`](src/main/java/org/wyx6669036/wtfnel/WtfNel.java) | **实例门面**：持有一份配置和若干账号，登录 / 查角色 / 建角色 / 按角色启动 / 登出 |
| [`NelAccount`](src/main/java/org/wyx6669036/wtfnel/NelAccount.java) | 一次登录的句柄，角色与启动都挂在它身上 |
| [`GameClient`](src/main/java/org/wyx6669036/wtfnel/GameClient.java) | **一个跑起来的客户端**：`launchGame` 的返回值，带状态 / pid / 退出码 / 两个开关，可 `stop()` / `await()` / `onExit()` |
| [`WtfNelFacade`](src/main/java/org/wyx6669036/wtfnel/nel/WtfNelFacade.java) | **静态便利层**：一次性「读配置 / 登录 / 跑一遍 / 挂日志」，不需要自己持有对象 |

### 实例门面

```java
WtfNel nel = new WtfNel();                          // 读程序目录下的 shadow.json
NelAccount account = nel.login("4399com", 账号, 密码);   // cookie 渠道：nel.loginWithCookie(sauthJson)

for (NelAccount.Role role : account.getRoles()) {   // 每次真去 X19 查，Role 自带推导出的 uuid
    System.out.println(role.name() + " " + role.uuid());
}
account.addRole("新角色");                             // POST /game-character

nel.setHwidSpoof(true).setProxy(true);              // 两个开关等价于 shadow.json 的字段

GameClient client = account.launchGame(account.userId(), "角色名", true, true);  // 立刻返回
System.out.println(client.state() + " pid=" + client.pid());
// ... 宿主继续做自己的事，客户端在后台跑 ...
client.stop();                                      // 要关客户端的时候

account.logout(account.userId());
nel.logout();                                       // 登出全部
```

`launchGame` 另有 `(String role)` 与 `(int uid, String role)` 两个重载，开关按配置走；
四参那个用参数临时覆盖两个开关。`getRoles()` 每次都联网，需要缓存请自己留。

### 工作目录与自带原生产物

`shadow.json`、`accounts.json`、`logs/`、`cache/`、`LocalRuntime/`、`payloads/` 与
`WPFLauncher.exe` 都相对**工作目录**。默认的工作目录就是程序运行目录
`AppPaths.baseDirectory()`（`-Dwtfnel.home` → jar 所在目录，installDist 布局退到 `<dist>/bin`
→ 当前工作目录）——`new WtfNel()`、静态层 `WtfNelFacade`、CLI 的 `cli.Main` 都用这个，
行为与以前完全一样，不会凭空跑到别处去建文件。

想让它们落在别的地方，就把工作目录传进去：

```java
WtfNel nel = new WtfNel(Path.of("D:\\nel-home"));            // 全落在这个目录（会自动创建）
WtfNel alt = new WtfNel(Path.of("D:\\nel-home"),
                        Path.of("D:\\cfg\\shadow.json"));  // 工作目录 + 另指定配置文件
nel.workDir();                                             // D:\nel-home
```

传入的目录会成为进程级的程序目录（`AppPaths.setBaseDirectory`），所以之后
`launchGame` 找 payload/替身、写日志与缓存、`accounts.json`、`YggdrasilSetup` 的登录缓存
都在同一个目录里；再 `new WtfNel()` 会撤销这个钉住、回到运行目录。

原生产物随 jar 走，释放时机是「`new WtfNel(...)` / CLI 启动 / 真正拉起客户端之前」，
目标目录就是上面那个工作目录：`WPFLauncher.exe` 放根下，两个 DLL 放 `payloads/`。

**判断依据是内容，不是文件名或「文件在不在」**：`NativeAssets` 先把 jar 里的资源释放到
同目录的临时文件，再与目标做 MD5 比对——一致就删掉临时文件、目标一个字节都不动
（mtime 也不变）；不一致（或目标缺失）才把临时文件原子挪过去覆盖。所以重打包换了新
payload，下次启动会自动更新；手工替换过的文件只要内容不同，同样会被 jar 内的版本覆盖回来。

运行期还有三处兜底，走的是同一套比对逻辑：`InjectionService.resolvePayload`、
`LauncherIdentity.resolveStub`、`ParentedLauncher.ensureLauncher`。

### 客户端对象与异步启动

`launchGame` **不阻塞**：整段启动（装文件 → 起桥接与认证 → 拉客户端 → 注入 → 等退出）都在名为
`wtfnel-launch` 的后台线程里跑，调用当场返回一个 `GameClient`：

```java
GameClient client = account.launchGame(account.userId(), "角色名", true, false);

client.state();        // RUNNING(运行中) / EXITED(已退出) / FAILED(启动失败)
client.isRunning();    // 还在跑
client.isExited();     // 正常退出了
client.pid();          // 客户端 pid（还没起到那一步是 0）
client.exitCode();     // 退出码，还没退出时是 0
client.hwidSpoof();    // 这次有没有注入改机 payload
client.proxy();        // 这次有没有注入代理 payload
client.failure();      // 启动失败的原因，没失败是 null

client.onExit(code -> System.out.println("客户端退出: " + code));   // 退出回调
Integer code = client.await(10, TimeUnit.SECONDS);   // 最多等 10 秒，还在跑就返回 null

client.stop();         // 关客户端：GameRunner 每 500ms 看一次标记，看到就收尾并强杀客户端与替身
client.await();        // 真要等它断干净再 return；也可以 try (GameClient c = ...) 自动关
```

要「跑完再往下走」的场景（命令行、脚本）用 `launchGameAndWait(...)`，它内部就是
`launchGame(...).await()`，直接返回真实退出码。

语义上的几条：

* 「准备」是同步的、「启动」是异步的：`launchGame` 自己写完配置并做身份自检就返回，
  所以账号已登出、角色名为空、已经有一个客户端在跑这三种错是调用当场抛出的；此后的失败
  （网络、找不到 java、注入失败）只体现在 `client.state()` = `FAILED` 与 `client.failure()` 上。
* 同一个 JVM 里**同时只能有一个**客户端：桥接 11655 / 认证 11656 是固定端口，第二个
  `launchGame` 直接抛 `IllegalStateException`。`launchGameAndWait` 走同一条约束。
* 客户端退出后后台线程走到头，对象自动变成「已退出」，宿主不需要任何收尾调用。
* 启动线程不是 daemon：宿主 `main` 返回后 JVM 不会直接退出，客户端继续跑，直到它自己退出
  或被 `stop()` 停掉。需要程序随 `main` 一起结束的话，自己 `stop()` 或 `System.exit`。
* `stop()` 只是置标记，不阻塞；`await()` 才等。收尾会杀掉客户端与替身父进程（`ProcessGuard`）。

### 静态便利层

不想自己持有对象时：

```java
ShadowConfig cfg = WtfNelFacade.loadConfig(WtfNelFacade.defaultConfigFile());
WtfNelFacade.ensureGameDir(cfg);

NeteaseSession session = WtfNelFacade.login(cfg, "netease", 账号, 密码, captchaPrompt);
WtfNelFacade.useSession(session);

WtfNelFacade.onLog(entry -> System.out.println(entry.level() + " " + entry.message()));
WtfNelFacade.onProxyReady(port -> System.out.println("代理端口 " + port));

int code = WtfNelFacade.launch(cfg, () -> 该不该停);   // 阻塞到客户端退出，返回真实退出码

GameClient client = WtfNelFacade.launchAsync(cfg);     // 不阻塞，立刻返回客户端对象
```

### 要点

* `launchGame` 不阻塞，立刻返回 `GameClient`；`launchGameAndWait` 与静态层的 `launch`
  才阻塞到客户端进程退出并返回真实退出码（内部自己起替身、桥接、authlib、可选转发层）。
* jar 自带原生产物：`native/` 下放着 `WPFLauncher.exe` 与两个 payload DLL，启动时按 MD5
  同步到工作目录（一致不动、不一致覆盖）。宿主项目只要引用一个 jar，不用再手工布置
  exe 和 `payloads/`。
* `GameDir` 可以留空：`GameRunner` 在装文件之前会调用一次 `GameDirLocator`，按「配置里写的 →
  程序目录与各固定盘上的 `MCLDownload\Game\.minecraft` / `.minecraft` / `Game\.minecraft` → `%APPDATA%\.minecraft`
  → 新建」的顺序定下来。要指定安装位置就 `cfg.setGameDir("D:\\MCLDownload\\Game\\.minecraft")`。
* 一个进程只有一份「当前会话」（`YggdrasilSetup` 里的静态 session）：可以同时留多个账号，
  启动前会自动切到对应账号的 token，但**不能并行跑两份客户端**（桥接 / 认证端口是固定的）。
  第二个启动请求会抛 `IllegalStateException`，不管走哪个入口。
* 登录后默认把 uid / userToken 写回 `shadow.json`、把凭据记进 `accounts.json`（与 CLI 一致）；
  `nel.setPersist(false)` 可关掉这两处明文落盘。
* `onProxyReady` 的回调跑在 `wtfnel-inject` 注入线程里，不要在里面做重活或直接碰 UI。
* 取消语义：静态层的 `launch(cfg, stopRequested)` 收一个 `BooleanSupplier`；实例层用
  `GameClient.stop()`。`launch` / `launchGameAndWait` 返回客户端进程的真实退出码
  （中途被叫停时返回 0）。

验证码需要宿主给个 `ICaptchaPrompt` 实现（命令行版见
[`ConsoleCaptchaPrompt`](src/main/java/org/wyx6669036/wtfnel/cli/ConsoleCaptchaPrompt.java)）。

## 包结构

| 包 | 对应 C# | 内容 |
| --- | --- | --- |
| `config` | `Core/Configuration` | `ShadowConfig`（全部配置项 + 派生路径）、`GameDirLocator` |
| `auth` / `auth.login` | `Core/Auth` | `AccountStore`、网易邮箱/4399PC/4399com 登录、`X19HttpCipher`、验证码接口与图片尺寸探测 |
| `auth.yggdrasil` | `Core/Auth/Yggdrasil` | `Skip32`、`LaunchToken`、`HttpToken`、`ChaChaPacker`、`Rsa`、`GameProfile`、`YggdrasilClient`、`AuthLibServer`、`ModInfoProvider`、`YggdrasilSetup` |
| `bridge` | `Core/Bridge` | `Frame`（长度 LE / 消息号 BE）、`LauncherBridge`、`ServerList`（手写未压缩 NBT） |
| `install` | `Core/Install` | `FastDownloader`（分段下载）、`Archives`（7z/zip 解压防穿越）、`GameInstaller`（五阶段安装） |
| `launch` | `Core/Launch` | `LaunchCommand`（模板占位符）、`ParentedLauncher`（伪造父进程）、`LauncherIdentity`（改版本资源 + 注册表）、`GameRunner`、`ProcessGuard`、`Elevation` |
| `injection` | `Core/Injection` | `PeImage`、`RemoteProcess`、`ReflectivePeLoader`、`ManualMapper`、`InjectionService`、`InjectionRunner`、`ProxyPortProbe` |
| `proxy` | `Core/Proxy` | `GameRelay`、`PacketTap`（只读旁路统计） |
| `log` / `terminal` | `Core/Logging` / `Core/Terminal` | `Log`（三路输出）、`ProgressLine`、`Term` |
| `cli` | `WtfNel.Ui` + `WtfNel.Stub` | `Main`（命令行入口）、`ConsoleCaptchaPrompt` |

原 WPF 界面（`WtfNel.Ui` 的 18 个文件）没有移植，用 CLI 命令替代；其余 `WtfNel.Core` 的类
（含原本是死代码的 `Elevation`、`Term`）全部有对应实现。

C# 里按文件名分的两个类在 Java 侧按职责拆开了，名字对不上不是漏移植：

* `Auth/Login/CaptchaPrompt.cs`（`ICaptchaPrompt` + `ImageSize`）→ `auth.login.ICaptchaPrompt` + `auth.login.ImageSize`
* `Auth/Yggdrasil/NeteaseCrypto.cs`（`Skip32` / `LaunchToken` / `HttpToken` / `Rsa` / `BinExt`）
  → `auth.yggdrasil` 下的 `Skip32`、`LaunchToken`、`HttpToken`、`Rsa`、`Bytes`（= `BinExt`）、`ChaChaPacker`

## 与 C# 原版的已知差异

* `LauncherBridge` 的 LoadLibrary 兜底：C# 对 `LoadLibrary` 策略失败还会再试一次同策略，Java 侧跳过这次无意义重试。
* `PacketTap` 摘要里包号用 `Integer.toHexString`，没有前导零（C# 是 `x2`），只影响日志长相。
* C# `Console.CancelKeyPress` 的收尾改成 JVM shutdown hook 覆盖（Ctrl+C 同样触发）。
* `ModInfoProvider` 多了一个 6 参构造器（可换掉两个 X19 主机地址）；`NeteaseLogin` / `Pc4399Login` / `Com4399Login` 多了一个接收 `Endpoints` 的包级构造器。这些只给测试把接口指到本地 `HttpServer` 用，生产路径仍是原构造器 + 官方域名常量。
* `ShadowConfig` 的 `UserToken` 等敏感字段仍是明文，与原版一致。
* `ManualMapper.buildImage` 与 `RemoteProcess.callStub` 是包级可见的纯函数，只为了让单测不碰远程进程就能验镜像铺排与桩字节；调用路径与 C# 一致。
* `YggdrasilClient` 的握手帧构建拆成了一个包级方法（`initializeMessage`），并因此把类从 `final` 改成可继承：测试用的假认证服没有网易的私钥，只能跳过服务端签名校验来跑完整的 TCP 流程；真实签名校验由 `YggdrasilGoldenTest` 对着 C# 抓包覆盖。
* C# 原版只有一层静态门面；Java 侧拆成两层：原来那个静态类**改名为 `WtfNelFacade`**（方法签名与行为完全不变，纯 rename），`WtfNel` 这个名字留给新增的**可实例化**门面（配 `NelAccount` 句柄）。从 C# 移植过来的调用点因此写作 `WtfNelFacade.xxx`。

## 测试

`gradlew.bat test`，43 个测试类 / 351 个测试。重点：

* `GoldenVectorTest` / `YggdrasilGoldenTest` —— Skip32、RoleUuid、HttpToken、LaunchToken、握手包，断言与 C# 原版输出逐字节一致。
* `LauncherBridgeTest` —— 真开 ServerSocket 验帧协议与三种推送布局（含 ChatBanQuery 必须回 19 字节）。
* `PeImageTest` / `PayloadArtifactTest` —— 内存里手工拼 x64 DLL 验 PE 解析；再用 `native/prebuilt` 里真实的 MSVC 产物验导出表与策略选择。
* `LauncherIdentityTest` —— 手工拼带 `.rsrc` 的最小 PE，验版本资源定位、改写、槽位不足时的降级。
* `ArchivesTest` / `FastDownloaderTest` —— 真 7z/zip 解压（含防目录穿越）、本地 HTTP 服务上的分段下载与 MD5 校验。
* `GameInstallerTest` —— MCL 根推导、Java 选择（`JavaExe` 必须在 MCLDownload 内）、库包落地路径、`.installed` 标记的 md5 短路与失败不留痕、`LocalRuntime` 兜底拷贝。
* `ServerListTest` —— `servers.dat` 的合并语义（已有条目保留、无关字段不动、坏文件不抛）。
* `ModInfoProviderTest` —— 本地 `HttpServer` 顶替 X19：请求体、`user-id`/`user-token` 签名头、非 0 code 与 HTTP 错误、核心 mods 与服务器资源包的拼装与 `res_md5` 缓存。
* `ProcessGuardTest` —— 真起子进程验证收尾。
* `YggdrasilClientTest` —— 本地假认证服跑完整进服握手：272 字节上下文、握手帧布局、ChaCha 加密的 type-9 包、状态码与校验失败分支。
* `InjectionResultTest` —— 注入结果与参数的值语义（链式 setter、`withDetail` 不改原对象、日志 sink 可空）。
* `FormsTest` —— 表单编码与 query 解析（空格必须是 %20、UTF-8 百分号转义、与 C# `parts.Length == 2` 一致地丢掉残缺段）。
* `ManualMapperTest` / `ReflectivePeLoaderTest` —— 手动映射的镜像铺排与重定位修正（DIR64 加 delta、HIGHLOW 只留低 32 位、填充项与越界条目跳过、截断文件不越界读），以及反射注入在拿到目标进程之前的失败分支。
* `MainTest` / `ConsoleCaptchaPromptTest` —— CLI 的用法文案与配置打印、「没配角色时用 accounts.json 最后一次登录补齐」的推导、控制台验证码应答。
* `WtfNelApiTest` / `WtfNelFacadeTest` —— 两个门面的约定：配置归属与持久化开关、开关透传、宿主给的会话能装上且能登出、登出后的句柄拒绝干活、「同渠道同账号只记一条」与 cookie 账号不落密码。
* `NeteaseLoginTest` / `Pc4399LoginTest` / `Com4399LoginTest` —— 本地 `HttpServer` 顶替网易与 4399 的登录接口：两段 OTP 认证、加密请求体、三种验证码（图形 / 滑块 / 点选）、429 限频与重试预算。
* `GameRunnerTest` —— 取消闸门、缺 Java 的可读报错、`LauncherGameId` 的回写与沿用。

`src/test/resources/oracle/` 里是从 C# 原版跑出来的金标准产物（完整启动命令行、servers.dat）。
