# scala安卓工程插件

给**结绳**（`cn.tiecode.android` / QiStudio）加一种新工程类型：**Scala 安卓工程**。
工程结构与 [`scala-android-app-modern`](../scala-android-app-modern) 完全一致（`build.sbt` + `src/main/scala|res|AndroidManifest.xml`），
编译打包走**结绳本体同一套宿主工具**（aapt2 / javac / D8 / ApkGenerator / ApkSigner / zipalign），
而 `build.sbt` 由**插件自带、手写的 sbt 构建脚本解析器**解析，用它驱动依赖解析下载、清单与资源、dex、打包。

|  |  |
| --- | --- |
| 插件 id | `com.scala.android` |
| 插件名 | `scala安卓工程插件` |
| 工程类型 id | `scala.android.sbt`（`ScalaAndroidProjectManager.IDENTIFIER`） |
| 产物 | `scala安卓工程插件-1.10.rmx` |
| 最低 API | 33（Scala 3.8 编译器/运行库用到 `java.lang.invoke.VarHandle`；模板默认 `androidMinSdk := 33`，低于 33 会在 D8 阶段给出警告） |

> **1.10 新增**：「结绳插件工程」模板 + 「构建结绳插件」/「调试结绳插件」运行配置。
> 新建工程时除了「默认模板（sbt）」（给设备用的 APK 应用）之外，可以选
> **「结绳插件工程」**（给结绳宿主用的 .rmx 插件，结构与结绳本体 `BuildQiPluginConfig` /
> `DebugQiPluginConfig` 对齐）。详见 [第 10 节](#10-结绳插件工程110-新增)。

---

## 1. 做了什么（一页速览）

| 能力 | 实现位置 | 说明 |
| --- | --- | --- |
| **sbt 构建脚本解析器** | `sbt/`（13 个类，纯 Java） | 自研词法 + 递归下降语法 + 求值器，解析 `build.sbt` / `project/*.sbt` / `project/build.properties`，产出「设置 + 子工程 + sbt 插件 + 诊断」模型 |
| **依赖解析下载** | `dependency/`（7 个类，纯 Java） | 自研 Maven-lite：POM 解析（parent/properties/dependencyManagement）、BFS 传递依赖、最近者优先版本仲裁、scope/optional/exclude 过滤、m2 布局本地缓存、HTTP 下载、**AAR 自动解包** |
| **编译打包 APK** | `task/`（12 个任务） | `sbtResolve → compileProjectResources / mergeManifest → linkAppResources → compileRJava → compileScalaSources → compileJavaSources → dexWithD8 → packageApk → signApkScala → zipAlignScala → buildApk`，每一步都调用宿主的 `Tool.*` 工具链，状态（跳过/无需执行）与宿主 executor 的显示规则一致 |
| **Scala 代码补全** | `project/`、`compiler/`、`lang/`、`tool/`（**复制自 `scala控制台插件` 并适配**） | 关键字 / 片段 / 文件内声明 / classpath 类型自动 import / dotty 交互式**语义补全**；**`.sbt`、`.sc` 与 `.scala` 同等待遇**（同一套高亮与引擎）；`build.sbt` 里额外补 sbt 的键/运算符/片段；**build.sbt 里声明的依赖也进补全 classpath** |
| **工程模板** | `androidproject/template/` + `assets/templates/project/scala_android_sbt{.zip,/**}` | 一键生成可编译的工程（含示例 `MainActivity.scala`、res、清单、测试签名密钥库、`.sbtopts`）；以单个 zip 资源下发，避开 `AssetManager.list()` 对嵌套目录不可靠的坑 |
| **结绳插件工程模板**（1.10） | `androidproject/template/ScalaPluginProjectTemplate` + `assets/templates/project/scala_tiecode_plugin{.zip,/**}` | 新建工程时可选模板：产物是 .rmx 结绳插件（清单带 `tiecode_plugin` 开关 + `META-INF/program.json`），示例入口 `TestMenuAction.scala`（`domain.menu` 扩展点，菜单项） |
| **运行配置** | `run/` | 「调试打包运行」「正式打包运行」（命名与行为对齐结绳本体的 `DebugRunConfig`/`ReleaseRunConfig`：构建完直接装到设备）＋「同步sbt依赖」「清理构建产物」＋（1.10）「构建结绳插件」「调试结绳插件」（对齐结绳本体 `BuildQiPluginConfig`/`DebugQiPluginConfig`） |
| **设置页** | `setting/` | 设置 → 项目管理器 → 「Scala安卓工程」：离线模式 / 复用宿主 Maven 目录 / 包含 Test 依赖 / 依赖仓库 / 额外仓库 |

产物路径：`build/outputs/apk/<release|debug>/<工程名>.apk`

---

## 2. 工程结构（与 scala-android-app-modern 一致）

```
我的scala安卓工程/
├── build.sbt                        ← 本插件解析它
├── .sbtopts                         （--server）
├── local.properties                 （可选，真实 sbt 构建时才需要）
├── release-test.keystore            测试用 release 签名
├── project/
│   └── build.properties             sbt.version=2.0.3（元信息；不需要 plugins.sbt）
├── lib/                             本地 jar（sbt 的 unmanagedBase，自动进 classpath）
└── src/main/
    ├── AndroidManifest.xml
    ├── scala/<包名目录>/MainActivity.scala
    ├── java/                        （可选，与 Scala 一起编译）
    ├── res/…                        布局 / 图标 / values
    ├── assets/…                     → APK 的 assets/（aapt2 link 输入）
    └── jniLibs/<abi>/*.so           → APK 的 lib/<abi>/
```

`build.sbt` 里被识别的键（取自模板，键名兼容 `sbt-modern-android` 与老的 `android-sdk-plugin` 两套写法）。
**这些键由插件自己的解析器解释，不需要 `project/plugins.sbt` 里声明 sbt 插件**（真要用命令行 sbt 构建时再加回 `addSbtPlugin(...)`）：

```scala
ThisBuild / organization := "com.example"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "3.8.4"

lazy val root = (project in file("."))
  .enablePlugins(modernandroid.AndroidApp)
  .settings(
    name := "scala-android-app-modern",
    platformTarget := "android-35",          // → compileSdk 35
    androidApplicationId := "com.example.tes",
    androidMinSdk := 33,   // Scala 3.8 运行库要求 API 33+
    androidTargetSdk := 35,
    androidVersionCode := 1,
    androidVersionName := "1.0.0",
    androidReleaseKeystore := Some(baseDirectory.value / "release-test.keystore"),
    androidReleaseKeystoreAlias := Some("release"),
    androidReleaseKeystorePassword := Some("changeit"),
    androidReleaseKeyPassword := Some("changeit"),
    Compile / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    resolvers ++= Seq("Aliyun Public" at "https://maven.aliyun.com/repository/public/",
                      Resolver.mavenCentral),
    libraryDependencies ++= Seq(
      "com.squareup.okhttp3" %% "okhttp" % "4.12.0",        // %% → okhttp_3（按 scalaVersion 推）
      "com.google.code.gson"  % "gson"   % "2.11.0",
      "junit" % "junit" % "4.13.2" % Test                    // % Test 默认不进 APK
    )
  )
```

---

## 3. sbt 构建脚本解析器（本插件的核心）

> 代码：`app/src/main/java/com/scala/android/sbt/`（≈3.4k 行，**纯 Java、零第三方依赖**，因此可以在普通 JVM 上单独跑测试）

### 3.1 为什么自己写

* `build.sbt` 是 **Scala 语法**（不是 Gradle/XML），没有现成可用的轻量解析器；
* 结绳本体的 Gradle 解析栈依赖 `groovy-parser.jar` 的 ANTLR2 Groovy 前端，解析 Groovy 而非 Scala，用不上；
* 宿主的 Maven 设施（`studio.java.api.maven.*`）编译期依赖 Eclipse Aether 的类文件，本插件要做到**不装 Java 插件也能独立工作**，所以连依赖下载也一并自研。

### 3.2 三层结构

```
文本 ──SbtLexer──▶ 记号流 ──SbtParser──▶ 语法树(SbtNode) ──SbtEvaluator──▶ SbtBuild 模型
```

**① 词法 `SbtLexer`**（Scala 的一个可用子集）

| 处理 | 说明 |
| --- | --- |
| 标识符 | 普通标识符、反引号标识符、`at`/`in`/`to` 这类**字母运算符** |
| 运算符 | 符号字符的最大匹配：`:=` `+=` `++=` `-=` `%` `%%` `/` `->` `=>` … |
| 字面量 | 字符串（含三引号与 `s"…"` 插值前缀、转义）、字符、数字（含小数/指数/后缀） |
| 注释 | `//` 与**可嵌套**的 `/* */` |
| **换行推断** | 按 Scala 规范裁剪：`( ) [ ]` 内不产生换行；上一记号是运算符/`.`/`,` 时续行；下一有效字符是 `.` 时续行（链式调用折行） |

**② 语法 `SbtParser`**（递归下降）

```
语句    := 定义 | import/package | 表达式
定义    := ["lazy"] ("val"|"var"|"def") 名字 [参数] "=" 表达式
表达式  := 后缀链 { 中缀运算符 后缀链 } { 无点方法调用 } [ 赋值运算符 右值 ]
后缀链  := 基本式 { "." 名字 [实参] | "[" 类型参数 "]" }
基本式  := 标识符 | 字符串 | 数字 | 布尔 | "(" … ")" | "{" … "}"
```

* 赋值运算符优先级最低，且**右侧允许换行续写**（`libraryDependencies ++=` 后直接换行接 `Seq(...)` 是 sbt 的常见写法）；
* 无点方法调用（`… % "1.0" exclude("g", "a")`）按 Scala 语义**作用在整条中缀链上**（字母运算符优先级低于符号运算符）；
* 遇到无法解释的构造（`if`/`for`/`class`/`object` …）只**整段跳过并记一条警告**，绝不抛异常——解析器必须"读不动的脚本也能继续给下游用"。

**③ 求值 `SbtEvaluator`**（sbt 语义的可用子集）

| sbt 写法 | 求值结果 |
| --- | --- |
| `val x = …` / `lazy val x = …` | 存进环境，后续语句按名字引用（`val okhttpVersion = "4.12.0"` 能被引用） |
| `key := v` / `+=` / `++=` / `-=` | 写入 setting；`+=` 对列表追加、`++=` 连接、`-=` 删除 |
| `ThisBuild / organization := v` | 键路径 `ThisBuild/organization`，取值时按「工程 → 工程的 ThisBuild → 根 → 根的 ThisBuild」回退 |
| `(project in file("app")).enablePlugins(X).settings(...)` | 新建子工程，`settings(...)` 里的 setting 挂到该工程；`.dependsOn` / `.aggregate` 记下来 |
| `"org" % "name" % "1.0"` / `%%` / `% Test` | `SbtModuleId`（含 crossVersion、configuration、`exclude(...)`、`classifier`、`withSources`） |
| `"名字" at "地址"`、`Resolver.mavenCentral` | `SbtResolver`（Maven/Ivy/本地三类） |
| `Seq/List/Vector/Set(...)`、`Seq.empty`、`Some(x)`、`None`、`Map(...)` | 列表/映射/Option 值 |
| `file("x")`、`baseDirectory.value / "x"`、`target.value` | 解析成相对工程目录的绝对路径 |
| `addSbtPlugin("g" % "a" % "v")` | 记进 `SbtPlugin` 列表（并识别 Android 插件） |

**④ 装载 `SbtBuildScriptLoader`**：按 sbt 的真实顺序读
`project/build.properties` → `project/*.sbt`（`plugins.sbt` 优先）→ `build.sbt` → 其它 `*.sbt`，
`project/*.scala` 只登记不解析（记一条警告）。

**⑤ 配置提取 `SbtAndroidConfig`**：把模型变成打包需要的东西——
`applicationId / versionName / versionCode / minSdk / targetSdk / compileSdk(platformTarget)`、
源码/资源/assets/jniLibs/清单/`lib/` 目录、`scalacOptions`、签名配置、依赖与仓库列表；
多模块工程会自动选中**启用了 AndroidApp 插件的子工程**（`app`），而不是空壳 `root`。

### 3.3 解析器自检（可复现）

```bash
./tools/run-tests.sh
```

会对一个穷举常见写法的 `build.sbt` 打印完整模型（子工程、插件、依赖坐标、仓库、诊断）。实测输出：

```
sbt 插件:
  - sbt-modern-android 1.0.0  [android]
子工程:
  - app @ …/fixture/app plugins=[AndroidApp]
  - root @ …/fixture/. plugins=[]
  applicationId  = com.example.demo
  minSdk/target  = 24 / 34 (compile 34)
  依赖:
    * com.squareup.okhttp3 %% okhttp % 4.12.0  →  com.squareup.okhttp3:okhttp_2.13:4.12.0
    * junit % junit % 4.13.2 % test
    * androidx.core % core-ktx % 1.13.1（含 exclude）
  诊断 (0)
```

---

## 4. 依赖解析下载（自研 Maven-lite）

> 代码：`app/src/main/java/com/scala/android/dependency/`

```
SbtModuleId × N ──▶ SbtDependencyResolver ──▶ SbtResolutionResult
                      │                          ├ jars             → 编译/dex classpath
                      │                          ├ providedJars     → 只编译，不进 APK
                      │                          ├ testJars         → 只编译（可选）
                      │                          ├ aarClassesJars   → AAR 的 classes.jar
                      │                          ├ aarResDirs/manifests → 资源与清单合并
                      │                          ├ aarJniLibs       → APK lib/<abi>/
                      │                          └ aarAssetsDirs    → APK assets/
                      ├ MavenRepositoryClient（本地缓存优先 / HTTP 下载）
                      ├ MavenPomParser（parent、properties、dependencyManagement）
                      └ AarExtractor（纯 java.util.zip 解包 AAR）
```

* **版本仲裁**：BFS 层序遍历 + `group:artifact → 版本` 最近者优先（与 Maven 一致），命中时打一行日志说明保留了谁；
* **scope**：`compile/runtime` 传递，`provided` 只对直接依赖生效且只进编译 classpath，`test` 默认不解析（设置页可开）；
* **optional**：一律不传递（与 Maven 一致）；
* **排除**：sbt 侧 `exclude("g","a")` 与 POM 侧 `<exclusions>` 都会生效；
* **本地缓存**：m2 布局；默认**复用宿主的公共 Maven 目录**（`/sdcard/Tiecode/Maven`，见 `studio.java.api.PublicPathExt.MAVEN`），拿不到时退到插件私有目录；离线模式下只查缓存；
* **仓库**：优先 `build.sbt` 的 `resolvers`，再补内置镜像（阿里云 public/google → Maven Central → Google → JitPack）；
* **AAR**：自动解包成 `classes.jar` / `res/` / `AndroidManifest.xml` / `jni/<abi>/` / `assets/`，分别喂给编译与打包任务（aapt2 的 `--extra-packages` 也从 AAR 清单的 package 提取）；
* **失败不中断**：单条依赖拉不下来只记一条 `failures`，构建日志里会列出来，不会把整个解析器拖垮。

自检（含 parent+property 版本、版本冲突、exclude、AAR 解包、`lib/` 本地 jar）：

```
版本仲裁：保留 com.example:libb:1.0，忽略 1.1
按 exclude 跳过 com.example:libb:1.0
本地 lib/ 目录纳入 classpath：1 个 jar
== 依赖解析：成功 8 个坐标 · classpath 9 个条目 · AAR 1 个 · 本地 jar 1
```

---

## 5. 编译打包 APK（按结绳本体的写法接宿主工具）

### 5.0 运行配置

| 运行配置 | 变体 | 指向的任务 | 行为 |
| --- | --- | --- | --- |
| **调试打包运行** | debug | `installApk` | 整条链（debug，宿主测试证书）→ 把 APK 交给系统安装器 |
| **正式打包运行** | release | `installApk` | 整条链（release，build.sbt 里的密钥库）→ 交给系统安装器 |
| 同步sbt依赖 | debug | `sbtResolve` | 只解析 build.sbt + 下载依赖，不编译不打包 |
| 清理构建产物 | debug | `cleanScalaAndroid` | 删除 `build/` |

与结绳本体的对应关系：本体的 `DebugRunConfig`（调试打包运行）/ `ReleaseRunConfig`（正式打包运行）就是"构建完 `ContentUtils.installApk()`"；
本插件把"安装"也做成任务（`installApk`），所以日志里「构建APK」和「安装APK」是分开的两行，能看清装的是哪个文件。

### 5.1 任务链

| 任务 id | 任务名（构建日志里的显示） | 调用的宿主工具 / 写法参照 |
| --- | --- | --- |
| `sbtResolve` | 解析并下载依赖 | 本插件解析器 + Maven-lite（对照本体 `AndroidBuildGradleProcessor` + `DependencyHandler.resolveDependencies`）；**解析 build.sbt 并进这条任务，不再单独占一行** |
| `compileProjectResources` | 编译项目资源文件 | `Tool.AAPT2` + `Aapt2CompileParams`（对照本体 `CompileProjectResourcesTask`） |
| `mergeManifest` | 合并清单文件 | `Tool.MANIFEST_MERGER` + `ManifestMergeParams`（对照本体 `MergeManifestTask`） |
| `linkAppResources` | 链接资源文件 | `Tool.AAPT2` + `Aapt2LinkParams`（对照本体 `LinkResourcesTask`：`--auto-add-overlay` / `--extra-packages` / `-I android.jar`） |
| `compileRJava` | 编译R类 | `Tool.JAVA_COMPILER`（**新增的一步**，见 5.3） |
| `compileScalaSources` | 编译Scala源文件 | `ToolchainService.getTool(SCALA_COMPILE)`（本插件注册的 dotty），classpath 含 R 类 |
| `compileJavaSources` | 编译Java源文件 | `Tool.JAVA_COMPILER` + `JavacParams`（对照本体 `CompileWithJavacTask`） |
| `dexWithD8` | 编译到Dex(D8) | `Tool.D8` + `D8CompileParams`（`DexIndexed` 多 dex；program = **逐个 .class 文件** + 依赖 jar + **Scala 运行库**，library = android.jar + core-lambda-stubs） |
| `packageApk` | 生成APK | **本插件自研 `ApkPacker`**（res.ap_ + dex + nativeLibs + javaRes；`resources.arsc` 与 `.so` 不压缩）——宿主没有注册 `Tool.APK_GENERATOR`，结绳本体的 `GenerateAPKTask` 也是自己写的打包器 |
| `zipAlignScala` | 对齐APK | `Tool.ZIPALIGN`（4 字节对齐，与本体 `ZipAlignTask` 一致） |
| `signApkScala` | 签名APK | **带 `--ks-type` 直接调用宿主内置的 apksigner**（`com.android.apksigner.ApkSignerTool`，v1+v2/v3；release 用 build.sbt 的密钥库，debug 用宿主测试证书）；签名前用 `KeystoreTool` 用**同类型+同口令**预校验。宿主 `Tool.APK_SIGNER` 只作为兜底（它不传 `--ks-type`） |
| `buildApk` | 构建APK | 收口：拷贝到 `build/outputs/apk/<variant>/<工程名>.apk` 并打日志 |
| `installApk` | 安装APK | `ContentUtils.installApk(activity, path)`（与本体 `DebugRunConfig` 的"调试打包运行"同一套做法） |

```
sbtResolve（解析 build.sbt + 依赖下载 / AAR 解包）
  ├─ compileProjectResources ─┐
  └─ mergeManifest ───────────┴─ linkAppResources（→ res.ap_ + R.java）
                                   └─ compileRJava ─┬─ compileScalaSources
                                                    └─ compileJavaSources
                                                          └─ dexWithD8
                                                                └─ packageApk（ApkPacker 自研打包）
                                                                      └─ zipAlignScala
                                                                            └─ signApkScala
                                                                                  └─ buildApk
                                                                                        └─ installApk（交给系统安装器）
```

### 5.2 日志显示方式（与结绳本体一致）

宿主的 `DefaultTaskExecutor.executeSingleTask()` 是这么打印的：

```java
task.prepare(project, variant, logger);
String tip = "任务: " + project.getName() + " ==> " + task.getName();
if (state == NO_SOURCE)       logger.info(tip + "(跳过)");
else if (state == UP_TO_DATE) logger.info(tip + "(无需执行)");
else if (state == PREPARE)  { logger.info(tip); task.execute(); }
```

也就是说：**状态在 `prepare()` 里定，`execute()` 里改状态对显示没有意义**；而且任务实例在工程打开时创建一次、整个会话复用，
上一次构建留下的 `NO_SOURCE/UP_TO_DATE` 会粘住 —— 所以本插件**每个任务的 `prepare()` 都显式设状态**
（`ScalaAndroidTask.decideState(...)`：没输入 → `NO_SOURCE`；产物比输入新 → `UP_TO_DATE`；否则 `PREPARE`），增量判断按
"产物 mtime ≥ 所有输入的递归最新 mtime，且 `build.sbt` 一改全部失效"来做：

| 任务 | 增量依据 |
| --- | --- |
| 编译项目资源文件 | `res/` + AAR 的 `res/` → flat 目录 |
| 合并清单文件 | 主清单 + AAR 清单 → 合并后的清单 |
| 链接资源文件 | flat + 合并清单 + assets → `res.ap_` |
| 编译R类 | 生成的 R.java → R class 目录 |
| 编译Scala源文件 | `.scala` + R class + 依赖 jar + `build.sbt` → class 目录（无 `.scala` → 跳过） |
| 编译Java源文件 | `src/main/java/**` + R class + Scala class（无 `.java` → 跳过） |
| 编译到Dex(D8) | R/Scala/Java class + 运行时依赖 → dex 目录 |
| 生成APK | `res.ap_` + dex + nativeLibs + javaRes → 未签名 APK |
| 对齐APK | 生成的 APK → 对齐后的 APK |
| 签名APK | 对齐后的 APK + 密钥库（换密钥库会重签）→ 签名后的 APK |
| 构建APK | 签名后的 APK → `build/outputs/apk/<variant>/` |
| 安装APK | 每次都执行（它是"动作"，没有产物可比） |

结果就是：改一个文件 → 从"编译Scala源文件"开始往下重跑；什么都没改 → 只剩 `(无需执行)`，与本体日志同一种读感。

### 5.3 为什么 R 类要单独一个任务、还要排在 Scala 之前

安卓源码里到处是 `R.layout.xxx / R.id.xxx`，而 R 类只有 **aapt2 link 之后**才存在。
最初的顺序是 `编译Scala → … → 链接资源`，于是 Scala 编译直接报：

```
MainActivity.scala:12:20 Not found: R
```

修正后把资源链整体提到编译之前，并把"生成 R.java → 编译 R 类"独立成 `编译R类`：

```
编译项目资源文件 → 合并清单文件 → 链接资源文件（产出 R.java）→ 编译R类 → 编译Scala源文件 → …
```

R class 目录同时进 Scala/Java 编译的 classpath 与 D8 的 program files。

### 5.4 与结绳本体的差异（有意为之）

* 本体用 `GenerateAPKTask` 手写 `JarOutputStream` + SHA1 摘要 + PKCS7 签名，那套代码依赖宿主内部的 `sun1.security.*`（不在对外 API 里）；本插件改用**公开的 `ApkGenerator`/`ApkSigner`/`Zipalign` 接口**，效果一致且不依赖宿主私有类；
* 本体把编译交给 `compileWithTiecodec`（结绳编译器），本插件交给随插件打包的 **Scala 3 编译器**。

### 5.5 接宿主 API 时踩过的坑（都已修，留档免得再踩）

| 现象 | 根因 | 修法 |
| --- | --- | --- |
| `Multiple entries with same key: applicationId=… and applicationId=…` | 宿主的 `ManifestMerger2.merge()` 会自己往占位符表里放 `packageName`/`applicationId`（取自 `overridePackage`），我在 `ManifestMergeParams.placeholders` 里又放了一遍，Guava `ImmutableMap.Builder` 直接抛异常 | `placeholders` 置 null，只走 `overridePackage/overrideVersionCode/...` |
| `aapt2 compile` 报文件不存在 | 宿主的 `Aapt2.compile()` 是把 `sourcePaths` **按文件**拼到命令行的（不是 `--dir`），我传了资源<b>目录</b> | 递归收集资源文件逐个传；每个资源根单独输出到子目录，避免同名文件产出同名 `.flat` |
| assets 被打到 APK 根目录 | `ApkGeneratingParams.jarResDirs` 会被摊到 APK **根**下（它是给 `src/main/resources` 用的）；assets 应该只由 aapt2 link 的 `-A` 打进 `res.ap_` 的 `assets/` | `jarResDirs` 只放 `src/main/resources` |
| v2/v3 签名失效风险 | 我原来的顺序是"签名 → 对齐"，而 zipalign 会重写 zip 布局 | 改成结绳本体/Google 的顺序：**生成APK → 对齐 → 签名** |
| 签名APK：`KeyStoreException: JKS not found`（且装不了 APK） | **这台 Android 运行时没有 JKS 密钥库实现**（JKS 由 SUN provider 提供，很多 ROM/运行时只注册了 BouncyCastle 的 BKS/PKCS12；本机 `/system/etc/security/java.security` 甚至不存在）。宿主的测试证书之所以走 X509 的 `--key/--cert`，正是为了绕开密钥库。**JKS 读不了 → 预校验失败 → 我拦住了 apksigner（拦不住就是闪退）**，于是链停在签名，后面的构建APK/安装APK 都不会执行 —— 这就是"没有安装apk"的原因 | ①运行时**探测**可用类型并写进日志（`可用 [PKCS12, BKS] · 不可用 [JKS]`）；②要用的类型不可用且命中"早期模板发的 JKS"（按 SHA-256 精确匹配）→ **自动换成插件自带的同一把密钥的 PKCS12 副本**（签名身份不变，不用改工程）；③其他读不了的密钥库 → **退回宿主测试证书**（X509，与 debug 同一条路）并写明原因；④模板发的测试密钥库改成 **PKCS12** |
| 签名APK 报 `INTERRUPT` 但日志里没有原因、也没装 APK | 1) 宿主的 `DefaultTaskExecutor` 异步路径**不打印 `TaskInterruptError` 的消息**，只打 `执行中断`；2) 签名前的密钥库预校验/转换失败被我先拦下了（这是好事：拦不住就是闪退） | ①`ScalaAndroidTask.fail(...)`：所有任务失败都先 `logger.error` 再抛异常（本次共改 42 处），原因一定进构建日志；②**不再做类型转换**，改成**带 `--ks-type` 直接调用宿主内置 apksigner**，类型由文件头嗅探且已用同类型预校验，加载必然成功；③安装APK 是签名之后的任务，签名失败自然不会执行（日志里能直接看到原因了） |
| **正式打包运行闪退**（debug 正常；`intermediates/signApkScala/release/` 为空、`Crashes/` 里没有崩溃日志） | 宿主的 apksigner 只传 `--ks`，**从不传 `--ks-type`**，而 apksig 的 `SignerParams` 里用的是 `KeyStore.getDefaultType()`；Android 上默认类型回退到 **JKS**，于是拿工程里的 **PKCS12** 去 `KeyStore.getInstance("JKS").load()` → 加载失败 → `ApkSignerTool.main` 走到 `System.exit(...)` → **结绳整个进程退出（闪退）**，所以既没有构建报错也没有崩溃日志。debug 之所以正常：宿主测试证书是 `KeyType.X509`（`AndroidTestKey.toStoreParams()`），走 `--key/--cert` 分支，根本不碰密钥库。宿主自己的 `SignAPKTask` 里也写着「V2签名不能使用PKCS12签名证书，请更换JKS或PK8」 | 新增 `KeystoreTool`：签名前先 **按 `KeyStore.getDefaultType()` 打开并取出私钥做预校验**，类型不一致就**自动转成平台默认类型的副本**（别名/口令不变，写在插件缓存目录）再交给 apksigner —— 我们和 apksigner 在同一进程同一 runtime，`getDefaultType()` 必然一致；密钥库有问题时**提前抛出可读错误**，绝不把坏参数丢给那个会 `System.exit` 的工具。模板里的 `release-test.keystore` 也换成了 **JKS** |
| 新建工程没有 `project/`，认不出 sbt 版本与 Android 插件 | `AssetManager.list()` 对手工 zip 的嵌套目录不可靠 | 模板改为单个 zip 资源 + `open()` + `java.util.zip` 解包；并兜底补 `project/build.properties`（见 8.1） |
| `生成APK` 不产出文件（intermediates/packageApk 是空目录） | 宿主注册的工具里**没有 `Tool.APK_GENERATOR`**（只有 AAPT2/D8/MANIFEST_MERGER/JAVA_COMPILER/ZIPALIGN/APK_SIGNER/R8/PROGUARD…；结绳本体打包走的是 `GenerateAPKTask` 自己的 `JarOutputStream`），所以 `getTool(APK_GENERATOR)` 返回 null | 打包改为自研 `ApkPacker`（`java.util.zip`，`resources.arsc`/`.so` 不压缩），对齐/签名仍用宿主的 `Tool.ZIPALIGN`/`Tool.APK_SIGNER`；并加了打包器自检（见 8） |
| `com.android.tools.r8.internal.ax: Unsupported source file type` | 宿主内置的 D8 **不接受目录**作为 program 输入；结绳本体也是递归收集 `.class` 再喂（`DexClassWithD8Task.getClassFiles()`），目录只当 classpath | `programFiles` 改成"逐个 `.class` 文件 + jar"，并打印 class/jar 数量 |
| （未报错但必然踩）APK 一运行就 `NoClassDefFoundError: scala/...` | 只编译不 dex 运行库：`scala-library`/`scala3-library` 必须进 dex，而编译期的 `android-hidden-stubs.jar` 绝不能进 | program files 加上 `ScalaToolchain.runtimeJars()`；有 Scala 类却拿不到运行库时直接报错，不产出坏 APK |

