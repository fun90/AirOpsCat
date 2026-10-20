// Package traffic 经 gRPC 读取账户累计流量，不清零计数，也不写入流量存储。
package traffic

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/traffic/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

const statsAddress = "127.0.0.1:101"

// Counters 是单个账户的原始累计字节数，未应用服务器倍率。
type Counters struct {
	UploadBytes   int64
	DownloadBytes int64
}

// Collector 的零值可用。Timeout 为整次采集（含连接）的上限，默认 10 秒。
type Collector struct {
	Timeout time.Duration
}

// Query 接管 factory 返回连接的所有权，完成或失败时关闭连接。
// gRPC 可能多次调用 factory，每次必须返回新连接并响应 ctx 取消。
// 无效统计名被忽略；合法统计中的负值或累加溢出返回错误，不返回部分结果。
func (c Collector) Query(ctx context.Context, factory func(context.Context) (net.Conn, error)) (map[string]Counters, error) {
	if factory == nil {
		return nil, errors.New("缺少流量连接工厂")
	}
	timeout := c.Timeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	var dialMu sync.Mutex
	var dialErr error
	client, err := grpc.NewClient("passthrough:///traffic", grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithContextDialer(func(_ context.Context, _ string) (net.Conn, error) {
			// gRPC 的拨号 ctx 在建连后即取消，隧道须使用整次查询的 ctx。
			conn, err := factory(ctx)
			dialMu.Lock()
			dialErr = err
			dialMu.Unlock()
			return conn, err
		}), grpc.WithDisableRetry())
	if err != nil {
		return nil, fmt.Errorf("创建流量客户端: %w", err)
	}
	defer client.Close()
	response, err := pb.NewStatsServiceClient(client).QueryStats(ctx, &pb.QueryStatsRequest{
		Reset_:   false,
		Patterns: []string{"user>>>.*>>>traffic>>>.*"},
		Regexp:   true,
	})
	if err != nil {
		dialMu.Lock()
		cause := dialErr
		dialMu.Unlock()
		return nil, fmt.Errorf("查询累计流量: %w", errors.Join(err, cause))
	}
	return parseStats(response.Stat)
}

func parseStats(stats []*pb.Stat) (map[string]Counters, error) {
	result := make(map[string]Counters)
	for _, stat := range stats {
		parts := strings.Split(stat.GetName(), ">>>")
		if len(parts) != 4 || parts[0] != "user" || parts[1] == "" || parts[2] != "traffic" || (parts[3] != "uplink" && parts[3] != "downlink") {
			continue
		}
		value := stat.GetValue()
		if value < 0 {
			return nil, fmt.Errorf("统计 %q 包含负数", stat.Name)
		}
		counter := result[parts[1]]
		target := &counter.UploadBytes
		if parts[3] == "downlink" {
			target = &counter.DownloadBytes
		}
		if value > math.MaxInt64-*target {
			return nil, fmt.Errorf("统计 %q 累计溢出", stat.Name)
		}
		*target += value
		result[parts[1]] = counter
	}
	return result, nil
}

// CollectSSH 经已有 TOFU 认证的 SSH 转发读取 127.0.0.1:101，返回累计计数。
func CollectSSH(ctx context.Context, db *sql.DB, cipher *crypto.Cipher, serverID int64) (map[string]Counters, error) {
	return (Collector{}).CollectSSH(ctx, db, cipher, serverID)
}

// CollectSSH 使用 Collector 的整次采集超时，包括 SSH 握手与转发。
func (c Collector) CollectSSH(ctx context.Context, db *sql.DB, cipher *crypto.Cipher, serverID int64) (map[string]Counters, error) {
	if db == nil || cipher == nil {
		return nil, errors.New("缺少数据库或凭据密钥")
	}
	d := deploy.NewDeployer(db, cipher, nil)
	return c.Query(ctx, func(ctx context.Context) (net.Conn, error) {
		conn, closer, err := d.OpenTunnel(ctx, serverID, statsAddress)
		if err != nil {
			return nil, err
		}
		return &tunnelConn{Conn: conn, closer: closer}, nil
	})
}

type tunnelConn struct {
	net.Conn
	closer io.Closer
}

func (c *tunnelConn) Close() error { return c.closer.Close() }
