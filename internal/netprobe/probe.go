// Package netprobe 使用原生 socket 检测端口，不启动外部命令。
package netprobe

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"syscall"
	"time"
)

const Check = "airopscat-probe-v1"

// Request 的 Packet 由控制端生成，密码不会传到探测程序。
type Request struct {
	Network string `json:"network"`
	IP      string `json:"ip"`
	Port    int    `json:"port"`
	Packet  []byte `json:"packet,omitempty"`
}

func (r Request) Validate() error {
	if net.ParseIP(r.IP) == nil || r.Port < 1 || r.Port > 65535 {
		return errors.New("目标必须是有效 IP 和端口")
	}
	if r.Network != "tcp" && r.Network != "udp" {
		return errors.New("仅支持 TCP 和 UDP")
	}
	if r.Network == "udp" && (len(r.Packet) == 0 || len(r.Packet) > 4096) {
		return errors.New("UDP 报文长度必须为 1 至 4096 字节")
	}
	if r.Network == "tcp" && len(r.Packet) != 0 {
		return errors.New("TCP 检测不接受报文")
	}
	return nil
}

// Serve 每次只接受一个有长度限制的 JSON 请求，输出兼容既有探测解析器。
func Serve(ctx context.Context, in io.Reader, out io.Writer) error {
	data, err := io.ReadAll(io.LimitReader(in, 8193))
	if err != nil {
		return err
	}
	if len(data) > 8192 {
		return errors.New("探测请求过大")
	}
	var r Request
	if err := json.Unmarshal(data, &r); err != nil {
		return errors.New("探测请求 JSON 无效")
	}
	if err := r.Validate(); err != nil {
		return err
	}
	start := time.Now()
	err = probe(ctx, r)
	code, detail := 0, ""
	if err != nil {
		code, detail = 1, "connection failed"
		var ne net.Error
		switch {
		case errors.Is(err, context.DeadlineExceeded), errors.As(err, &ne) && ne.Timeout():
			code, detail = 124, "timeout"
		case errors.Is(err, syscall.ECONNREFUSED):
			detail = "connection refused"
		case errors.Is(err, syscall.ENETUNREACH), errors.Is(err, syscall.EHOSTUNREACH):
			detail = "network unreachable"
		}
	}
	_, err = fmt.Fprintf(out, "%s\n%d %d\n", detail, code, time.Since(start).Milliseconds())
	return err
}

func probe(ctx context.Context, r Request) error {
	conn, err := (&net.Dialer{}).DialContext(ctx, r.Network, net.JoinHostPort(r.IP, strconv.Itoa(r.Port)))
	if err != nil {
		return err
	}
	defer conn.Close()
	if r.Network == "tcp" {
		return nil
	}
	if deadline, ok := ctx.Deadline(); ok {
		conn.SetDeadline(deadline)
	}
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	// 两次 Write 对应两个独立的 UDP datagram，不能拼成一次写入。
	for i := 0; i < 2; i++ {
		if _, err := conn.Write(r.Packet); err != nil {
			return err
		}
	}
	var reply [4096]byte
	_, err = conn.Read(reply[:])
	if ctx.Err() != nil {
		return ctx.Err()
	}
	return err
}
