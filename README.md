# 结绳插件参考实现

> 结绳（Tiecode，宿主包名 `com.tiecode.develop`）插件开发的一套**真实、可编译、可直接参考**的示例源码集合。
>
> 配套完整开发文档见 skill：**《结绳插件开发》**（内容含 `studio-api` 全部扩展点、补全 / 文件树 / 运行配置 / 新建文件 / 工程模板 / 编译任务等 API 与实现方式）。

---

## 目录结构与功能映射

| 目录 | 插件 / 模块 | 演示的核心能力 |
| --- | --- | --- |
| [`go语言工程插件/`](./go语言工程插件/) | go 语言工程插件（控制台 + SDL3 合并版） | 单工程类型双模板、`Project.prepare()` 流水线装配、**LSP(gopls) 补全 + 离线兜底**、TextMate 高亮、工具链注册、运行配置、文件树虚拟节点、手动 `.rmx` 打包 |
| [`结绳安卓工程aar构建插件/`](./结绳安卓工程aar构建插件/) | 结绳安卓工程 aar 构建插件 | **复用宿主编译链**打 aar/jar、覆盖结绳编译器 `NameGenerator` 输出中文类名、`project.component` 挂运行配置 |
| [`结绳免安装运行插件/`](./结绳免安装运行插件/) | 免安装运行器 | **复用宿主任务链**、插件化加载未安装 APK（子优先 ClassLoader / 换 Instrumentation / 主题 / native 库）、`tools/build-rmx.sh` 手动打包 |
| [`libgdx游戏编辑器插件/`](./libgdx游戏编辑器插件/) | libgdx 游戏引擎可视化编辑器 | **可视化设计器**、编辑器代码区菜单 `domain.menu.editor.code`、`PSObjectCreationOption` 新建场景类 |
| [`BasicLanguages-lang参考/`](./BasicLanguages-lang参考/) | 官方 BasicLanguages（csml/json/markdown/xml/properties/gitignore） | **语言高亮 / 分析器 / 决策器**的纯语言层最简参考（无独立构建系统） |

---

## 快速索引：按功能找参考

### 1. 代码补全（LSP + 兜底）
- **主实现**：`go语言工程插件/app/src/main/java/com/goproject/plugin/project/GoCompletionProvider.java`
  —— `CompletionProvider`：优先问 gopls，失败退回 `GoOfflineCompletion`。
- **LSP 对接**：`go语言工程插件/app/src/main/java/com/goproject/plugin/lsp/`
  —— 自起 gopls 子进程、unix socket / TCP 连接、`lsp4j` 转换。
- **语言引擎容器**：`.../GoLanguageEngine.java`（`DefaultLanguageEngine.setCompletion(...)`）。
- **TextMate 语法层**：`.../lang/GoLanguageFactory.java` + `GoTexLanguage.java`
  —— `TextMateLanguage` 包装，只保留宿主引擎一条补全路径。

### 2. 文件树 / 新建文件 / 打开方式
- **新建文件菜单项**：`go语言工程插件/.../structure/GoFileCreationOption.java`（`PSObjectCreationOption`）
  ；`libgdx游戏编辑器插件/.../NewSceneClassOption`。
- **文件打开方式**：`go语言工程插件/.../structure/NoopOpeningOption.java`（`PSObjectOpeningOption`）。
- **虚拟「外部库」节点展开**：`go语言工程插件/.../structure/ExternalLibrariesExpansionCase.java`（`PSObjectExpansionCase`）。
- **文件树图标**：`go语言工程插件/.../GoFileManager.java`（`ProjectFileUiEvent.onBindView`）。

### 3. 运行配置
- **任务型运行配置**：`go语言工程插件/.../running/GoStartRunConfig.java` / `ElfRunConfig.java`（`TaskRunConfiguration`）。
- **组件挂运行配置**：`结绳安卓工程aar构建插件/.../AarProjectComponentFactory`；`结绳免安装运行插件/.../NoMountComponentFactory`。

### 4. 工程模板
- `go语言工程插件/.../template/GoConsoleTemplate.java`（写文件）、`GoSdl3Template.java`（解压 assets zip）。
- 模板资源在 `app/src/main/assets/`。

### 5. 编译任务 / 工具链
- **自定义工具链 + 任务**：`go语言工程插件/.../CompilerGoTask.java`（`BaseTask`）、`.../tool/BaseGoCompiler.java`；
  入口注册见 `MyApplication.onInit()`（`ToolchainService.setTool`）。
- **复用宿主任务链**：`结绳安卓工程aar构建插件/README.md`、`结绳免安装运行插件/README.md`（任务依赖图详解）。

### 6. 打包 `.rmx`
- **Gradle 方式**：`go语言工程插件/app/build.gradle`（`compileOnly` 宿主 SDK、`useLegacyPackaging=false`、产物重命名 `.rmx`）。
- **手动脚本方式**：`结绳免安装运行插件/tools/build-rmx.sh`（aapt2 + javac + D8 + zipalign 组装）。

---

## 关于被排除的大文件

`go语言工程插件/app/src/main/assets/go-toolchain/go1.27.1-aarch64.zip`（约 88MB）
未提交（超 GitHub 单文件上限且非核心源码）。构建时从 Go 官网下载对应工具链放入该目录即可；
`tools/build-rmx.sh` 与 `MyApplication` 的安装逻辑会按需查找。

---

## 许可与说明

各插件保留原 README 与许可声明。本仓库仅为技术参考，完整插件开发文档见同名 skill。