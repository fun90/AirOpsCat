# Shadowrocket 配置文件调整方案

## 背景

当前 `src/main/resources/config/subscription/shadowrocket.yaml` 标注为 Shadowrocket iOS 配置，但文件主体实际是 Clash/Mihomo Profile：

- 使用 `mode`、`mixed-port`、`tun`、`dns`、`proxies`、`proxy-groups`、`rule-providers`、`rules` 等 Clash 配置项。
- 节点使用 Clash YAML 对象输出。
- 规则使用 `RULE-SET,myreject,REJECT`、`MATCH,代理` 等 Clash 规则写法。
- `rule-providers` 引用的是 `rules/clash/*.yaml`。

这会导致 Shadowrocket 导入时存在两个问题：

1. Shadowrocket 对节点订阅和完整配置文件的支持边界不同，不能把 Clash 完整 Profile 直接当作 Shadowrocket 配置长期依赖。
2. 当前模板内的策略名出现乱码，如 `浠ｇ悊`、`鑷姩`、`鐩磋繛`，即使客户端接受部分字段，也可能因为策略引用不一致导致规则失效。

## Sub-Store 分析结论

分析目录：`C:\code\Sub-Store`。

### 1. Sub-Store 的 Shadowrocket 输出定位

Sub-Store 的 `backend/src/core/proxy-utils/producers/shadowrocket.js` 中，`Shadowrocket_Producer` 只负责把内部节点结构转换为 Shadowrocket 可用的节点订阅输出。

关键点：

- `produce()` 入口对代理节点做兼容性过滤和字段规整。
- 最终调用 `produceProxyListOutput(list, type, opts)`。
- `produceProxyListOutput()` 默认输出：

```yaml
proxies:
  - {"name":"...","type":"vless",...}
```

也就是说，Sub-Store 的 Shadowrocket 产物仍是“节点列表 YAML”，不是包含 `[General]`、`[Proxy Group]`、`[Rule]` 的完整配置文件。

这说明：如果 AirOpsCat 只想给 Shadowrocket 提供节点订阅，可以保留 YAML 节点列表路线；如果要提供分流规则配置，则应改成 Shadowrocket/Surge 风格的 `.conf` 配置。

### 2. Sub-Store 对 Shadowrocket 节点兼容性的处理

Sub-Store 的 Shadowrocket Producer 提供了几个可借鉴规则：

- 默认过滤明显不支持的协议，如 `tailscale`、`mieru`、`sudoku`、`naive`、`masque`。
- `anytls` 仅允许 `tcp` 且不能带 `reality-opts`。
- `xhttp` 不直接过滤，但提示 Shadowrocket 可能无法完全兼容。
- `vless` 会把 `sni` 转换为 `servername`。
- `hysteria2` 会将 `alpn` 规范为数组，并把 `tfo` 映射为 `fast-open`。
- `trojan`、`tuic`、`hysteria`、`hysteria2` 等 TLS 类协议在输出 Shadowrocket 时删除通用 `tls` 字段，避免多余字段干扰。
- 输出前删除内部字段：`subName`、`collectionName`、`id`、`resolved`、`no-resolve`、`ip-cidr`、`ipv6-cidr` 和 `_` 开头的元数据。

AirOpsCat 当前只有 `vless`、`vless-reality`、`hysteria2` 这几类订阅输出，范围比 Sub-Store 小，可以采用更保守的白名单策略。

### 3. Sub-Store 不是当前完整配置的直接模板

Sub-Store 对 Shadowrocket 的 App 配置说明里，是让 Shadowrocket 安装 Surge 模块，而不是生成一个完整 Shadowrocket `.conf`。因此它更适合作为“节点字段兼容性参考”，不适合直接复制为 AirOpsCat 的完整配置模板。

## 外部资料校正

参考资料：

- Shadowrocket 说明文档：`https://github.com/LOWERTOP/Shadowrocket/blob/main/README.md`
- Shadowrocket lazy group 示例解析：`https://deepwiki.com/LOWERTOP/Shadowrocket/4.3-proxy-groups`
- Shadowrocket `.conf` 结构示例：`https://shadowrocketvpn.app/help/servers/config`

