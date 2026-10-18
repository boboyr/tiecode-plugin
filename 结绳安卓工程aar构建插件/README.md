# 结绳安卓工程aar构建插件

给**结绳安卓工程**（`typeId = cn.tiecode.android`）加两个运行配置：

| 运行配置 | 做什么 | 产物 |
| --- | --- | --- |
| **构建aar** | 复用宿主编译链，把工程打成 aar | `build/outputs/bundleAarOriginal/release/<工程名>.aar` |
| **构建jar** | 复用宿主编译链，把编译产物打成 jar | `build/outputs/bundleJarOriginal/release/<工程名>.jar` |

两个配置在构建期间都会：① 按**结绳原中文类名**输出（不再随机生成 `rn_`）；
② 使用设置页里选的 **Java 版本**。构建结束立刻还原，平时调试/构建应用不受影响。

- 插件名：`结绳安卓工程aar构建插件`　插件 id：`com.tiecode.aarplugin`
- 产物：`结绳安卓工程aar构建插件-1.5.rmx`

**本插件不打包编译器**：没有 `tiecode-compiler.aar`、没有 `libtiec_shared.so`，
结绳源码一律交给宿主的 `compileWithTiecodec` 任务编译。

---

## 1. 名字从哪来：宿主编译器的 NameGenerator

结绳源码 → Java 的输出名由编译器的 `NameGenerator` 决定。宿主的
`AndroidTiecodecCompiler.beforeCompile()` 里只有一句：

```java
NameGenerator nameGenerator = AndroidNameGenerator.instance(context);
```

而 `AndroidNameGenerator.instance(context)` 是「**上下文里有就拿来用，没有才 new 默认的**」；
`Context.register()` 的实现就是 `Map.put`（覆盖式）。所以只要在编译前把自己的
`NameGenerator` 注册进上下文，`SymbolEnter` 里这些赋值就全走我们的实现：

| 结绳元素 | 调用 | 默认(RANDOM) | 原名模式 |
| --- | --- | --- | --- |
| 类 / 事件 / 变量 / 普通方法 | `randName(name)` | `rn_1234` | `启动窗口` / `唱` / `计数` |
| 虚拟方法、getter/setter | `randCacheName(name)` | `rn_1234` | `创建完毕` |
| 运算符重载 | `randOperatorName(name)` | `rn_1234` | 原名（不合法则回退随机） |
| 包名 | `randPackage(name)` | `rn_1.rn_2.rn_3` | `结绳.安卓` |

插件的接法（`compiler/` 包）：

```java
CompilerPlugins.assemble(new OriginalNameCompilerPlugin());   // activate() 里装配一次

public void apply(Compiler compiler) {                        // 宿主 compile() 内部回调
    if (!OriginalNameSwitch.isEnabled()) return;              // 只有点「构建aar/构建jar」才接管
    Context ctx = compiler.getContext();
    OriginalNameGenerator g = new OriginalNameGenerator();
    Options o = ctx.get(Options.key);
    g.putCacheName(Names.of(o.namespace), PinYinUtils.getPinYin(o.namespace)); // 应用包名留 ASCII
    ctx.register(NameGenerator.key, g);
}
```

- 宿主 `TiecodeCompiler.compile()` 顺序是 `beforeCompile(...)` → `Plugins.applyCompilers(this)`，
  注册是覆盖式的，所以即使默认生成器已经 new 出来也会被替换；
- 应用包名（`我的.安卓.应用`）仍按宿主做法走拼音 `wode.anzhuo.yingyong`（aapt2/Android 要求 ASCII），
  类名方法名保持中文，绳包包名（`结绳.安卓`）保持中文；
- 构建结束立刻关掉开关，平时的调试/构建应用仍旧是宿主的随机命名。

## 2. 任务链（全部复用宿主任务）

```
构建aar：
compileWithTiecodec  (宿主：编译结绳源码)
      ↓  res / assets / jniLibs / AndroidManifest.xml / metadata.json
compileProjectResources → mergeManifest → linkAppResources  (宿主：资源与清单、R.txt)
      ↓
compileWithJavac     (宿主：编译 .class)
      ↓
bundleAarOriginal    (本插件：只做收集 + 压缩)

构建jar：
compileWithJavac     (宿主：同一条链)
      ↓
bundleJarOriginal    (本插件：只出 jar)
```

