#!/usr/bin/env python3
import argparse
import asyncio
import base64
import json
import os
import signal
import socket
import struct
import time


def parse_args():
    parser = argparse.ArgumentParser(description="AirOpsCat 限速代理压测客户端")
    parser.add_argument("--proxy-host", help="测试服务器代理地址，direct 模式不需要")
    parser.add_argument("--proxy-port", type=int, help="测试服务器代理端口，direct 模式不需要")
    parser.add_argument("--protocol", choices=("direct", "socks5", "http-connect"), default="socks5")
    parser.add_argument("--accounts-file", help="账号 JSON 文件，格式为 [{\"username\":\"u\",\"password\":\"p\"}]；direct 模式可不传")
    parser.add_argument("--connections-per-account", type=int, default=100)
    parser.add_argument("--target-host", default="cp.cloudflare.com")
    parser.add_argument("--target-port", type=int, default=80)
    parser.add_argument("--duration", type=int, default=600, help="压测持续秒数")
    parser.add_argument("--ramp-seconds", type=int, default=120, help="连接升压秒数")
    parser.add_argument("--request-interval", type=float, default=10.0, help="每条连接发送探活请求的间隔秒数，0 表示只建连")
    parser.add_argument("--connect-timeout", type=float, default=8.0)
    parser.add_argument("--report-interval", type=float, default=5.0)
    return parser.parse_args()


def load_accounts(path):
    if not path:
        return [("direct", "")]
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if isinstance(data, dict):
        data = data.get("accounts", [])
    accounts = []
    for item in data:
        if not isinstance(item, dict):
            continue
        username = str(item.get("username") or item.get("accountNo") or "").strip()
        password = str(item.get("password") or "").strip()
        if username:
            accounts.append((username, password))
    if not accounts:
        raise SystemExit("账号文件没有可用账号")
    return accounts


class Stats:
    def __init__(self):
        self.started = time.monotonic()
        self.opening = 0
        self.active = 0
        self.connected = 0
        self.closed = 0
        self.connect_failed = 0
        self.request_ok = 0
        self.request_failed = 0
        self.bytes_read = 0
        self._lock = asyncio.Lock()

    async def add(self, field, value=1):
        async with self._lock:
            setattr(self, field, getattr(self, field) + value)

    async def snapshot(self):
        async with self._lock:
            return {
                "elapsed": time.monotonic() - self.started,
                "opening": self.opening,
                "active": self.active,
                "connected": self.connected,
                "closed": self.closed,
                "connect_failed": self.connect_failed,
                "request_ok": self.request_ok,
                "request_failed": self.request_failed,
                "bytes_read": self.bytes_read,
            }


async def socks5_connect(reader, writer, args, username, password):
    methods = b"\x00"
    if username or password:
        methods += b"\x02"
    writer.write(b"\x05" + bytes([len(methods)]) + methods)
    await writer.drain()
    data = await reader.readexactly(2)
    if data[0] != 5 or data[1] == 0xFF:
        raise RuntimeError("SOCKS5 方法协商失败")

    if data[1] == 2:
        user_bytes = username.encode()
        pass_bytes = password.encode()
        if len(user_bytes) > 255 or len(pass_bytes) > 255:
            raise RuntimeError("SOCKS5 用户名或密码过长")
        writer.write(b"\x01" + bytes([len(user_bytes)]) + user_bytes + bytes([len(pass_bytes)]) + pass_bytes)
        await writer.drain()
        data = await reader.readexactly(2)
        if data != b"\x01\x00":
            raise RuntimeError("SOCKS5 认证失败")

    host_bytes = args.target_host.encode()
    if len(host_bytes) > 255:
        raise RuntimeError("目标域名过长")
    writer.write(b"\x05\x01\x00\x03" + bytes([len(host_bytes)]) + host_bytes + struct.pack("!H", args.target_port))
    await writer.drain()
    head = await reader.readexactly(4)
    if head[1] != 0:
        raise RuntimeError(f"SOCKS5 CONNECT 失败: code={head[1]}")
    if head[3] == 1:
        await reader.readexactly(4)
    elif head[3] == 3:
        size = await reader.readexactly(1)
        await reader.readexactly(size[0])
    elif head[3] == 4:
        await reader.readexactly(16)
    else:
        raise RuntimeError("SOCKS5 响应地址类型无效")
    await reader.readexactly(2)


