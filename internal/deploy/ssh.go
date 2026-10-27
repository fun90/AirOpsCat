// Package deploy 负责把 sing-box 配置经 SSH 推送到服务器，并把结果落库。
package deploy

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"

	"github.com/fun90/airopscat/internal/store"
)

// ErrHostKeyMismatch 表示服务器主机密钥与首次信任时保存的指纹不一致，拒绝连接；
// 上层应提示「如服务器重装请在服务器编辑页清除主机密钥」。
var ErrHostKeyMismatch = errors.New("SSH 主机密钥与已信任的指纹不一致")

// OutboundDisabled 由 AIROPSCAT_OUTBOUND_DISABLED=1 打开，本地挂生产库时拒绝连接真实服务器。
var OutboundDisabled bool

// Remote 是对一台已连接服务器的最小抽象，便于用假实现测试部署流程。
type Remote interface {
	// Run 执行命令；非零退出码通过 code 返回而不是 err，err 只表示传输/超时类错误。
	Run(ctx context.Context, cmd string) (stdout, stderr string, code int, err error)
	// WriteFile 把 data 写入远端 path（覆盖）。
	WriteFile(ctx context.Context, path string, data []byte) error
	Close() error
}

// shQuote 把任意字符串转成单个 shell 单引号字面量。
func shQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

// checkHostKey 判断主机指纹：stored 为空表示首次信任，需要记录；一致返回 false；不一致返回 ErrHostKeyMismatch。
func checkHostKey(stored, got string) (record bool, err error) {
	switch stored {
	case "":
		return true, nil
	case got:
		return false, nil
	}
	return false, fmt.Errorf("%w（已信任 %s，当前 %s）", ErrHostKeyMismatch, stored, got)
}

// parseAuth 按 auth_type 构造认证方式。私钥统一换行、去 BOM，要求含 PRIVATE KEY，带口令（ENCRYPTED）的私钥不支持。
func parseAuth(authType, secret string) (ssh.AuthMethod, error) {
	switch authType {
	case "PASSWORD", "":
		if secret == "" {
			return nil, errors.New("未配置登录密码")
		}
		return ssh.Password(secret), nil
	case "KEY":
		key := strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(strings.TrimPrefix(secret, "\ufeff"), "\r\n", "\n"), "\r", "\n"))
		if !strings.Contains(key, "PRIVATE KEY") {
			return nil, errors.New("私钥格式不正确，缺少 PRIVATE KEY")
		}
		if strings.Contains(key, "ENCRYPTED") {
			return nil, errors.New("不支持带口令的私钥")
		}
		signer, err := ssh.ParsePrivateKey([]byte(key + "\n"))
		if err != nil {
			return nil, fmt.Errorf("私钥解析失败: %w", err)
		}
		return ssh.PublicKeys(signer), nil
	}
	return nil, fmt.Errorf("不支持的认证类型 %q", authType)
}

type sshRemote struct {
	client  *ssh.Client
	timeout time.Duration // 单条命令的整体超时
}

func (r *sshRemote) Close() error { return r.client.Close() }

func (r *sshRemote) Run(ctx context.Context, cmd string) (string, string, int, error) {
	return r.run(ctx, cmd, nil)
}

func (r *sshRemote) WriteFile(ctx context.Context, path string, data []byte) error {
	_, stderr, code, err := r.run(ctx, "cat > "+shQuote(path), data)
	if err != nil {
		return err
	}
	if code != 0 {
		return fmt.Errorf("写入 %s 失败(退出码 %d): %s", path, code, strings.TrimSpace(stderr))
	}
	return nil
}

func (r *sshRemote) run(ctx context.Context, cmd string, stdin []byte) (string, string, int, error) {
	ctx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()
	s, err := r.client.NewSession()
	if err != nil {
		return "", "", -1, err
	}
	defer s.Close()
	var stdout, stderr bytes.Buffer
	s.Stdout, s.Stderr = &stdout, &stderr
	if stdin != nil {
		s.Stdin = bytes.NewReader(stdin)
	}
	done := make(chan error, 1)
	go func() { done <- s.Run(cmd) }()
	select {
	case <-ctx.Done():
		s.Close() // 超时后丢弃缓冲区，避免与仍在写入的 goroutine 竞争
		return "", "", -1, ctx.Err()
	case err = <-done:
	}
	var exit *ssh.ExitError
	if errors.As(err, &exit) {
		return stdout.String(), stderr.String(), exit.ExitStatus(), nil
	}
	if err != nil {
		return "", "", -1, err
	}
	return stdout.String(), stderr.String(), 0, nil
}

// dialSSH 连接服务器：解密凭据、TOFU 校验主机密钥。
func (d *Deployer) dialSSH(ctx context.Context, t *store.SSHTarget) (Remote, error) {
	if OutboundDisabled {
		return nil, errors.New("已禁用外发连接（AIROPSCAT_OUTBOUND_DISABLED=1）")
	}
	secret, err := d.Cipher.Decrypt(t.Auth)
	if err != nil {
		return nil, fmt.Errorf("凭据解密失败: %w", err)
	}
	auth, err := parseAuth(t.AuthType, secret)
	if err != nil {
		return nil, err
	}
	user, port := t.Username, t.Port
	if user == "" {
		user = "root"
	}
	if port == 0 {
		port = 22
	}
	addr := net.JoinHostPort(t.IP, strconv.Itoa(port))
	timeout := d.connectTimeout()
	dctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	var hkErr error // 握手错误会被 ssh 包重新包装，单独保存以便 errors.Is 判断
	cfg := &ssh.ClientConfig{
		User: user,
		Auth: []ssh.AuthMethod{auth},
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			hkErr = d.verifyHostKey(dctx, t, ssh.FingerprintSHA256(key))
			return hkErr
		},
		Timeout: timeout,
	}
	conn, err := (&net.Dialer{}).DialContext(dctx, "tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("连接 %s 失败: %w", addr, err)
	}
	stop := context.AfterFunc(dctx, func() { conn.Close() }) // 握手期间超时或取消时中断连接
	sc, chans, reqs, err := ssh.NewClientConn(conn, addr, cfg)
	if !stop() && err == nil {
		err = dctx.Err()
	}
	if err != nil {
		conn.Close()
		if hkErr != nil {
			return nil, hkErr
		}
		return nil, fmt.Errorf("SSH 连接 %s 失败: %w", addr, err)
	}
	return &sshRemote{client: ssh.NewClient(sc, chans, reqs), timeout: d.commandTimeout()}, nil
}

// verifyHostKey 首次连接记录指纹，之后必须一致。
func (d *Deployer) verifyHostKey(ctx context.Context, t *store.SSHTarget, fp string) error {
	record, err := checkHostKey(t.HostKey, fp)
	if err != nil || !record {
		return err
	}
	cur, err := store.TrustHostKey(ctx, d.DB, t.ID, fp)
	if err != nil {
		return fmt.Errorf("保存主机密钥失败: %w", err)
	}
	_, err = checkHostKey(cur, fp) // 并发连接时以先写入的指纹为准
	return err
}

var locks sync.Map // serverID -> *sync.Mutex

// Lock 获取服务器级互斥锁（部署与限速同步共用，决策 20 第 3 条），返回的函数用于释放。
func Lock(serverID int64) (unlock func()) {
	m, _ := locks.LoadOrStore(serverID, &sync.Mutex{})
	mu := m.(*sync.Mutex)
	mu.Lock()
	return mu.Unlock
}
