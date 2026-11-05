package netprobe

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"
)

func serveRequest(t *testing.T, r Request, timeout time.Duration) (string, int) {
	t.Helper()
	data, err := json.Marshal(r)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	var out bytes.Buffer
	if err := Serve(ctx, bytes.NewReader(data), &out); err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	var code, ms int
	if _, err := fmt.Sscan(lines[len(lines)-1], &code, &ms); err != nil || ms < 0 {
		t.Fatalf("结果格式无效: %q", out.String())
	}
	return out.String(), code
}

func TestTCP(t *testing.T) {
	for _, ip := range []string{"127.0.0.1", "::1"} {
		t.Run(ip, func(t *testing.T) {
			ln, err := net.Listen("tcp", net.JoinHostPort(ip, "0"))
			if err != nil && ip == "::1" {
				t.Skip("本机未启用 IPv6")
			}
			if err != nil {
				t.Fatal(err)
			}
			defer ln.Close()
			r := Request{Network: "tcp", IP: ip, Port: ln.Addr().(*net.TCPAddr).Port}
			if out, code := serveRequest(t, r, time.Second); code != 0 {
				t.Fatal(out)
			}
			ln.Close()
			if out, code := serveRequest(t, r, time.Second); code != 1 || !strings.Contains(out, "refused") {
				t.Fatal(out)
			}
		})
	}
}

func TestUDPTwoDatagramsAndReply(t *testing.T) {
	for _, ip := range []string{"127.0.0.1", "::1"} {
		t.Run(ip, func(t *testing.T) {
			udp, err := net.ListenPacket("udp", net.JoinHostPort(ip, "0"))
			if err != nil && ip == "::1" {
				t.Skip("本机未启用 IPv6")
			}
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			packet := bytes.Repeat([]byte{0x42}, 1208)
			received := make(chan error, 1)
			go func() {
				udp.SetDeadline(time.Now().Add(time.Second))
				b := make([]byte, 4096)
				for i := 0; i < 2; i++ {
					n, addr, err := udp.ReadFrom(b)
					if err != nil {
						received <- err
						return
					}
					if !bytes.Equal(b[:n], packet) {
						received <- fmt.Errorf("第 %d 个 UDP 报文发生改变，长度 %d", i, n)
						return
					}
					if i == 1 {
						_, err = udp.WriteTo([]byte("reply"), addr)
						if err != nil {
							received <- err
							return
						}
					}
				}
				received <- nil
			}()
			r := Request{Network: "udp", IP: ip, Port: udp.LocalAddr().(*net.UDPAddr).Port, Packet: packet}
			if out, code := serveRequest(t, r, time.Second); code != 0 {
				t.Fatal(out)
			}
			if err := <-received; err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestUDPNoReplyAndClosed(t *testing.T) {
	udp, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	r := Request{Network: "udp", IP: "127.0.0.1", Port: udp.LocalAddr().(*net.UDPAddr).Port, Packet: []byte("ping")}
	// 端口存在且发送成功，但没有回包时仍必须超时。
	if out, code := serveRequest(t, r, 80*time.Millisecond); code != 124 {
		t.Fatal(out)
	}
	udp.Close()
	if out, code := serveRequest(t, r, time.Second); code != 1 || !strings.Contains(out, "refused") {
		t.Fatal(out)
	}
}

func TestTCPDeadlineExceeded(t *testing.T) {
	ctx, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer cancel()
	var out bytes.Buffer
	if err := Serve(ctx, strings.NewReader(`{"network":"tcp","ip":"127.0.0.1","port":443}`), &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "\n124 ") {
		t.Fatalf("TCP 超时应映射为 124: %q", out.String())
	}
}

func TestCanceledTCP(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	var out bytes.Buffer
	if err := Serve(ctx, strings.NewReader(`{"network":"tcp","ip":"127.0.0.1","port":443}`), &out); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out.String(), "\n0 ") {
		t.Fatal("已取消的连接不应成功")
	}
}

func TestInvalidRequest(t *testing.T) {
	for _, data := range []string{
		`{}`, `{"network":"tcp","ip":"example.com","port":443}`,
		`{"network":"tcp","ip":"127.0.0.1","port":0}`,
		`{"network":"udp","ip":"127.0.0.1","port":443}`,
		`{"network":"exec","ip":"127.0.0.1","port":443}`,
		`{"network":"tcp","ip":"127.0.0.1","port":443,"packet":"YQ=="}`,
		`{} {}`, strings.Repeat("x", 8193),
	} {
		var out bytes.Buffer
		if err := Serve(context.Background(), strings.NewReader(data), &out); err == nil || out.Len() != 0 {
			t.Fatalf("应拒绝非法请求，输出 %q", out.String())
		}
	}
}