资料结论：

- Shadowrocket 添加节点支持 `Subscribe` 类型，也支持复制 `trojan://`、`vmess://`、`vless://` 等节点链接后识别导入。
- Shadowrocket 支持的协议类型包含 `VLESS`、`Hysteria2` 等，但“节点订阅链接”与“完整配置 `[Proxy]` 本地节点行”不是同一种输出格式。
- Shadowrocket `.conf` 的核心结构是 `[General]`、`[Rule]`、`[Proxy]`、`[Proxy Group]`；`[Proxy Group]` 可使用 `select`、`url-test` 等策略组类型。
- Shadowrocket 的策略组示例可以通过订阅名称加 `use=true` 引用已存在订阅；同时社区配置中也常见 `policy-path` 这类策略组级远程节点写法。AirOpsCat 可以优先沿用“完整配置引用远程节点订阅”的主路径，但节点订阅内容不能直接照搬 Loon 本地节点行。

## 目标

将 AirOpsCat 的 Shadowrocket 订阅调整为真正面向 Shadowrocket 的输出，避免继续把 Clash Profile 伪装成 Shadowrocket 配置。

建议目标分两层：

1. 必须完成：让 `/config/{authCode}/ios/shadowrocket/...` 输出 Shadowrocket 可导入、可分流的 `.conf` 配置。
2. 可选增强：增加 `/nodes/{authCode}/shadowrocket`，提供独立 Shadowrocket 节点订阅，便于完整配置引用远程节点。

## 推荐方案

推荐采用“完整 `.conf` 配置内联 `[Proxy]` 节点”的结构。

真机验证结论：

- `policy-path` 无法在 Shadowrocket 中正常获取远程节点。
- `[Remote Proxy]` 也无法在 Shadowrocket 中正常获取远程节点。
- 因此完整配置不再依赖 `/nodes/{authCode}/shadowrocket` 作为远程节点来源，而是在 `shadowrocket.conf` 的 `[Proxy]` 段直接展开当前账号可用节点。

核心调整：

1. 完整配置 `/config/{authCode}/ios/shadowrocket/...`：生成 Shadowrocket `.conf`，在 `[Proxy]` 中内联当前账号节点，在 `[Proxy Group]` 中引用这些节点名称。
2. 独立节点订阅 `/nodes/{authCode}/shadowrocket`：继续输出 Shadowrocket 可导入的 URI 节点订阅，作为独立节点订阅入口保留。
3. 不再在完整配置中使用 `policy-path` 或 `[Remote Proxy]` 拉取远程节点。

### 文件结构

新增或调整以下文件：

```text
src/main/resources/config/subscription/shadowrocket.conf
src/main/resources/config/subscription/nodes/shadowrocket.html
```

继续复用 Clash 规则文件：

```text
src/main/resources/config/subscription/rules/clash/*.yaml
```

不单独维护 `rules/shadowrocket/*.list`。Shadowrocket 的 `RULE-SET` 可以直接引用现有 Clash classical 规则集，避免两套自定义规则漂移。

### 服务端后缀调整

修改 `SubscriptionService#getSubscriptionFileSuffix`：

```java
private String getSubscriptionFileSuffix(String appName) {
    if ("loon".equalsIgnoreCase(appName) || "shadowrocket".equalsIgnoreCase(appName)) {
        return ".conf";
    } else if ("sing-box".equalsIgnoreCase(appName)) {
        return ".json";
    }
    return ".yaml";
}
```

这样 `shadowrocket` 会读取 `shadowrocket.conf`，不再读取 `shadowrocket.yaml`。

### Remote Proxy 与 policy-path 的区别

`policy-path` 是策略组级别的远程节点来源。配置直接写在 `[Proxy Group]` 里：

```ini
代理 = select, 自动, DIRECT, policy-path={subscriptionUrl}/nodes/{account.authCode}/shadowrocket, update-interval=43200
自动 = url-test, policy-path={subscriptionUrl}/nodes/{account.authCode}/shadowrocket, url=http://www.gstatic.com/generate_204, interval=300, tolerance=50
```

