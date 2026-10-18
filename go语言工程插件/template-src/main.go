package main

import (
	"fmt"
	"log"
	"math"

	"github.com/Zyko0/go-sdl3/sdl"
)

func main() {
	if err := sdl.LoadLibrary(libSDL3()); err != nil {
		log.Printf("加载 SDL3 失败: %v", err)
		return
	}
	defer sdl.Quit()

	if err := sdl.Init(sdl.INIT_VIDEO); err != nil {
		panic(err)
	}
	log.Printf("SDL3 版本: %s", sdl.GetVersion())

	window, renderer, err := sdl.CreateWindowAndRenderer("Hello world", 0, 0, 0)
	if err != nil {
		panic(err)
	}
	defer renderer.Destroy()
	defer window.Destroy()

	w, h, _ := window.SizeInPixels()

	touchX, touchY := float32(0.5), float32(0.5)
	var frames uint64

	sdl.RunLoop(func() error {
		var event sdl.Event

		for sdl.PollEvent(&event) {
			switch event.Type {
			case sdl.EVENT_QUIT:
				return sdl.EndLoop
			case sdl.EVENT_FINGER_DOWN, sdl.EVENT_FINGER_MOTION:
				f := event.TouchFingerEvent()
				touchX, touchY = f.X, f.Y
			}
		}

		renderer.SetDrawColor(18, 22, 30, 255)
		renderer.Clear()

		side := float32(math.Min(float64(w), float64(h))) * 0.22

		renderer.SetDrawColor(0, 200, 255, 255)
		renderer.RenderFillRect(&sdl.FRect{
			X: touchX*float32(w) - side/2,
			Y: touchY*float32(h) - side/2,
			W: side, H: side,
		})

		renderer.SetDrawColor(255, 255, 255, 255)
		renderer.DebugText(16, 16, fmt.Sprintf("go-sdl3 %s + purego", sdl.GetVersion()))
		renderer.DebugText(16, 32, fmt.Sprintf("finger x=%.2f y=%.2f", touchX, touchY))
		renderer.DebugText(16, 48, fmt.Sprintf("surface %dx%d frame %d", w, h, frames))

		renderer.Present()
		frames++
		return nil
	})
}
