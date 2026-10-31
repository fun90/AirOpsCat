// Package monitor 解析服务器指标：只做命令生成与输出解析，不碰数据库。
package monitor

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Runner 是远端执行接口，与部署用的 Remote 兼容。
type Runner interface {
	Run(ctx context.Context, cmd string) (stdout, stderr string, code int, err error)
}

// Script 在远端取两次间隔 1 秒的 CPU 与网卡计数，再读内存；只读 /proc，不改任何状态。
// 网卡统计排除回环与容器/隧道虚拟接口，避免重复计入。
const Script = `net(){ awk 'NR>2{gsub(":"," ");if($1!="lo"&&$1!~/^(docker|veth|br-|virbr|tun|tap|wg|tailscale|sing)/){r+=$2;t+=$10}}END{printf "%.0f %.0f\n",r+0,t+0}' /proc/net/dev; }
cpu(){ awk '/^cpu /{idle=$5+$6;tot=0;for(i=2;i<=9&&i<=NF;i++)tot+=$i;printf "%.0f %.0f\n",idle+0,tot+0}' /proc/stat; }
c1=$(cpu); n1=$(net); u1=$(cut -d' ' -f1 /proc/uptime); sleep 1; c2=$(cpu); n2=$(net); u2=$(cut -d' ' -f1 /proc/uptime)
echo "cpu $c1 $c2"
echo "net $n1 $n2"
awk -v a="$u1" -v b="$u2" 'BEGIN{printf "elapsed %.0f\n",(b-a)*1000}'
awk '/^MemTotal:/{t=$2}/^MemAvailable:/{a=$2}END{printf "mem %.0f %.0f\n",t*1024,a*1024}' /proc/meminfo
df -PB1 / 2>/dev/null | awk 'NR==2{printf "disk %.0f %.0f\n",$3,$2}'
if [ "$(systemctl show -p LoadState --value sing-box 2>/dev/null)" = loaded ]; then systemctl is-active --quiet sing-box && echo "sb 0" || echo "sb 1"; fi`

// VnstatCommand 取按日统计，由调用方按流量重置日汇总；未安装 vnstat 时输出为空由调用方处理。
const VnstatCommand = `command -v vnstat >/dev/null 2>&1 && vnstat --json d 2>/dev/null`

type Sample struct {
	CPU, Memory         float64 // 0–1
	MemUsed, MemTotal   int64
	Disk                float64 // 根分区使用率 0–1；DiskTotal 为 0 表示未采到
	DiskUsed, DiskTotal int64
	Singbox             string // active / down；空表示未安装或无法判断
	RxRate, TxRate      int64  // 字节/秒
}

// ParseMonitor 解析 Script 的输出；任何字段缺失或数值不合理都返回错误，不写入半截数据。
func ParseMonitor(out string) (Sample, error) {
	f := map[string][]int64{}
	for _, line := range strings.Split(strings.TrimSpace(out), "\n") {
		parts := strings.Fields(line)
		if len(parts) < 2 {
			continue
		}
		for _, p := range parts[1:] {
			n, err := strconv.ParseInt(p, 10, 64)
			if err != nil || n < 0 {
				return Sample{}, fmt.Errorf("指标输出无效: %q", line)
			}
			f[parts[0]] = append(f[parts[0]], n)
		}
	}
	cpu, net, mem, disk := f["cpu"], f["net"], f["mem"], f["disk"]
	if len(cpu) != 4 || len(net) != 4 || len(mem) != 2 || mem[0] == 0 || len(f["elapsed"]) != 1 || f["elapsed"][0] <= 0 || cpu[3] <= cpu[1] || cpu[2] < cpu[0] {
		return Sample{}, errors.New("指标输出不完整")
	}
	s := Sample{MemTotal: mem[0], MemUsed: mem[0] - min(mem[1], mem[0])}
	if sb := f["sb"]; len(sb) == 1 {
		s.Singbox = map[bool]string{true: "down", false: "active"}[sb[0] != 0]
	}
	if len(disk) == 2 && disk[0] <= disk[1] && disk[1] > 0 {
		s.DiskUsed, s.DiskTotal, s.Disk = disk[0], disk[1], float64(disk[0])/float64(disk[1])
	}
	s.Memory = float64(s.MemUsed) / float64(mem[0])
	if dt := cpu[3] - cpu[1]; dt > 0 {
		s.CPU = 1 - float64(cpu[2]-cpu[0])/float64(dt)
		s.CPU = min(max(s.CPU, 0), 1)
	}
	// 计数回绕或网卡重置时速率记 0，而不是负数
	if net[2] >= net[0] {
		s.RxRate = int64(float64(net[2]-net[0]) * 1000 / float64(f["elapsed"][0]))
	}
	if net[3] >= net[1] {
		s.TxRate = int64(float64(net[3]-net[1]) * 1000 / float64(f["elapsed"][0]))
	}
	return s, nil
}

type Vnstat struct {
	Iface            string
	Year, Month      int // 流量周期起始日所在年月
	RxBytes, TxBytes int64
}

type vnstatEntry struct {
	Date struct{ Year, Month, Day int } `json:"date"`
	Rx   int64                          `json:"rx"`
	Tx   int64                          `json:"tx"`
}

