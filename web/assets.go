// Package assets 把管理页面模板和静态资源编进二进制。
package assets

import "embed"

//go:embed templates static
var FS embed.FS
