package web

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCoreBackupCleanupScope(t *testing.T) {
	dir := t.TempDir()
	old := time.Now().Add(-6 * 24 * time.Hour)
	for _, name := range []string{"config.json.backup.123", "config.json.backup.456", "config.json.backup.invalid", "config.json"} {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte("x"), 0600); err != nil {
			t.Fatal(err)
		}
		if name != "config.json.backup.456" {
			os.Chtimes(p, old, old)
		}
	}
	os.Symlink(filepath.Join(dir, "config.json"), filepath.Join(dir, "config.json.backup.999"))
	cmd := strings.Replace(coreBackupCleanup, "/etc/sing-box", "'"+dir+"'", 1)
	if out, err := exec.Command("sh", "-c", cmd).CombinedOutput(); err != nil {
		t.Fatal(string(out), err)
	}
	if _, err := os.Stat(filepath.Join(dir, "config.json.backup.123")); !os.IsNotExist(err) {
		t.Fatal("过期备份未删除")
	}
	for _, name := range []string{"config.json.backup.456", "config.json.backup.invalid", "config.json", "config.json.backup.999"} {
		if _, err := os.Lstat(filepath.Join(dir, name)); err != nil {
			t.Fatal(name, err)
		}
	}
}