## 6. Scala 代码补全（复制自 scala控制台插件 + 依赖联动）

复制并适配的部分（包名 `com.scala.plugin` → `com.scala.android`）：

| 来源 | 去向 | 作用 |
| --- | --- | --- |
| `project/ScalaCompletionProvider` | `project/` | 关键字 / 代码片段 / 当前文件声明 / **classpath 类型（带自动 import）** |
| `project/ScalaSemanticCompletion` | `project/` | dotty 交互式分析的**语义补全**（单飞 + 防抖，永不阻塞补全线程） |
| `project/ScalaLanguageEngine`、`ScalaSources` | `project/` | 语言引擎接线；包名 ↔ 源码目录换算（已同时支持 `src` 与 `src/main/scala`） |
| `project/option/*` | `project/option/` | 「新建Scala类」「新建单例类」 |
| `compiler/ScalaCompilerApi`、`ScalaAnalysisApi`、`ScalaClasspathIndexApi` | `compiler/` | dotty 封装：编译、交互式补全、classpath 类型索引 |
| `lang/*` | `lang/` | Scala 词法 / 高亮 / 括号运算符 |
| `tool/*` | `tool/` | 编译工具链（`ScalaCompile` 工具 + 标准库安装） |
| `task/ScalaFiles` | `task/` | 源文件收集 / dex 排序 |

