package monitor

import (
	"fmt"
	"strings"
)

// AgentReport 使用主机累计计数，速率由中心根据相邻样本的单调运行时间计算。
type AgentReport struct {
	ServerID     int64  `json:"serverId"`
	SampledAt    int64  `json:"sampledAt"`
	BootID       string `json:"bootId"`
	UptimeMS     int64  `json:"uptimeMs"`
	CPUIdle      int64  `json:"cpuIdle"`
	CPUTotal     int64  `json:"cpuTotal"`
	RxBytes      int64  `json:"rxBytes"`
	TxBytes      int64  `json:"txBytes"`
	MemTotal     int64  `json:"memTotal"`
	MemAvailable int64  `json:"memAvailable"`
	DiskUsed     int64  `json:"diskUsed,omitempty"`
	DiskTotal    int64  `json:"diskTotal,omitempty"`
	Singbox      string `json:"singbox,omitempty"` // active / down；缺省表示未安装或无法判断
	Vnstat       string `json:"vnstat,omitempty"`
}

func (r AgentReport) Validate() error {
	if r.ServerID <= 0 || r.SampledAt <= 0 || strings.TrimSpace(r.BootID) == "" || len(r.BootID) > 64 || r.UptimeMS <= 0 || r.CPUIdle < 0 || r.CPUTotal <= 0 || r.CPUIdle > r.CPUTotal || r.RxBytes < 0 || r.TxBytes < 0 || r.MemTotal <= 0 || r.MemAvailable < 0 || r.MemAvailable > r.MemTotal || r.DiskUsed < 0 || r.DiskTotal < 0 || r.DiskUsed > r.DiskTotal || (r.Singbox != "" && r.Singbox != "active" && r.Singbox != "down") {
		return fmt.Errorf("监控指标无效")
	}
	return nil
}

// SampleFromCounters 首次采样、重启、断报和计数重置只更新基线，不生成虚假的零速率。
func SampleFromCounters(previous, current AgentReport) (Sample, bool) {
	elapsed := current.UptimeMS - previous.UptimeMS
	if previous.BootID != current.BootID || elapsed < 1000 || elapsed > 30000 || current.CPUTotal <= previous.CPUTotal || current.CPUIdle < previous.CPUIdle || current.RxBytes < previous.RxBytes || current.TxBytes < previous.TxBytes {
		return Sample{}, false
	}
	total := current.CPUTotal - previous.CPUTotal
	idle := current.CPUIdle - previous.CPUIdle
	if idle > total {
		return Sample{}, false
	}
	s := Sample{CPU: 1 - float64(idle)/float64(total), MemTotal: current.MemTotal, MemUsed: current.MemTotal - current.MemAvailable}
	s.Memory = float64(s.MemUsed) / float64(s.MemTotal)
	s.Singbox = current.Singbox
	if current.DiskTotal > 0 {
		s.DiskUsed, s.DiskTotal, s.Disk = current.DiskUsed, current.DiskTotal, float64(current.DiskUsed)/float64(current.DiskTotal)
	}
	// 先做浮点运算，避免累计字节差值乘 1000 时整数溢出。
	rx := float64(current.RxBytes-previous.RxBytes) * 1000 / float64(elapsed)
	tx := float64(current.TxBytes-previous.TxBytes) * 1000 / float64(elapsed)
	if rx >= float64(int64(1<<63-1)) || tx >= float64(int64(1<<63-1)) {
		return Sample{}, false
	}
	s.RxRate, s.TxRate = int64(rx), int64(tx)
	return s, true
}
