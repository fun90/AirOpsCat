package assets

import (
	"bytes"
	"encoding/xml"
	"io"
	"io/fs"
	"regexp"
	"strings"
	"testing"
)

// 校验模板与页面脚本中使用的 CSS 类均已在样式表中定义，避免拼写错误或遗漏规则。
// shortcut: 只查模板和页面脚本里字面的 class="..."，Go 代码与 classList 动态加的类不覆盖。
func TestTemplateClassesDefined(t *testing.T) {
	var css []byte
	for _, f := range []string{"static/css/coss.css", "static/css/app.css", "static/vendor/tom-select.bootstrap5.min.css"} {
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
	// 不带样式的语义类与脚本钩子
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
					t.Errorf("%s：类 %s 未在样式表中定义", f, c)
				}
			}
		}
	}
}

// 校验 Lucide SVG sprite XML 合法、symbol 内无嵌套 svg，且静态引用的图标均已定义。
func TestLucideSpriteValidAndReferenced(t *testing.T) {
	raw, err := FS.ReadFile("static/icons/lucide.svg")
	if err != nil {
		t.Fatal(err)
	}
	dec := xml.NewDecoder(bytes.NewReader(raw))
	symbols := map[string]bool{}
	inSymbol := false
	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatalf("lucide.svg XML 语法错误：%v", err)
		}
		switch el := tok.(type) {
		case xml.StartElement:
			if el.Name.Local == "symbol" {
				inSymbol = true
				for _, a := range el.Attr {
					if a.Name.Local == "id" && a.Value != "" {
						symbols[a.Value] = true
					}
				}
			} else if inSymbol && el.Name.Local == "svg" {
				t.Errorf("lucide.svg：<symbol> 内存在嵌套 <svg>")
			}
		case xml.EndElement:
			if el.Name.Local == "symbol" {
				inSymbol = false
			}
		}
	}
	if len(symbols) == 0 {
		t.Fatal("lucide.svg 未解析出任何 symbol")
	}
	refRe := regexp.MustCompile(`lucide\.svg(?:\?[^#"'\s]*)?#([a-z0-9-]+)`)
	pages, _ := fs.Glob(FS, "templates/*.html")
	scripts, _ := fs.Glob(FS, "static/js/*.js")
	for _, f := range append(pages, scripts...) {
		b, err := FS.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range refRe.FindAllSubmatch(b, -1) {
			id := string(m[1])
			if !symbols[id] {
				t.Errorf("%s：引用了未在 lucide.svg 中定义的图标 #%s", f, id)
			}
		}
	}
}

// 校验模板与 ES module 引用的 /static/js/*.js URL 一致，避免同一模块因 query 不同被浏览器实例化两份。
func TestStaticJSModuleURLConsistency(t *testing.T) {
	refRe := regexp.MustCompile(`(?:src=|from\s+|import\s*\()['"](/static/js/[^'"?]+(?:\?[^'"]*)?)['"]`)
	seen := map[string]map[string][]string{}
	pages, _ := fs.Glob(FS, "templates/*.html")
	scripts, _ := fs.Glob(FS, "static/js/*.js")
	for _, f := range append(pages, scripts...) {
		b, err := FS.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range refRe.FindAllSubmatch(b, -1) {
			full := string(m[1])
			base, _, _ := strings.Cut(full, "?")
			if seen[base] == nil {
				seen[base] = map[string][]string{}
			}
			seen[base][full] = append(seen[base][full], f)
		}
	}
	for base, urls := range seen {
		if len(urls) > 1 {
			t.Errorf("脚本 %s 存在多个不同引用 URL（会导致 ES module 重复实例化）：%v", base, urls)
		}
	}
}
