package deploy

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"net"
	"strings"
	"sync"
	"testing"

	"golang.org/x/crypto/ssh"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
)

// startSSHServer 起一个进程内 SSH 服务器（密码 pw），exec 请求记录命令；`cat > path` 把 stdin 存入 files。
func startSSHServer(t *testing.T) (port int, files map[string]string, cmds *[]string, hostKey ssh.Signer) {
	t.Helper()
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostKey, _ = ssh.NewSignerFromKey(priv)
	cfg := &ssh.ServerConfig{PasswordCallback: func(c ssh.ConnMetadata, p []byte) (*ssh.Permissions, error) {
		if c.User() == "root" && string(p) == "pw" {
			return nil, nil
		}
		return nil, errors.New("denied")
	}}
	cfg.AddHostKey(hostKey)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	var mu sync.Mutex
	files, cmds = map[string]string{}, &[]string{}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				sc, chans, reqs, err := ssh.NewServerConn(conn, cfg)
				if err != nil {
					return
				}
				go ssh.DiscardRequests(reqs)
				for nc := range chans {
					ch, creqs, _ := nc.Accept()
					go func() {
						for req := range creqs {
							if req.Type != "exec" {
								req.Reply(false, nil)
								continue
							}
							req.Reply(true, nil)
							cmd := string(req.Payload[4:])
							mu.Lock()
							*cmds = append(*cmds, cmd)
							mu.Unlock()
							code := byte(0)
							if path, ok := strings.CutPrefix(cmd, "cat > "); ok {
								var sb strings.Builder
								buf := make([]byte, 4096)
								for {
									n, err := ch.Read(buf)
									sb.Write(buf[:n])
									if err != nil {
										break
									}
								}
								mu.Lock()
								files[strings.Trim(path, "'")] = sb.String()
								mu.Unlock()
							} else if strings.Contains(cmd, "check") {
								ch.Stderr().Write([]byte("bad"))
								code = 1
							} else {
								ch.Write([]byte("airopscat-ssh-ok\n"))
							}
							ch.SendRequest("exit-status", false, []byte{0, 0, 0, code})
							ch.Close()
							return
						}
					}()
				}
				sc.Close()
			}()
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, files, cmds, hostKey
}

func TestSSHPingTOFUAndMismatch(t *testing.T) {
	port, _, _, hostKey := startSSHServer(t)
	db := newTestDB(t)
	c, _ := crypto.New(make([]byte, 32))
	enc, _ := c.Encrypt("pw")
	sid := exec(t, db, `INSERT INTO server (ip, ssh_port, auth_type, username, auth) VALUES ('127.0.0.1',?,'PASSWORD','root',?)`, port, enc)
	d := NewDeployer(db, c, nil)

	if err := d.Ping(context.Background(), sid); err != nil {
		t.Fatal(err)
	}
	var fp string
	db.QueryRow(`SELECT host_key FROM server WHERE id = ?`, sid).Scan(&fp)
	if fp != ssh.FingerprintSHA256(hostKey.PublicKey()) {
		t.Fatalf("应记录主机指纹，实际 %q", fp)
	}
	if err := d.Ping(context.Background(), sid); err != nil {
		t.Fatal("指纹一致应放行", err)
	}

	db.Exec(`UPDATE server SET host_key = 'SHA256:other' WHERE id = ?`, sid)
	if err := d.Ping(context.Background(), sid); !errors.Is(err, ErrHostKeyMismatch) {
		t.Fatalf("应返回 ErrHostKeyMismatch，实际 %v", err)
	}
	if err := store.ClearHostKey(context.Background(), db, sid); err != nil {
		t.Fatal(err)
	}
	if err := d.Ping(context.Background(), sid); err != nil {
		t.Fatal("清除后应重新信任", err)
	}
}

func TestSSHDeployWritesFileAndFailsOnCheck(t *testing.T) {
	port, files, cmds, _ := startSSHServer(t)
	db := newTestDB(t)
	c, _ := crypto.New(make([]byte, 32))
	enc, _ := c.Encrypt("pw")
	sid := exec(t, db, `INSERT INTO server (ip, ssh_port, auth_type, username, auth) VALUES ('127.0.0.1',?,'PASSWORD','root',?)`, port, enc)
	d := NewDeployer(db, c, func(context.Context, int64) ([]byte, error) { return []byte(`{"k":"it's"}`), nil })

	res := d.DeployServers(context.Background(), []int64{sid}, false)[0]
	if res.Success || !strings.Contains(res.Message, "校验失败") {
		t.Fatalf("服务端 check 失败应返回失败: %+v", res)
	}
	var body string
	for p, v := range files {
		if strings.Contains(p, "config.json.new-") && strings.HasSuffix(p, ".tmp") {
			body = v
		}
	}
	if body != `{"k":"it's"}` {
		t.Fatalf("上传内容 %q, files=%v cmds=%v", body, files, *cmds)
	}
}

func TestOutboundDisabledBlocksSSH(t *testing.T) {
	port, _, _, _ := startSSHServer(t)
	db := newTestDB(t)
	c, _ := crypto.New(make([]byte, 32))
	enc, _ := c.Encrypt("pw")
	sid := exec(t, db, `INSERT INTO server (ip, ssh_port, auth_type, username, auth) VALUES ('127.0.0.1',?,'PASSWORD','root',?)`, port, enc)
	OutboundDisabled = true
	defer func() { OutboundDisabled = false }()
	if err := NewDeployer(db, c, nil).Ping(context.Background(), sid); err == nil || !strings.Contains(err.Error(), "AIROPSCAT_OUTBOUND_DISABLED") {
		t.Fatalf("应拒绝连接，实际 %v", err)
	}
	var fp string
	db.QueryRow(`SELECT coalesce(host_key,'') FROM server WHERE id = ?`, sid).Scan(&fp)
	if fp != "" {
		t.Fatal("被拒绝时不应记录主机指纹")
	}
}