**语言关联（很容易漏的一环）**：宿主的语言是按**扩展名**找工厂的 ——
`DefaultLanguageManager.findLanguageFactory(fileType)` 遍历工厂比对 `getAssociateFileType()`，
再由工厂的 `languageId` 找到引擎（`getEngineByExtension`）。只注册 `scala` 的话，
`build.sbt`（扩展名 `sbt`）既没有语言（不高亮）也没有引擎（不补全）。所以本插件注册三个工厂，
它们**共用同一个 `languageId = scala`**：

| 工厂 | 认领扩展名 | 效果 |
| --- | --- | --- |
| `ScalaLanguageFactory` | `.scala` | 高亮 + 补全 |
| `SbtLanguageFactory` | `.sbt` | 高亮 + 补全，且额外给 sbt 的键/运算符/片段 |
| `ScalaScriptLanguageFactory` | `.sc` | 高亮 + 补全 |

`*.sbt` 里额外补的内容（`ScalaCompletionProvider.addSbtSettings`）：`ThisBuild / scalaVersion`、
`libraryDependencies`、`resolvers`、`Compile / scalacOptions`、`platformTarget`、
`androidApplicationId/androidMinSdk/androidTargetSdk/androidVersionCode/androidVersionName`、
`androidReleaseKeystore*` 等键，`:= += ++= -= % %%` 运算符，以及
`libraryDependencies += "org" % "name" % "1.0"`、`resolvers += "名" at "url"` 这类片段。

