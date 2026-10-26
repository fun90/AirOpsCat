package store

import (
	"testing"
	"time"
)

func TestRenewActiveAccountKeepsDeployment(t *testing.T) {
	for _, disabled := range []bool{false, true} {
		t.Run(map[bool]string{false: "active", true: "disabled"}[disabled], func(t *testing.T) {
			db, ctx := testDB(t)
			tag := mustTag(t, db, "renew")
			nid := deployedNode(t, db, "renew", tag)
			expires := time.Now().AddDate(0, 1, 0)
			a := onboard(t, db, "renew@example.test", func(in *OnboardInput) {
				in.Account.TagIDs = []int64{tag}
				in.Account.ToDate = &expires
				in.Account.Disabled = disabled
			})
			resetDeployed(db, nid)
			if err := RenewAccount(ctx, db, a.AccountID, expires.AddDate(0, 1, 0), nil, ""); err != nil {
				t.Fatal(err)
			}
			want := Deployed
			if disabled {
				want = PendingDeploy
			}
			if got := nodeDeployed(t, db, nid); got != want {
				t.Fatalf("deployed=%d want=%d", got, want)
			}
		})
	}
}

func TestEditToDateKeepsDeploymentUnlessValidityChanges(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "edit")
	nid := deployedNode(t, db, "edit", tag)
	expires := time.Now().AddDate(0, 1, 0)
	r := onboard(t, db, "edit@example.test", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag}
		in.Account.ToDate = &expires
	})
	a, _ := AccountByID(ctx, db, r.AccountID)
	from := time.Now().AddDate(0, -1, 0)
	a.FromDate = &from
	for _, c := range []struct {
		to   time.Time
		want int
	}{
		{expires.AddDate(0, 1, 0), Deployed},         // 有效期内延长
		{time.Now().Add(-time.Hour), PendingDeploy},  // 改为已过期
		{time.Now().AddDate(0, 2, 0), PendingDeploy}, // 恢复有效
	} {
		resetDeployed(db, nid)
		edit := *a
		edit.ToDate = &c.to
		if err := UpdateAccount(ctx, db, a.ID, &edit); err != nil {
			t.Fatal(err)
		}
		if got := nodeDeployed(t, db, nid); got != c.want {
			t.Fatalf("to=%v deployed=%d want=%d", c.to, got, c.want)
		}
	}
}
