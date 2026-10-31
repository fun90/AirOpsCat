package assets

import (
	"io/fs"
	"regexp"
	"strings"
	"testing"
)

// Tabler CSS 按 scripts/purge-css.sh 扫描结果裁剪过，模板新用的类或图标不重跑裁剪会静默失效。
// shortcut: 只查模板和页面脚本里字面的 class="..."，Go 代码与 classList 动态加的类不覆盖。
func TestTemplateClassesDefined(t *testing.T) {
	var css []byte
	for _, f := range []string{"static/tabler/css/tabler.min.css", "static/tabler/css/tabler-icons.min.css", "static/css/app.css", "static/vendor/tom-select.bootstrap5.min.css"} {
		b, err := FS.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		css = append(css, b...)
	}
	defined := map[string]bool{}
	for _, m := range regexp.MustCompile(`\.(-?[_a-zA-Z][\w-]*)`).FindAllSubmatch(css, -1) {
		defined[string(m[1])] = true
	}
	// Tabler 本身不带样式的语义类和脚本钩子
	for _, c := range []string{"datagrid-item", "datagrid-content", "tag-edit"} {
		defined[c] = true
	}
	action := regexp.MustCompile(`(?s)\{\{.*?\}\}`)
	attr := regexp.MustCompile(`class="([^"]*)"`)
	pages, _ := fs.Glob(FS, "templates/*.html")
	scripts, _ := fs.Glob(FS, "static/js/*.js")
	for _, f := range append(pages, scripts...) {
		b, err := FS.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range attr.FindAllSubmatch(action.ReplaceAll(b, []byte(" ")), -1) {
			for _, c := range strings.Fields(string(m[1])) {
				if !defined[c] && !strings.ContainsAny(c, "$'+") {
					t.Errorf("%s：类 %s 不在裁剪后的 CSS 中，重跑 scripts/purge-css.sh", f, c)
				}
			}
		}
	}
}
