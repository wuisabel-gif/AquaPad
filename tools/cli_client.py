#!/usr/bin/env python3
"""
cli_client.py — a terminal stand-in for the AquaPad phone.

Exercises the full protocol against fake_robot.py (or the real bridge) with no Android:
  * sends UDP heartbeats at the profile's rate (drives the deadman)
  * listens for UDP telemetry and prints a one-line HUD
  * sends acked commands typed at the prompt

Run:  python3 tools/cli_client.py profiles/barracuda.yaml
      python3 tools/cli_client.py profiles/barracuda.yaml --host 192.168.1.50

Commands (type at the prompt):  arm  disarm  estop  start [plan]  stop  marker [label]
Try: start, then stop sending heartbeats (Ctrl-C the client) and watch the robot latch a
deadman kill on its side.
"""
import argparse
import asyncio
import json
import socket
import sys
import time

import yaml
import websockets


async def heartbeat_loop(sock, dest, hz):
    period = 1.0 / hz
    seq = 0
    while True:
        seq += 1
        sock.sendto(json.dumps({"type": "hb", "seq": seq, "t": time.time()}).encode(), dest)
        await asyncio.sleep(period)


async def telemetry_loop(listen_port):
    loop = asyncio.get_running_loop()
    rsock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rsock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    rsock.bind(("0.0.0.0", listen_port))
    rsock.setblocking(False)
    while True:
        data = await loop.sock_recv(rsock, 65535)
        try:
            f = json.loads(data.decode())
        except ValueError:
            continue
        safety = "KILL" if f.get("kill_latched") else ("ARM" if f.get("armed") else "safe")
        bits = [f"[{safety}]", f"hb={f.get('hb_age')}s"]
        for k in ("depth", "batt", "rpy", "dvl_vel"):
            if k in f:
                bits.append(f"{k}={f[k]}")
        if f.get("mission_active"):
            bits.append("mission")
        sys.stdout.write("\r" + "  ".join(bits) + " " * 8 + "\n")


async def command_loop(ws_url):
    seq = 1000
    async with websockets.connect(ws_url) as ws:
        print(f"connected commands -> {ws_url}")
        loop = asyncio.get_running_loop()
        while True:
            line = (await loop.run_in_executor(None, sys.stdin.readline)).strip()
            if not line:
                continue
            parts = line.split(maxsplit=1)
            verb, rest = parts[0], (parts[1] if len(parts) > 1 else "")
            cmd = {"seq": seq, "t": time.time()}
            seq += 1
            if verb in ("arm", "disarm", "estop"):
                cmd["type"] = verb
            elif verb == "start":
                cmd.update(type="mission_start", args={"plan": rest or "default"})
            elif verb == "stop":
                cmd["type"] = "mission_stop"
            elif verb == "marker":
                cmd.update(type="marker", args={"label": rest})
            elif verb in ("q", "quit", "exit"):
                return
            else:
                print(f"? unknown: {verb}")
                continue
            await ws.send(json.dumps(cmd))
            ack = json.loads(await ws.recv())
            print(f"  ack#{ack.get('ack')} ok={ack.get('ok')} {ack.get('detail','')}")


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("profile")
    ap.add_argument("--host", default="127.0.0.1", help="robot/bridge host")
    args = ap.parse_args()
    with open(args.profile) as f:
        p = yaml.safe_load(f)
    net = p["network"]

    hb_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    hb_dest = (args.host, net["udp_listen_port"])
    ws_url = f"ws://{args.host}:{net['ws_port']}"

    print(f"AquaPad CLI -> {args.host}  (profile: {p['name']})")
    print("commands: arm | disarm | estop | start [plan] | stop | marker [label] | quit")
    await asyncio.gather(
        heartbeat_loop(hb_sock, hb_dest, net.get("heartbeat_expected_hz", 50)),
        telemetry_loop(net["udp_telemetry_port"]),
        command_loop(ws_url),
    )


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