特点：

- 配置更短，节点来源和策略组绑定关系一眼可见。
- 不需要额外定义一个 `订阅节点` 中间资源。
- 如果多个策略组都写同一个 `policy-path`，需要真机确认 Shadowrocket 是否会复用缓存；逻辑上它们是各自从同一个 URL 取候选节点。
- 更适合 AirOpsCat 当前这种“一个账号一条节点订阅，策略组直接消费”的场景。

`[Remote Proxy]` 是先声明一个远程节点资源，再在策略组里引用：

```ini
[Remote Proxy]
订阅节点 = {subscriptionUrl}/nodes/{account.authCode}/shadowrocket, update-interval=43200, opt-parser=false, enabled=true

[Proxy Group]
代理 = select, 自动, 订阅节点, DIRECT
自动 = url-test, 订阅节点, url=http://www.gstatic.com/generate_204, interval=300, tolerance=50
```

特点：

- 远程节点资源有独立名称，多个策略组可以通过同一个名称复用。
- 结构更接近现有 `loon.conf` 的 `[Remote Proxy]` 写法，迁移认知成本低。
- 多了一层 `订阅节点`，策略组里看到的是资源名而不是直接的 URL。
- 需要真机确认 Shadowrocket 对 `[Remote Proxy]` 中 `opt-parser`、`enabled` 等参数的兼容情况。

结论：真机验证中 `policy-path` 与 `[Remote Proxy]` 均无法获取节点，当前不再作为完整配置主路径，仅保留为历史尝试记录。

### Shadowrocket 节点模板

Shadowrocket 节点订阅不能直接复用 `nodes/loon.html` 的本地节点行：

```text
节点名=VLESS,host,port,"uuid",transport=tcp,...
```

推荐让 `nodes/shadowrocket.html` 输出 Shadowrocket 可识别的标准节点 URI：

```text
vless://uuid@host:port?encryption=none&security=reality&sni=example.com&pbk=...&sid=...&type=tcp&flow=xtls-rprx-vision#节点名
hysteria2://password@host:port?insecure=1&sni=example.com&obfs=salamander&obfs-password=...#节点名
```

该节点订阅继续作为独立入口保留，但完整 `.conf` 不再通过 `policy-path` / `[Remote Proxy]` 引用它。

### Shadowrocket 完整配置模板

建议 `shadowrocket.conf` 使用如下结构：

```ini
# AirOpsCat Subscription Config for Shadowrocket (iOS)
# Generated at: {timestamp}
# Account: {account.uuid}

[General]
bypass-system = true
skip-proxy = 192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, localhost, *.local
dns-server = system
ipv6 = false

[Proxy]
{#for node in nodes}
{#if node.protocol == 'hysteria2'}
{node.name} = hysteria2, {node.serverHost}, {node.port}, password={account.uuid}, sni={node.inbound.tls.server_name}, skip-cert-verify=true, udp=true, fast-open=true, obfs=salamander, obfs-password={node.inbound.obfs.password}
{#else if node.protocol == 'vless'}
{node.name} = vless, {node.serverHost}, {node.port}, password={account.uuid}, tls=true, sni={node.serverHost}, flow=xtls-rprx-vision, udp=true, skip-cert-verify=true
{#else if node.protocol == 'vless-reality'}
{node.name} = vless, {node.serverHost}, {node.port}, password={account.uuid}, tls=true, sni={node.inbound.tls.server_name}, reality=true, public-key={node.inbound.tls.reality.public_key}, short-id={node.inbound.tls.reality.short_id[0]}, flow=xtls-rprx-vision, udp=true, skip-cert-verify=true
{/if}
{/for}

[Proxy Group]
代理 = select, 自动, DIRECT{#for node in nodes}, {node.name}{/for}
自动 = url-test{#for node in nodes}, {node.name}{/for}, url=http://www.gstatic.com/generate_204, interval=300, tolerance=50
Apple = select, DIRECT, 代理
Microsoft = select, 代理, DIRECT

[Rule]
RULE-SET,{subscriptionUrl}/rules/clash/myreject.yaml,REJECT
RULE-SET,{subscriptionUrl}/rules/clash/mydirect.yaml,DIRECT
RULE-SET,{subscriptionUrl}/rules/clash/myproxy.yaml,代理
IP-CIDR,10.0.0.0/8,DIRECT,no-resolve
IP-CIDR,172.16.0.0/12,DIRECT,no-resolve
IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
IP-CIDR,127.0.0.0/8,DIRECT,no-resolve
GEOIP,CN,DIRECT
FINAL,代理
```

