package store

import (
	"context"
	"database/sql"
	"strings"
)

// SettingDefaults 仅包含运行时实际读取的配置；端口、目录和采集范围由环境变量控制。
var SettingDefaults = map[string]struct{ Group, Value string }{
	"airopscat.subscription.url": {"open", "http://localhost:8080/subscribe"},
	"airopscat.docs.url":         {"open", ""}, "airopscat.domain": {"open", ""},
	"airopscat.api.token": {"open", ""}, "airopscat.apple.id": {"open", ""}, "airopscat.apple.pwd": {"open", ""},
	"airopscat.bark.url": {"bark", ""}, "airopscat.bark.device-key": {"bark", ""}, "airopscat.bark.encrypt-enabled": {"bark", "false"}, "airopscat.bark.encrypt-key": {"bark", ""}, "airopscat.bark.encrypt-iv": {"bark", ""}, "airopscat.bark.default-group": {"bark", "AirOpsCat"}, "airopscat.bark.default-sound": {"bark", "healthnotification"}, "airopscat.bark.default-icon": {"bark", ""},
	"airopscat.ratelimit.enabled": {"account", "false"}, "airopscat.account.traffic-over-quota.download-mbps": {"account", "1"}, "airopscat.account.traffic-over-quota.upload-mbps": {"account", "1"},
	"airopscat.online.check-minutes": {"monitor", "10"}, "airopscat.account.guard.ttl-seconds": {"monitor", "30"}, "airopscat.account.guard.ip-overlap-seconds": {"monitor", "300"},
	"airopscat.account.connection-limit.alert.enabled": {"monitor", "true"}, "airopscat.account.connection-limit.alert.consecutive-times": {"monitor", "3"}, "airopscat.account.connection-limit.alert.min-interval-minutes": {"monitor", "60"}, "airopscat.account.connection-limit.alert.recovery-notify-enabled": {"monitor", "false"},
	"airopscat.server.monitor.enabled": {"monitor", "true"}, "airopscat.server.monitor.max-parallel-servers": {"monitor", "10"}, "airopscat.server.monitor.retention-days": {"history", "30"}, "airopscat.server.monitor.cleanup.batch-size": {"history", "1000"},
	"airopscat.server.monitor.alert.cpu-threshold": {"monitor", "0.9"}, "airopscat.server.monitor.alert.memory-threshold": {"monitor", "0.95"}, "airopscat.server.monitor.alert.disk-threshold": {"monitor", "0.9"}, "airopscat.server.monitor.alert.traffic-threshold": {"monitor", "0.85"}, "airopscat.server.monitor.alert.continuous-minutes": {"monitor", "30"}, "airopscat.server.monitor.alert.min-interval-minutes": {"monitor", "60"},
	"airopscat.server.expiring.alert.min-interval-hours": {"monitor", "23"}, "airopscat.domain.expiring.alert.min-interval-hours": {"monitor", "23"}, "airopscat.account.expiring.alert.min-interval-hours": {"monitor", "23"},
	"airopscat.config.templates.dir": {"template", ""}, "airopscat.install.scripts.dir": {"template", ""},
	"airopscat.account.traffic.retention-days": {"history", "30"}, "airopscat.system-log.retention-days": {"history", "90"},
	"airopscat.backup.retention-days":     {"backup", "15"},
	"airopscat.sing-box.clash-api.secret": {"monitor", ""},
	ProbeServersKey:                       {"monitor", ""},
}

// ProbeServersKey 是国内探测服务器 ID 列表（逗号分隔），节点编辑页经其 SSH 拨测部署位端口。
const ProbeServersKey = "airopscat.probe.server-ids"

// EnsureSettings 补齐缺失的有效配置，保留已有值；不再消费的旧键保留于数据库但不展示/修改。
func EnsureSettings(ctx context.Context, db *sql.DB) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		for k, d := range SettingDefaults {
			if _, err := tx.ExecContext(ctx, `INSERT INTO system_config(config_key,config_value,group_key)VALUES(?,?,?) ON CONFLICT(config_key) DO NOTHING`, k, d.Value, d.Group); err != nil {
				return err
			}
		}
		return nil
	})
}
func ActiveSetting(k string) bool { _, ok := SettingDefaults[strings.ToLower(k)]; return ok }
