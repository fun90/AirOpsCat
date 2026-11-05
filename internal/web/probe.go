package web

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/netprobe"
	"github.com/fun90/airopscat/internal/probeassets"
	"github.com/fun90/airopscat/internal/store"
	"golang.org/x/crypto/blake2b"
)

type probeResult struct {
	Name string `json:"name"`
	OK   bool   `json:"ok"`
	Ms   int    `json:"ms,omitempty"`
	Msg  string `json:"msg"`
}

// placementProbe 从配置的国内探测服务器经 SSH 拨测部署位 IP:端口，返回每台探测机的结果。
func (s *Server) placementProbe(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var ip, protocol, inbound string
	var port int
	err := s.DB.QueryRowContext(r.Context(), `SELECT s.ip, n.port, n.protocol, coalesce(n.inbound,'') FROM node_server p
		JOIN server s ON s.id = p.server_id JOIN node n ON n.id = p.node_id WHERE p.id = ?`, id).Scan(&ip, &port, &protocol, &inbound)
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	request, err := probeRequest(ip, port, protocol, inbound)
	if err != nil {
		http.Error(w, err.Error(), 422)
		return
	}
	ids, err := store.ParseIDs(store.ConfigValue(r.Context(), s.DB, store.ProbeServersKey))
	if err != nil || len(ids) == 0 {
		http.Error(w, "未配置国内探测服务器", 422)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	out := make([]probeResult, len(ids))
	var wg sync.WaitGroup
	for i, pid := range ids {
		wg.Add(1)
		go func() {
			defer wg.Done()
			out[i] = s.probeFrom(ctx, pid, request)
		}()
	}
	wg.Wait()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(out)
}

// probeRequest 生成经标准输入发送的 JSON 请求，不包含远端 shell 命令。
// hysteria2 走 UDP（QUIC Initial，按需 salamander 封装），其余协议走 TCP。
func probeRequest(ip string, port int, protocol, inbound string) (string, error) {
	// 仅允许 IP 字面量，避免探测受远端 DNS 解析影响。
	if net.ParseIP(ip) == nil || port < 1 || port > 65535 {
		return "", errors.New("部署位不支持检测")
	}
	request := netprobe.Request{Network: "tcp", IP: ip, Port: port}
	if protocol != "hysteria2" {
		data, err := json.Marshal(request)
		return string(data), err
	}
	var in struct {
		Obfs *struct{ Type, Password string } `json:"obfs"`
	}
	if inbound != "" && json.Unmarshal([]byte(inbound), &in) != nil || in.Obfs != nil && in.Obfs.Type != "salamander" {
		return "", errors.New("仅支持 salamander 混淆或无混淆的 hysteria2")
	}
	pkt := quicInitialPing()
	if in.Obfs != nil {
		pkt = salamander(pkt, in.Obfs.Password)
	}
	request.Network, request.Packet = "udp", pkt
	data, err := json.Marshal(request)
	return string(data), err
}

func (s *Server) prepareProbe(ctx context.Context, remote deploy.Remote) (*deploy.Probe, error) {
	load := s.probeLoad
	if load == nil {
		load = probeassets.Load
	}
	return deploy.PrepareProbe(ctx, remote, load)
}

func (s *Server) probeFrom(ctx context.Context, probeID int64, request string) probeResult {
	res := probeResult{Name: "#" + strconv.FormatInt(probeID, 10)}
	t, err := store.SSHTargetByID(ctx, s.DB, probeID)
	if err != nil {
		res.Msg = "读取探测服务器失败"
		return res
	}
	if t == nil {
		res.Msg = "探测服务器不存在"
		return res
	}
	res.Name = t.Name
	remote, err := s.Deployer.Dial(ctx, t)
	if err != nil {
		res.Msg = "探测服务器 SSH 连接失败"
		return res
	}
	defer remote.Close()
	probe, err := s.prepareProbe(ctx, remote)
	if err != nil {
		res.Msg = "探测程序准备失败"
		return res
	}
	stdout, err := probe.Run(ctx, []byte(request))
	if err != nil {
		res.Msg = "探测命令执行失败"
		return res
	}
	return parseProbe(res, stdout)
}

// parseProbe 解析原生探测程序输出：末行为退出码与耗时，之前为规范化的连接错误。
func parseProbe(res probeResult, stdout string) probeResult {
	lines := strings.Split(strings.TrimSpace(stdout), "\n")
	var code, ms int
	if _, err := fmt.Sscan(lines[len(lines)-1], &code, &ms); err != nil {
		res.Msg = "探测输出无法解析"
		return res
	}
	detail := strings.ToLower(strings.Join(lines[:len(lines)-1], " "))
	switch {
	case code == 0:
		res.OK, res.Ms, res.Msg = true, ms, "可达"
	case code == 124:
		res.Msg = "超时"
	case strings.Contains(detail, "refused"):
		res.Msg = "拒绝连接"
	case strings.Contains(detail, "no route"), strings.Contains(detail, "unreachable"):
		res.Msg = "网络不可达"
	default:
		res.Msg = "连接失败"
	}
	return res
}

// quicInitialPing 按 RFC 9000/9001 构造 1200 字节的 QUIC v1 客户端 Initial 包，载荷只有 PING+PADDING，服务端会回 ACK。
// hysteria2 开混淆时 sing-quic 关闭了版本协商，只能发合法 Initial 才有回包。
func quicInitialPing() []byte {
	dcid := make([]byte, 8)
	rand.Read(dcid)
	salt, _ := hex.DecodeString("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
	initial, _ := hkdf.Extract(sha256.New, dcid, salt)
	client := quicLabel(initial, "client in", 32)
	key, iv, hp := quicLabel(client, "quic key", 16), quicLabel(client, "quic iv", 12), quicLabel(client, "quic hp", 16)
	hdr := append([]byte{0xc0, 0, 0, 0, 1, 8}, dcid...) // Initial、1 字节包号、版本 1、DCID
	hdr = append(hdr, 0, 0)                             // SCID 长度、Token 长度
	length := 1200 - len(hdr) - 2                       // 包号 + 密文 + 16 字节标签
	hdr = append(hdr, byte(0x40|length>>8), byte(length), 0)
	plain := make([]byte, length-1-16)
	plain[0] = 1 // PING，其余 0 为 PADDING
	block, _ := aes.NewCipher(key)
	aead, _ := cipher.NewGCM(block)
	pkt := aead.Seal(append([]byte(nil), hdr...), iv, plain, hdr) // 包号 0，nonce 即 iv
	pn := len(hdr) - 1
	mask := make([]byte, 16)
	hb, _ := aes.NewCipher(hp)
	hb.Encrypt(mask, pkt[pn+4:pn+20])
	pkt[0] ^= mask[0] & 0x0f
	pkt[pn] ^= mask[1]
	return pkt
}

func quicLabel(secret []byte, label string, n int) []byte {
	label = "tls13 " + label
	info := append([]byte{byte(n >> 8), byte(n), byte(len(label))}, label...)
	out, _ := hkdf.Expand(sha256.New, secret, string(append(info, 0)), n)
	return out
}

// salamander 按 hysteria2 混淆封装：8 字节随机盐 + 与 blake2b-256(密码+盐) 循环异或的原包。
func salamander(pkt []byte, password string) []byte {
	out := make([]byte, 8, 8+len(pkt))
	rand.Read(out)
	key := blake2b.Sum256(append([]byte(password), out...))
	for i, c := range pkt {
		out = append(out, c^key[i%len(key)])
	}
	return out
}
