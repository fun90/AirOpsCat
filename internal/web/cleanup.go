package web

import (
	"context"
	"fmt"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/store"
	"strings"
	"time"
)

// 固定目录、常规文件、严格数字后缀，至少超过五天；不跟随符号链接。
const coreBackupCleanup = `find /etc/sing-box -maxdepth 1 -type f -name 'config.json.backup.*' -mmin +7200 -exec sh -c 'for p do n=${p##*/}; suffix=${n#config.json.backup.}; case "$suffix" in ""|*[!0-9]*) continue;; esac; rm -f -- "$p" || exit; done' sh {} +`

func (s *Server) cleanupCoreBackups(ctx context.Context) error {
	ids, err := store.RuntimeTrafficServers(ctx, s.DB, s.TrafficServers, time.Now())
	if err != nil {
		return err
	}
	var failures []string
	for _, id := range ids {
		if err := s.cleanupCoreServer(ctx, id); err != nil {
			failures = append(failures, fmt.Sprintf("服务器%d：%v", id, err))
		}
	}
	if len(failures) > 0 {
		return fmt.Errorf("%s", strings.Join(failures, "；"))
	}
	return nil
}
func (s *Server) cleanupCoreServer(ctx context.Context, id int64) error {
	unlock := deploy.Lock(id)
	defer unlock()
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	t, err := store.SSHTargetByID(ctx, s.DB, id)
	if err != nil {
		return err
	}
	if t == nil || t.Disabled || t.External {
		return nil
	}
	r, err := s.Deployer.Dial(ctx, t)
	if err != nil {
		return err
	}
	defer r.Close()
	_, _, code, err := r.Run(ctx, coreBackupCleanup)
	if err != nil {
		return err
	}
	if code != 0 {
		return fmt.Errorf("清理命令退出码%d", code)
	}
	return nil
}
