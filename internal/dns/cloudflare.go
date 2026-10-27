// Package dns 封装 Cloudflare DNS API；只做 HTTP 调用，不碰数据库。
package dns

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

const DefaultBaseURL = "https://api.cloudflare.com/client/v4"

// OutboundDisabled 由 AIROPSCAT_OUTBOUND_DISABLED=1 打开，本地挂生产库时拒绝调用真实 DNS。
var OutboundDisabled bool

type Client struct {
	BaseURL string // 测试注入；生产为空，使用固定的 Cloudflare 地址
	Token   string
	HTTP    *http.Client
}

// Record 是服务商侧记录；Raw 保留完整原文。
type Record struct {
	ID       string
	Name     string
	Type     string
	Content  string
	TTL      *int
	Proxied  *bool
	Priority *int
	Comment  string
	Raw      string
}

// Change 是批量推送中的一条记录。
type Change struct {
	ID      string // patch/delete 必填
	Type    string
	Name    string
	Content string
	TTL     int
	Proxied bool
	Comment string
}

type Batch struct{ Deletes, Patches, Posts []Change }

func (c *Client) base() string {
	if c.BaseURL != "" {
		return c.BaseURL
	}
	return DefaultBaseURL
}

func (c *Client) do(ctx context.Context, method, path string, body any) (json.RawMessage, json.RawMessage, error) {
	if OutboundDisabled {
		return nil, nil, errors.New("已禁用外发连接（AIROPSCAT_OUTBOUND_DISABLED=1）")
	}
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base()+path, rd)
	if err != nil {
		return nil, nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	hc := c.HTTP
	if hc == nil {
		hc = &http.Client{Timeout: 30 * time.Second}
	}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, nil, fmt.Errorf("连接 Cloudflare 失败: %w", errors.Unwrap(err))
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return nil, nil, err
	}
	var env struct {
		Success    bool                       `json:"success"`
		Errors     []struct{ Message string } `json:"errors"`
		Result     json.RawMessage            `json:"result"`
		ResultInfo json.RawMessage            `json:"result_info"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, nil, fmt.Errorf("Cloudflare 返回非 JSON（HTTP %d）", resp.StatusCode)
	}
	if !env.Success {
		msg := fmt.Sprintf("Cloudflare 请求失败（HTTP %d）", resp.StatusCode)
		if len(env.Errors) > 0 && env.Errors[0].Message != "" {
			msg = env.Errors[0].Message
		}
		return nil, nil, errors.New(msg)
	}
	return env.Result, env.ResultInfo, nil
}

// Verify 校验 Token 有效（不带 Zone）。
func (c *Client) Verify(ctx context.Context) error {
	_, _, err := c.do(ctx, "GET", "/user/tokens/verify", nil)
	return err
}

// List 拉取 Zone 下全部记录（每页 100，翻完所有页）。
func (c *Client) List(ctx context.Context, zoneID string) ([]Record, error) {
	var out []Record
	for page, total := 1, 1; page <= total; page++ {
		res, info, err := c.do(ctx, "GET", fmt.Sprintf("/zones/%s/dns_records?page=%d&per_page=100", url.PathEscape(zoneID), page), nil)
		if err != nil {
			return nil, err
		}
		var pi struct {
			TotalPages int `json:"total_pages"`
		}
		json.Unmarshal(info, &pi)
		if pi.TotalPages > total {
			total = pi.TotalPages
		}
		var items []json.RawMessage
		if err := json.Unmarshal(res, &items); err != nil {
			return nil, err
		}
		for _, it := range items {
			var v struct {
				ID       string `json:"id"`
				Name     string `json:"name"`
				Type     string `json:"type"`
				Content  string `json:"content"`
				TTL      *int   `json:"ttl"`
				Proxied  *bool  `json:"proxied"`
				Priority *int   `json:"priority"`
				Comment  string `json:"comment"`
			}
			if err := json.Unmarshal(it, &v); err != nil {
				return nil, err
			}
			out = append(out, Record{v.ID, v.Name, v.Type, v.Content, v.TTL, v.Proxied, v.Priority, v.Comment, string(it)})
		}
	}
	return out, nil
}

func payload(ch Change) map[string]any {
	p := map[string]any{"type": ch.Type, "name": ch.Name, "content": ch.Content, "ttl": ch.TTL, "proxied": ch.Proxied}
	if ch.Comment != "" {
		p["comment"] = ch.Comment
	}
	return p
}

// Push 一次批量提交（Cloudflare batch 为原子操作），返回 Posts 按提交顺序对应的新记录 ID。
func (c *Client) Push(ctx context.Context, zoneID string, b Batch) ([]string, error) {
	body := map[string]any{}
	if len(b.Deletes) > 0 {
		var l []map[string]any
		for _, d := range b.Deletes {
			l = append(l, map[string]any{"id": d.ID})
		}
		body["deletes"] = l
	}
	if len(b.Patches) > 0 {
		var l []map[string]any
		for _, d := range b.Patches {
			p := payload(d)
			p["id"] = d.ID
			l = append(l, p)
		}
		body["patches"] = l
	}
	if len(b.Posts) > 0 {
		var l []map[string]any
		for _, d := range b.Posts {
			l = append(l, payload(d))
		}
		body["posts"] = l
	}
	if len(body) == 0 {
		return nil, errors.New("没有可推送的变更")
	}
	res, _, err := c.do(ctx, "POST", fmt.Sprintf("/zones/%s/dns_records/batch", url.PathEscape(zoneID)), body)
	if err != nil {
		return nil, err
	}
	var r struct {
		Posts []struct{ ID string } `json:"posts"`
	}
	json.Unmarshal(res, &r)
	if len(r.Posts) != len(b.Posts) {
		return nil, fmt.Errorf("Cloudflare 返回的新建记录数 %d 与提交数 %d 不符", len(r.Posts), len(b.Posts))
	}
	ids := make([]string, len(r.Posts))
	for i, p := range r.Posts {
		ids[i] = p.ID
	}
	return ids, nil
}
