package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
)

func TestToCentsExact(t *testing.T) {
	for in, want := range map[string]string{"0": "0", "12.5": "1250", "-0.07": "-7", "1234567890123.45": "123456789012345", "3.10": "310"} {
		if got, err := toCents(in); err != nil || got != want {
			t.Fatalf("%s: %s %v", in, got, err)
		}
	}
	if _, err := toCents("1.234"); err == nil {
		t.Fatal("应拒绝超过 2 位小数")
	}
}

func TestCheckAuthCodes(t *testing.T) {
	src := map[string]string{"1": "dup", "2": "dup", "3": "ok"}
	regen := map[string]bool{"2": true}
	if err := checkAuthCodes(src, map[string]string{"1": "dup", "2": "new", "3": "ok"}, regen); err != nil {
		t.Fatal(err)
	}
	for name, dst := range map[string]map[string]string{
		"保留项改动":  {"1": "x", "2": "new", "3": "ok"},
		"未重新生成":  {"1": "dup", "2": "dup2x", "3": "dup"},
		"目标重复":   {"1": "dup", "2": "ok", "3": "ok"},
		"重生成沿用旧": {"1": "dup", "2": "dup", "3": "ok"},
	} {
		if err := checkAuthCodes(src, dst, regen); err == nil {
			t.Fatal(name, "未被发现")
		}
	}
}

func TestReencryptLegacyAndPlain(t *testing.T) {
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	im := &importer{legacy: "k", field: field}
	enc, wasPlain, err := im.reencrypt("legacy-plain-secret")
	if err != nil || !wasPlain {
		t.Fatal(wasPlain, err)
	}
	if got, err := field.Decrypt(enc); err != nil || got != "legacy-plain-secret" {
		t.Fatal(got, err)
	}
}

func TestRunFailureLeavesNoOutput(t *testing.T) {
	t.Setenv("AIROPSCAT_SECRET_KEY", strings.Repeat("k", 40))
	out := filepath.Join(t.TempDir(), "new.db")
	if err := run("u:p@tcp(127.0.0.1:1)/x", "k", out, false); err == nil {
		t.Fatal("源库不可达应失败")
	}
	for _, f := range []string{out, out + ".tmp", out + ".tmp-wal", out + ".tmp-shm"} {
		if _, err := os.Stat(f); err == nil {
			t.Fatal("残留", f)
		}
	}
}
