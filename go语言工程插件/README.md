# go语言工程插件

结绳 Go 语言工程插件 —— 由 **go语言控制台插件** 与 **go语言SDL3工程插件** 合并而成。

一个工程类型「**go语言工程**」，带两个创建模板：

| 模板 | 内容 | 构建流水线 |
|---|---|---|
| **控制台工程** | `src/main.go` + `src/go.mod`（标准 Go 命令行工程） | 编译 go → 运行 ELF → go mod（GoStart / Elf / Gomod 三个运行配置） |
| **SDL3工程** | 解压 `gosdl3_template.zip`（main.go 入口 `//export SDL_main` + vendor 内置 go-sdl3/purego） | buildLibmain（`go build -buildmode=c-shared -o libmain.so`）→ publishLib，运行用 GoSdl3RunConfiguration 打开 SDLPluginPage 图形界面 |

## 工程类别如何区分

两套流水线在**打开工程**时装配：宿主 IDE 调用 `GoProject.prepare()`，此时模板文件已写出，
`GoProjectKind.isSdl3()` 按工程目录内容判定（`.gosdl3` 标记文件 / `entry.go` /
`vendor/github.com/Zyko0` / 根目录 `main.go`+`go.mod`），走 SDL3 流水线，否则走控制台流水线。

## 结构

```
app/src/main/java/com/goproject/plugin/
├── MyApplication.java          # 插件入口：注册 go 编译工具链 + 卸载时结束 gopls
├── lang/  lsp/                 # Go 语言高亮 / gopls LSP 补全（两插件原本就同源）
├── project/
│   ├── GoProjectManager.java   # 工程类型「go语言工程」，注册两个模板
│   ├── GoProject.java          # 统一工程对象，prepare() 自动装配两套流水线
│   ├── GoProjectKind.java      # SDL3 / 控制台类别判定
│   ├── GoFileManager.java / GoOfflineCompletion.java ...
│   ├── running/                # 控制台运行配置 + GoSdl3RunConfiguration
│   └── template/
│       ├── GoConsoleTemplate.java   # 模板「控制台工程」
│       └── GoSdl3Template.java      # 模板「SDL3工程」
├── task/                       # BuildLibmainTask / PublishLibTask（SDL3）
├── tool/                       # 控制台编译器 + SDL3 工具链(下载/解压 GCC)
├── page/SDLPluginPage.java     # SDL3 图形界面页
└── org/libsdl/app/             # SDL Java 胶水(SDLPluginSurface/SDLPluginEntry)
```

## 构建

两种方式：

1. **Gradle**（需要完整 SDK）：`gradlew assembleDebug`，产物在
   `app/build/outputs/apk/debug/go语言工程插件-1.0.rmx`；
2. **手动脚本**（无完整 SDK 的机器，本机已验证）：`bash tools/build-rmx.sh`，
   用 javac + D8 + aapt2 + zipalign 直接产出工程根目录的
   `go语言工程插件-1.0.rmx`。依赖路径写在脚本头部，可用环境变量覆盖。

## 注意

- 合并插件 id 为 `com.goproject.plugin`，与旧的两个插件不同：**安装前先卸载旧的
  「go语言控制台插件」和「结绳Go-SDL3工程插件」**，避免工程类型重复注册。
- 旧插件创建的工程（project.json 的 typeId 为 `sample.go` 或 `gosdl3`）：
  - 旧控制台工程 typeId 就是 `sample.go`，合并插件可直接打开；
  - 旧 SDL3 工程把 project.json 里的 `"typeId": "gosdl3"` 改成 `"sample.go"`
    （type 改成 `go语言工程`）即可正常打开并自动走 SDL3 流水线。
- `libSDL3.so` 以 STORED（未压缩）方式打进插件（`packagingOptions.jniLibs
  useLegacyPackaging = false`），结绳安装器按 `available()`+单次 read 读取条目，
  压缩条目会读出零尾导致段头损坏 —— 不要删掉这条配置。
