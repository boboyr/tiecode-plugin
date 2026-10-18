# 免安装运行器（结绳插件 `.rmx`）

给**结绳安卓工程**（`cn.tiecode.android`）增加一个运行配置「**免安装运行**」：
编译/打包整条任务链**完全复用宿主自己的任务**，只在最后一步把「安装并启动」
换成**在结绳进程内直接加载该 APK 的 dex + 资源并显示它的界面**，全程不安装。

- 不新增工程类型、不替换宿主任务、不改宿主 IDE；
- 目标 APK 的包不会出现在系统应用列表里；
- 插件跑在结绳进程内，界面就长在结绳里（宿主里已声明的 `DynamicActivity` 当占坑窗口）。

## 一、用法

1. 装插件（二选一）：
   - 结绳里「功能 → 安装插件」选 `免安装运行器-0.2.0.rmx`；
   - 或直接放置：`/sdcard/Tiecode/Programs/com.dsh.nomount/` 下放
     `program.json`（含 `"file": "免安装运行器-0.2.0.rmx"`）与本 `.rmx`。
2. 打开任意**结绳安卓工程** → 运行菜单里会多出「**免安装运行**」。
3. 点它：宿主照常编译打包 → 构建成功后不安装，直接在结绳里显示你的应用。

> 运行配置里的任务链 = 宿主 `apkAlign`（即 compileWithTiecodec → 资源 → 清单 → javac →
> D8 合并 → generateApk → signAPK → apkAlign），与宿主「调试打包运行」共用，只是最后一步不同。

## 二、构建（不需要 Android SDK / Gradle）

```bash
./tools/build-rmx.sh          # 产出 免安装运行器-<版本>.rmx，并顺带推送热加载 payload
```

依赖容器里已有的：`javac` / `aapt2` / `zipalign` / `r8.jar`（D8）/ `android-stripped.jar` /
`studio-api.jar`。脚本也把 `classes.dex` 拷到 `/sdcard/Tiecode/nomount-payload.dex`
（开发期热加载用，见第五节）。

## 三、源码结构

| 文件 | 作用 |
| --- | --- |
| `NoMountPlugin` | 插件入口（`application` 扩展点）。激活时装 hook、启动监听 |
| `NoMountProjectComponent` / `NoMountComponentFactory` | `project.component` 扩展点：打开结绳安卓工程时挂上运行配置 |
| `NoMountRunConfiguration` | 运行配置「免安装运行」：复用宿主任务，构建成功后调用 `NoMountPlugin.runApk(apk)` |
| `HostInstrumentation` | 换掉宿主 `ActivityThread.mInstrumentation`：改写启动目标、注入目标环境、改写内部跳转 |
| `ApkRuntime` | 加载未安装 APK：子优先 ClassLoader、独立 Resources、目标 Application、主题、native 库 |
| `ApkClassLoader` | **子优先**类加载器（插件化关键，见第四节） |
| `TargetContext` | 给目标 APK 用的 Context：资源/类加载器/主题指向目标，**系统身份保持宿主** |
| `ManifestParser` | 自己解二进制 AXML，找出 MAIN/LAUNCHER 启动窗口并补全类名 |
| `HotLoader` | 开发用热加载：payload dex 一变就重装 hook（生产不影响） |
| `Reflect` | 反射小工具 |

## 四、为什么能跑起来（踩过的坑，都是真机实测）

1. **占坑窗口**：宿主 `com.tiecode.develop` 清单里已声明
   `com.rapid.program.impl.android.dynamic.DynamicActivity`，插件把启动 Intent 指向它，
   再由 `HostInstrumentation.newActivity()` 换成目标 APK 里的真实 Activity。
2. **子优先 ClassLoader**：宿主体积极大，自带 androidx / material 同名库。父优先会让目标 APK 的
   `MaterialButton` 解析到宿主那一份，宿主 `R.attr` 与目标资源 id 对不上 →
   主题校验失败 / 界面发黑。所以除 `java./android./...` 外一律先到目标 dex 里找。
3. **主题**：`Activity.setTheme()` 会先取 `getBaseContext().getTheme()` 再 `Theme.setTo()`，
   而 `setTo()` 会把 **AssetManager 一起复制**。若 baseContext 给的是宿主主题，
   目标 style id 会在宿主 AssetManager 里查不到（实测 `Resources$NotFoundException #0x7f030000`），
   主题失效、窗口退回宿主样式。故 `TargetContext.getTheme()` 必须返回目标资源的主题。
4. **不要伪装包名**：把 `getPackageName()` 换成目标包会触发系统校验
   `SecurityException: Package xxx not in UID yyy`（Toast/弹窗直接崩主线程）。
   对外身份一律保持宿主，只有资源与类加载器用目标 APK 的。
5. **native 库**：带 `.so` 的工程（Spine/GDX）要把 APK 里当前 ABI 的 `lib/<abi>/*.so`
   解到插件私有目录，并作为 `DexClassLoader` 的 `librarySearchPath`
   （否则 `Couldn't load shared library 'gdx'`）。
6. **启动窗口识别**：`getPackageArchiveInfo` 不给 intent-filter，故自解 AXML 找
   MAIN/LAUNCHER，并把 `.MainActivity` 这类相对名补成完整类名。
7. **不直接加载构建产物**：`DexClassLoader` 会 mmap 打开 APK，之后宿主的打包/签名任务再写
   同一个文件就会失败。所以先把产物拷到插件私有目录（`target-<mtime+size>.apk`）再加载，
   构建产物全程不被占用。
8. **同一路径产物变了要整份重载**：否则一直复用旧 dex/资源（表现就是「必须重启结绳才更新画面」）。
   现在按产物 `mtime+size` 换新 odex 目录、重新加载 dex/资源/native 库，并先 `finish()` 掉上一版窗口。
9. **插件激活时不自动运行**：调试触发文件只在「内容发生变化」时跑一次，
   避免每次打开结绳就被上一次调试用的 APK 顶掉界面。

## 五、开发期热加载

`sdcard/Tiecode/nomount-payload.dex` 一变，插件自动用子优先加载器重新装 hook：
改代码 → `./tools/build-rmx.sh` → 把结绳切到前台（后台进程会被系统冻结）即可生效，**不必重启结绳**。

另有一个**可选**调试触发文件 `/sdcard/Tiecode/免安装运行.txt`：只在内容发生变化时跑一次，
写 `<apk路径>[#启动窗口]` 即可（默认留空，打开结绳不会自动跳任何画面）。

## 六、已知限制

- 只支持**单进程**：目标 APK 里的 `Service`/`ContentProvider`/静态注册广播不会工作；
  动态注册的广播可用。
- 目标 APK 内部跳转已改写为占坑窗口，但 `startActivityForResult`、`singleTask`、
  多任务栈等语义只做到「能用」级别。
- `getPackageName()` 返回的是宿主包名（这是系统身份的要求），依赖自身包名做业务判断的应用可能异常。
- 宿主 targetSdk 28，用的都是 greylist 级 hidden API；换到 targetSdk ≥ 30 的宿主需要额外的
  hidden API 绕过。
- Android 会把后台进程冻结：免安装应用被切到后台后，插件的监听线程会暂停，切回前台才继续。

## 七、产物

- `免安装运行器-0.2.0.rmx` — 插件本体（= 一个 APK：清单 + classes.dex + `META-INF/program.json`）
- `app/src/main/resources/META-INF/program.json` — 插件清单（扩展点声明）