插件只加两个任务，都 `dependsOn(compileWithJavac)`；「构建aar」额外挂了
`linkAppResources`（取 R.txt）和宿主的「构建动态链接库」`compileNativeObj`
（工程自己写 JNI 时产出 .so，没有 JNI 时该任务自动跳过）。

## 3. 注解产物 → aar 里的位置

| 结绳注解 / 产物 | 编译器输出目录 | aar 里的位置 |
| --- | --- | --- |
| `@安卓资源.图片("../res/drawable/go_sdl3.xml")` | `<编译输出>/res/**` | `res/**` |
| `@附加资源("../资源")` | `<编译输出>/assets/**` | `assets/**` |
| `@外部动态库("../jniLibs")` | `<编译输出>/jniLibs/**` | `jni/**`（aar 标准目录名） |
| 编译器生成的清单 | `<编译输出>/AndroidManifest.xml` | 根 `AndroidManifest.xml` |
| 编译出的 class | `compileWithJavac` 中间目录 | 根 `classes.jar`（**只有 class，不含 META-INF，不含 R 类**） |
| 资源链接产物 | `linkAppResources/R.txt` | 根 `R.txt` |
| `extra_res` / `extraRes` | `<编译输出>/extra_res/**` | 合并进 `res/**` |
| 编译器收集的依赖 jar | `<编译输出>/libs/**` | `libs/**` |
| 工程根 `consumer-rules.pro` | — | 根 `proguard.txt` |

### 兼容两种 metadata.json

宿主不同版本写出的结构不一样，插件两种都解析（`util/AarMetadata.java`）：

- 旧版（手机上 4.6 系就是这个）：顶层 `extraRes` / `libs` + `target.{res,assets,jni,jniLibs,manifest,extra_res,libs,src}`
- 新版：`productInfo.{resDir,assetsDir,jniLibsDir,manifest,extraResDirs,libsDir,...}` + `appConfig`

## 4. `@附加资源` 落在 assets/（与宿主一致）

宿主 `AssetsProcessor` 的实现是 `FileUtils.copyDir(注解目录, output.assetsPath)`：
注解目录的内容会被拷进编译输出的 `assets/`，打 APK 时就是 APK 的 assets。

所以 AAR 里也**原样放进 `assets/`**，插件不做任何“根目录合并 / 并进 classes.jar”的加工——
工程里没有的类，产物里也不该冒出来。

例：工程里放

```
源代码/初始代码.t          @安卓资源.图片("../res/drawable/go_sdl3.xml")
                          @附加资源("../资源")
                          @外部动态库("../jniLibs")
res/drawable/go_sdl3.xml
资源/classes.jar
资源/R.txt
资源/META-INF/com/android/build/gradle/aar-metadata.properties
jniLibs/arm64-v8a/libtiec_shared.so
```

「构建aar」后得到（拿该工程真实的 `build/intermediates` 产物跑 `util/AarPacker`
验证出来的，不是推测）：

```
我的应用cs.aar
 ├── classes.jar                 14237   （只有 19 个本模块 .class，无 META-INF、无外来类）
 ├── AndroidManifest.xml           738
 ├── R.txt                         122
 ├── res/drawable/go_sdl3.xml      591   ← @安卓资源.图片
 ├── res/drawable/icon.png        2051
 ├── res/values/{strings,themes}.xml
 ├── assets/classes.jar          79292   ← @附加资源（宿主把附加内容放 assets）
 ├── assets/R.txt                     0
 ├── assets/META-INF/com/android/build/gradle/aar-metadata.properties
 └── jni/arm64-v8a/libtiec_shared.so  6827888  ← @外部动态库
```

## 5. 运行配置「构建jar」

- 产物：`build/outputs/bundleJarOriginal/release/<工程名>.jar`
- 内容：`compileWithJavac` 中间目录里的类与资源原样压包（含 R 类；插件不会自己写
  `META-INF/MANIFEST.MF`）。
