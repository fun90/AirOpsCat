package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"

	"github.com/fun90/airopscat/internal/crypto"
)

// ErrConflict 表示违反唯一约束或仍被引用，调用方应提示用户而不是报 500。
var ErrConflict = errors.New("冲突")

type Server struct {
	ID            int64
	Name          string
	IP            string
	SSHPort       int
	AuthType      string
	Username      string
	HasAuth       bool // 是否已保存凭据；明文不回传页面
	Region        string
	ServerType    int
	ExpireDate    string
	BandwidthDate string // 日期部分，库中为当天 00:00:00
	Supplier      string
	Price         *int64 // 分，对应 PriceMonths 个月
	PriceMonths   int    // 1 月付，12 年付
	Multiple      float64
	Bandwidth     *int
	CPUCores      *int
	Disabled      bool
	External      bool
	Remark        string
	Failover      int  // 已手动切走的部署位数：DNS 自动切到同节点其他部署位
	Placements    int  // 列表用：部署位数量
}

const serverCols = `id, coalesce(name,''), ip, coalesce(ssh_port,22), coalesce(auth_type,'PASSWORD'), coalesce(username,''),
	coalesce(auth,'') <> '', coalesce(region,''), coalesce(server_type,0), coalesce(expire_date,''),
	substr(coalesce(bandwidth_date,''),1,10), coalesce(supplier,''), price, price_months, coalesce(multiple,1), bandwidth, cpu_cores,
	disabled, external_server, coalesce(remark,''), (SELECT count(*) FROM node_server f WHERE f.server_id = server.id AND f.failover = 1)`

func scanServer(row interface{ Scan(...any) error }) (*Server, error) {
	var s Server
	var price, bw, cpu sql.NullInt64
	if err := row.Scan(&s.ID, &s.Name, &s.IP, &s.SSHPort, &s.AuthType, &s.Username, &s.HasAuth, &s.Region,
		&s.ServerType, &s.ExpireDate, &s.BandwidthDate, &s.Supplier, &price, &s.PriceMonths, &s.Multiple, &bw, &cpu,
		&s.Disabled, &s.External, &s.Remark, &s.Failover); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	if price.Valid {
		s.Price = &price.Int64
	}
	if bw.Valid {
		v := int(bw.Int64)
		s.Bandwidth = &v
	}
	if cpu.Valid {
		v := int(cpu.Int64)
		s.CPUCores = &v
	}
	return &s, nil
}