**新增的联动**（`ScalaToolchain.setExtraClasspathProvider`）：工程打开后台**离线**解析一次 `build.sbt` 的依赖（只用本地缓存、不联网），
把解析出的 jar/AAR classes.jar 接进补全 classpath —— 于是 build.sbt 里写的 `okhttp` 之类，在编辑器里也能补全并自动 `import`。

---

## 7. 目录结构

```
scala安卓工程插件/
├── app/
│   ├── libs/api/studio-api-1.0-preview4.jar     宿主 API（compileOnly，不进包）
│   ├── libs/scala/*.jar                         Scala 3 编译器/标准库（随插件 dex 进包）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/drawable/{ic_scala_project,ic_file_scala,ic_file_sbt}.xml  工程/模板图标 + .scala/.sbt 文件图标（矢量）
│       ├── resources/META-INF/program.json      扩展点：application + project.manager + platform.settingsBuilder
│       ├── assets/
│       │   ├── scala-toolchain/lib/*.jar        运行时标准库（首次使用复制到私有 env 目录）
│       │   └── templates/project/
│       │       ├── scala_android_sbt/**                 新建工程模板（= scala-android-app-modern 结构，仓库里的可读来源）
│       │       ├── scala_android_sbt.zip                同一份模板的单文件形态（运行时用这个，见 8.2）
│       │       ├── scala_tiecode_plugin/**              结绳插件工程模板（1.10，= 结绳本体插件工程结构）
│       │       └── scala_tiecode_plugin.zip             同一份模板的单文件形态（运行时用这个）
│       └── java/com/scala/android/
│           ├── ScalaAndroidPlugin.java          插件入口：注册 Scala 编译器 + 设置页
│           ├── sbt/                             ★ 自研 sbt 构建脚本解析器（词法/语法/求值/模型）+ TiecodePluginKeys（1.10）
│           ├── dependency/                      ★ 自研 Maven-lite 依赖解析下载 + AAR 解包
│           ├── androidproject/                  工程 / 管理器 / 文件树 / 模板（ScalaPluginProjectTemplate，1.10）
│           ├── task/                            14 个构建任务（编译/资源/清单/dex/打包/签名/对齐 + buildPlugin/installPlugin，1.10）
│           ├── run/                             6 个运行配置（+ 构建结绳插件 / 调试结绳插件，1.10）
│           ├── setting/                         设置页与设置读写
│           ├── util/                            宿主路径、清单信息、密钥库、assets 拷贝
│           ├── compiler/ lang/ tool/ project/   ← 复制自 scala控制台插件 的补全与编译链路
├── tests/                                       两个 JVM 自检 Harness（不参与打包）
├── tools/build-rmx.sh                           没有 Android SDK 也能出 .rmx（1.10 起自动重建两个模板 zip，带 UTF-8 locale 兜底）
├── tools/build-template-zip.sh                  Scala 安卓工程模板 → 单文件 zip
├── tools/build-plugin-template-zip.sh           结绳插件工程模板 → 单文件 zip（1.10）
├── tools/run-tests.sh                           sbt 解析器 / 依赖解析器自检
├── build.gradle / settings.gradle / app/build.gradle
└── scala安卓工程插件-1.10.rmx
```

