package store

// 部署位状态的共用 SQL 谓词，查询须以 ns 作为 node_server 别名、n 作为 node 别名。

// placementRunningSQL 判断部署位当前是否在服务器上运行：待删除视为已移除；已部署，
// 或虽待部署但最近一次成功下发的快照为启用（服务器仍按旧配置运行）。
// 任何账户改动都会把部署位置为待部署，不能据此停止采流量、限速、探测或忽略 guard 上报。
const placementRunningSQL = `ns.deployed<>2 AND (ns.deployed=1 OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id=ns.id AND coalesce(d.disabled,0)=0))`

// placementNeedsDeploySQL 判断部署位是否需要部署：待删除总是需要；待部署中排除节点已禁用、
// 且最新快照为禁用或从无快照的部署位——它们在服务器上与数据库一致，部署后仍保持待部署（决策 20 第 5 条）。
const placementNeedsDeploySQL = `(ns.deployed=2 OR (ns.deployed=0 AND NOT (n.disabled=1 AND coalesce((SELECT d.disabled FROM node_deployment d WHERE d.node_server_id=ns.id),1)<>0)))`

// placementSyncedSQL 判断部署位与服务器一致：已部署，或待部署但不需要部署（节点已禁用且服务器上没有运行）。
// 节点列表的「已部署 / 待部署」口径以此与 placementNeedsDeploySQL 互补，禁用节点不再永远显示待部署。
const placementSyncedSQL = `(ns.deployed<>2 AND NOT ` + placementNeedsDeploySQL + `)`