async def http_connect(reader, writer, args, username, password):
    auth = ""
    if username or password:
        token = base64.b64encode(f"{username}:{password}".encode()).decode()
        auth = f"Proxy-Authorization: Basic {token}\r\n"
    request = (
        f"CONNECT {args.target_host}:{args.target_port} HTTP/1.1\r\n"
        f"Host: {args.target_host}:{args.target_port}\r\n"
        f"{auth}\r\n"
    )
    writer.write(request.encode())
    await writer.drain()
    data = await reader.readuntil(b"\r\n\r\n")
    status = data.split(b"\r\n", 1)[0]
    if b" 200 " not in status:
        raise RuntimeError(status.decode(errors="replace"))


async def open_tunnel(args, username, password):
    if args.protocol == "direct":
        return await asyncio.wait_for(
            asyncio.open_connection(args.target_host, args.target_port, family=socket.AF_UNSPEC),
            timeout=args.connect_timeout,
        )
    if not args.proxy_host or not args.proxy_port:
        raise RuntimeError("非 direct 模式必须传入 --proxy-host 和 --proxy-port")

    reader, writer = await asyncio.wait_for(
        asyncio.open_connection(args.proxy_host, args.proxy_port, family=socket.AF_UNSPEC),
        timeout=args.connect_timeout,
    )
    try:
        if args.protocol == "socks5":
            await asyncio.wait_for(socks5_connect(reader, writer, args, username, password), timeout=args.connect_timeout)
        else:
            await asyncio.wait_for(http_connect(reader, writer, args, username, password), timeout=args.connect_timeout)
        return reader, writer
    except Exception:
        writer.close()
        await writer.wait_closed()
        raise


async def connection_worker(args, stats, username, password, stop_at):
    await stats.add("opening")
    reader = None
    writer = None
    active = False
    try:
        reader, writer = await open_tunnel(args, username, password)
        await stats.add("active")
        active = True
        await stats.add("connected")
        while time.monotonic() < stop_at:
            if args.request_interval <= 0:
                await asyncio.sleep(1)
                continue
            request = (
                f"GET / HTTP/1.1\r\n"
                f"Host: {args.target_host}\r\n"
                f"User-Agent: airopscat-ratelimit-loadtest\r\n"
                f"Connection: keep-alive\r\n\r\n"
            )
            writer.write(request.encode())
            await writer.drain()
            data = await asyncio.wait_for(reader.read(4096), timeout=max(5, args.request_interval))
            if not data:
                raise RuntimeError("目标连接已关闭")
            await stats.add("bytes_read", len(data))
            await stats.add("request_ok")
            await asyncio.sleep(args.request_interval)
    except Exception:
        if writer is None:
            await stats.add("connect_failed")
        else:
            await stats.add("request_failed")
    finally:
        await stats.add("opening", -1)
        if active:
            await stats.add("active", -1)
        if writer is not None:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass
        await stats.add("closed")


async def reporter(stats, interval, stop_event):
    last = await stats.snapshot()
    while not stop_event.is_set():
        await asyncio.sleep(interval)
        snap = await stats.snapshot()
        delta_ok = snap["request_ok"] - last["request_ok"]
        delta_bytes = snap["bytes_read"] - last["bytes_read"]
        last = snap
        print(
            "elapsed={elapsed:.0f}s opening={opening} connected={connected} closed={closed} "
            "active={active} connect_failed={connect_failed} request_failed={request_failed} "
            "rps={rps:.1f} read_kbps={kbps:.1f}".format(
                **snap,
                rps=delta_ok / interval,
                kbps=(delta_bytes * 8 / 1000) / interval,
            ),
            flush=True,
        )


async def main():
    args = parse_args()
    accounts = load_accounts(args.accounts_file)
    targets = [(username, password) for username, password in accounts for _ in range(args.connections_per_account)]
    stats = Stats()
    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop_event.set)

    stop_at = time.monotonic() + args.duration
    reporter_task = asyncio.create_task(reporter(stats, args.report_interval, stop_event))
    tasks = []
    delay = args.ramp_seconds / max(1, len(targets))

    print(f"计划连接数: accounts={len(accounts)} total_connections={len(targets)}", flush=True)
    for username, password in targets:
        if stop_event.is_set() or time.monotonic() >= stop_at:
            break
        tasks.append(asyncio.create_task(connection_worker(args, stats, username, password, stop_at)))
        if delay > 0:
            await asyncio.sleep(delay)

    while time.monotonic() < stop_at and not stop_event.is_set():
        await asyncio.sleep(1)
    stop_event.set()

    await asyncio.gather(*tasks, return_exceptions=True)
    reporter_task.cancel()
    final = await stats.snapshot()
    print("最终统计: " + json.dumps(final, ensure_ascii=False, sort_keys=True), flush=True)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
