// localtoken 给本地生产快照签发管理员会话令牌，供 curl 与浏览器脚本自查页面；由 scripts/local-prod.sh 调用。
package main

import (
	"context"
	"fmt"
	"os"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "签发本地令牌失败：", err)
		os.Exit(1)
	}
}

func run() error {
	ctx := context.Background()
	secret, out := os.Getenv("AIROPSCAT_SECRET_KEY"), os.Args[len(os.Args)-1]
	if secret == "" || len(os.Args) != 2 {
		return fmt.Errorf("用法：AIROPSCAT_SECRET_KEY=… AIROPSCAT_DB=… localtoken <输出文件>")
	}
	db, err := store.Open(ctx, os.Getenv("AIROPSCAT_DB"))
	if err != nil {
		return err
	}
	defer db.Close()
	var email string
	if err := db.QueryRowContext(ctx, `SELECT email FROM user WHERE role='ADMIN' AND disabled=0 ORDER BY id LIMIT 1`).Scan(&email); err != nil {
		return fmt.Errorf("查找管理员：%w", err)
	}
	u, err := store.UserByEmail(ctx, db, email)
	if err != nil {
		return err
	}
	a := auth.NewService(db, crypto.DeriveKey(secret, "session"), crypto.DeriveKey(secret, "csrf"))
	return os.WriteFile(out, []byte(a.IssueToken(u, auth.SessionMaxAge)), 0o600)
}
