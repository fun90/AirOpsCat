package deploy

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
)

const (
	configDir  = "/etc/sing-box"
	configPath = configDir + "/config.json"
	singBox    = "/usr/bin/sing-box"

	defaultParallel       = 4
	defaultConnectTimeout = 30 * time.Second
	defaultCommandTimeout = 120 * time.Second
)

// ConfigSource 返回指定服务器完整的 config.json 内容。
type ConfigSource func(ctx context.Context, serverID int64) ([]byte, error)

// Prepared 保存由同一读取版本生成的配置与快照。
type Prepared struct {
	Config    []byte
	Revision  int64
	Snapshots []store.DeploymentSnapshot
	LimitsKey string // 配置内账户限速指纹，成功后经 Deployer.Applied 回报
}

// Result 是单台服务器的下发结果。
type Result struct {
	ServerID   int64
	ServerName string
	Success    bool
	Skipped    bool // 服务器已禁用而跳过，不算部署失败
	Message    string
	Warning    string // 配置已生效但附带操作未完成（如补防火墙规则失败），已并入 Message
}

// Deployer 把配置推送到服务器。零值超时/并发度取默认值。
type Deployer struct {
	DB     *sql.DB
	Cipher *crypto.Cipher
	Config ConfigSource
	// Prepare 优先于 Config；捕获输入和快照后构建配置，保持两者一致。
	Prepare func(ctx context.Context, serverID int64) (*Prepared, error)
	// Applied 在某台服务器配置成功下发并落库后调用，参数为 Prepared.LimitsKey。
	Applied func(serverID int64, limitsKey string)
	// BeforeApply 在持有服务器锁、配置生成成功后、推送前调用（外部服务器不调用），
	// 用于 restart 清零内核计数前先采集流量；不得再获取同一服务器锁。返回错误只记日志，不阻断部署。
	BeforeApply    func(ctx context.Context, serverID int64, action string) error
	Parallel       int           // 同时部署的服务器数，默认 4
	ConnectTimeout time.Duration // 默认 30s
	CommandTimeout time.Duration // 单条命令整体超时，默认 120s

	// Dial 可替换以便测试，默认走真实 SSH。
	Dial func(ctx context.Context, t *store.SSHTarget) (Remote, error)
	now  func() time.Time
}

func NewDeployer(db *sql.DB, c *crypto.Cipher, src ConfigSource) *Deployer {
	d := &Deployer{DB: db, Cipher: c, Config: src, now: time.Now}
	d.Dial = d.dialSSH
	return d
}

func (d *Deployer) connectTimeout() time.Duration {
	if d.ConnectTimeout > 0 {
		return d.ConnectTimeout
	}
	return defaultConnectTimeout
}

func (d *Deployer) commandTimeout() time.Duration {
	if d.CommandTimeout > 0 {
		return d.CommandTimeout
	}
	return defaultCommandTimeout
}

// ParallelLimit 返回同时部署的服务器数上限。
func (d *Deployer) ParallelLimit() int {
	if d.Parallel < 1 {
		return defaultParallel
	}
	return d.Parallel
}

// Ping 测试 SSH 连通性（首次连接会信任并保存主机密钥）。
func (d *Deployer) Ping(ctx context.Context, serverID int64) error {
	t, err := store.SSHTargetByID(ctx, d.DB, serverID)
	if err != nil {
		return err
	}
	if t == nil {
		return errors.New("服务器不存在")
	}
	r, err := d.Dial(ctx, t)
	if err != nil {
		return err
	}
	defer r.Close()
	out, stderr, code, err := r.Run(ctx, "echo airopscat-ssh-ok")
	if err != nil {
		return err
	}
	if code != 0 || !strings.Contains(out, "airopscat-ssh-ok") {
		return fmt.Errorf("测试命令执行失败(退出码 %d): %s", code, strings.TrimSpace(stderr))
	}
	return nil
}

// DeployServers 对每台服务器整机下发配置并 restart sing-box，同时最多 Parallel 台并行，结果与 serverIDs 顺序一致。
// 每台服务器总是用其全部部署位重建完整配置，所以 force 只影响调用方如何选取服务器，这里不使用。
func (d *Deployer) DeployServers(ctx context.Context, serverIDs []int64, force bool) []Result {
	parallel := d.ParallelLimit()
	results := make([]Result, len(serverIDs))
	sem := make(chan struct{}, parallel)
	var wg sync.WaitGroup
	for i, id := range serverIDs {
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer func() { <-sem; wg.Done() }()
			results[i] = d.apply(ctx, id, "restart")
		}()
	}
	wg.Wait()
	return results
}

