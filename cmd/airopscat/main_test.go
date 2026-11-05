package main

import (
	"bytes"
	"context"
	"log/slog"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/store"
)

func TestEnsureAdmin(t *testing.T) {
	for _, tc := range []struct {
		name     string
		password string
		existing bool
		wantErr  bool
	}{
		{name: "empty database requires password", wantErr: true},
		{name: "configured password", password: "test-admin-password"},
		{name: "existing admin needs no bootstrap password", existing: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx := context.Background()
			db, err := store.Open(ctx, filepath.Join(t.TempDir(), "admin.db"))
			if err != nil {
				t.Fatal(err)
			}
			defer db.Close()
			t.Setenv("AIROPSCAT_ADMIN_EMAIL", "admin@example.com")
			t.Setenv("AIROPSCAT_ADMIN_PASSWORD", tc.password)
			if tc.existing {
				if _, err := store.CreateUser(ctx, db, "admin@example.com", "existing-hash", "ADMIN", "管理员"); err != nil {
					t.Fatal(err)
				}
			}
			var logs bytes.Buffer
			previous := slog.Default()
			slog.SetDefault(slog.New(slog.NewTextHandler(&logs, nil)))
			defer slog.SetDefault(previous)
			err = ensureAdmin(ctx, db)
			if tc.wantErr {
				if err == nil || !strings.Contains(err.Error(), "AIROPSCAT_ADMIN_PASSWORD") {
					t.Errorf("空库未配置密码应返回配置错误，得到 %v", err)
				}
				if total, _, err := store.CountUsers(ctx, db); err != nil || total != 0 {
					t.Errorf("初始化失败不应创建用户: total=%d err=%v", total, err)
				}
			} else if err != nil {
				t.Fatal(err)
			} else {
				u, err := store.UserByEmail(ctx, db, "admin@example.com")
				if err != nil || u == nil || u.Role != "ADMIN" {
					t.Fatalf("管理员未正确创建: user=%v err=%v", u, err)
				}
				if tc.existing {
					if u.Password != "existing-hash" {
						t.Error("已有管理员密码不应改变")
					}
				} else if _, err := auth.NewService(db, nil, nil).Login(ctx, u.Email, tc.password); err != nil {
					t.Fatalf("配置的密码应能登录: %v", err)
				}
			}
			if strings.Contains(logs.String(), "password=") || (tc.password != "" && strings.Contains(logs.String(), tc.password)) {
				t.Error("初始化日志包含密码")
			}
		})
	}
}
