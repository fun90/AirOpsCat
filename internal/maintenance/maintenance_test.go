package maintenance

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

type remote struct {
	commands []string
	files    map[string][]byte
	code     int
}

func (r *remote) Run(_ context.Context, c string) (string, string, int, error) {
	r.commands = append(r.commands, c)
	if strings.Contains(c, "mktemp") {
		return "/tmp/airopscat-maintenance.ABC123\n", "", 0, nil
	}
	return "secret-token", "secret-token", r.code, nil
}
func (r *remote) WriteFile(_ context.Context, p string, b []byte) error { r.files[p] = b; return nil }
func (r *remote) Close() error                                          { return nil }
func TestReadAndExecute(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "01-test.sh"), []byte("#!/bin/bash\n# @title: 测试\necho ok\n"), 0600)
	os.Symlink(filepath.Join(dir, "01-test.sh"), filepath.Join(dir, "02-link.sh"))
	if _, err := Read(dir, "../01-test.sh"); err == nil {
		t.Fatal("允许路径穿越")
	}
	if _, err := Read(dir, "02-link.sh"); err == nil {
		t.Fatal("允许符号链接")
	}
	s, err := Read(dir, "01-test.sh")
	if err != nil || s.Title != "测试" || len(s.Hash) != 64 {
		t.Fatal(s, err)
	}
	r := &remote{files: map[string][]byte{}}
	env := Env(map[string]string{"airopscat_api_token": "secret-token", "server_host": "a'$(touch /tmp/bad)"}, []string{"a.example.com"})
	if err := Execute(context.Background(), r, s, env); err != nil {
		t.Fatal(err)
	}
	if len(r.files) != 2 || len(r.commands) != 3 {
		t.Fatal(r)
	}
	for _, cmd := range r.commands {
		if strings.Contains(cmd, "secret-token") {
			t.Fatal("Token 出现在命令中")
		}
	}
	if !strings.Contains(string(r.files["/tmp/airopscat-maintenance.ABC123/env"]), "secret-token") || !strings.Contains(r.commands[1], "chmod 600") {
		t.Fatal("未通过私有文件传环境")
	}
	r.code = 12
	if err := Execute(context.Background(), r, s, env); err == nil || strings.Contains(err.Error(), "secret-token") {
		t.Fatal("退出错误未脱敏", err)
	}
	if !strings.Contains(r.commands[len(r.commands)-1], "rmdir") {
		t.Fatal("失败未清理")
	}
}

func TestEnvShellRoundTrip(t *testing.T) {
	marker := filepath.Join(t.TempDir(), "must-not-exist")
	secret := "a'\n$(touch " + marker + ")$HOME`id`\\z"
	env := Env(map[string]string{"airopscat_api_token": secret}, []string{"a.example.com"})
	cmd := exec.Command("bash", "-c", string(env)+"printf '%s' \"$airopscat_api_token\"")
	out, err := cmd.Output()
	if err != nil || string(out) != secret {
		t.Fatalf("roundtrip: %q %v", out, err)
	}
	if _, err := os.Stat(marker); !os.IsNotExist(err) {
		t.Fatal("环境值被当作命令执行")
	}
}
