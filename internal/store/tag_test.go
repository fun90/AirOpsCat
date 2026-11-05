package store

import (
	"errors"
	"fmt"
	"testing"
)

func TestTagListLiteralSearchAndPagination(t *testing.T) {
	db, ctx := testDB(t)
	for _, name := range []string{"rate%", "rateX", "tag_", "tagX", `path\name`, "plain"} {
		mustTag(t, db, name)
	}
	for _, search := range []string{"%", "_", `\`} {
		t.Run(search, func(t *testing.T) {
			list, total, err := TagList(ctx, db, search, 1, 20)
			if err != nil || total != 1 || len(list) != 1 {
				t.Fatalf("特殊字符应按字面搜索: total=%d list=%v err=%v", total, list, err)
			}
		})
	}
	for i := range 100 {
		mustTag(t, db, fmt.Sprintf("extra-%03d", i))
	}
	for _, tc := range []struct {
		page, size, want int
	}{
		{1, 1000, 100},
		{0, 0, 10},
		{-1, -1, 10},
		{2, 100, 6},
	} {
		list, total, err := TagList(ctx, db, "", tc.page, tc.size)
		if err != nil || total != 106 || len(list) != tc.want {
			t.Errorf("page=%d size=%d: total=%d len=%d err=%v", tc.page, tc.size, total, len(list), err)
		}
	}
}

func TestDeleteTagKeepsAuthorizationOnFailure(t *testing.T) {
	for _, queryFailure := range []bool{false, true} {
		t.Run(fmt.Sprint(queryFailure), func(t *testing.T) {
			db, ctx := testDB(t)
			tag := mustTag(t, db, "in-use")
			node := deployedNode(t, db, "tag-node", tag)
			if queryFailure {
				// 故障注入：占用检查依赖的表暂不可用，不能继续执行级联删除。
				mustExec(t, db, `ALTER TABLE account_tag RENAME TO unavailable_account_tag`)
			}
			err := DeleteTag(ctx, db, tag)
			if err == nil {
				t.Error("占用检查失败时不应删除标签")
			} else if !queryFailure && !errors.Is(err, ErrConflict) {
				t.Errorf("在用标签应返回冲突: %v", err)
			} else if queryFailure && errors.Is(err, ErrConflict) {
				t.Errorf("查询失败应向上传播，不能伪装成业务冲突: %v", err)
			}
			got, err := TagByID(ctx, db, tag)
			if err != nil || got == nil {
				t.Errorf("标签应保留: %v %v", got, err)
			}
			var count int
			if err := db.QueryRow(`SELECT count(*) FROM node_tag WHERE node_id=? AND tag_id=?`, node, tag).Scan(&count); err != nil || count != 1 {
				t.Errorf("节点授权应保留: count=%d err=%v", count, err)
			}
		})
	}
}
