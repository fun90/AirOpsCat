//go:build ignore

// 在本包目录运行，生成的二进制不进入 Git。
package main

import (
	"compress/gzip"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

func main() {
	if err := generate(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func generate() error {
	dir, err := os.MkdirTemp("", "airopscat-probe-build-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	for _, arch := range []string{"amd64", "arm64"} {
		path := filepath.Join(dir, "probe-"+arch)
		cmd := exec.Command("go", "build", "-trimpath", "-ldflags=-s -w", "-o", path, "../../cmd/airopscat-probe")
		cmd.Env = append(os.Environ(), "GOOS=linux", "GOARCH="+arch, "CGO_ENABLED=0")
		cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
		if err := cmd.Run(); err != nil {
			return err
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		f, err := os.Create("assets/probe-linux-" + arch + ".gz")
		if err != nil {
			return err
		}
		w := gzip.NewWriter(f)
		_, writeErr := w.Write(data)
		zipErr, closeErr := w.Close(), f.Close()
		if writeErr != nil {
			return writeErr
		}
		if zipErr != nil {
			return zipErr
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}
