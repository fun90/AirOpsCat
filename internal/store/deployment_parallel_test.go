package store

import "testing"

func TestDeploymentConfirmationAcrossServers(t *testing.T) {
	for _, deleting := range []bool{false, true} {
		name := "pending"
		if deleting {
			name = "delete"
		}
		t.Run(name, func(t *testing.T) {
			db, ctx := testDB(t)
			tag := mustTag(t, db, "parallel")
			nodes := []int64{deployedNode(t, db, "first", tag), deployedNode(t, db, "second", tag)}
			servers := make([]int64, 2)
			captures := make([]*CapturedDeployment, 2)
			for i, nid := range nodes {
				mustExec(t, db, `UPDATE node SET inbound='{"type":"vless"}' WHERE id=?`, nid)
				if deleting {
					if _, err := DeleteNode(ctx, db, nid); err != nil {
						t.Fatal(err)
					}
				} else {
					mustExec(t, db, `UPDATE node_server SET deployed=0 WHERE node_id=?`, nid)
				}
				if err := db.QueryRow(`SELECT server_id FROM node_server WHERE node_id=?`, nid).Scan(&servers[i]); err != nil {
					t.Fatal(err)
				}
			}
			for i, sid := range servers {
				var err error
				captures[i], err = CaptureDeployment(ctx, db, sid, LoadOptions{})
				if err != nil {
					t.Fatal(err)
				}
			}
			for i, sid := range servers {
				c := captures[i]
				if err := RecordDeploymentSnapshots(ctx, db, sid, "hash", true, "ok", c.Revision, c.Snapshots); err != nil {
					t.Fatal(err)
				}
			}
			for _, nid := range nodes {
				if deleting {
					var count int
					if err := db.QueryRow(`SELECT count(*) FROM node WHERE id=?`, nid).Scan(&count); err != nil {
						t.Fatal(err)
					}
					if count != 0 {
						t.Fatalf("节点 %d 部署后仍未删除", nid)
					}
				} else if got := nodeDeployed(t, db, nid); got != Deployed {
					t.Fatalf("节点 %d 仍待部署: %d", nid, got)
				}
			}
		})
	}
}
