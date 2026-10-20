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
	Message    string
}

// Deployer 把配置推送到服务器。零值超时/并发度取默认值。
type Deployer struct {
	DB     *sql.DB
	Cipher *crypto.Cipher
	Config ConfigSource
	// Prepare 优先于 Config；捕获输入和快照后构建配置，保持两者一致。
	Prepare func(ctx context.Context, serverID int64) (*Prepared, error)
	// Applied 在某台服务器配置成功下发并落库后调用，参数为 Prepared.LimitsKey。
	Applied        func(serverID int64, limitsKey string)
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
	parallel := d.Parallel
	if parallel < 1 {
		parallel = defaultParallel
	}
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
			err = d.push(ctx, t, cfg, action)
			res.Message = "部署成功"
			if action == "reload" {
				res.Message = "配置已重载"
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
	}
	store.Log(context.WithoutCancel(ctx), d.DB, "DEPLOY", level, &serverID, t.Name, res.Message, "action="+action)
	if res.Success && d.Applied != nil {
		d.Applied(serverID, limitsKey)
	}
	return res
}

// push 连接服务器并安全替换配置：上传临时文件 → check → 备份 → 原子 mv → systemctl <action>。
// 临时文件与备份都不以 .json 结尾，因为 systemd 以 -C /etc/sing-box 加载目录下所有 *.json。
func (d *Deployer) push(ctx context.Context, t *store.SSHTarget, cfg []byte, action string) error {
	r, err := d.Dial(ctx, t)
	if err != nil {
		return err
	}
	defer r.Close()

	stamp := d.now().UnixMilli()
	tmp := fmt.Sprintf("%s.new-%d.tmp", configPath, stamp)
	backup := fmt.Sprintf("%s.backup.%d", configPath, stamp)
	cleanup := func() { r.Run(context.WithoutCancel(ctx), "rm -f "+shQuote(tmp)) }

	if err := runOK(ctx, r, "mkdir -p "+shQuote(configDir)); err != nil {
		return fmt.Errorf("创建配置目录失败: %w", err)
	}
	if err := r.WriteFile(ctx, tmp, cfg); err != nil {
		cleanup()
		return fmt.Errorf("配置上传失败: %w", err)
	}
	if err := runOK(ctx, r, singBox+" check -c "+shQuote(tmp)); err != nil {
		cleanup()
		return fmt.Errorf("配置校验失败，线上配置未改动: %w", err)
	}
	if err := runOK(ctx, r, fmt.Sprintf("if [ -f %s ]; then cp -p %s %s; fi", shQuote(configPath), shQuote(configPath), shQuote(backup))); err != nil {
		cleanup()
		return fmt.Errorf("备份旧配置失败，线上配置未改动: %w", err)
	}
	if err := runOK(ctx, r, fmt.Sprintf("mv -f %s %s", shQuote(tmp), shQuote(configPath))); err != nil {
		cleanup()
		return fmt.Errorf("替换配置失败: %w", err)
	}
	if err := runOK(ctx, r, "systemctl "+action+" sing-box"); err != nil {
		return fmt.Errorf("配置已替换，%s 失败: %w", action, err)
	}
	return nil
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
