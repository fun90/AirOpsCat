package store

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"testing"
)

func TestCaptureAndRecordDeploymentSnapshots(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	nid := addNode(t, db, "n", "vless", 0, 443, `{"type":"vless","tls":{"enabled":true}}`, nil)
	pid := place(t, db, nid, sid, 0, 0)
	capture := func() *CapturedDeployment {
		t.Helper()
		c, err := CaptureDeployment(ctx, db, sid, LoadOptions{})
		if err != nil {
			t.Fatal(err)
		}
		return c
	}
	record := func(c *CapturedDeployment, success bool) {
		t.Helper()
		if err := RecordDeploymentSnapshots(ctx, db, sid, "config", success, "result", c.Revision, c.Snapshots); err != nil {
			t.Fatal(err)
		}
	}
	read := func() (DeploymentSnapshot, string, int) {
		t.Helper()
		var raw, hash string
		var version int
		if err := db.QueryRow(`SELECT snapshot_json,snapshot_hash,version FROM node_deployment WHERE node_server_id=?`, pid).Scan(&raw, &hash, &version); err != nil {
			t.Fatal(err)
		}
		sum := sha256.Sum256([]byte(raw))
		if hash != hex.EncodeToString(sum[:]) {
			t.Fatal("快照哈希不匹配")
		}
		var s DeploymentSnapshot
		if err := json.Unmarshal([]byte(raw), &s); err != nil {
			t.Fatal(err)
		}
		return s, raw, version
	}
	c := capture()
	if len(c.Snapshots) != 1 || c.Snapshots[0].NodeServerID != pid || c.Snapshots[0].Port != c.Input.Nodes[0].Port {
		t.Fatalf("捕获不一致: %+v", c)
	}
	record(c, false)
	var n int
	db.QueryRow(`SELECT count(*) FROM node_deployment`).Scan(&n)
	if n != 0 {
		t.Fatal("失败创建快照")
	}
	record(c, true)
	s, raw, v := read()
	if s.Port != 443 || v != 1 {
		t.Fatalf("首次快照: %s v%d", raw, v)
	}
	var shape map[string]any
	json.Unmarshal([]byte(raw), &shape)
	if _, ok := shape["inbound"].(map[string]any); !ok {
		t.Fatal("inbound 不是对象")
	}
	if _, ok := shape["nodeServerId"]; !ok {
		t.Fatal("快照没有 camelCase 键")
	}
	// 捕获后编辑：仍记录下发的旧输入，新的编辑不能被确认。
	mustExec(t, db, `UPDATE node_server SET deployed=0 WHERE id=?`, pid)
	c = capture()
	mustExec(t, db, `UPDATE node SET port=8443 WHERE id=?`, nid)
	record(c, true)
	s, _, v = read()
	if s.Port != 443 || v != 2 {
		t.Fatalf("必须保存旧输入: %+v v%d", s, v)
	}
	db.QueryRow(`SELECT deployed FROM node_server WHERE id=?`, pid).Scan(&n)
	if n != 0 {
		t.Fatal("冲突错误确认部署位")
	}
	record(capture(), false)
	s, _, v = read()
	if s.Port != 443 || v != 2 {
		t.Fatal("失败覆盖了快照")
	}
	// 禁用节点成功下发后写入 disabled，仍保持待部署。
	mustExec(t, db, `UPDATE node SET disabled=1 WHERE id=?`, nid)
	record(capture(), true)
	s, _, v = read()
	if !s.Disabled || s.Port != 8443 || v != 3 {
		t.Fatalf("禁用快照错误: %+v", s)
	}
	db.QueryRow(`SELECT deployed FROM node_server WHERE id=?`, pid).Scan(&n)
	if n != 0 {
		t.Fatal("禁用节点不应确认部署")
	}
	// 删除成功级联移除旧快照。
	mustExec(t, db, `UPDATE node_server SET deployed=2 WHERE id=?`, pid)
	c = capture()
	if len(c.Snapshots) != 0 || len(c.Input.Nodes) != 0 {
		t.Fatal("待删除节点进入配置")
	}
	record(c, true)
	db.QueryRow(`SELECT count(*) FROM node_deployment`).Scan(&n)
	if n != 0 {
		t.Fatal("删除后快照残留")
	}
}

func TestSnapshotSkipsRemovedPlacement(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	nid := addNode(t, db, "n", "vless", 0, 443, `{}`, nil)
	pid := place(t, db, nid, sid, 0, 0)
	c, err := CaptureDeployment(ctx, db, sid, LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `DELETE FROM node_server WHERE id=?`, pid)
	if err := RecordDeploymentSnapshots(ctx, db, sid, "config", true, "ok", c.Revision, c.Snapshots); err != nil {
		t.Fatal(err)
	}
	var n int
	db.QueryRow(`SELECT count(*) FROM node_deployment`).Scan(&n)
	if n != 0 {
		t.Fatal("重新创建已删除部署位快照")
	}
}