// ServerList 分页查询服务器，search 匹配名称、IP、域名、供应商、备注。
func ServerList(ctx context.Context, db *sql.DB, search string, page, size int) ([]*Server, int, error) {
	where, args := "1=1", []any{}
	if search != "" {
		where = `(name LIKE ? OR ip LIKE ? OR supplier LIKE ? OR remark LIKE ?)`
		for range 4 {
			args = append(args, "%"+search+"%")
		}
	}
	var total int
	if err := db.QueryRowContext(ctx, "SELECT count(*) FROM server WHERE "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, `SELECT `+serverCols+`, (SELECT count(*) FROM node_server ns WHERE ns.server_id = server.id)
		FROM server WHERE `+where+` ORDER BY id DESC LIMIT ? OFFSET ?`, append(args, size, (page-1)*size)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var list []*Server
	for rows.Next() {
		var s Server
		var price, bw, cpu sql.NullInt64
		if err := rows.Scan(&s.ID, &s.Name, &s.IP, &s.SSHPort, &s.AuthType, &s.Username, &s.HasAuth, &s.Region,
			&s.ServerType, &s.ExpireDate, &s.BandwidthDate, &s.Supplier, &price, &s.PriceMonths, &s.Multiple, &bw, &cpu,
			&s.Disabled, &s.External, &s.Remark, &s.Failover, &s.Placements); err != nil {
			return nil, 0, err
		}
		list = append(list, &s)
	}
	return list, total, rows.Err()
}

func ServerByID(ctx context.Context, db *sql.DB, id int64) (*Server, error) {
	return scanServer(db.QueryRowContext(ctx, `SELECT `+serverCols+` FROM server WHERE id = ?`, id))
}

func SetServerFailover(ctx context.Context, db *sql.DB, id int64, on bool) error {
	var exists bool
	if err := db.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM server WHERE id = ?)`, id).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return sql.ErrNoRows
	}
	_, err := db.ExecContext(ctx, `UPDATE node_server SET failover = ? WHERE server_id = ?`, on, id)
	return err
}

func nz(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func dateTime(d string) any {
	if d == "" {
		return nil
	}
	return d + " 00:00:00"
}

// SaveServer 新建（ID=0）或更新服务器。auth 为空表示不修改已保存的凭据。
func SaveServer(ctx context.Context, db *sql.DB, c *crypto.Cipher, s *Server, auth string) (int64, error) {
	var encAuth string
	if auth != "" {
		var err error
		if encAuth, err = c.Encrypt(auth); err != nil {
			return 0, err
		}
	}
	now := FormatTime(nowFunc())
	if s.ID == 0 {
		res, err := db.ExecContext(ctx, `INSERT INTO server (name, ip, ssh_port, auth_type, username, auth, region, server_type,
			expire_date, bandwidth_date, supplier, price, price_months, multiple, bandwidth, cpu_cores, disabled, external_server, remark,
			create_time, update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
			nz(s.Name), s.IP, s.SSHPort, s.AuthType, nz(s.Username), nz(encAuth), nz(s.Region), s.ServerType,
			nz(s.ExpireDate), dateTime(s.BandwidthDate), nz(s.Supplier), s.Price, s.PriceMonths, s.Multiple, s.Bandwidth, s.CPUCores,
			s.Disabled, s.External, nz(s.Remark), now, now)
		if err != nil {
			return 0, err
		}
		return res.LastInsertId()
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	if s.External || s.ServerType == NodeLanding {
		// 部署位不再自动同步：服务器改为落地或外部前，上面的代理节点部署位须先手动删除
		rows, err := tx.QueryContext(ctx, `SELECT n.name FROM node_server ns JOIN node n ON n.id = ns.node_id
			WHERE ns.server_id = ? AND ns.deployed <> 2 AND coalesce(n.type,0) <> ? ORDER BY n.id`, s.ID, NodeLanding)
		if err != nil {
			return 0, err
		}
		var names []string
		for rows.Next() {
			var name string
			if err := rows.Scan(&name); err != nil {
				rows.Close()
				return 0, err
			}
			names = append(names, name)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return 0, err
		}
		if len(names) > 0 {
			return 0, invalid("服务器上还有代理节点的部署位，不能改为落地或外部服务器，请先在节点中删除：%s", strings.Join(names, "、"))
		}
	}
	res, err := tx.ExecContext(ctx, `UPDATE server SET name=?, ip=?, ssh_port=?, auth_type=?, username=?, auth=coalesce(?, auth), region=?,
		server_type=?, expire_date=?, bandwidth_date=?, supplier=?, price=?, price_months=?, multiple=?, bandwidth=?, cpu_cores=?, disabled=?,
		external_server=?, remark=?, update_time=? WHERE id=?`,
		nz(s.Name), s.IP, s.SSHPort, s.AuthType, nz(s.Username), nz(encAuth), nz(s.Region), s.ServerType,
		nz(s.ExpireDate), dateTime(s.BandwidthDate), nz(s.Supplier), s.Price, s.PriceMonths, s.Multiple, s.Bandwidth, s.CPUCores,
		s.Disabled, s.External, nz(s.Remark), now, s.ID)
	if err != nil {
		return 0, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return 0, sql.ErrNoRows
	}
	return s.ID, tx.Commit()
}

// DeleteServer 删除服务器：存在已部署的部署位时拒绝；其余部署位（待部署/待删除）随服务器一起删除。
func DeleteServer(ctx context.Context, db *sql.DB, id int64) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var deployed int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM node_server ns WHERE ns.server_id = ? AND (ns.deployed IN (1, 2) OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = ns.id))`, id).Scan(&deployed); err != nil {
		return err
	}
	if deployed > 0 {
		return fmt.Errorf("%w：服务器上还有 %d 个部署过的节点，需先在节点上移除并完成部署", ErrConflict, deployed)
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM node_server WHERE server_id = ?`, id); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM server WHERE id = ?`, id); err != nil {
		return err
	}
	return tx.Commit()
}
