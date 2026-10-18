# Go-SDL3 工程

由「结绳Go-SDL3工程插件」创建。用 **go-sdl3**（纯 purego 绑定，不含任何 C 文件/C 头文件）
调用 `libSDL3.so`，编译产物是 Android 原生库 `libmain.so`，由插件页面 `SDLPluginPage` 加载显示。

## 文件

| 文件 | 说明 |
|---|---|
| `main.go` | **应用主体**。和 go-sdl3 官方示例写法一致，`import "C"` 都没有 |
| `entry.go` | **整个工程唯一含 C 的文件**。`//export SDL_main` 把 `main()` 包一层，无 preamble、无 `#include` |
| `logging.go` | 日志落文件 + 定位 `libSDL3.so` 路径 |
| `go.mod` / `vendor/` | 依赖已内置，编译**不需要联网** |

## 为什么不能照抄官方示例的两行

```go
defer binsdl.Load().Unload()   // ← Android 上不能用
```

`bin/binsdl/binary_linux_arm64.go` 的构建约束是 `//go:build linux && arm64`，
而 **`GOOS=android` 同样满足 `linux` 标签**，于是它会嵌进 glibc 版 Linux 的 `libSDL3.so`，
在手机上 dlopen 必然失败。Android 上 SDL3 已经由 `SDLPluginEntry` 用 `System.load()`
装进进程了，所以换成：

```go
sdl.LoadLibrary(libSDL3())   // 从 /proc/self/maps 找出已加载的真实路径
```

## 为什么需要 entry.go

SDL3 在 Android 上由 `SDLPluginEntry` 启动：`dlopen(libmain.so)` → `dlsym("SDL_main")`
→ `SDL_RunApp(...)`。所以 `libmain.so` 必须导出 `int SDL_main(int, char**)`。
Go 里导出 C 符号只能用 cgo 的 `//export`，而 `//export` 导不出 `main`
（`package main` 的 `func main()` 签名是固定的），因此把 `main()` 包一层。

`SDL_main` 里还做了两件插件场景必须做的事：

* **兜住 panic** —— Go 的 panic 跨不过 cgo 边界，默认 SIGABRT 会把宿主 IDE 一起带崩；
* **不用 `log.Fatal`** —— 它内部是 `os.Exit`，同样会结束宿主进程。

## 版本适配（重要）

`libSDL3.so` 是 **SDL3 3.2.28**，而 go-sdl3 v0.1.1 是按 **3.4.0** 生成的。
它的 `initialize()` 会急切解析全部 987+43 个符号，原始行为是缺一个就 `panic`，
实测缺 30 个（`SDL_LoadPNG` / `SDL_GetSystemPageSize` / `SDL_SetWindowProgressState` …），
都是本示例用不到的 GPU / PNG / 渲染状态接口。

插件在**每次构建前**都会幂等地把这两处降级（见 `BuildLibmainTask.patchSdl3Tolerance`）：

* `panic("cannot puregogen.OpenSymbol: X")` → `missingSymbol("X")`
* `purego.RegisterLibFunc(f, h, "X")` → `registerLibFuncOrSkip(f, h, "X")`

> 第二处容易漏：`purego.RegisterLibFunc` **内部自己会 panic**。
> 一共 996 处 panic + 43 处 RegisterLibFunc，分布在
> `sdl_functions.gen_impl.go` 和 `sdl_functions_ex_impl.go` 里。

配套的 `vendor/…/sdl/zz_plugin_missing.go`（同样每次构建重写）提供这两个函数，
缺的符号记到 `sdl.MissingSymbols` 并打日志。所以重新 `go mod vendor` 也不会把 panic 带回来。

实测（用只导出 2 个符号的假库）：

```
LoadLibrary 成功（没有 panic）
跳过（缺失）的符号数量: 1037
SDL_GetVersion() = 3.2.28   <- 存在的符号可正常调用
```

换 SDL3 3.4.0+ 后这层自然全部命中，代码一行都不用改。

## 日志

运行日志写在 `argv[1]`（`<私有目录>/linux/gosdl3.log`），插件页面看不到 stderr。
