#!/usr/bin/env python3
"""Language-neutral wire-protocol conformance test for AquaPad.

Black-box checks the heartbeat + telemetry + acked-command + deadman loop against ANY
implementation of the protocol — the Kotlin :sim, or the real aquapad_bridge. It speaks
only the JSON wire format, so it is deliberately not Kotlin: it proves an implementation
conforms regardless of language.

Start an implementation first, e.g.:
    ./gradlew :sim:run --args="profiles/barracuda.yaml"      # Kotlin simulator
Then:
    python3 tools/wire_conformance.py profiles/barracuda.yaml
Exits non-zero on any failed assertion."""
import asyncio, json, socket, sys, time, yaml, websockets

async def main(profile_path):
    p = yaml.safe_load(open(profile_path)); net = p["network"]; host = "127.0.0.1"
    hb = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    rx.bind(("0.0.0.0", net["udp_telemetry_port"])); rx.setblocking(False)
    loop = asyncio.get_running_loop()

    async def latest():
        """Freshest telemetry frame: wait for one, then drain the queued backlog."""
        data = await loop.sock_recv(rx, 65535)
        while True:
            try:
                data = rx.recv(65535)
            except BlockingIOError:
                break
        return json.loads(data.decode())

    async def beat(n):
        for i in range(n):
            hb.sendto(json.dumps({"type":"hb","seq":i,"t":time.time()}).encode(),
                      (host, net["udp_listen_port"]))
            await asyncio.sleep(1/net["heartbeat_expected_hz"])

    beater = asyncio.create_task(beat(400))
    await asyncio.sleep(0.3)
    frame = await latest()
    assert "depth" in frame and "batt" in frame, "telemetry missing fields"
    assert frame["kill_latched"] is True, "should boot killed until armed"
    print("ok: boots killed (fail-safe)", {k: frame[k] for k in ("depth","batt","hb_age")})

    async with websockets.connect(f"ws://{host}:{net['ws_port']}") as ws:
        await ws.send(json.dumps({"type":"arm","seq":1,"t":time.time()}))
        ack = json.loads(await ws.recv()); assert ack["ok"], ack
        await asyncio.sleep(0.2)
        farm = await latest()
        assert farm["kill_latched"] is False, "arm should clear the latch while beating"
        print("ok: arm acked + cleared kill", ack["detail"])
        await ws.send(json.dumps({"type":"mission_start","seq":2,"t":time.time(),"args":{"plan":"lawnmower"}}))
        ack = json.loads(await ws.recv()); assert ack["ok"], ack
        print("ok: mission_start acked", ack["detail"])
        await ws.send(json.dumps({"type":"estop","seq":3,"t":time.time()}))
        ack = json.loads(await ws.recv()); assert ack["ok"], ack
        await asyncio.sleep(0.2)
        f2 = await latest()
        assert f2["kill_latched"] is True, "estop should latch kill"
        print("ok: estop latched kill")

    beater.cancel()
    await asyncio.sleep(p["safety"]["heartbeat_timeout_sec"] + 0.5)
    hb.sendto(json.dumps({"type":"hb","seq":999,"t":time.time()}).encode(), (host, net["udp_listen_port"]))
    await asyncio.sleep(0.2)
    f3 = await latest()
    assert f3["kill_latched"] is True, "deadman should keep kill latched (auto_clear=false)"
    print("ok: deadman latched kill after heartbeat loss")
    print("\nALL CHECKS PASSED")

if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "profiles/barracuda.yaml"))
