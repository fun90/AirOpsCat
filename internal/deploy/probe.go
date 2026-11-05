package deploy

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"strings"

	"github.com/fun90/airopscat/internal/netprobe"
)

// InputRunner 将数据直接交给 SSH 标准输入，避免写入命令行或远端临时请求文件。
type InputRunner interface {
	RunInput(context.Context, string, []byte) (string, string, int, error)
}

// Probe 是一条 SSH 连接上已校验并可执行的探测程序。
type Probe struct {
	runner  InputRunner
	command string
}

func (p *Probe) Run(ctx context.Context, request []byte) (string, error) {
	out, _, code, err := p.runner.RunInput(ctx, p.command, request)
	if err != nil {
		return "", err
	}
	if code != 0 {
		return "", fmt.Errorf("探测程序执行失败（退出码 %d）", code)
	}
	return out, nil
}

// PrepareProbe 每条连接安装或校验一次，以内容哈希命名，支持并发分发。
// 准备失败属于探测机错误，不能被记为目标端口不可达。
func PrepareProbe(ctx context.Context, r Remote, load func(string) ([]byte, error)) (*Probe, error) {
	input, ok := r.(InputRunner)
	if !ok {
		return nil, errors.New("SSH 连接不支持探测标准输入")
	}
	run := func(cmd string) (string, error) {
		out, _, code, err := r.Run(ctx, cmd)
		if err != nil {
			return "", err
		}
		if code != 0 {
			return "", fmt.Errorf("探测程序准备失败（退出码 %d）", code)
		}
		return strings.TrimSpace(out), nil
	}
	info, err := run(`uname -sm; printf '\n%s\n' "$HOME"`)
	if err != nil {
		return nil, err
	}
	lines := strings.Split(info, "\n")
	if len(lines) != 3 || lines[1] != "" || !strings.HasPrefix(lines[2], "/") {
		return nil, errors.New("探测机系统或用户目录无效")
	}
	arch := ""
	switch lines[0] {
	case "Linux x86_64":
		arch = "amd64"
	case "Linux aarch64", "Linux arm64":
		arch = "arm64"
	default:
		return nil, errors.New("探测机仅支持 Linux amd64/arm64")
	}
	data, err := load(arch)
	if err != nil {
		return nil, err
	}
	if len(data) == 0 {
		return nil, errors.New("探测程序为空")
	}
	hash := fmt.Sprintf("%x", sha256.Sum256(data))
	dir := strings.TrimRight(lines[2], "/") + "/.local/lib/airopscat"
	path := dir + "/probe-" + hash
	verify := func(path string) string {
		return "test -x " + shQuote(path) + " && test \"$(sha256sum " + shQuote(path) + " | cut -d ' ' -f 1)\" = " + shQuote(hash)
	}
	// 缓存只以远端实际文件和校验结果为准，不跨连接缓存安装成功状态。
	_, _, code, err := r.Run(ctx, verify(path))
	if err != nil {
		return nil, err
	}
	if code != 0 {
		tmp, err := run("umask 077; mkdir -p " + shQuote(dir) + " && mktemp " + shQuote(dir+"/.probe-XXXXXXXXXX"))
		if err != nil {
			return nil, err
		}
		if !strings.HasPrefix(tmp, dir+"/.probe-") || strings.ContainsAny(strings.TrimPrefix(tmp, dir+"/.probe-"), "/\n\r") {
			return nil, errors.New("探测程序临时路径无效")
		}
		defer r.Run(context.WithoutCancel(ctx), "rm -f -- "+shQuote(tmp))
		if err := r.WriteFile(ctx, tmp, data); err != nil {
			return nil, fmt.Errorf("上传探测程序失败: %w", err)
		}
		if _, err := run("chmod 700 " + shQuote(tmp) + " && " + verify(tmp) + " && mv -f -- " + shQuote(tmp) + " " + shQuote(path)); err != nil {
			return nil, err
		}
	}
	out, err := run(shQuote(path) + " --check")
	if err != nil {
		return nil, err
	}
	if out != netprobe.Check {
		return nil, errors.New("探测程序版本校验失败")
	}
	return &Probe{runner: input, command: shQuote(path)}, nil
}