说明：

- `MATCH,代理` 改为 `FINAL,代理`。
- `proxy-groups` 改为 `[Proxy Group]`。
- `[Proxy]` 直接内联 Shadowrocket 本地节点行。
- `[Proxy Group]` 引用内联节点名称，不再使用 `policy-path` 或 `[Remote Proxy]`。
- `rule-providers` 改为 `[Rule]` 中直接引用远程 `RULE-SET`，规则 URL 复用现有 `rules/clash/*.yaml`。
- 私网地址用明确的 `IP-CIDR` 规则替代 `GEOIP,LAN`。
- 策略名统一使用正常 UTF-8：`代理`、`自动`，不再使用乱码名称。

### Shadowrocket 节点订阅模板

新增或调整 `nodes/shadowrocket.html`。Shadowrocket 节点订阅建议输出标准 URI 链接，一行一个节点。

建议模板：

```text
{#for node in nodes}
{#if node.protocol == 'hysteria2'}
hysteria2://{account.uuid}@{node.serverHost}:{node.port}?insecure=1&sni={node.inbound.tls.server_name}&obfs=salamander&obfs-password={node.inbound.obfs.password}#{node.name}
{#else if node.protocol == 'vless'}
vless://{account.uuid}@{node.serverHost}:{node.port}?encryption=none&security=tls&sni={node.serverHost}&type=tcp&flow=xtls-rprx-vision#{node.name}
{#else if node.protocol == 'vless-reality'}
vless://{account.uuid}@{node.serverHost}:{node.port}?encryption=none&security=reality&sni={node.inbound.tls.server_name}&pbk={node.inbound.tls.reality.public_key}&sid={node.inbound.tls.reality.short_id[0]}&type=tcp&flow=xtls-rprx-vision#{node.name}
{/if}
{/for}
```

注意：

- AirOpsCat 已收敛为 sing-box 单内核，`vless-reality` 可优先使用 `node.inbound.tls.*` 路径，不建议继续保留 xray 分支。
- URI 中的 `node.name`、`sni`、`obfs-password` 等值理论上需要 URL 编码；如果 Qute 模板没有可用编码函数，应在服务层预先准备编码后的字段，避免节点名、密码或域名特殊字符破坏链接。
- Hysteria2 URI 的 `obfs=salamander` / `obfs-password`、VLESS Reality URI 的 `pbk` / `sid` 需要真机确认 Shadowrocket 当前版本是否完整识别；如果不识别，再按 Shadowrocket 实测字段做最小调整。
- 完整配置不再引用该订阅，而是在 `[Proxy]` 中内联节点；该订阅仅作为独立节点订阅入口。

### 规则文件复用策略

Shadowrocket 的 `RULE-SET` 可以直接使用当前 Clash classical 规则集，因此第一阶段不新增 `rules/shadowrocket` 目录。

继续使用：

```text
{subscriptionUrl}/rules/clash/myreject.yaml
{subscriptionUrl}/rules/clash/mydirect.yaml
{subscriptionUrl}/rules/clash/myproxy.yaml
```

这样自定义规则只维护一份，Clash、Stash、Shadowrocket 可以共享 `rules/clash/*.yaml`。后续只有在发现某类规则 Shadowrocket 无法正确消费时，再按实际不兼容项做最小转换。

## 备选方案

### 远程节点订阅引用

完整配置中通过 `policy-path` 或 `[Remote Proxy]` 引用 `/nodes/{authCode}/shadowrocket`。

