// Package probeassets 内嵌探测机使用的 Linux 原生程序。
package probeassets

import (
	"bytes"
	"compress/gzip"
	"embed"
	"fmt"
	"io"
)

//go:generate go run generate.go
//go:embed assets/*
var files embed.FS

func Load(arch string) ([]byte, error) {
	if arch != "amd64" && arch != "arm64" {
		return nil, fmt.Errorf("不支持的探测架构 %q", arch)
	}
	data, err := files.ReadFile("assets/probe-linux-" + arch + ".gz")
	if err != nil {
		return nil, fmt.Errorf("缺少内嵌探测程序，请先运行 go generate ./internal/probeassets: %w", err)
	}
	r, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	defer r.Close()
	return io.ReadAll(r)
}