---

## 8. 构建

```bash
# A: AIDE / Gradle（需要 Android SDK）
./gradlew :app:assembleRelease     # → app/build/outputs/apk/release/scala安卓工程插件-1.10.rmx

# B: 命令行（本仓库的 .rmx 就是这么打的，只需 javac/java/aapt2/zipalign + android.jar + r8.jar）
ANDROID_JAR=/path/android-stripped.jar R8_JAR=/path/r8.jar ./tools/build-rmx.sh

# 单独重建模板 zip（build-rmx.sh 与 Gradle 的 templateZip / pluginTemplateZip 任务都会自动做）
./tools/build-template-zip.sh          # 默认 sbt 模板
./tools/build-plugin-template-zip.sh   # 结绳插件工程模板（1.10）

# 自检（普通 JVM，不需要设备/SDK）：解析器 / 依赖解析器 / 工程模板
./tools/run-tests.sh
```

`run-tests.sh` 的五步：① sbt 解析器读一个穷举写法的 `build.sbt`；② 本地 m2 仓库上的依赖解析；③ **模板自检** —— 解包
`scala_android_sbt.zip` 成工程，检查 `project/build.properties`、`project/plugins.sbt` 等关键文件都在，再交给真实解析器读一遍
（必须读出 `sbt.version=2.0.3`、`applicationId=com.example.myapp`、`androidPlugin=true`、0 条诊断）；
⑤ **密钥库归一化自检**（PKCS12 → 平台默认类型，并校验别名/口令/证书链）；④ **APK 打包器自检** —— 用一份 `res.ap_` + `classes.dex` 打出 APK，核对 `resources.arsc` 不压缩、其余压缩、zip 可读。

