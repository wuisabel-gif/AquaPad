#!/usr/bin/env python3
"""
aquapad_bridge — robot-agnostic ROS 2 node bridging AquaPad <-> any ROS 2 robot.

Everything robot-specific lives in the profile YAML (topics, types, kill, deadman). This
node contains no vehicle names. It:
  * subscribes the telemetry topics named in the profile, tracks per-topic freshness
  * publishes the kill topic (E-stop, arm/disarm, and the heartbeat deadman)
  * terminates the phone link: UDP heartbeat in, UDP telemetry out, WebSocket commands (acked)

ROS runs on rclpy timers; the UDP + WebSocket I/O runs on an asyncio loop in a side thread.
Shared state is guarded by a lock.

Run (on a machine with ROS 2 + rclpy):
    python3 bridge/aquapad_bridge.py --profile profiles/barracuda.yaml
"""
import argparse
import asyncio
import importlib
import json
import socket
import threading
import time

import yaml

import rclpy
from rclpy.node import Node
from rclpy.qos import QoSProfile, DurabilityPolicy, ReliabilityPolicy


def import_msg(type_str):
    """'std_msgs/Bool' -> the std_msgs.msg.Bool class."""
    pkg, name = type_str.split("/")
    return getattr(importlib.import_module(f"{pkg}.msg"), name)


def dotted_get(obj, path):
    for part in path.split("."):
        obj = getattr(obj, part)
    return obj


def quat_to_euler(q):
    """geometry quaternion -> [roll, pitch, yaw]."""
    x, y, z, w = q.x, q.y, q.z, q.w
    import math
    roll = math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y))
    pitch = math.asin(max(-1.0, min(1.0, 2 * (w * y - z * x))))
    yaw = math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z))
    return [round(roll, 4), round(pitch, 4), round(yaw, 4)]


def extract(msg, spec):
    """Pull an AquaPad value out of a ROS message per the profile spec."""
    if spec.get("extract") == "quat_to_euler":
        q = getattr(msg, "orientation", None)
        if q is None:
            q = msg.pose.orientation
        return quat_to_euler(q)
    val = dotted_get(msg, spec["field"])
    if all(hasattr(val, a) for a in ("x", "y", "z")):
        return [round(val.x, 4), round(val.y, 4), round(val.z, 4)]
    return round(float(val), 4) if isinstance(val, (int, float)) else val


