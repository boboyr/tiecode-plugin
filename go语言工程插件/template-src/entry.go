package main

import "C"

import (
	"log"
	"runtime/debug"
	"unsafe"
)

//export SDL_main
func SDL_main(argc C.int, argv **C.char) (rc C.int) {
	if argc > 1 && argv != nil {
		if args := unsafe.Slice(argv, int(argc)); args[1] != nil {
			initLog(C.GoString(args[1]))
		}
	}
	log.Printf("=== Go SDL3 入口启动 (argc=%d) ===", int(argc))

	defer func() {
		if r := recover(); r != nil {
			log.Printf("panic: %v\n%s", r, debug.Stack())
			rc = 1
		}
	}()

	main()

	log.Printf("=== 正常退出 ===")
	return 0
}
