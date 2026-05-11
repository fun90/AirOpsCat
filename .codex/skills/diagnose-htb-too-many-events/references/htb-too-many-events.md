# HTB "too many events" Notes

## What the warning means

`htb: too many events!` comes from the Linux HTB scheduler path that processes delayed class events. HTB keeps classes in event queues while they wait for enough token/ctoken budget. If one event pass exceeds the kernel's time budget, HTB logs the warning and continues later.

Treat it as a scheduler pressure signal. It does not by itself prove packet loss, link failure, or application logging overload.

## Common causes

- Too many HTB classes, especially per-IP, per-user, or per-connection dynamic classes.
- Many leaf classes becoming eligible at nearly the same time after token refill.
- Very small burst/cburst relative to traffic, causing frequent state transitions.
- CPU pressure, softirq delay, virtual-machine scheduling delay, or NIC interrupt backlog.
- A QoS, firewall, VPN, proxy panel, or traffic-shaping script recreating tc rules.
- Kernel/version-specific HTB edge behavior where a warning appears once despite light current traffic.

## How to read `tc -s -d qdisc`

- `dropped`: packets dropped by the qdisc. Zero supports low severity.
- `overlimits`: packets or dequeues that hit rate limiting. Zero means the current qdisc has not recorded shaping pressure since creation.
- `backlog`: queued bytes/packets. Zero means there is no current queue buildup.
- `requeues`: packets requeued by the stack. High values can indicate device/driver pressure.
- `direct_packets_stat`: packets bypassing classful handling or sent directly depending on qdisc path; small values are usually not important by themselves.

## How to read `tc -s -d class`

- Count classes first. A handful of classes is not the classic "too many classes" scenario.
- `level 0` indicates leaf classes. A root class with unexpectedly high `level` can mean the tree was created by a generic template or previously had deeper structure.
- `lended` counts packets sent using the class's own tokens.
- `borrowed` counts packets borrowing from ancestors. Zero is normal when `rate == ceil` or borrowing is not available.
- `tokens` and `ctokens` near maximum after the event only show the class is currently replenished.
- `giants 0` means no oversized packet accounting issue is visible.

## `r2q`, `quantum`, and burst

HTB derives per-class `quantum` from rate and `r2q`, but tools may clamp or set it near MTU. A `quantum` around 1514 is common and not suspicious alone.

Large `burst`/`cburst` relative to a low rate usually reduces token starvation. Very small burst values are more suspicious than generous ones.

Be careful with units in `tc` output:

- `Kbit` is kilobits per second for rates.
- `Kb` in burst output is kilobits, so `2000Kb` is roughly 250 KB.

## Filters and marks

`fw` filters classify by skb mark:

```text
filter parent 1: protocol all pref 49152 fw handle 0x2 classid 1:2
```

Interpret this as mark `0x2` going to class `1:2`. Unmarked or unmatched traffic goes to the qdisc default class.

When counters show most bytes in the default class, the configured limiting classes may not be receiving much marked traffic.

## Risk patterns

Low-risk isolated warning:

- The warning appears once or rarely.
- There are few classes.
- `dropped 0`, `overlimits 0`, `backlog 0`, `requeues 0`.
- `journalctl -k` around the timestamp has no CPU stall, soft lockup, NETDEV WATCHDOG, NIC reset, OOM, or conntrack errors.

Likely operational issue:

- The warning repeats frequently.
- Class count is large or dynamically changing.
- Backlog and drops increase.
- Overlimits are high on active leaf classes.
- Nearby logs show CPU/softirq/network driver problems.
- There is evidence a service reloads tc rules at the warning time.

## Recommended conclusion template

Use this structure:

1. State what the warning is.
2. Summarize the observed HTB topology and counters.
3. Explain what is unlikely.
4. Give the most likely causes for that specific output.
5. Recommend either observation or targeted next checks.

Example conclusion:

```text
从当前统计看，这不像 class 数量过多或持续拥塞：class 很少，dropped/overlimits/backlog/requeues 都是 0。因为命令是在告警数小时后执行的，它只能证明当前状态健康，不能完全还原告警那一秒。更可能是当时一次流量突发、tc 规则刷新，或虚拟化/CPU 调度延迟让 HTB 的事件处理超过预算。若只出现一次，可以观察；若频繁出现，再查创建 tc 规则的服务、附近完整系统日志和 CPU/软中断压力。
```
