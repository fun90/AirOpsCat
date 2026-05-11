---
name: diagnose-htb-too-many-events
description: 'Diagnose Linux kernel logs that contain "htb: too many events!" on Debian, Ubuntu, OpenWrt, router, VPN, proxy, or traffic-shaping hosts. Use when Codex needs to explain why HTB emitted this warning, inspect tc qdisc/class/filter output, judge whether it is harmless or symptomatic, and recommend next checks for QoS, skb mark, virtual NIC, or traffic-limiting configurations.'
---

# Diagnose HTB Too Many Events

## Overview

Analyze `htb: too many events!` as a Linux traffic-control HTB scheduler warning, not as a journald or application log problem. Work from live `tc` statistics and nearby kernel logs toward a risk judgment: isolated scheduler warning, tc rule churn, traffic burst, overloaded host, or malformed/oversized HTB tree.

Load `references/htb-too-many-events.md` when the user provides `tc -s -d` output, asks for root-cause likelihood, or needs help interpreting `r2q`, `quantum`, `overlimits`, `backlog`, filters, or HTB class levels.

## Workflow

1. Anchor the timeline.
   - Record the exact warning time and the time commands were run.
   - State that later `tc` counters may not fully describe the warning moment, especially if qdisc was recreated.

2. Confirm HTB is present.
   - Ask for or inspect:
     ```bash
     tc qdisc show
     tc class show dev <dev>
     tc filter show dev <dev>
     ```
   - If no HTB qdisc is present now, explain that a prior QoS/limit program may have removed or rebuilt it.

3. Request detailed counters when needed.
   ```bash
   tc -s -d qdisc show dev <dev>
   tc -s -d class show dev <dev>
   journalctl -k --since "<time-before>" --until "<time-after>"
   ```

4. Interpret the shape of the HTB tree.
   - Count classes and levels.
   - Identify root, default class, leaf classes, and filters.
   - Map `fw` filters to skb marks when present.
   - Compare configured rates, ceilings, burst/cburst, and observed traffic.

5. Judge severity from counters.
   - Low risk: few classes, `dropped 0`, `overlimits 0`, `backlog 0`, `requeues 0`, and no nearby kernel errors.
   - Higher risk: many classes, persistent backlog, drops, frequent overlimits, repeated warnings, CPU stalls, NETDEV WATCHDOG, NIC resets, or rule reloads near the timestamp.

6. Give a concise conclusion.
   - Prefer a probability-ranked explanation.
   - Separate "what the current counters prove" from "what likely happened at the warning time".
   - Recommend action only when the warning is repeated or counters show real congestion.

## Useful Commands

Use these commands as copyable probes:

```bash
tc -s -d qdisc show dev <dev>
tc -s -d class show dev <dev>
tc filter show dev <dev>
journalctl -k --since "<YYYY-MM-DD HH:MM:SS>" --until "<YYYY-MM-DD HH:MM:SS>"
journalctl --since "<YYYY-MM-DD HH:MM:SS>" --until "<YYYY-MM-DD HH:MM:SS>"
```

To locate the program that creates HTB rules:

```bash
grep -R "tc qdisc\|tc class\|tc filter" /etc /usr/local /opt 2>/dev/null
systemctl list-timers
systemctl list-units --type=service | grep -Ei "qos|tc|shape|limit|vpn|proxy|firewall"
```

## Response Style

Answer in the user's language. For Chinese users, use direct operational wording:

- "这不是 journald 事件太多，而是 HTB 队列调度器告警。"
- "从当前统计看，不像持续拥塞。"
- "这只能说明现在的 qdisc 状态，不能完全还原告警那一秒。"
- "只出现一次可以观察；频繁出现再调整 QoS/限速方案。"

Avoid overstating certainty. If command output is several hours after the warning, explicitly say so.
