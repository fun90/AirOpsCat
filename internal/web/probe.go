package web

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
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

	"github.com/fun90/airopscat/internal/store"
	"golang.org/x/crypto/blake2b"
)

// probeCmd 在探测机上用 bash /dev/tcp 拨目标，末行输出「退出码 耗时毫秒」；timeout 超时退出码为 124。
const probeCmd = `s=$(date +%%s%%N); timeout 5 bash -c 'exec 3<>/dev/tcp/%s/%d' 2>&1; c=$?; echo; echo "$c $(( ($(date +%%s%%N)-s)/1000000 ))"`

// udpProbeCmd 经 bash /dev/udp 发两次 QUIC Initial（防丢包），收到任一回包即退出码 0；端口关闭时读写报 Connection refused。
const udpProbeCmd = `s=$(date +%%s%%N); timeout 5 bash -c 'exec 3<>/dev/udp/%s/%d; for i in 1 2; do echo %s | base64 -d | dd bs=4k count=1 iflag=fullblock status=none >&3 || exit; done; dd bs=4k count=1 status=none <&3 >/dev/null' 2>&1; c=$?; echo; echo "$c $(( ($(date +%%s%%N)-s)/1000000 ))"`

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
	// IP 会拼进远端命令，必须是合法 IP 字面量
	if net.ParseIP(ip) == nil || port < 1 || port > 65535 {
		http.Error(w, "部署位不支持检测", 422)
		return
	}
	cmd := fmt.Sprintf(probeCmd, ip, port)
	if protocol == "hysteria2" {
		var in struct {
			Obfs *struct{ Type, Password string } `json:"obfs"`
		}
		if json.Unmarshal([]byte(inbound), &in) != nil || in.Obfs != nil && in.Obfs.Type != "salamander" {
			http.Error(w, "仅支持 salamander 混淆或无混淆的 hysteria2", 422)
			return
		}
		pkt := quicInitialPing()
		if in.Obfs != nil {
			pkt = salamander(pkt, in.Obfs.Password)
		}
		cmd = fmt.Sprintf(udpProbeCmd, ip, port, base64.StdEncoding.EncodeToString(pkt))
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
			out[i] = s.probeFrom(ctx, pid, cmd)
		}()
	}
	wg.Wait()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(out)
}

func (s *Server) probeFrom(ctx context.Context, probeID int64, cmd string) probeResult {
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
	stdout, _, _, err := remote.Run(ctx, cmd)
	if err != nil {
		res.Msg = "探测命令执行失败"
		return res
	}
	return parseProbe(res, stdout)
}

// parseProbe 解析 probeCmd 输出：末行为退出码与耗时，之前为 bash 的连接错误。
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