打包/签名这条链另外在开发机上用**真机的产物**端到端验证过：
`res.ap_` + `classes.dex`（取自设备 `build/intermediates/`）→ `ApkPacker` → `zipalign -p -f 4` → `apksigner sign`
→ `apksigner verify` 输出 **Verified using v3 scheme: true**。

### 8.1 为什么模板要打包成 zip

插件运行时是用 `AssetManager.addAssetPath(<插件.rmx>)` 建的资源管理器。实测（真机 结绳 + 本插件）：

* `assets/scala-toolchain/lib/*.jar` 用 **`open()` 读取正常**（Scala 编译成功就是证据）；
* 但 `AssetManager.list()` 对**手工打的 zip 里的嵌套目录**不可靠 —— `assets/templates/.../project/` 整棵子树被静默漏掉，
  结果新建出来的工程缺 `project/plugins.sbt` 与 `project/build.properties`，插件于是"认不出 Android 插件、也不知道 sbt 版本"。

所以模板改为：**目录（仓库里的可读来源）→ 打成单个 zip 资源 → 运行时 `open()` 读出来 → 纯 `java.util.zip` 解包 → 文本占位符替换**。
只依赖"能按路径读单个 assets 文件"这一条。另外 `ScalaAndroidTemplate.ensureProjectFiles()` 还兜一层：
万一 `project/plugins.sbt`、`project/build.properties` 仍然缺失，直接按常量写出来。

