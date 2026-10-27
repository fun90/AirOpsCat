package dns

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestListPaginatesAndAuth(t *testing.T) {
	var auth []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		auth = append(auth, r.Header.Get("Authorization"))
		page := r.URL.Query().Get("page")
		fmt.Fprintf(w, `{"success":true,"result":[{"id":"r%s","name":"a.example.com","type":"A","content":"1.1.1.1","ttl":1,"proxied":false}],"result_info":{"total_pages":2}}`, page)
	}))
	defer srv.Close()
	c := &Client{BaseURL: srv.URL, Token: "tok"}
	got, err := c.List(context.Background(), "zone")
	if err != nil || len(got) != 2 || got[1].ID != "r2" || got[0].Raw == "" {
		t.Fatalf("%v %+v", err, got)
	}
	if auth[0] != "Bearer tok" {
		t.Fatal(auth)
	}
}

func TestErrorsAreSafe(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(403)
		fmt.Fprint(w, `{"success":false,"errors":[{"message":"Invalid token"}]}`)
	}))
	defer srv.Close()
	err := (&Client{BaseURL: srv.URL, Token: "secret-token"}).Verify(context.Background())
	if err == nil || err.Error() != "Invalid token" || strings.Contains(err.Error(), "secret") {
		t.Fatal(err)
	}
}

func TestPushBatchBody(t *testing.T) {
	var body map[string][]map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/zones/z1/dns_records/batch") {
			t.Error(r.URL.Path)
		}
		json.NewDecoder(r.Body).Decode(&body)
		fmt.Fprint(w, `{"success":true,"result":{"posts":[{"id":"n1"},{"id":"n2"}]}}`)
	}))
	defer srv.Close()
	ids, err := (&Client{BaseURL: srv.URL, Token: "t"}).Push(context.Background(), "z1", Batch{
		Deletes: []Change{{ID: "d"}}, Patches: []Change{{ID: "p", Type: "A", Name: "x", Content: "1.2.3.4", TTL: 60}},
		Posts: []Change{{Type: "A", Name: "a"}, {Type: "A", Name: "b"}}})
	if err != nil || len(ids) != 2 || ids[1] != "n2" || body["deletes"][0]["id"] != "d" || body["patches"][0]["id"] != "p" {
		t.Fatalf("%v %v %v", err, ids, body)
	}
	if _, err := (&Client{BaseURL: srv.URL}).Push(context.Background(), "z", Batch{}); err == nil {
		t.Fatal("空批次应拒绝")
	}
}

func TestOutboundDisabledBlocksRequest(t *testing.T) {
	hit := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hit = true }))
	defer srv.Close()
	OutboundDisabled = true
	defer func() { OutboundDisabled = false }()
	if err := (&Client{BaseURL: srv.URL, Token: "tok"}).Verify(context.Background()); err == nil || hit {
		t.Fatalf("应拒绝外发：err=%v hit=%v", err, hit)
	}
}
