#!/usr/bin/env bash
# 从 npm 取 Tabler 官方完整 CSS，按模板、JS、Go 源码里出现的类名裁剪后写入 web/static/tabler/css/。
# 新增页面用到此前没用过的 Tabler 类或图标后须重跑；升级 Tabler 时改下面的版本号。
set -Eeuo pipefail

CORE=1.6.1
ICONS=3.34.0
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cd "$tmp"
npm pack --silent "@tabler/core@$CORE" "@tabler/icons-webfont@$ICONS" >/dev/null
mkdir core icons
tar xzf "tabler-core-$CORE.tgz" -C core
tar xzf "tabler-icons-webfont-$ICONS.tgz" -C icons
cp core/package/dist/css/tabler.min.css icons/package/dist/tabler-icons.min.css .

# 类名可能写在 Go 代码里（如 BadgeClass）或由 Tabler/Tom Select 脚本运行时添加，都要纳入扫描
# 暗色主题与 Bootstrap/Popper 运行时才加的定位属性（下拉菜单、popover 方向）源码里扫不到，须用正则白名单整体保留；
# 命令行参数只支持精确类名，所以写成配置文件
cat > purgecss.config.cjs <<EOF
module.exports = {
  css: ['tabler.min.css', 'tabler-icons.min.css'],
  content: ['$root/web/templates/**/*.html', '$root/web/static/js/*.js',
    '$root/web/static/tabler/js/*.js', '$root/web/static/vendor/*.js', '$root/internal/**/*.go'],
  safelist: { greedy: [/data-bs-theme/, /data-bs-popper/, /data-tblr-popper/, /data-popper-placement/] },
  output: '$root/web/static/tabler/css/',
};
EOF
npx -y purgecss@8 --config purgecss.config.cjs

ls -l "$root/web/static/tabler/css/"*.css
