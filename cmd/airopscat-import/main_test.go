package main

import (
	"context"
	"database/sql"
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

func TestEncryptedReconciliationRejectsMismatch(t *testing.T) {
	ctx := context.Background()
	open := func(name string) *sql.DB {
		db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), name))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { db.Close() })
		for _, q := range []string{`CREATE TABLE server(id INTEGER PRIMARY KEY,auth TEXT)`, `CREATE TABLE dns_provider_config(id INTEGER PRIMARY KEY,credential_json TEXT)`, `CREATE TABLE system_config(config_key TEXT PRIMARY KEY,config_value TEXT)`} {
			if _, err := db.Exec(q); err != nil {
				t.Fatal(err)
			}
		}
		return db
	}
	src, dst := open("source.db"), open("target.db")
	field, err := crypto.New(crypto.DeriveKey(strings.Repeat("k", 40), "field"))
	if err != nil {
		t.Fatal(err)
	}
	secret := "test-source-secret"
	encrypted, err := field.Encrypt(secret)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := src.Exec(`INSERT INTO server VALUES(1,?)`, secret); err != nil {
		t.Fatal(err)
	}
	if _, err := dst.Exec(`INSERT INTO server VALUES(1,?)`, encrypted); err != nil {
		t.Fatal(err)
	}
	im := &importer{src: src, field: field, legacy: "old-test-key"}
	if err := im.verifyEncrypted(ctx, dst); err != nil {
		t.Fatal(err)
	}
	different, err := field.Encrypt("different-secret")
	if err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{"v1:invalid", different} {
		if _, err := dst.Exec(`UPDATE server SET auth=?`, bad); err != nil {
			t.Fatal(err)
		}
		err := im.verifyEncrypted(ctx, dst)
		if err == nil || !strings.Contains(err.Error(), "server.auth[1]") {
			t.Fatal("未定位不一致", err)
		}
		if strings.Contains(err.Error(), secret) || strings.Contains(err.Error(), "different-secret") || strings.Contains(err.Error(), bad) {
			t.Fatal("对账错误包含敏感值")
		}
	}
}

func TestMoneyReconciliationRejectsMismatch(t *testing.T) {
	ctx := context.Background()
	open := func(name string) *sql.DB {
		db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), name))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { db.Close() })
		for _, q := range []string{`CREATE TABLE transactions(type INTEGER,amount NUMERIC)`, `CREATE TABLE server(price NUMERIC)`, `CREATE TABLE domain(price NUMERIC)`} {
			if _, err := db.Exec(q); err != nil {
				t.Fatal(err)
			}
		}
		return db
	}
	src, dst := open("source.db"), open("target.db")
	for _, v := range []struct {
		db *sql.DB
		q  string
	}{
		{src, `INSERT INTO transactions VALUES(0,12.34),(1,5.67)`}, {dst, `INSERT INTO transactions VALUES(0,1234),(1,567)`},
		{src, `INSERT INTO server VALUES(9.99)`}, {dst, `INSERT INTO server VALUES(999)`},
	} {
		if _, err := v.db.Exec(v.q); err != nil {
			t.Fatal(err)
		}
	}
	im := &importer{src: src}
	if err := im.verifyMoney(ctx, dst); err != nil {
		t.Fatal(err)
	}
	if _, err := dst.Exec(`UPDATE transactions SET amount=amount+1 WHERE type=0`); err != nil {
		t.Fatal(err)
	}
	if err := im.verifyMoney(ctx, dst); err == nil {
		t.Fatal("未发现流水金额不一致")
	}
	if _, err := dst.Exec(`UPDATE transactions SET amount=1234 WHERE type=0; UPDATE server SET price=1000`); err != nil {
		t.Fatal(err)
	}
	if err := im.verifyMoney(ctx, dst); err == nil {
		t.Fatal("未发现服务器价格不一致")
	}
}