// TrafficPeriod 返回 at 所在流量周期的首日与末日；重置日超过当月天数时取月末。
func TrafficPeriod(resetDay int, at time.Time) (start, end time.Time) {
	day := func(y int, m time.Month) time.Time {
		return time.Date(y, m, min(max(resetDay, 1), time.Date(y, m+1, 0, 0, 0, 0, 0, at.Location()).Day()), 0, 0, 0, 0, at.Location())
	}
	start = day(at.Year(), at.Month())
	if at.Before(start) {
		start = day(at.Year(), at.Month()-1)
	}
	return start, day(start.Year(), start.Month()+1).AddDate(0, 0, -1)
}

// ParseVnstat 取 at 所在流量周期流量最大的网卡（排除回环），避免把 docker 网桥重复相加。
// 日数据按周期求和；只有月数据（旧 agent）时仅在重置日为 1 号时可用。没有本周期数据返回 ok=false。
func ParseVnstat(raw string, at time.Time, resetDay int) (v Vnstat, ok bool, err error) {
	if strings.TrimSpace(raw) == "" {
		return v, false, nil
	}
	var doc struct {
		Interfaces []struct {
			Name    string `json:"name"`
			Traffic struct {
				Day   []vnstatEntry `json:"day"`
				Month []vnstatEntry `json:"month"`
			} `json:"traffic"`
		} `json:"interfaces"`
	}
	if err := json.Unmarshal([]byte(raw), &doc); err != nil {
		return v, false, fmt.Errorf("vnstat 输出不是有效 JSON")
	}
	for _, i := range doc.Interfaces {
		if i.Name == "lo" || strings.HasPrefix(i.Name, "docker") || strings.HasPrefix(i.Name, "veth") || strings.HasPrefix(i.Name, "br-") || strings.HasPrefix(i.Name, "tun") || strings.HasPrefix(i.Name, "wg") {
			continue
		}
		start, end := TrafficPeriod(resetDay, at)
		cur := Vnstat{Iface: i.Name, Year: start.Year(), Month: int(start.Month())}
		found := false
		for _, d := range i.Traffic.Day {
			date := time.Date(d.Date.Year, time.Month(d.Date.Month), d.Date.Day, 0, 0, 0, 0, at.Location())
			if !date.Before(start) && !date.After(end) && d.Rx >= 0 && d.Tx >= 0 {
				cur.RxBytes, cur.TxBytes, found = cur.RxBytes+d.Rx, cur.TxBytes+d.Tx, true
			}
		}
		if len(i.Traffic.Day) == 0 && start.Day() == 1 {
			for _, m := range i.Traffic.Month {
				if m.Date.Year == start.Year() && m.Date.Month == int(start.Month()) && m.Rx >= 0 && m.Tx >= 0 {
					cur.RxBytes, cur.TxBytes, found = m.Rx, m.Tx, true
				}
			}
		}
		if found && cur.RxBytes+cur.TxBytes >= v.RxBytes+v.TxBytes {
			v, ok = cur, true
		}
	}
	return v, ok, nil
}

// Collect 在已建立的远端连接上采集一次监控指标。
func Collect(ctx context.Context, r Runner) (Sample, error) {
	out, stderr, code, err := r.Run(ctx, Script)
	if err != nil {
		return Sample{}, err
	}
	if code != 0 {
		return Sample{}, fmt.Errorf("采集命令退出码 %d: %s", code, strings.TrimSpace(stderr))
	}
	return ParseMonitor(out)
}

// CollectVnstat 未安装 vnstat 时返回 ok=false。
func CollectVnstat(ctx context.Context, r Runner, at time.Time, resetDay int) (Vnstat, bool, error) {
	out, _, _, err := r.Run(ctx, VnstatCommand)
	if err != nil {
		return Vnstat{}, false, err
	}
	return ParseVnstat(out, at, resetDay)
}

// Point 是窗口判定使用的一个采样。
type Point struct {
	At    time.Time
	Value float64
}

// Sustained 判断窗口内是否持续不低于阈值。covered 表示样本足以下结论：覆盖整个窗口（允许最多一个采样间隔的缺口）、
// 不少于 2 个、最新样本不陈旧且相邻样本间隔不超过 3 个采样间隔；covered 为 false 时调用方不应告警也不应恢复。
func Sustained(points []Point, now time.Time, window, interval time.Duration, threshold float64) (over, covered bool) {
	var in []Point
	for _, p := range points {
		if !p.At.Before(now.Add(-window)) && !p.At.After(now) {
			in = append(in, p)
		}
	}
	if len(in) < 2 {
		return false, false
	}
	sort.Slice(in, func(i, j int) bool { return in[i].At.Before(in[j].At) })
	if in[0].At.After(now.Add(-window).Add(interval)) || now.Sub(in[len(in)-1].At) > 2*interval {
		return false, false
	}
	over = true
	for i, p := range in {
		if i > 0 && p.At.Sub(in[i-1].At) > 3*interval {
			return false, false
		}
		if p.Value < threshold {
			over = false
		}
	}
	return over, true
}

// NormalizeThreshold 支持 0–1 与 0–100 两种写法；无效或不大于 0 视为 fallback。
func NormalizeThreshold(v float64, fallback float64) float64 {
	if v <= 0 {
		return fallback
	}
	if v > 1 {
		v /= 100
	}
	return min(v, 1)
}