// Reload 整机下发配置并 reload sing-box，和部署共用结果与状态更新。
func (d *Deployer) Reload(ctx context.Context, serverID int64) Result {
	return d.apply(ctx, serverID, "reload")
}

// apply 单台服务器的完整流程：互斥 → 生成配置 → 推送 → 短事务落库。SSH 期间不持有数据库事务。
func (d *Deployer) apply(ctx context.Context, serverID int64, action string) Result {
	t, err := store.SSHTargetByID(ctx, d.DB, serverID)
	if err != nil {
		return Result{ServerID: serverID, Message: "读取服务器失败: " + err.Error()}
	}
	if t == nil {
		return Result{ServerID: serverID, Message: "服务器不存在"}
	}
	res := Result{ServerID: serverID, ServerName: t.Name}
	if t.Disabled {
		res.Skipped = true
		res.Message = "服务器已禁用，已跳过部署"
		return res
	}
	defer Lock(serverID)()

	var revision int64
	var cfg []byte
	var snapshots []store.DeploymentSnapshot
	var limitsKey string
	if d.Prepare != nil {
		var prepared *Prepared
		prepared, err = d.Prepare(ctx, serverID)
		if err == nil {
			if prepared == nil {
				err = errors.New("部署准备结果为空")
			} else {
				cfg, revision, snapshots, limitsKey = prepared.Config, prepared.Revision, prepared.Snapshots, prepared.LimitsKey
			}
		}
	} else {
		revision, err = store.DeploymentRevision(ctx, d.DB)
		if err != nil {
			res.Message = "读取部署版本失败: " + err.Error()
			return res
		}
		if d.Config == nil {
			err = errors.New("未配置部署配置来源")
		} else {
			cfg, err = d.Config(ctx, serverID)
		}
	}
	var sum string
	if err == nil {
		h := sha256.Sum256(cfg)
		sum = hex.EncodeToString(h[:])
		switch {
		case t.External:
			res.Message = "外部服务器，仅生成配置，未连接"
		default:
			if d.BeforeApply != nil {
				if herr := d.BeforeApply(ctx, serverID, action); herr != nil {
					store.Log(context.WithoutCancel(ctx), d.DB, "DEPLOY", "WARN", &serverID, t.Name, "部署前采集流量失败，继续部署: "+herr.Error(), "action="+action)
				}
			}
			res.Warning, err = d.push(ctx, t, cfg, action)
			res.Message = "部署成功"
			if action == "reload" {
				res.Message = "配置已重载"
			}
			if res.Warning != "" {
				res.Message += "；" + res.Warning
			}
		}
	} else {
		err = fmt.Errorf("生成配置失败: %w", err)
	}
	if err != nil {
		res.Message = err.Error()
	}
	res.Success = err == nil

	// 落库不受调用方取消影响，避免远端已变更而结果丢失
	if err := store.RecordDeploymentSnapshots(context.WithoutCancel(ctx), d.DB, serverID, sum, res.Success, res.Message, revision, snapshots); err != nil {
		res.Success = false
		res.Message += "；记录部署结果失败: " + err.Error()
	} else {
		// 落库可能因部署期间的数据变更追加提示，返回给操作人。
		if err := d.DB.QueryRowContext(context.WithoutCancel(ctx), `SELECT coalesce(message,'') FROM server_deployment WHERE server_id=?`, serverID).Scan(&res.Message); err != nil {
			res.Success = false
			res.Message = "读取部署结果失败: " + err.Error()
		}
	}
	level := "INFO"
	if !res.Success {
		level = "ERROR"
	} else if res.Warning != "" {
		level = "WARN"
	}
	store.Log(context.WithoutCancel(ctx), d.DB, "DEPLOY", level, &serverID, t.Name, res.Message, "action="+action)
	if res.Success && d.Applied != nil {
		d.Applied(serverID, limitsKey)
	}
	return res
}

