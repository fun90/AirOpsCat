package probeassets

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io/fs"
	"strings"
	"testing"
)

func TestLoad(t *testing.T) {
	for arch, machine := range map[string]uint16{"amd64": 62, "arm64": 183} {
		t.Run(arch, func(t *testing.T) {
			_, assetErr := files.ReadFile("assets/probe-linux-" + arch + ".gz")
			data, err := Load(arch)
			if errors.Is(assetErr, fs.ErrNotExist) {
				if err == nil || !strings.Contains(err.Error(), "go generate") {
					t.Fatal("产物缺失时应明确提示生成", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if len(data) < 20 || !bytes.Equal(data[:4], []byte{0x7f, 'E', 'L', 'F'}) || data[5] != 1 || binary.LittleEndian.Uint16(data[18:20]) != machine {
				t.Fatal("探测程序不是对应架构的 Linux ELF")
			}
		})
	}
	if _, err := Load("riscv64"); err == nil {
		t.Fatal("应拒绝未支持的架构")
	}
}
