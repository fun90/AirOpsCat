// airopscat-probe 是由控制端自动分发的单次端口探测程序。
package main

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/fun90/airopscat/internal/netprobe"
)

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--check" {
		fmt.Println(netprobe.Check)
		return
	}
	if len(os.Args) != 1 {
		os.Exit(2)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := netprobe.Serve(ctx, os.Stdin, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
