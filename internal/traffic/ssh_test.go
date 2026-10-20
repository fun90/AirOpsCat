package traffic

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"database/sql"
	"errors"
	"io"
	"net"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/traffic/pb"
	"golang.org/x/crypto/ssh"
	_ "modernc.org/sqlite"
)

func sshDB(t *testing.T, port int) (*sql.DB, *crypto.Cipher) {
	t.Helper()
	db, err := sql.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { db.Close() })
	_, err = db.Exec(`CREATE TABLE server (id INTEGER PRIMARY KEY, name TEXT, ip TEXT, ssh_port INTEGER, auth_type TEXT, username TEXT, auth TEXT, host_key TEXT, disabled BOOLEAN, external_server BOOLEAN)`)
	if err != nil {
		t.Fatal(err)
	}
	cipher, err := crypto.New(make([]byte, 32))
	if err != nil {
		t.Fatal(err)
	}
	secret, err := cipher.Encrypt("pw")
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO server VALUES (1,'local','127.0.0.1',?,'PASSWORD','root',?,'',false,false)`, port, secret)
	if err != nil {
		t.Fatal(err)
	}
	return db, cipher
}

func TestCollectSSHAndTOFU(t *testing.T) {
	factory := serveStats(t, func(context.Context, *pb.QueryStatsRequest) (*pb.QueryStatsResponse, error) {
		return &pb.QueryStatsResponse{Stat: []*pb.Stat{{Name: "user>>>A>>>traffic>>>uplink", Value: 12}}}, nil
	})
	_, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(key)
	if err != nil {
		t.Fatal(err)
	}
	cfg := &ssh.ServerConfig{PasswordCallback: func(meta ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
		if meta.User() != "root" || string(password) != "pw" {
			return nil, errors.New("denied")
		}
		return nil, nil
	}}
	cfg.AddHostKey(signer)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	var active atomic.Int32
	var forwards atomic.Int32
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				sc, channels, requests, err := ssh.NewServerConn(conn, cfg)
				if err != nil {
					return
				}
				active.Add(1)
				defer active.Add(-1)
				defer sc.Close()
				go ssh.DiscardRequests(requests)
				for incoming := range channels {
					var target struct {
						Address    string
						Port       uint32
						Origin     string
						OriginPort uint32
					}
					if incoming.ChannelType() != "direct-tcpip" || ssh.Unmarshal(incoming.ExtraData(), &target) != nil || target.Address != "127.0.0.1" || target.Port != 101 {
						incoming.Reject(ssh.Prohibited, "invalid target")
						continue
					}
					forwards.Add(1)
					channel, reqs, err := incoming.Accept()
					if err != nil {
						return
					}
					go ssh.DiscardRequests(reqs)
					upstream, err := factory(context.Background())
					if err != nil {
						channel.Close()
						return
					}
					go func() { defer upstream.Close(); defer channel.Close(); io.Copy(upstream, channel) }()
					go func() { defer upstream.Close(); defer channel.Close(); io.Copy(channel, upstream) }()
				}
			}()
		}
	}()
	db, cipher := sshDB(t, listener.Addr().(*net.TCPAddr).Port)
	for range 2 {
		got, err := CollectSSH(context.Background(), db, cipher, 1)
		if err != nil || got["A"].UploadBytes != 12 {
			t.Fatalf("采集失败: %v %v", got, err)
		}
	}
	var fp string
	if err := db.QueryRow(`SELECT host_key FROM server WHERE id=1`).Scan(&fp); err != nil {
		t.Fatal(err)
	}
	if fp != ssh.FingerprintSHA256(signer.PublicKey()) {
		t.Fatalf("未保存指纹: %s", fp)
	}
	if forwards.Load() != 2 {
		t.Fatalf("转发次数: %d", forwards.Load())
	}
	deadline := time.Now().Add(time.Second)
	for active.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if active.Load() != 0 {
		t.Fatal("SSH 连接未关闭")
	}
	if _, err := db.Exec(`UPDATE server SET host_key='SHA256:other' WHERE id=1`); err != nil {
		t.Fatal(err)
	}
	_, err = (Collector{Timeout: 100 * time.Millisecond}).CollectSSH(context.Background(), db, cipher, 1)
	if !errors.Is(err, deploy.ErrHostKeyMismatch) {
		t.Fatalf("CollectSSH 应保留主机密钥错误身份: %v", err)
	}
	d := deploy.NewDeployer(db, cipher, nil)
	_, _, err = d.OpenTunnel(context.Background(), 1, statsAddress)
	if !errors.Is(err, deploy.ErrHostKeyMismatch) {
		t.Fatalf("应拒绝变化的主机密钥: %v", err)
	}
	_, _, err = d.OpenTunnel(context.Background(), 999, statsAddress)
	if err == nil {
		t.Fatal("应拒绝不存在的服务器")
	}
}

func TestCollectSSHHandshakeTimeout(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	closed := make(chan struct{})
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		io.Copy(io.Discard, conn)
		close(closed)
	}()
	_, port, _ := net.SplitHostPort(listener.Addr().String())
	portNo, _ := strconv.Atoi(port)
	db, cipher := sshDB(t, portNo)
	start := time.Now()
	if _, err := (Collector{Timeout: 100 * time.Millisecond}).CollectSSH(context.Background(), db, cipher, 1); err == nil {
		t.Fatal("握手应超时")
	}
	if time.Since(start) > time.Second {
		t.Fatal("整体超时未覆盖握手")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("超时后底层连接未关闭")
	}
}
