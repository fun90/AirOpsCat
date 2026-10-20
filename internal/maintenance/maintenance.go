// Package maintenance 只执行经过预览、摘要确认的本地运维脚本。
package maintenance

import (
	"context"
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/deploy"
)

var scriptName = regexp.MustCompile(`^[0-9]{2}-[a-zA-Z0-9-]+\.sh$`)

const maxScript = 1 << 20

type Script struct{ Name, Title, Description, Hash, Body string }

func Read(dir, name string) (*Script, error) {
	if !scriptName.MatchString(name) {
		return nil, fmt.Errorf("脚本名称无效")
	}
	p := filepath.Join(dir, name)
	info, err := os.Lstat(p)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > maxScript {
		return nil, fmt.Errorf("脚本须为不超过 1 MB 的常规文件")
	}
	raw, err := os.ReadFile(p)
	if err != nil {
		return nil, err
	}
	body := strings.ReplaceAll(string(raw), "\r\n", "\n")
	s := &Script{Name: name, Title: name, Body: body, Hash: fmt.Sprintf("%x", sha256.Sum256([]byte(body)))}
	for i, l := range strings.Split(body, "\n") {
		if i >= 12 {
			break
		}
		if strings.HasPrefix(l, "# @title:") {
			s.Title = strings.TrimSpace(strings.TrimPrefix(l, "# @title:"))
		}
		if strings.HasPrefix(l, "# @description:") {
			s.Description = strings.TrimSpace(strings.TrimPrefix(l, "# @description:"))
		}
	}
	return s, nil
}
func List(dir string) ([]Script, error) {
	es, err := os.ReadDir(dir)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var out []Script
	for _, e := range es {
		if !scriptName.MatchString(e.Name()) {
			continue
		}
		s, err := Read(dir, e.Name())
		if err != nil {
			return nil, err
		}
		out = append(out, *s)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}
func quote(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\"'\"'") + "'" }

// Env 放进 0600 文件，不进入 SSH 命令行；server_hosts 保留 Bash 数组契约。
func Env(values map[string]string, hosts []string) []byte {
	var b strings.Builder
	keys := make([]string, 0, len(values))
	for k := range values {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		fmt.Fprintf(&b, "%s=%s\n", k, quote(values[k]))
	}
	b.WriteString("server_hosts=(")
	for _, h := range hosts {
		b.WriteString(quote(h) + " ")
	}
	b.WriteString(")\n")
	return []byte(b.String())
}

// Execute 创建私有随机目录并设置清理 trap。脚本可输出凭据，因此只保留退出码，丢弃原始输出。
func Execute(ctx context.Context, r deploy.Remote, s *Script, env []byte) error {
	out, _, code, err := r.Run(ctx, "umask 077; mktemp -d /tmp/airopscat-maintenance.XXXXXXXXXX")
	if err != nil {
		return fmt.Errorf("创建远端临时目录失败")
	}
	if code != 0 {
		return fmt.Errorf("创建远端临时目录失败（%d）", code)
	}
	dir := strings.TrimSpace(out)
	if !regexp.MustCompile(`^/tmp/airopscat-maintenance\.[A-Za-z0-9]+$`).MatchString(dir) {
		return fmt.Errorf("远端临时路径无效")
	}
	defer func() {
		c, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cancel()
		r.Run(c, "rm -f "+quote(dir+"/env")+" "+quote(dir+"/step.sh")+"; rmdir "+quote(dir))
	}()
	if err := r.WriteFile(ctx, dir+"/env", env); err != nil {
		return fmt.Errorf("上传环境文件失败")
	}
	if err := r.WriteFile(ctx, dir+"/step.sh", []byte(s.Body)); err != nil {
		return fmt.Errorf("上传脚本失败")
	}
	cmd := "chmod 600 " + quote(dir+"/env") + "; chmod 700 " + quote(dir+"/step.sh") + "; /bin/bash -c " + quote("trap 'rm -f "+dir+"/env "+dir+"/step.sh; rmdir "+dir+"' EXIT; set -a; source "+dir+"/env; set +a; source "+dir+"/step.sh") + " >/dev/null 2>&1"
	_, _, code, err = r.Run(ctx, cmd)
	if err != nil {
		return fmt.Errorf("执行中断或超时；请检查服务器实际状态")
	}
	if code != 0 {
		return fmt.Errorf("脚本退出码 %d，请检查服务器实际状态", code)
	}
	return nil
}
