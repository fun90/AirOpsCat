// AirOpsCat 主程序：读取环境变量、打开数据库、启动 HTTP 服务。
package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/dbbackup"
	"github.com/fun90/airopscat/internal/store"
	"github.com/fun90/airopscat/internal/web"
)

var version = "dev"

func main() {
	if err := run(); err != nil {
		slog.Error("启动失败", "err", err)
		os.Exit(1)
	}
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func run() error {
	secret := os.Getenv("AIROPSCAT_SECRET_KEY")
	if len(secret) < crypto.MinSecretLen {
		return fmt.Errorf("AIROPSCAT_SECRET_KEY 必须是至少 %d 个字符的随机串（可用 openssl rand -base64 48 生成）", crypto.MinSecretLen)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := store.Open(ctx, env("AIROPSCAT_DB", "/var/lib/airopscat/airopscat.db"))
	if err != nil {
		return err
	}
	defer db.Close()
	if err := store.EnsureSettings(ctx, db); err != nil {
		return err
	}
	if err := ensureAdmin(ctx, db); err != nil {
		return err
	}

	a := auth.NewService(db, crypto.DeriveKey(secret, "session"), crypto.DeriveKey(secret, "csrf"))
	field, err := crypto.New(crypto.DeriveKey(secret, "field"))
	if err != nil {
		return err
	}
	srv, err := web.New(db, a, field)
	if err != nil {
		return err
	}
	hs := &http.Server{
		Addr:              env("AIROPSCAT_LISTEN", "127.0.0.1:8080"),
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	ln, err := net.Listen("tcp", hs.Addr)
	if err != nil {
		return err
	}
	srv.Backup = &dbbackup.Manager{DB: db, Dir: env("AIROPSCAT_BACKUP_DIR", "/var/lib/airopscat/backup")}
	srv.NotifyDisabled = os.Getenv("AIROPSCAT_NOTIFY_DISABLED") == "1"
	srv.DNSAutoDomains = os.Getenv("AIROPSCAT_DNS_AUTO_DOMAINS")
	srv.ScriptDir = os.Getenv("AIROPSCAT_SCRIPTS_DIR")
	srv.MonitorServers = os.Getenv("AIROPSCAT_MONITOR_SERVERS")
	srv.TrafficServers = os.Getenv("AIROPSCAT_TRAFFIC_SERVERS")
	if os.Getenv("AIROPSCAT_TASKS_DISABLED") != "1" {
		srv.StartTasks(ctx)
	}
	errc := make(chan error, 1)
	go func() { errc <- hs.Serve(ln) }()
	slog.Info("AirOpsCat 已启动", "version", version, "listen", hs.Addr)

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := hs.Shutdown(shutdownCtx); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// ensureAdmin 只在空库（一个用户都没有）时创建首个管理员；已有用户但没有管理员视为数据异常，拒绝启动而不是自动提权。
func ensureAdmin(ctx context.Context, db *sql.DB) error {
	total, admins, err := store.CountUsers(ctx, db)
	if err != nil {
		return err
	}
	if admins > 0 {
		return nil
	}
	if total > 0 {
		return errors.New("库中有用户但没有 ADMIN，请检查数据后手动修复")
	}
	email := env("AIROPSCAT_ADMIN_EMAIL", "admin@airopscat.local")
	password := os.Getenv("AIROPSCAT_ADMIN_PASSWORD")
	generated := password == ""
	if generated {
		b := make([]byte, 12)
		rand.Read(b)
		password = base64.RawURLEncoding.EncodeToString(b)
	}
	hash, err := auth.HashPassword(password)
	if err != nil {
		return err
	}
	if _, err := store.CreateUser(ctx, db, email, hash, "ADMIN", "管理员"); err != nil {
		return err
	}
	if generated {
		// 只在首次创建时输出一次，登录后应立即修改
		slog.Warn("已创建初始管理员", "email", email, "password", password)
	} else {
		slog.Info("已创建初始管理员", "email", email)
	}
	return nil
}
