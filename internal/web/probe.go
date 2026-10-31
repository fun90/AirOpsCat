package web

import (
	"context"
	"database/sql"
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
)

// probeCmd 在探测机上用 bash /dev/tcp 拨目标，末行输出「退出码 耗时毫秒」；timeout 超时退出码为 124。
const probeCmd = `s=$(date +%%s%%N); timeout 5 bash -c 'exec 3<>/dev/tcp/%s/%d' 2>&1; c=$?; echo; echo "$c $(( ($(date +%%s%%N)-s)/1000000 ))"`

type probeResult struct {
	Name string `json:"name"`
	OK   bool   `json:"ok"`
	Ms   int    `json:"ms,omitempty"`
	Msg  string `json:"msg"`
}

// placementProbe 从配置的国内探测服务器经 SSH 拨测部署位 IP:端口，返回每台探测机的结果。
func (s *Server) placementProbe(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var ip, protocol string
	var port int
	err := s.DB.QueryRowContext(r.Context(), `SELECT s.ip, n.port, n.protocol FROM node_server p
		JOIN server s ON s.id = p.server_id JOIN node n ON n.id = p.node_id WHERE p.id = ?`, id).Scan(&ip, &port, &protocol)
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	// IP 会拼进远端命令，必须是合法 IP 字面量
	if net.ParseIP(ip) == nil || port < 1 || port > 65535 || protocol == "hysteria2" {
		http.Error(w, "部署位不支持 TCP 检测", 422)
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
			out[i] = s.probeFrom(ctx, pid, ip, port)
		}()
	}
	wg.Wait()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(out)
}

func (s *Server) probeFrom(ctx context.Context, probeID int64, ip string, port int) probeResult {
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
	stdout, _, _, err := remote.Run(ctx, fmt.Sprintf(probeCmd, ip, port))
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