（1.10 的结绳插件模板 `ScalaPluginProjectTemplate` 走同一条路：`scala_tiecode_plugin.zip` 优先，
兜底按常量补 `project/build.properties` 与 `src/main/resources/META-INF/program.json`。）

`build-rmx.sh` 做的事：`aapt2 compile/link`（顺带生成 `R.java`）→ `javac` 插件（含 R.java）→ `D8` 把插件 class **和 Scala 编译器一起** dex 成多 dex → 组装 `.rmx`（`base.apk` 条目 + `classes*.dex` + `META-INF/program.json` + `assets/**`，`resources.arsc` 保持不压缩）→ `zipalign`。

当前产物：`scala安卓工程插件-1.10.rmx`（3 个 dex，约 16 MB，内含 21 个默认模板文件、22 个结绳插件模板文件与 scala-toolchain 标准库）。

---

## 9. 已知限制（有意保留的边界）

**解析器**
* 是 Scala 的**子集**：不解释任意方法调用、for/comprehension、模式匹配、宏；`project/*.scala` 形式的构建定义只登记不解析（会记警告）。
* setting 只做「键路径 → 值」的存储与三类增删，不实现 sbt 的 task 图/setting 依赖求值。
* 支持 `val` 引用与字符串拼接，但不做类型检查。

**依赖解析**
* 支持版本号字面量，不解析 Maven 版本区间（`[1.0,)`）、`latest.release`、SNAPSHOT 时间戳元数据；
* `dependencyManagement` 支持普通约束，不处理 `<scope>import</scope>` 的 BOM；
* 只做公开仓库的匿名访问，不处理私服认证/代理配置（用设置页的"额外仓库"加镜像）。

**打包**
* 不做混淆（proguard/R8）与资源压缩：aapt2 link 会产出 `aapt_rules.txt`，但暂未接入 `Tool.PROGUARD`；
* 不做增量编译缓存（每次都全量编译），也没有多模块 `dependsOn` 的产物合并（只打当前工程）；
* release 变体若 `build.sbt` 里配的密钥库不存在，会退回宿主测试证书并在日志里提示。

**运行时**
* 插件最低 API 33（Scala 3.8 编译器的硬要求）；
* **1.0 之前新建过的工程**：受上面那个 `list()` 问题影响，工程里可能缺 `project/` 目录。重新用一个插件新建即可；
  已有工程手工补上 `project/plugins.sbt`（`addSbtPlugin("io.github.botr" % "sbt-modern-android" % "1.0.0")`）
  与 `project/build.properties`（`sbt.version=2.0.3`）也一样能构建（插件现在也能只凭 `enablePlugins` 认出安卓工程）；
