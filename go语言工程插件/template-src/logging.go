package main

import (
	"log"
	"os"
	"strings"
)

func initLog(path string) {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return
	}
	log.SetOutput(f)
	log.Printf("日志文件: %s", path)
}

func libSDL3() string {
	const name = "libSDL3.so"
	data, err := os.ReadFile("/proc/self/maps")
	if err != nil {
		return name
	}
	for _, line := range strings.Split(string(data), "\n") {
		idx := strings.Index(line, "/")
		if idx < 0 {
			continue
		}
		if p := strings.TrimSpace(line[idx:]); strings.HasSuffix(p, "/"+name) {
			return p
		}
	}
	return name
}
