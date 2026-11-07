package web

import (
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

// accountBatchDeploy 部署所选账户可用节点所在的服务器（与单个账户「部署账户」同口径）。
func (s *Server) accountBatchDeploy(w http.ResponseWriter, r *http.Request) {
	var accounts []int64
	if !postIDs(w, r, "账户 ID 不正确", idField{"account_ids", &accounts}) {
		return
	}
	seen := map[int64]bool{}
	var servers []int64
	for _, id := range accounts {
		ids, err := store.AccountDeployServers(r.Context(), s.DB, id)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		for _, sid := range ids {
			if !seen[sid] {
				seen[sid] = true
				servers = append(servers, sid)
			}
		}
	}
	s.runDeploy(w, r, "/admin/accounts", servers, "所选账户没有可用节点，无需部署")
}

// accountBatchTags 批量追加、移除或替换标签；有变化的账户相关节点会置为待部署。
func (s *Server) accountBatchTags(w http.ResponseWriter, r *http.Request) {
	var accounts, tags []int64
	if !postIDs(w, r, "账户或标签 ID 不正确", idField{"account_ids", &accounts}, idField{"tag_ids", &tags}) {
		return
	}
	sc := s.beginDeploy(r.Context())
	for _, id := range accounts { // 调整前的标签：移除或替换后旧标签节点也要下发移除
		sc.account(id)
	}
	updated, unchanged, err := store.BatchAccountTags(r.Context(), s.DB, accounts, tags, r.PostFormValue("mode"))
	if s.redirectError(w, r, "/admin/accounts", err, store.ErrInvalid) {
		return
	}
	msg := fmt.Sprintf("标签已更新 %d 个账户，未变化 %d 个", updated, unchanged)
	if updated > 0 {
		for _, id := range accounts {
			sc.account(id)
		}
		msg += sc.finish("批量调整账户标签", "；相关节点需重新部署")
	}
	redirectMsg(w, r, "/admin/accounts", "msg", msg)
}

// accountBatchRenew 批量续期 N 个月：每户从各自 max(原到期, 当前) 起算并月末钳制，长期有效账户跳过；
// 记账可选不记或每户记同一金额与付款方式，整体一个事务。
func (s *Server) accountBatchRenew(w http.ResponseWriter, r *http.Request) {
	var accounts []int64
	if !postIDs(w, r, "账户 ID 不正确", idField{"account_ids", &accounts}) {
		return
	}
	months, err := strconv.Atoi(r.PostFormValue("months"))
	if err != nil {
		redirectMsg(w, r, "/admin/accounts", "err", "请选择续期月数")
		return
	}
	var amount *int64
	method := ""
	if r.PostFormValue("record") == "same" {
		if amount, err = parseAmount(formValue(r, "amount")); err != nil || amount == nil {
			redirectMsg(w, r, "/admin/accounts", "err", "记账时请填写大于 0 的金额（最多两位小数）")
			return
		}
		method = formValue(r, "payment_method")
	}
	sc := s.beginDeploy(r.Context())
	for _, id := range accounts {
		sc.account(id)
	}
	renewed, skipped, err := store.BatchRenewAccounts(r.Context(), s.DB, accounts, months, amount, method)
	if s.redirectError(w, r, "/admin/accounts", err, store.ErrInvalid) {
		return
	}
	msg := fmt.Sprintf("已续期 %d 个账户（+%d 月）", renewed, months)
	if amount != nil && renewed > 0 {
		msg += fmt.Sprintf("，每户记账 %s 元", store.FormatCents(*amount))
	}
	if skipped > 0 {
		msg += fmt.Sprintf("，跳过长期有效账户 %d 个", skipped)
	}
	redirectMsg(w, r, "/admin/accounts", "msg", msg+sc.finish("批量续期账户", "；恢复有效的账户需部署后生效"))
}

// accountBatchToggle 批量启用或禁用账户，整体一个事务。
func (s *Server) accountBatchToggle(w http.ResponseWriter, r *http.Request) {
	var accounts []int64
	if !postIDs(w, r, "账户 ID 不正确", idField{"account_ids", &accounts}) {
		return
	}
	disable := r.PostFormValue("disabled") == "1"
	sc := s.beginDeploy(r.Context())
	for _, id := range accounts {
		sc.account(id)
	}
	changed, err := store.BatchSetAccountsDisabled(r.Context(), s.DB, accounts, disable)
	if s.redirectError(w, r, "/admin/accounts", err, store.ErrInvalid) {
		return
	}
	verb := "启用"
	if disable {
		verb = "禁用"
	}
	msg := fmt.Sprintf("已%s %d 个账户，未变化 %d 个", verb, changed, len(accounts)-changed)
	redirectMsg(w, r, "/admin/accounts", "msg", msg+sc.finish("批量"+verb+"账户", "；相关节点需重新部署"))
}

// accountBatchDelete 批量删除账户，语义同单个删除（流水保留），整体一个事务。
func (s *Server) accountBatchDelete(w http.ResponseWriter, r *http.Request) {
	var accounts []int64
	if !postIDs(w, r, "账户 ID 不正确", idField{"account_ids", &accounts}) {
		return
	}
	sc := s.beginDeploy(r.Context())
	for _, id := range accounts { // 删除后无法再查账户的服务器
		sc.account(id)
	}
	n, err := store.BatchDeleteAccounts(r.Context(), s.DB, accounts)
	if s.redirectError(w, r, "/admin/accounts", err, store.ErrInvalid) {
		return
	}
	redirectMsg(w, r, "/admin/accounts", "msg", fmt.Sprintf("已删除 %d 个账户，流水已保留", n)+sc.finish("批量删除账户", "；相关节点需重新部署"))
}

// accountPeriodQuota 本期加量：只改当期配额快照，limit-sync 按新配额自动解除或下发限速，不需要部署。
func (s *Server) accountPeriodQuota(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	back := fmt.Sprintf("/admin/accounts/%d/edit", id)
	gb, err := strconv.Atoi(formValue(r, "gb"))
	if err != nil {
		redirectMsg(w, r, back, "err", "加量需为整数 GB")
		return
	}
	quota, err := store.AddAccountPeriodQuota(r.Context(), s.DB, id, gb, time.Now())
	if notFound(w, r, err) || s.redirectError(w, r, back, err, store.ErrInvalid) {
		return
	}
	redirectMsg(w, r, back, "msg", fmt.Sprintf("本期已加量 %d GB，本期配额 %d GB；限速约 1 分钟内自动同步", gb, quota))
}

// accountPeriodReset 重置本期用量：当期上下行清零，limit-sync 自动解除超额限速，不需要部署。
func (s *Server) accountPeriodReset(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	back := fmt.Sprintf("/admin/accounts/%d/edit", id)
	used, err := store.ResetAccountPeriodUsage(r.Context(), s.DB, id, time.Now())
	if notFound(w, r, err) || s.redirectError(w, r, back, err, store.ErrInvalid) {
		return
	}
	if used == 0 {
		redirectMsg(w, r, back, "msg", "本期用量已为 0，无需重置")
		return
	}
	redirectMsg(w, r, back, "msg", fmt.Sprintf("本期用量已清零（原 %.2f GB）；限速约 1 分钟内自动同步", float64(used)/(1<<30)))
}