class AquaPadBridge(Node):
    def __init__(self, profile):
        super().__init__("aquapad_bridge")
        self.p = profile
        self.lock = threading.Lock()

        net = profile["network"]
        self.udp_listen_port = net["udp_listen_port"]
        self.udp_telemetry_port = net["udp_telemetry_port"]
        self.ws_port = net["ws_port"]
        self.telemetry_rate = net.get("telemetry_rate_hz", 20)

        safety = profile["safety"]
        self.hb_timeout = safety["heartbeat_timeout_sec"]
        self.auto_clear = safety.get("auto_clear_kill", False)

        self.armed = False
        self.kill_latched = False
        self.latch_reason = None
        self.last_hb = 0.0
        self.phone_addr = None
        self.latest = {}
        self.tx_seq = 0
        self.udp_tx = None

        ks = safety["kill"]
        self.KillMsg = import_msg(ks["type"])
        kq = QoSProfile(depth=1, reliability=ReliabilityPolicy.RELIABLE)
        if ks.get("transient_local"):
            kq.durability = DurabilityPolicy.TRANSIENT_LOCAL
        self.kill_pub = self.create_publisher(self.KillMsg, ks["topic"], kq)
        self.heartbeat_age_pub = self.create_publisher(
            import_msg("std_msgs/Float32"), "/aquapad/heartbeat_age", 10)
        self.StringMsg = import_msg("std_msgs/String")
        self.marker_pub = self.create_publisher(self.StringMsg, "/aquapad/marker", 10)

        self.specs = {}
        for field, spec in profile.get("telemetry", {}).items():
            self._subscribe(field, spec)
            fb = spec.get("fallback")
            if fb:
                self._subscribe(field + "__fallback", fb)
            self.specs[field] = spec

        self.mission = profile.get("mission", {})
        self.mission_pubs = {}
        for key in ("start", "stop"):
            m = self.mission.get(key)
            if m and m.get("kind") == "topic":
                self.mission_pubs[key] = self.create_publisher(
                    import_msg(m["type"]), m["name"], 10)

        self.create_timer(1.0 / self.telemetry_rate, self._tick)
        self._publish_kill(True)
        self.get_logger().info(f"aquapad_bridge up for profile '{profile['name']}'")

    def _subscribe(self, key, spec):
        msg_cls = import_msg(spec["type"])
        qos = QoSProfile(depth=1, reliability=ReliabilityPolicy.BEST_EFFORT)
        self.create_subscription(
            msg_cls, spec["topic"],
            lambda m, k=key, s=spec: self._on_telem(k, s, m), qos)

    def _on_telem(self, key, spec, msg):
        try:
            val = extract(msg, spec)
        except Exception as e:  # noqa: BLE001
            self.get_logger().warn(f"extract failed for {key}: {e}")
            return
        with self.lock:
            self.latest[key] = (val, time.monotonic())

    def _publish_kill(self, value):
        self.kill_pub.publish(self.KillMsg(data=bool(value)))

    def _latch_kill(self, reason, drop_intent):
        if not self.kill_latched:
            self.kill_latched = True
            self.latch_reason = reason
            if drop_intent:
                self.armed = False
            self._publish_kill(True)
            self.get_logger().warn(f"KILL latched: {reason}")

    def apply_command(self, cmd):
        t = cmd.get("type")
        with self.lock:
            if t in ("estop", "disarm"):
                self._latch_kill(t, drop_intent=True)
                return True, t
            if t == "arm":
                self.kill_latched = False
                self.armed = True
                self.latch_reason = None
                self._publish_kill(False)
                return True, "armed"
            if t == "marker":
                label = (cmd.get("args") or {}).get("label", "")
                self.marker_pub.publish(self.StringMsg(data=label))
                self.get_logger().info(f"MARKER {cmd.get('args', {})}")
                return True, "marker logged"
            if self.kill_latched:
                return False, "rejected: kill latched, send 'arm'"
            if t in ("mission_start", "mission_stop"):
                key = "start" if t == "mission_start" else "stop"
                pub = self.mission_pubs.get(key)
                if pub is not None:
                    Msg = import_msg(self.mission[key]["type"])
                    pub.publish(Msg(data=True))
                    return True, f"mission {key}"
                return False, f"no mission {key} configured"
        return False, f"unknown command {t!r}"

    def _tick(self):
        now = time.monotonic()
        with self.lock:
            hb_age = now - self.last_hb if self.last_hb else 1e9
            if hb_age > self.hb_timeout:
                self._latch_kill(f"deadman: hb_age {hb_age:.2f}s", drop_intent=False)
            elif (self.kill_latched and (self.latch_reason or "").startswith("deadman")
                  and self.auto_clear and self.armed):
                self.kill_latched = False
                self.latch_reason = None
                self._publish_kill(False)
            frame = self._frame(hb_age)
            addr = self.phone_addr
        self.heartbeat_age_pub.publish(import_msg("std_msgs/Float32")(data=float(min(hb_age, 1e6))))
        if addr and self.udp_tx:
            self.udp_tx.sendto(json.dumps(frame).encode(), (addr, self.udp_telemetry_port))

    def _frame(self, hb_age):
        self.tx_seq += 1
        now = time.monotonic()
        frame = {"t": round(time.time(), 3), "seq": self.tx_seq,
                 "armed": self.armed, "kill_latched": self.kill_latched,
                 "hb_age": round(hb_age, 3), "health": {}}
        for field in self.specs:
            entry = self.latest.get(field) or self.latest.get(field + "__fallback")
            if entry is None:
                frame["health"][field] = "nodata"
                continue
            val, stamp = entry
            frame[field] = val
            frame["health"][field] = "ok" if (now - stamp) < (2.0 / self.telemetry_rate) else "stale"
        return frame


class UdpProtocol(asyncio.DatagramProtocol):
    def __init__(self, node):
        self.node = node

    def connection_made(self, transport):
        self.node.udp_tx = transport

    def datagram_received(self, data, addr):
        try:
            msg = json.loads(data.decode())
        except (ValueError, UnicodeDecodeError):
            return
        if msg.get("type") == "hb":
            with self.node.lock:
                self.node.last_hb = time.monotonic()
                self.node.phone_addr = addr[0]


def start_network_thread(node):
    import websockets

    async def runner():
        loop = asyncio.get_running_loop()
        await loop.create_datagram_endpoint(
            lambda: UdpProtocol(node), local_addr=("0.0.0.0", node.udp_listen_port))

        async def ws_handler(ws):
            async for raw in ws:
                try:
                    cmd = json.loads(raw)
                except ValueError:
                    await ws.send(json.dumps({"ack": None, "ok": False, "detail": "bad json"}))
                    continue
                ok, detail = node.apply_command(cmd)
                await ws.send(json.dumps({"ack": cmd.get("seq"), "ok": ok, "detail": detail}))

        async with websockets.serve(ws_handler, "0.0.0.0", node.ws_port):
            await asyncio.Future()

    threading.Thread(target=lambda: asyncio.run(runner()), daemon=True).start()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", required=True)
    args = ap.parse_args()
    with open(args.profile) as f:
        profile = yaml.safe_load(f)

    rclpy.init()
    node = AquaPadBridge(profile)
    start_network_thread(node)
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