// push 连接服务器并安全替换配置：上传临时文件 → check → 备份 → 原子 mv → systemctl <action>。
// 临时文件与备份都不以 .json 结尾，因为 systemd 以 -C /etc/sing-box 加载目录下所有 *.json。
// 配置生效后的附带操作失败不算部署失败，以 warning 返回。
func (d *Deployer) push(ctx context.Context, t *store.SSHTarget, cfg []byte, action string) (warning string, err error) {
	r, err := d.Dial(ctx, t)
	if err != nil {
		return "", err
	}
	defer r.Close()

	stamp := d.now().UnixMilli()
	tmp := fmt.Sprintf("%s.new-%d.tmp", configPath, stamp)
	backup := fmt.Sprintf("%s.backup.%d", configPath, stamp)
	cleanup := func() { r.Run(context.WithoutCancel(ctx), "rm -f "+shQuote(tmp)) }

	if err := runOK(ctx, r, "mkdir -p "+shQuote(configDir)); err != nil {
		return "", fmt.Errorf("创建配置目录失败: %w", err)
	}
	if err := r.WriteFile(ctx, tmp, cfg); err != nil {
		cleanup()
		return "", fmt.Errorf("配置上传失败: %w", err)
	}
	if err := runOK(ctx, r, singBox+" check -c "+shQuote(tmp)); err != nil {
		cleanup()
		return "", fmt.Errorf("配置校验失败，线上配置未改动: %w", err)
	}
	if err := runOK(ctx, r, fmt.Sprintf("if [ -f %s ]; then cp -p %s %s; fi", shQuote(configPath), shQuote(configPath), shQuote(backup))); err != nil {
		cleanup()
		return "", fmt.Errorf("备份旧配置失败，线上配置未改动: %w", err)
	}
	if err := runOK(ctx, r, fmt.Sprintf("mv -f %s %s", shQuote(tmp), shQuote(configPath))); err != nil {
		cleanup()
		return "", fmt.Errorf("替换配置失败: %w", err)
	}
	if err := runOK(ctx, r, "systemctl "+action+" sing-box"); err != nil {
		return "", fmt.Errorf("配置已替换，%s 失败: %w", action, err)
	}
	// 配置已生效：防火墙失败只附告警，部署位照常确认；每次部署都会重新补齐规则。
	if err := d.syncBlockedPorts(ctx, r, t); err != nil {
		return "配置已生效，但补防火墙封禁规则失败（下次部署会重试）: " + err.Error(), nil
	}
	return "", nil
}

// firewallBlockCommand 幂等地在 INPUT 链最前面封禁端口的 TCP/UDP（先于 ESTABLISHED 放行，存量连接也会被切断），并保存规则。
func firewallBlockCommand(ports []int) string {
	var b strings.Builder
	b.WriteString("set -e\n")
	for _, p := range ports {
		for _, proto := range []string{"tcp", "udp"} {
			rule := fmt.Sprintf("INPUT -p %s --dport %d -j DROP", proto, p)
			fmt.Fprintf(&b, "iptables -C %s 2>/dev/null || iptables -I %s\n", rule, rule)
		}
	}
	b.WriteString("mkdir -p /etc/iptables\niptables-save > /etc/iptables/rules.v4\n")
	return b.String()
}

// syncBlockedPorts 补齐该服务器记录的封禁端口规则；没有记录时不执行任何命令。
// 初始化防火墙的脚本会清空规则，因此每次部署后都重新确保。
// 硬性保护：SSH 端口和该服务器上任何节点正在使用的端口不封（导入、手工改库等路径可能绕过写入时的校验），跳过并记日志。
func (d *Deployer) syncBlockedPorts(ctx context.Context, r Remote, t *store.SSHTarget) error {
	ports, err := store.BlockedPorts(ctx, d.DB, t.ID)
	if err != nil {
		return err
	}
	inUse, err := store.ServerNodePorts(ctx, d.DB, t.ID)
	if err != nil {
		return err
	}
	var safe []int
	for _, p := range ports {
		if p == t.Port || inUse[p] {
			store.Log(ctx, d.DB, "DEPLOY", "WARN", &t.ID, t.Name, fmt.Sprintf("端口 %d 是 SSH 端口或节点在用端口，已跳过防火墙封禁", p), "")
			continue
		}
		safe = append(safe, p)
	}
	if len(safe) == 0 {
		return nil
	}
	return runOK(ctx, r, firewallBlockCommand(safe))
}

// runOK 执行命令，传输错误或非零退出码都转成带输出的错误。
func runOK(ctx context.Context, r Remote, cmd string) error {
	stdout, stderr, code, err := r.Run(ctx, cmd)
	if err != nil {
		return err
	}
	if code == 0 {
		return nil
	}
	msg := strings.TrimSpace(stderr + "\n" + stdout)
	if len(msg) > 1000 {
		msg = msg[:1000] + "…"
	}
	return fmt.Errorf("退出码 %d: %s", code, msg)
}
