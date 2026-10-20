package subscribe

import (
	"bytes"
	"embed"
	"fmt"
	"io/fs"
	"strings"
	"text/template"
)

//go:embed templates
var embedded embed.FS

// Templates 是以客户端模板文件名为根路径的内置默认资源，包含原样规则文件。
var Templates, _ = fs.Sub(embedded, "templates")

// Render 每次读取并渲染一个标准 Go text/template；不缓存、不转义、不处理 HTTP 或节点行清理。
func Render(files fs.FS, path string, data map[string]any) ([]byte, error) {
	source, err := fs.ReadFile(files, path)
	if err != nil {
		return nil, fmt.Errorf("读取订阅模板 %s: %w", path, err)
	}
	t, err := template.New(path).Option("missingkey=error").Funcs(template.FuncMap{
		"text": text,
		"get":  func(m map[string]any, key string) any { return m[key] },
		// Qute Elvis 只对缺键/null 兜底，空字符串、false 和 0 均原样保留。
		"default": func(fallback, value any) any {
			if value == nil {
				return fallback
			}
			return value
		},
		"urlEncode":  urlEncode,
		"contains":   strings.Contains,
		"trimSuffix": strings.TrimSuffix,
		"add":        func(a, b int) int { return a + b },
		"shadowTag":  shadowTag,
	}).Parse(string(source))
	if err != nil {
		return nil, fmt.Errorf("解析订阅模板 %s: %w", path, err)
	}
	var out bytes.Buffer
	if err = t.Execute(&out, data); err != nil {
		return nil, fmt.Errorf("渲染订阅模板 %s: %w", path, err)
	}
	return out.Bytes(), nil
}

func text(v any) string {
	if v == nil {
		return ""
	}
	return fmt.Sprint(v)
}

// urlEncode 对齐 Qute 扩展：Java URLEncoder 后把 + 替换成 %20。
func urlEncode(v any) string {
	const hex = "0123456789ABCDEF"
	var b strings.Builder
	for _, c := range []byte(text(v)) {
		if c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || strings.ContainsRune(".-*_", rune(c)) {
			b.WriteByte(c)
		} else {
			b.WriteByte('%')
			b.WriteByte(hex[c>>4])
			b.WriteByte(hex[c&15])
		}
	}
	return b.String()
}

// 避免辅助出站名与用户节点名碰撞；策略组始终只引用原节点名。
func shadowTag(i int, nodes []map[string]any) string {
	tag := fmt.Sprintf("__airopscat_shadowtls_%d", i)
	for {
		found := false
		for _, n := range nodes {
			if n["name"] == tag {
				found = true
				break
			}
		}
		if !found {
			return tag
		}
		tag += "_"
	}
}
