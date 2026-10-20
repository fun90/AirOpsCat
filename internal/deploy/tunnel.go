package deploy

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"

	"github.com/fun90/airopscat/internal/store"
)

// OpenTunnel 复用部署的认证与 TOFU 规则建立 SSH 转发；调用方必须关闭返回的 closer。
// ctx 取消会关闭 SSH，closer 同时关闭转发连接和 SSH 连接。
func (d *Deployer) OpenTunnel(ctx context.Context, serverID int64, address string) (net.Conn, io.Closer, error) {
	t, err := store.SSHTargetByID(ctx, d.DB, serverID)
	if err != nil {
		return nil, nil, err
	}
	if t == nil {
		return nil, nil, errors.New("服务器不存在")
	}
	r, err := d.dialSSH(ctx, t)
	if err != nil {
		return nil, nil, err
	}
	remote := r.(*sshRemote)
	stop := context.AfterFunc(ctx, func() { remote.Close() })
	conn, err := remote.client.DialContext(ctx, "tcp", address)
	if err != nil {
		stop()
		remote.Close()
		return nil, nil, err
	}
	closer := &tunnelCloser{conn: conn, remote: remote, stop: stop}
	if err := ctx.Err(); err != nil {
		closer.Close()
		return nil, nil, err
	}
	return conn, closer, nil
}

type tunnelCloser struct {
	conn   net.Conn
	remote *sshRemote
	stop   func() bool
	once   sync.Once
	err    error
}

func (c *tunnelCloser) Close() error {
	c.once.Do(func() {
		c.stop()
		c.err = errors.Join(c.conn.Close(), c.remote.Close())
	})
	return c.err
}