优点：

- 配置文件短。
- 节点可远程刷新。

缺点：

- 已经真机验证无法获取节点。
- 会导致完整配置导入后策略组无可用节点。

推荐优先采用“完整 `.conf` 内联 `[Proxy]` 节点”，也就是上面的主方案。

## 实施步骤

1. 调整 `nodes/shadowrocket.html`，输出 Shadowrocket 可识别的节点订阅，优先使用 URI 节点链接，不复用 Loon 节点行模板。
2. 调整 `shadowrocket.conf`，使用 `[General]`、`[Proxy]`、`[Proxy Group]`、`[Rule]` 结构，在 `[Proxy]` 中内联节点，策略组引用内联节点名称。
3. 在 `shadowrocket.conf` 的 `RULE-SET` 中直接引用现有 `rules/clash/*.yaml`。
4. 修改 `SubscriptionService#getSubscriptionFileSuffix`，让 `shadowrocket` 使用 `.conf`。
5. 删除或停用旧 `shadowrocket.yaml`，避免后续误维护。
6. 检查前端导入 URL。当前 `shadowrocket://add/{encodedUrl}` 可以保留，但要验证是否需要针对 `.conf` 或远程配置链接调整 scheme。
7. 真机导入 Shadowrocket 验证节点、策略组、规则命中和流量统计头。

## 验证清单

### 服务端验证

- 请求 `/subscribe/config/{authCode}/ios/shadowrocket/{remark}` 返回文本以 `[General]` 开头。
- 完整配置的 `[Proxy]` 段包含当前账号节点。
- 完整配置不包含 `policy-path` 或 `[Remote Proxy]`。
- 响应头仍包含 Shadowrocket 专用 `subscription-userinfo`。
- 请求 `/subscribe/nodes/{authCode}/shadowrocket` 返回一行一个 `vless://...` 或 `hysteria2://...` 节点 URI。
- 请求 `/subscribe/rules/clash/myreject.yaml`、`mydirect.yaml`、`myproxy.yaml` 仍返回现有 Clash 规则内容，并能被 Shadowrocket 的 `RULE-SET` 拉取。

### 客户端验证

- Shadowrocket 能通过一键导入 URL 添加完整配置。
- Shadowrocket 能通过完整配置直接看到节点。
- Shadowrocket 能通过节点订阅 URL 添加独立节点订阅。
- 配置内能看到 `代理`、`自动`、`Apple`、`Microsoft` 策略组。
- 内联节点能被策略组引用；独立 URI 节点订阅能正常刷新。
- VLESS Reality 节点能连通。
- Hysteria2 节点能连通。
- 国内站点命中 `DIRECT`，未匹配国外站点命中 `FINAL,代理`。

### 回归验证

- Clash Meta、Clash Verge、Stash 仍读取各自 `.yaml` 模板。
- Loon 仍读取 `loon.conf`。
- `rules/clash/*.yaml` 不受影响。

## 风险与注意事项

- Shadowrocket 对协议字段的支持会随版本变化，VLESS Reality 和 Hysteria2 必须真机验证，尤其是 URI 参数名与本地 `[Proxy]` 参数名。
- Sub-Store 对 Shadowrocket 的默认产物是节点 YAML，不代表 Shadowrocket 完整配置也应使用 Clash Profile。
- Loon 的 `节点名=VLESS,...` 行看起来与 Shadowrocket 本地节点行相似，但参数名、协议大小写、远程订阅模型都不完全相同，不能作为 Shadowrocket 节点订阅模板直接复用。
- `policy-path` / `[Remote Proxy]` 已经真机验证无法获取节点，完整配置不要再依赖它们。
- 规则文件 URL 中如果包含中文策略名，一般没有问题，但建议策略名统一、简短，避免编码不一致。
- 当前仓库存在中文乱码文件内容，修改 Shadowrocket 模板时必须使用 UTF-8 保存。
- 如果真机验证发现 Shadowrocket 对某个 Clash 规则集格式不兼容，再针对该规则集做最小转换；不要提前维护两套规则。
