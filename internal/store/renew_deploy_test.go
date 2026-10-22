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