- 同样按结绳原中文类名输出，并应用设置页里选的 Java 版本。

## 6. 设置页：打包用的 Java 版本

路径：**设置 → 项目管理器 → 「AAR/JAR 打包」→ Java 版本**

可选：`默认`、`1.8`、`9`、`10`、`11` … `25`（默认 + 1.8 + 9~25，共 19 项）

实现（`setting/` 包）：

```java
// 构建开始（运行配置 onStart）
OriginalNameSwitch.setEnabled(true);
JavaVersionSetting.apply();     // 把工具链里的 Tool.JAVA_COMPILER 临时换成 VersionedJavaCompiler

// VersionedJavaCompiler：只改 source/target，真正编译仍委托宿主原来的编译器
public String getCompilerVersion() { return version; }          // 宿主用它填 JavacParams 的 source/target
public boolean compile(JavacParams p, OnExecutionCallback cb) {
    p.sourceVersion = version; p.targetVersion = version;
    return delegate.compile(p, cb);
}

// 构建结束（onResult）
JavaVersionSetting.restore();   // 原样换回宿主的编译器
```

- 为什么能生效：宿主 `CompileWithJavacTask` 是
  `params.sourceVersion = javac.getCompilerVersion(); params.targetVersion = ...`，
  而 `javac` 是每次执行时从 `ToolchainService.getTool(Tool.JAVA_COMPILER)` 现取的，
  所以临时替换就够，不需要改宿主代码。
- 选「默认」= 完全不碰工具链。
- 只在这两次构建期间生效，平时的调试/构建应用不受影响；替换失败会记日志并回退宿主默认编译器。

## 7. 目录

```
结绳安卓工程aar构建插件/
├── app/
│   ├── libs/api/studio-api-1.0-preview4.jar     宿主 API（compileOnly，不进包）
│   └── src/main/
│       ├── resources/META-INF/program.json      扩展点：application + project.component
│       └── java/com/tiecode/aarplugin/
│           ├── AarBuildPlugin.java              activate()：装配编译器插件 + 注册设置页
│           ├── AarProjectComponent.java         挂两个任务 + 注册「构建aar / 构建jar」
│           ├── compiler/OriginalNameCompilerPlugin.java   ★ 注册原名 NameGenerator
│           ├── compiler/OriginalNameGenerator.java        ★ 原名生成器（非法名回退随机）
│           ├── compiler/OriginalNameSwitch.java           原名开关（只在构建期间打开）
│           ├── setting/JavaVersionSetting.java            Java 版本设置 + 工具链替换/还原
│           ├── setting/VersionedJavaCompiler.java         javac 装饰器（只改 source/target）
│           ├── setting/AarSettingsBuilder.java            设置页
│           ├── run/BuildAarRunConfiguration.java          「构建aar」
│           ├── run/BuildJarRunConfiguration.java          「构建jar」
│           ├── task/BundleAarTask.java                    取宿主产物 → AarPacker
│           ├── task/BundleJarTask.java                    取编译产物 → 打 jar
│           └── util/{AarMetadata,AarPacker,AarFiles}.java metadata 解析 + 打包逻辑
├── tools/build-rmx.sh                           没有 Android SDK 时也能出 .rmx
└── 结绳安卓工程aar构建插件-1.5.rmx
```

## 8. 构建

```bash
# A: AIDE / Gradle
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/结绳安卓工程aar构建插件-1.5.rmx

# B: 命令行（本仓库的 .rmx 就是这么打的）
ANDROID_JAR=/path/android.jar R8_JAR=/path/r8.jar ./tools/build-rmx.sh
```

## 9. 注意

- 应用包名仍是拼音（`我的.安卓.应用` → `wode.anzhuo.yingyong`），这是 Android 的硬要求。
- 原名模式下，结绳源码里同作用域重名（事件与方法同名等）会真的撞成 Java 同名符号而报错，
  随机名时代是被隐藏掉的。
- 非法的 Java 标识符（`[]`、`+` 之类）会自动回退成随机名。
- Java 版本选高了而宿主 javac 不支持时，javac 会直接报错（这是选择本身的结果）。
- 只打包当前工程（含各绳包源码），不做多模块合并。
