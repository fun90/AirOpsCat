package traffic

import (
	"context"
	"errors"
	"math"
	"net"
	"reflect"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/traffic/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/grpc/test/bufconn"
)

type statsServer struct {
	pb.UnimplementedStatsServiceServer
	query func(context.Context, *pb.QueryStatsRequest) (*pb.QueryStatsResponse, error)
}

func (s statsServer) QueryStats(ctx context.Context, req *pb.QueryStatsRequest) (*pb.QueryStatsResponse, error) {
	return s.query(ctx, req)
}

func serveStats(t *testing.T, query func(context.Context, *pb.QueryStatsRequest) (*pb.QueryStatsResponse, error)) func(context.Context) (net.Conn, error) {
	t.Helper()
	listener := bufconn.Listen(1024 * 1024)
	server := grpc.NewServer()
	pb.RegisterStatsServiceServer(server, statsServer{query: query})
	go server.Serve(listener)
	t.Cleanup(func() { server.Stop(); listener.Close() })
	return listener.DialContext
}

func TestQueryCumulativeAndFilter(t *testing.T) {
	var calls atomic.Int32
	factory := serveStats(t, func(_ context.Context, req *pb.QueryStatsRequest) (*pb.QueryStatsResponse, error) {
		calls.Add(1)
		if req.GetReset_() || !req.Regexp || !reflect.DeepEqual(req.Patterns, []string{"user>>>.*>>>traffic>>>.*"}) {
			t.Errorf("查询参数错误: %v", req)
		}
		return &pb.QueryStatsResponse{Stat: []*pb.Stat{
			{Name: "user>>>A>>>traffic>>>uplink", Value: 7},
			{Name: "user>>>A>>>traffic>>>uplink", Value: 3},
			{Name: "user>>>A>>>traffic>>>downlink", Value: 20},
			{Name: "user>>>zero>>>traffic>>>downlink"},
			{Name: "inbound>>>A>>>traffic>>>uplink", Value: -1},
			{Name: "user>>>>>>traffic>>>uplink", Value: -1},
			{Name: "user>>>A>>>other>>>uplink", Value: -1},
			{Name: "user>>>A>>>traffic>>>unknown", Value: -1},
			{Name: "user>>>A>>>traffic>>>uplink>>>extra", Value: -1},
			{Name: "user>>>short", Value: -1}, nil,
		}}, nil
	})
	for range 2 {
		got, err := (Collector{}).Query(context.Background(), factory)
		want := map[string]Counters{"A": {UploadBytes: 10, DownloadBytes: 20}, "zero": {}}
		if err != nil || !reflect.DeepEqual(got, want) {
			t.Fatalf("Query = %v, %v; want %v", got, err, want)
		}
	}
	if calls.Load() != 2 {
		t.Fatal("应执行两次累计查询")
	}
}

func TestQueryRejectsInvalidCounters(t *testing.T) {
	for _, direction := range []string{"uplink", "downlink"} {
		for _, values := range [][]int64{{-1}, {math.MaxInt64, 1}} {
			t.Run(direction+"/"+fmtValues(values), func(t *testing.T) {
				factory := serveStats(t, func(context.Context, *pb.QueryStatsRequest) (*pb.QueryStatsResponse, error) {
					r := &pb.QueryStatsResponse{}
					for _, v := range values {
						r.Stat = append(r.Stat, &pb.Stat{Name: "user>>>A>>>traffic>>>" + direction, Value: v})
					}
					return r, nil
				})
				if got, err := (Collector{}).Query(context.Background(), factory); err == nil || got != nil {
					t.Fatalf("应拒绝且无部分结果: %v, %v", got, err)
				}
			})
		}
	}
	got, err := parseStats([]*pb.Stat{{Name: "user>>>A>>>traffic>>>uplink", Value: math.MaxInt64}})
	if err != nil || got["A"].UploadBytes != math.MaxInt64 {
		t.Fatalf("最大值应合法: %v %v", got, err)
	}
}

func fmtValues(values []int64) string {
	if len(values) == 1 {
		return "negative"
	}
	return "overflow"
}

func TestQueryTimeoutAndCancellation(t *testing.T) {
	canceled := make(chan struct{})
	factory := serveStats(t, func(ctx context.Context, _ *pb.QueryStatsRequest) (*pb.QueryStatsResponse, error) {
		<-ctx.Done()
		close(canceled)
		return nil, ctx.Err()
	})
	start := time.Now()
	_, err := (Collector{Timeout: 100 * time.Millisecond}).Query(context.Background(), factory)
	if status.Code(err) != codes.DeadlineExceeded || time.Since(start) > time.Second {
		t.Fatalf("RPC 应按时超时: %v", err)
	}
	select {
	case <-canceled:
	case <-time.After(time.Second):
		t.Fatal("服务端未收到取消")
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = (Collector{}).Query(ctx, factory)
	if status.Code(err) != codes.Canceled {
		t.Fatalf("应取消: %v", err)
	}
}

func TestQueryDialTimeout(t *testing.T) {
	done := make(chan struct{})
	_, err := (Collector{Timeout: 50 * time.Millisecond}).Query(context.Background(), func(ctx context.Context) (net.Conn, error) {
		<-ctx.Done()
		close(done)
		return nil, ctx.Err()
	})
	if status.Code(err) != codes.DeadlineExceeded {
		t.Fatalf("应连接超时: %v", err)
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("连接工厂未取消")
	}
}

func TestQueryRPCError(t *testing.T) {
	factory := serveStats(t, func(context.Context, *pb.QueryStatsRequest) (*pb.QueryStatsResponse, error) {
		return nil, status.Error(codes.Internal, "failed")
	})
	if got, err := (Collector{}).Query(context.Background(), factory); got != nil || status.Code(err) != codes.Internal {
		t.Fatalf("应传递RPC错误: %v %v", got, err)
	}
	if _, err := (Collector{}).Query(context.Background(), nil); err == nil {
		t.Fatal("应拒绝空工厂")
	}
	_, err := (Collector{Timeout: 30 * time.Millisecond}).Query(context.Background(), func(context.Context) (net.Conn, error) { return nil, errors.New("unreachable") })
	if err == nil {
		t.Fatal("应传递连接失败")
	}
}
