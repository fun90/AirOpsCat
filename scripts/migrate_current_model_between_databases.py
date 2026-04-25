#!/usr/bin/env python3
"""
直接在 MySQL 内部把旧库数据迁移到新库。

相比“先导出 SQL，再导入”的方案，这个脚本的优势是：
1. 更快：直接在数据库服务器内执行 INSERT ... SELECT
2. 更稳：可对 JSON、布尔值、字段改名做兼容转换
3. 更省事：不用生成超大的中间 SQL 文件

当前内置兼容：
- server.external -> server.external_server
- JSON 字段：非法 JSON 自动写入 NULL
- domain_dns_record.proxied：兼容 true/false/yes/no/1/0/Y/N

用法示例：
./scripts/migrate_current_model_between_databases.py \
  --host 127.0.0.1 \
  --port 3306 \
  --user root \
  --password '你的密码' \
  --source-db 旧库名 \
  --target-db airopscat

如果目标库里已经有初始化数据，想先清空再迁移：
python3 ./migrate_current_model_between_databases.py \
    --host 127.0.0.1 \
    --port 3306 \
    --user root \
    --password '你的密码' \
    --source-db 旧库名 \
    --target-db airopscat \
    --truncate-target

如果你想先看它实际会执行什么 SQL，可以先：
python3 ./migrate_current_model_between_databases.py \
  --host 127.0.0.1 \
  --port 3306 \
  --user root \
  --password '你的密码' \
  --source-db 旧库名 \
  --target-db airopscat \
  --dry-run
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from collections import OrderedDict


def raw(column: str) -> str:
    return f"`{column}`"


def renamed(*candidates: str) -> str:
    for column in candidates:
        return f"@@COLUMN:{column}"
    raise ValueError("renamed 至少需要一个候选字段")


def json_expr(column: str) -> str:
    return f"@@JSON:{column}"


def proxied_expr(column: str) -> str:
    return f"@@PROXIED:{column}"


TABLE_MAPPINGS: "OrderedDict[str, OrderedDict[str, str]]" = OrderedDict(
    {
        "user": OrderedDict(
            {
                "id": raw("id"),
                "email": raw("email"),
                "nick_name": raw("nick_name"),
                "remark_name": raw("remark_name"),
                "password": raw("password"),
                "remark": raw("remark"),
                "role": raw("role"),
                "referrer": raw("referrer"),
                "disabled": raw("disabled"),
                "failed_attempts": raw("failed_attempts"),
                "lock_time": raw("lock_time"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "account": OrderedDict(
            {
                "id": raw("id"),
                "level": raw("level"),
                "node_multiple": raw("node_multiple"),
                "node_prefix": raw("node_prefix"),
                "from_date": raw("from_date"),
                "to_date": raw("to_date"),
                "period_type": raw("period_type"),
                "uuid": raw("uuid"),
                "account_no": raw("account_no"),
                "auth_code": raw("auth_code"),
                "max_connections": raw("max_connections"),
                "speed": raw("speed"),
                "bandwidth": raw("bandwidth"),
                "disabled": raw("disabled"),
                "remark": raw("remark"),
                "user_id": raw("user_id"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "account_online_ip": OrderedDict(
            {
                "id": raw("id"),
                "account_no": raw("account_no"),
                "client_ip": raw("client_ip"),
                "connection_id": raw("connection_id"),
                "node_ip": raw("node_ip"),
                "node_id": raw("node_id"),
                "node_tag": raw("node_tag"),
                "last_online_time": raw("last_online_time"),
                "session_start_time": raw("session_start_time"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "account_traffic_stats": OrderedDict(
            {
                "id": raw("id"),
                "user_id": raw("user_id"),
                "account_id": raw("account_id"),
                "period_start": raw("period_start"),
                "period_end": raw("period_end"),
                "upload_bytes": raw("upload_bytes"),
                "download_bytes": raw("download_bytes"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "dns_provider_config": OrderedDict(
            {
                "id": raw("id"),
                "provider_type": raw("provider_type"),
                "status": raw("status"),
                "display_name": raw("display_name"),
                "credential_json": raw("credential_json"),
                "extension_json": json_expr("extension_json"),
                "last_check_time": raw("last_check_time"),
                "last_check_status": raw("last_check_status"),
                "last_check_message": raw("last_check_message"),
                "remark": raw("remark"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "domain": OrderedDict(
            {
                "id": raw("id"),
                "expire_date": raw("expire_date"),
                "domain": raw("domain"),
                "price": raw("price"),
                "supplier": raw("supplier"),
                "dns_provider_config_id": raw("dns_provider_config_id"),
                "dns_provider_type": raw("dns_provider_type"),
                "dns_sync_status": raw("dns_sync_status"),
                "dns_last_sync_time": raw("dns_last_sync_time"),
                "dns_binding_extension_json": json_expr("dns_binding_extension_json"),
                "remark": raw("remark"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "domain_dns_record": OrderedDict(
            {
                "id": raw("id"),
                "domain_id": raw("domain_id"),
                "dns_provider_config_id": raw("dns_provider_config_id"),
                "external_record_id": raw("external_record_id"),
                "name": raw("name"),
                "full_name": raw("full_name"),
                "type": raw("type"),
                "content": raw("content"),
                "ttl": raw("ttl"),
                "proxied": proxied_expr("proxied"),
                "priority": raw("priority"),
                "status": raw("status"),
                "remark": raw("remark"),
                "biz_tags_json": json_expr("biz_tags_json"),
                "extension_json": json_expr("extension_json"),
                "raw_data": json_expr("raw_data"),
                "last_sync_time": raw("last_sync_time"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "server": OrderedDict(
            {
                "id": raw("id"),
                "ip": raw("ip"),
                "ssh_port": raw("ssh_port"),
                "auth_type": raw("auth_type"),
                "username": raw("username"),
                "auth": raw("auth"),
                "host": raw("host"),
                "name": raw("name"),
                "expire_date": raw("expire_date"),
                "bandwidth_date": raw("bandwidth_date"),
                "supplier": raw("supplier"),
                "price": raw("price"),
                "multiple": raw("multiple"),
                "bandwidth": raw("bandwidth"),
                "cpu_cores": raw("cpu_cores"),
                "disabled": raw("disabled"),
                "external_server": "@@COLUMN:external_server|external",
                "remark": raw("remark"),
                "transit_config": json_expr("transit_config"),
                "core_config": json_expr("core_config"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "server_config": OrderedDict(
            {
                "id": raw("id"),
                "server_id": raw("server_id"),
                "config": raw("config"),
                "config_type": raw("config_type"),
                "path": raw("path"),
                "description": raw("description"),
                "enabled": raw("enabled"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "server_host": OrderedDict(
            {
                "id": raw("id"),
                "server_id": raw("server_id"),
                "host": raw("host"),
                "is_primary": raw("is_primary"),
                "enabled": raw("enabled"),
                "sort": raw("sort"),
                "domain_id": raw("domain_id"),
                "remark": raw("remark"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "server_monitor_stats": OrderedDict(
            {
                "id": raw("id"),
                "server_id": raw("server_id"),
                "cpu_usage": raw("cpu_usage"),
                "memory_usage": raw("memory_usage"),
                "memory_used_bytes": raw("memory_used_bytes"),
                "memory_total_bytes": raw("memory_total_bytes"),
                "network_rx_bytes": raw("network_rx_bytes"),
                "network_tx_bytes": raw("network_tx_bytes"),
                "network_rx_increment_bytes": raw("network_rx_increment_bytes"),
                "network_tx_increment_bytes": raw("network_tx_increment_bytes"),
                "network_rx_rate_bytes": raw("network_rx_rate_bytes"),
                "network_tx_rate_bytes": raw("network_tx_rate_bytes"),
                "sample_time": raw("sample_time"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "server_traffic_stats": OrderedDict(
            {
                "id": raw("id"),
                "server_id": raw("server_id"),
                "period_start": raw("period_start"),
                "period_end": raw("period_end"),
                "upload_bytes": raw("upload_bytes"),
                "download_bytes": raw("download_bytes"),
                "monitor_upload_adjustment_bytes": raw("monitor_upload_adjustment_bytes"),
                "monitor_download_adjustment_bytes": raw("monitor_download_adjustment_bytes"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "node": OrderedDict(
            {
                "id": raw("id"),
                "server_id": raw("server_id"),
                "access_host_id": raw("access_host_id"),
                "port": raw("port"),
                "protocol": raw("protocol"),
                "core_type": raw("core_type"),
                "type": raw("type"),
                "inbound": json_expr("inbound"),
                "out_id": raw("out_id"),
                "rule": json_expr("rule"),
                "level": raw("level"),
                "deployed": raw("deployed"),
                "disabled": raw("disabled"),
                "name": raw("name"),
                "no": raw("no"),
                "node_group": raw("node_group"),
                "remark": raw("remark"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "node_deployment": OrderedDict(
            {
                "id": raw("id"),
                "node_id": raw("node_id"),
                "version": raw("version"),
                "snapshot_hash": raw("snapshot_hash"),
                "snapshot_json": json_expr("snapshot_json"),
                "server_id": raw("server_id"),
                "node_group": raw("node_group"),
                "core_type": raw("core_type"),
                "protocol": raw("protocol"),
                "type": raw("type"),
                "port": raw("port"),
                "disabled": raw("disabled"),
                "deployed_at": raw("deployed_at"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "node_deployment_history": OrderedDict(
            {
                "id": raw("id"),
                "node_id": raw("node_id"),
                "version": raw("version"),
                "snapshot_hash": raw("snapshot_hash"),
                "snapshot_json": json_expr("snapshot_json"),
                "server_id": raw("server_id"),
                "node_group": raw("node_group"),
                "core_type": raw("core_type"),
                "protocol": raw("protocol"),
                "type": raw("type"),
                "port": raw("port"),
                "disabled": raw("disabled"),
                "deployed_at": raw("deployed_at"),
                "archived_at": raw("archived_at"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "route_rule": OrderedDict(
            {
                "id": raw("id"),
                "name": raw("name"),
                "core_type": raw("core_type"),
                "rule_type": raw("rule_type"),
                "rule_value": json_expr("rule_value"),
                "outbound_node_id": raw("outbound_node_id"),
                "enabled": raw("enabled"),
                "remark": raw("remark"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "system_config": OrderedDict(
            {
                "id": raw("id"),
                "config_key": raw("config_key"),
                "config_value": raw("config_value"),
                "group_key": raw("group_key"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "tag": OrderedDict(
            {
                "id": raw("id"),
                "name": raw("name"),
                "description": raw("description"),
                "color": raw("color"),
                "disabled": raw("disabled"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "transactions": OrderedDict(
            {
                "id": raw("id"),
                "transaction_date": raw("transaction_date"),
                "amount": raw("amount"),
                "type": raw("type"),
                "business_table": raw("business_table"),
                "business_id": raw("business_id"),
                "description": raw("description"),
                "payment_method": raw("payment_method"),
                "remark": raw("remark"),
                "create_time": raw("create_time"),
                "update_time": raw("update_time"),
            }
        ),
        "account_tag": OrderedDict(
            {
                "account_id": raw("account_id"),
                "tag_id": raw("tag_id"),
            }
        ),
        "node_tag": OrderedDict(
            {
                "node_id": raw("node_id"),
                "tag_id": raw("tag_id"),
            }
        ),
        "route_rule_server": OrderedDict(
            {
                "route_rule_id": raw("route_rule_id"),
                "server_id": raw("server_id"),
            }
        ),
    }
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="直接在 MySQL 内部执行跨库迁移")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", required=True)
    parser.add_argument("--password", default="")
    parser.add_argument("--source-db", required=True, help="旧数据库名")
    parser.add_argument("--target-db", required=True, help="新数据库名")
    parser.add_argument("--mysql-bin", default="mysql")
    parser.add_argument("--truncate-target", action="store_true", help="迁移前清空目标表")
    parser.add_argument(
        "--insert-mode",
        choices=["ignore", "replace"],
        default="ignore",
        help="冲突处理方式，默认 ignore",
    )
    parser.add_argument("--dry-run", action="store_true", help="只打印 SQL，不执行")
    return parser.parse_args()


def run_mysql(args: argparse.Namespace, sql: str) -> subprocess.CompletedProcess[str]:
    cmd = [
        args.mysql_bin,
        f"--host={args.host}",
        f"--port={args.port}",
        f"--user={args.user}",
        f"--password={args.password}",
        "--default-character-set=utf8mb4",
        args.target_db,
        "-e",
        sql,
    ]
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")


def fetch_columns(args: argparse.Namespace, db_name: str, table_name: str) -> set[str]:
    sql = (
        "SELECT COLUMN_NAME "
        "FROM INFORMATION_SCHEMA.COLUMNS "
        f"WHERE TABLE_SCHEMA = '{db_name}' AND TABLE_NAME = '{table_name}'"
    )
    result = run_mysql(args, sql)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or f"读取表结构失败: {db_name}.{table_name}")
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if lines and lines[0] == "COLUMN_NAME":
        lines = lines[1:]
    return set(lines)


def resolve_column_placeholder(placeholder: str, source_columns: set[str]) -> str | None:
    candidates = placeholder.removeprefix("@@COLUMN:").split("|")
    for candidate in candidates:
        if candidate in source_columns:
            return f"`{candidate}`"
    return None


def resolve_json_placeholder(placeholder: str, source_columns: set[str]) -> str | None:
    column = placeholder.removeprefix("@@JSON:")
    if column not in source_columns:
        return None
    return (
        f"CASE "
        f"WHEN `{column}` IS NULL OR TRIM(`{column}`) = '' THEN NULL "
        f"WHEN JSON_VALID(`{column}`) THEN `{column}` "
        f"ELSE NULL END"
    )


def resolve_proxied_placeholder(placeholder: str, source_columns: set[str]) -> str | None:
    column = placeholder.removeprefix("@@PROXIED:")
    if column not in source_columns:
        return None
    return (
        f"CASE "
        f"WHEN `{column}` IS NULL OR TRIM(CAST(`{column}` AS CHAR)) = '' THEN NULL "
        f"WHEN LOWER(TRIM(CAST(`{column}` AS CHAR))) IN ('1', 'true', 'yes', 'y', 'on') THEN TRUE "
        f"WHEN LOWER(TRIM(CAST(`{column}` AS CHAR))) IN ('0', 'false', 'no', 'n', 'off') THEN FALSE "
        f"ELSE NULL END"
    )


def resolve_expression(expr: str, source_columns: set[str]) -> str | None:
    if expr.startswith("@@COLUMN:"):
        return resolve_column_placeholder(expr, source_columns)
    if expr.startswith("@@JSON:"):
        return resolve_json_placeholder(expr, source_columns)
    if expr.startswith("@@PROXIED:"):
        return resolve_proxied_placeholder(expr, source_columns)
    column = expr.strip("`")
    if column in source_columns:
        return expr
    return None


def build_table_sql(args: argparse.Namespace, table_name: str) -> str:
    source_columns = fetch_columns(args, args.source_db, table_name)
    target_columns = fetch_columns(args, args.target_db, table_name)
    if not source_columns:
        return f"-- 跳过 `{table_name}`：源表不存在\n"
    if not target_columns:
        return f"-- 跳过 `{table_name}`：目标表不存在\n"

    selected_targets: list[str] = []
    selected_exprs: list[str] = []
    for target_column, source_expr in TABLE_MAPPINGS[table_name].items():
        if target_column not in target_columns:
            continue
        resolved = resolve_expression(source_expr, source_columns)
        if resolved is None:
            continue
        selected_targets.append(f"`{target_column}`")
        selected_exprs.append(resolved)

    if not selected_targets:
        return f"-- 跳过 `{table_name}`：无可迁移字段\n"

    statements = [f"-- 迁移 `{table_name}`"]
    if args.truncate_target:
        statements.append(f"TRUNCATE TABLE `{args.target_db}`.`{table_name}`;")

    insert_keyword = "REPLACE INTO" if args.insert_mode == "replace" else "INSERT IGNORE INTO"
    statements.append(
        f"{insert_keyword} `{args.target_db}`.`{table_name}` "
        f"({', '.join(selected_targets)}) "
        f"SELECT {', '.join(selected_exprs)} "
        f"FROM `{args.source_db}`.`{table_name}`;"
    )
    statements.append("")
    return "\n".join(statements)


def build_full_sql(args: argparse.Namespace) -> str:
    statements = [
        "SET NAMES utf8mb4;",
        "SET FOREIGN_KEY_CHECKS = 0;",
        "",
    ]
    for table_name in TABLE_MAPPINGS.keys():
        statements.append(build_table_sql(args, table_name))
    statements.extend(
        [
            "SET FOREIGN_KEY_CHECKS = 1;",
            "",
        ]
    )
    return "\n".join(statements)


def main() -> int:
    args = parse_args()
    sql = build_full_sql(args)

    if args.dry_run:
        print(sql)
        return 0

    result = run_mysql(args, sql)
    if result.stdout.strip():
        print(result.stdout)
    if result.returncode != 0:
        if result.stderr.strip():
            print(result.stderr, file=sys.stderr)
        return result.returncode

    print("迁移完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
