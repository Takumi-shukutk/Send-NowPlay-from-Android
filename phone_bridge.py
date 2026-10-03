"""スマホ(Androidアプリ)からの再生情報を受信し、操作コマンドを送るWebSocketサーバ。
単体実行: python phone_bridge.py --port 8765 --token 合言葉
lyrics_viewer.py からは --source phone で利用される。"""
import argparse
import asyncio
import dataclasses
import json
import os
import socket
import sys
import threading
import time

import websockets

from nowplaying import Track


def local_ips():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))  # 実際にはパケットは送られない
        ip = s.getsockname()[0]
        s.close()
        return [ip]
    except OSError:
        return ["(不明)"]


class BridgeServer(threading.Thread):
    def __init__(self, host="0.0.0.0", port=8765, token=""):
        super().__init__(daemon=True)
        self.host, self.port, self.token = host, port, token
        self.track = Track()
        self.connected = False
        self._loop = None
        self._clients = set()

    def describe(self):
        return f"待受: {', '.join(f'{ip}:{self.port}' for ip in local_ips())}  token={'あり' if self.token else 'なし'}"

    def run(self):
        asyncio.run(self._main())

    async def _main(self):
        self._loop = asyncio.get_running_loop()
        async with websockets.serve(self._handler, self.host, self.port):
            await asyncio.Future()

    async def _handler(self, ws):
        if self.token and ws.request.headers.get("Authorization") != f"Bearer {self.token}":
            await ws.close(1008, "unauthorized")
            return
        self._clients.add(ws)
        self.connected = True
        print("[bridge] phone connected", flush=True)
        try:
            async for raw in ws:
                try:
                    m = json.loads(raw)
                except ValueError:
                    continue
                if m.get("type") == "state":
                    self._apply(m)
        finally:
            self._clients.discard(ws)
            self.connected = bool(self._clients)
            t = self.track
            self.track = dataclasses.replace(t, position=t.now_position(), playing=False,
                                             updated_at=time.monotonic())
            print("[bridge] phone disconnected", flush=True)

    def _apply(self, m):
        self.track = Track(
            title=m.get("title", ""), artist=m.get("artist", ""), album=m.get("album", ""),
            duration=max(0, m.get("duration_ms", 0)) / 1000.0,
            position=max(0, m.get("position_ms", 0)) / 1000.0,
            playing=bool(m.get("playing")), speed=float(m.get("speed", 1.0) or 1.0),
            updated_at=time.monotonic(),
        )

    def send(self, action, **kw):
        """action: play / pause / toggle / next / prev / seek(position_ms=...)"""
        if not self._loop:
            return
        msg = json.dumps({"type": "cmd", "action": action, **kw})

        async def _bc():
            for c in list(self._clients):
                try:
                    await c.send(msg)
                except Exception:
                    pass

        asyncio.run_coroutine_threadsafe(_bc(), self._loop)


def _stdin_loop(srv):
    for line in sys.stdin:
        c = line.split()
        if not c:
            continue
        if c[0] == "p":
            srv.send("toggle")
        elif c[0] == "n":
            srv.send("next")
        elif c[0] == "b":
            srv.send("prev")
        elif c[0] == "s" and len(c) > 1:
            srv.send("seek", position_ms=int(float(c[1]) * 1000))
        elif c[0] == "q":
            os._exit(0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--token", default="")
    a = ap.parse_args()
    srv = BridgeServer(port=a.port, token=a.token)
    srv.start()
    print(srv.describe())
    print("コマンド: p=再生/停止  n=次  b=前  s <秒>=シーク  q=終了")
    threading.Thread(target=_stdin_loop, args=(srv,), daemon=True).start()
    last = None
    while True:
        t = srv.track
        cur = (t.key(), t.playing)
        if cur != last and t.title:
            print(f"{'▶' if t.playing else '⏸'} {t.title} / {t.artist} ({t.now_position():.0f}/{t.duration:.0f}s)", flush=True)
        last = cur
        time.sleep(0.5)


if __name__ == "__main__":
    main()