* 依赖下载在构建任务线程中进行，大依赖首次拉取会比较慢（有本地缓存后就快了）。

---

## 10. 结绳插件工程（1.10 新增）

在"新建 Scala 安卓工程"的模板选择对话框里多了一个 **「结绳插件工程」**（`ScalaPluginProjectTemplate`，
模板名「结绳插件工程」）。它生成的工程跟默认 sbt 模板**共用同一条构建链**
（`sbtResolve → … → buildApk`），只是多挂了两个收尾任务 + 两个运行配置，
行为对齐结绳本体 `Product-Plugin/Java` 的 `BuildQiPluginConfig` / `DebugQiPluginConfig`：

| 项 | 默认 sbt 模板（给设备） | 结绳插件工程模板（给结绳宿主） |
| --- | --- | --- |
| 示例源码 | `MainActivity.scala`（`Activity` 入口） | `TestMenuAction.scala`（继承 `com.rapid.api.framework.domain.menu.BaseDomainMenuAction`，挂 `domain.menu` 扩展点，覆写 `getId`/`getTitle`/`getIcon`/`perform`） |
| 清单 | 普通应用清单 | 多一组 `meta-data`：`tiecode_plugin=true`、`plugin_name`、`plugin_targetVersion=4`、`plugin_versionCode`、`plugin_versionName`、`plugin_author`、`plugin_note`、`plugin_class`（4.x 旧世代 .rmx 的识别键） |
| 插件清单 | 无 | `src/main/resources/META-INF/program.json`（新世代清单，`id`/`engine=plugin`/`extension[]{point,class}`） |
| build.sbt 键 | 普通 | 多一条 `tiecodePlugin := true`（让解析器把工程标记成插件工程） |
| 运行配置 | 调试/正式打包运行、同步sbt依赖、清理构建产物 | 原有 4 个 **+「构建结绳插件」「调试结绳插件」** |

### 10.1 识别规则（`TiecodePluginKeys`）

「这是不是一个结绳插件工程」按顺序命中任意一条就算：

1. `build.sbt` 里 `tiecodePlugin := true`（或 `tiecodePluginEnabled := true`）；
2. `project/plugins.sbt` 里 `addSbtPlugin("…tiecode…"/"…qiplat…")`；
3. `src/main/AndroidManifest.xml` 里出现 `tiecode_plugin` meta-data；
4. 工程目录里存在 `src/main/resources/META-INF/program.json` 且有 sbt 脚本
   （`ScalaAndroidProjectManager.isTiecodePluginProjectDir`，工程管理器据此优先认领该目录）。

命中时 `SbtAndroidConfig.isTiecodePlugin` 置位，打开工程的日志里会多一行
「结绳插件工程识别：✓ …」提示，两个插件运行配置照常注册（普通工程里它们也能用，
只是产物是 apk，不会做 rmx 改名与宿主安装）。

### 10.2 新增任务（`task/`）

| 任务 id | 任务名 | 行为 | 对应结绳本体 |
| --- | --- | --- | --- |
| `buildPlugin` | 构建结绳插件 | 整条 APK 链（release 变体）跑完后，把 `build/outputs/apk/release/<工程名>.apk` 拷成 `build/outputs/plugins/release/<工程名>.rmx`，并校验包里确实带 `META-INF/program.json`（缺了给可读错误） | `GenerateQiPluginTask` |
| `installPlugin` | 安装结绳插件 | 读 `program.json` 里的 `id` → 拿宿主公共目录 `<publicRoot>/Programs/<id>/` → 广播 `app.intent.action.SHUTDOWN` 停掉运行中的结绳 → 目录已有旧插件：**覆盖拷 rmx + 显式 Intent 重启宿主**；没有：**`file://` Uri 交给宿主 `Splash`，走 `ImportingPluginHandler` 首次安装** | `DebugQiPluginConfig.startDebug` |

任务依赖：`installPlugin` 依赖 `buildPlugin`，`buildPlugin` 依赖 `buildApk`。

### 10.3 新增运行配置（`run/`）

| 运行配置 | id | 指向任务 | 变体 | 说明 |
| --- | --- | --- | --- | --- |
| **构建结绳插件** | `scala-tiecode-plugin-build` | `buildPlugin` | release | 跑完链只出 rmx，不安装；成功时日志打印 rmx 绝对路径，缺 `program.json` 给 warning |
| **调试结绳插件** | `scala-tiecode-plugin-debug` | `installPlugin` | release | 跑完链把 rmx 装进宿主 `Programs` 目录并重启结绳，新会话装载器加载新插件 |

两者都会在 `onStart()` 里 `invalidateBuild()` 重解析 build.sbt（与 `PackAndRunConfiguration` 一致），
因为模板 `build.sbt` 里的 `tiecodePlugin` 键、签名配置随时可能被改。

### 10.4 安装路径与宿主对齐

* 宿主的程序安装目录由 `Platform.getSystemPath().getPublicPath(PublicPath.PROGRAMS)` 给出
  （`ProgramInstallerImpl` 就是这么定的），本任务优先用它；拿不到时兜底
  `<publicRoot>/Programs`，再拿不到退到旧世代目录
  `Android/data/com.tiecode.develop/files/plugins/Programs`。
* 宿主包名硬编码 `com.tiecode.develop`，启动页 `com.qiplat.develop.page.module.splash.Splash`
  （与结绳本体 `DebugQiPluginConfig` 里的 `DEBUG_PLATFORM_APP_PKG` / `DEBUG_PLATFORM_START_PAGE` 一致）。
* 覆盖安装后直接重启宿主（不传 Uri）；首装走 `file://` Uri 让宿主自己装
  （这样宿主会做它自己的校验 / 写 `plugin.properties`，与"用户手动拷 rmx 进目录"等价但更完整）。
