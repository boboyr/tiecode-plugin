package sdl

import (
	"log"

	purego "github.com/ebitengine/purego"
)

var MissingSymbols []string

func missingSymbol(name string) {
	MissingSymbols = append(MissingSymbols, name)
	log.Printf("[go-sdl3] libSDL3.so 缺少符号 %s（已跳过）", name)
}

func registerLibFuncOrSkip(fptr any, handle uintptr, name string) {
	addr, err := purego.Dlsym(handle, name)
	if err != nil || addr == 0 {
		missingSymbol(name)
		return
	}
	purego.RegisterFunc(fptr, addr)
}
