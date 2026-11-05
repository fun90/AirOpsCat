package deploy

import (
	"bytes"
	"context"
	"errors"
	"os"
	osexec "os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// probeHost 用隔离目录实际执行安装、校验和原子替换命令。
type probeHost struct {
	home      string
	arch      string
	fail      string
	uploadErr bool
	mu        sync.Mutex
	writes    int
}

func (h *probeHost) Run(ctx context.Context, command string) (string, string, int, error) {
	if strings.HasPrefix(command, "uname -sm") {
		return h.arch + "\n\n" + h.home + "\n", "", 0, nil
	}
	if h.fail != "" && strings.Contains(command, h.fail) {
		return "", "", 1, nil
	}
	cmd := osexec.CommandContext(ctx, "sh", "-c", command)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	err := cmd.Run()
	var exit *osexec.ExitError
	if errors.As(err, &exit) {
		return stdout.String(), stderr.String(), exit.ExitCode(), nil
	}
	return stdout.String(), stderr.String(), 0, err
}
func (h *probeHost) RunInput(ctx context.Context, command string, input []byte) (string, string, int, error) {
	if strings.Contains(command, "192.0.2.1") || strings.Contains(command, "packet") {
		return "", "", 1, errors.New("请求泄露到命令行")
	}
	cmd := osexec.CommandContext(ctx, "sh", "-c", command)
	cmd.Stdin = bytes.NewReader(input)
	out, err := cmd.Output()
	return string(out), "", 0, err
}
func (h *probeHost) WriteFile(_ context.Context, path string, data []byte) error {
	if h.uploadErr {
		return errors.New("模拟上传失败")
	}
	h.mu.Lock()
	h.writes++
	h.mu.Unlock()
	return os.WriteFile(path, data, 0600)
}
func (h *probeHost) Close() error { return nil }

func probeFixture(string) ([]byte, error) {
	return []byte("#!/bin/sh\nif [ \"$1\" = --check ]; then printf 'airopscat-probe-v1\\n'; else cat >/dev/null; printf '\\n0 1\\n'; fi\n"), nil
}

func TestPrepareProbeInstallCacheAndRepair(t *testing.T) {
	h := &probeHost{home: t.TempDir() + "/用户's home", arch: "Linux x86_64"}
	for i := 0; i < 2; i++ {
		p, err := PrepareProbe(context.Background(), h, probeFixture)
		if err != nil {
			t.Fatal(err)
		}
		if out, err := p.Run(context.Background(), []byte(`{"ip":"192.0.2.1"}`)); err != nil || out != "\n0 1\n" {
			t.Fatal(out, err)
		}
	}
	if h.writes != 1 {
		t.Fatal("缓存命中后不应重复上传", h.writes)
	}
	files, err := filepath.Glob(h.home + "/.local/lib/airopscat/probe-*")
	if err != nil || len(files) != 1 {
		t.Fatal(files, err)
	}
	if err := os.WriteFile(files[0], []byte("损坏的缓存"), 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := PrepareProbe(context.Background(), h, probeFixture); err != nil {
		t.Fatal(err)
	}
	if h.writes != 2 {
		t.Fatal("哈希不一致必须修复", h.writes)
	}
}

func TestPrepareProbeConcurrentInstall(t *testing.T) {
	h := &probeHost{home: t.TempDir(), arch: "Linux aarch64"}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := PrepareProbe(context.Background(), h, probeFixture); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	files, err := filepath.Glob(h.home + "/.local/lib/airopscat/.probe-*")
	if err != nil || len(files) != 0 {
		t.Fatal("临时文件未清理", files, err)
	}
}

func TestPrepareProbeFailures(t *testing.T) {
	for _, tc := range []struct {
		name, arch, fail string
		upload           bool
		loader           func(string) ([]byte, error)
	}{
		{name: "系统不支持", arch: "Darwin arm64"},
		{name: "架构不支持", arch: "Linux riscv64"},
		{name: "安装目录不可写", arch: "Linux x86_64", fail: "mkdir -p"},
		{name: "上传失败", arch: "Linux x86_64", upload: true},
		{name: "上传校验失败", arch: "Linux x86_64", fail: "chmod 700"},
		{name: "目录不可执行", arch: "Linux x86_64", fail: " --check"},
		{name: "内嵌产物缺失", arch: "Linux x86_64", loader: func(string) ([]byte, error) { return nil, errors.New("缺失") }},
		{name: "程序版本错误", arch: "Linux x86_64", loader: func(string) ([]byte, error) { return []byte("#!/bin/sh\necho unexpected\n"), nil }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := &probeHost{home: t.TempDir(), arch: tc.arch, fail: tc.fail, uploadErr: tc.upload}
			load := tc.loader
			if load == nil {
				load = probeFixture
			}
			if _, err := PrepareProbe(context.Background(), h, load); err == nil {
				t.Fatal("应报告准备失败")
			}
			files, err := filepath.Glob(h.home + "/.local/lib/airopscat/.probe-*")
			if err != nil || len(files) != 0 {
				t.Fatal("失败后应清理临时文件", files, err)
			}
		})
	}
}

func TestPrepareProbeRequiresInputRunner(t *testing.T) {
	if _, err := PrepareProbe(context.Background(), &fakeRemote{}, probeFixture); err == nil {
		t.Fatal("必须拒绝不支持标准输入的连接")
	}
}
