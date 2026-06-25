# aquapad_bridge — ROS 2 node spec

The single ROS 2 node that sits on the Jetson between AquaPad (phone) and the
[Barracuda](https://github.com/usc-robosub/barracuda_ws) stack. It owns the network sockets, translates JSON ↔ ROS messages, and — critically —
runs the **link-loss deadman** that makes losing the phone a safe event.

It does **not** re-implement actuation safety. It publishes to the robot's existing kill
topic (e.g. `/barracuda/kill`); the robot's own command-timeout watchdog remains the floor
underneath it.

> **Config-driven.** This node hard-codes no robot. Topic names, message types, the kill
> topic, and deadman timing all come from a **profile** (`profiles/*.yaml`, see
> `profiles/README.md`). The Barracuda values below are one profile's contents shown as a
> concrete example; the parameter *defaults* are the fallbacks when a profile omits them.
> The runnable implementation is [`bridge/aquapad_bridge.py`](../bridge/aquapad_bridge.py).

---

## Responsibilities

1. Terminate the two phone-facing transports (UDP for heartbeat/telemetry, WebSocket/TCP for acked commands).
2. Translate AquaPad commands → ROS 2 publishes / action calls.
3. Sample Barracuda telemetry topics → pack stamped JSON → UDP to the phone.
4. Run the **heartbeat deadman**: if heartbeats stop, latch a software kill.
5. Ack every reliable command back over the WebSocket with the echoed `seq`.

---

## Parameters

| param | type | default | meaning |
|---|---|---|---|
| `udp_listen_port` | int | `9870` | phone → bridge heartbeat/control |
| `udp_telemetry_addr` | string | `""` | phone IP, learned from first heartbeat if empty |
| `udp_telemetry_port` | int | `9871` | bridge → phone telemetry |
| `ws_port` | int | `9880` | acked command channel |
| `heartbeat_timeout_sec` | double | `0.75` | no heartbeat for this long → latch software kill |
| `heartbeat_expected_hz` | double | `50.0` | informational; drives staleness math on the phone |
| `telemetry_rate_hz` | double | `20.0` | outbound telemetry pack rate |
| `kill_topic` | string | `/barracuda/kill` | software-kill output |
| `auto_clear_kill` | bool | `false` | if false, a deadman kill requires explicit re-arm (recommended) |
| `frame_id` | string | `barracuda/base_link` | stamp frame for any republished setpoints |

`heartbeat_timeout_sec` **must be ≥** the phone's heartbeat period and **≤** the thruster
node's `cmd_timeout_sec`, so the bridge latches the kill *before* the thruster watchdog
would have to — the phone path fails first and visibly, hardware watchdog is the backstop.

---

## ROS interfaces

### Publishes
| topic | type | when |
|---|---|---|
| `/barracuda/kill` | `std_msgs/Bool` | E-stop pressed, arm/disarm, **or deadman trip** (latched `true`) |
| `/aquapad/heartbeat_age` | `std_msgs/Float32` | every tick — for the rest of the stack / rosbag to see link health |
| body twist setpoint *(option A only)* | `geometry_msgs/TwistStamped` | stick packets, into the control stack — never raw `cmd_thrust` |

### Subscribes (telemetry sources)
| topic | type | → phone field |
|---|---|---|
| `/barracuda/ping1d/range` *(or pressure)* | `sensor_msgs/Range` | `depth` |
| `/barracuda/estimated_pose` | `geometry_msgs/PoseStamped` | `rpy` (quat→euler) |
| `/barracuda/vectornav/imu` *(fallback)* | `sensor_msgs/Imu` | `rpy` if pose stale |
| `/barracuda/dvl/odometry` | `nav_msgs/Odometry` | `dvl_vel` (twist.linear) |
| battery topic | `sensor_msgs/BatteryState` | `batt` |

### Action / service clients
| name | type | when |
|---|---|---|
| mission start/stop | nav action (or mission topic) | `mission_start` / `mission_stop` commands |

Each subscription tracks **last-message time**; if `now - last > 1/expected_rate × N`, the
corresponding `health.<sensor>` goes `"stale"` in the outbound frame. Health is computed by
the bridge, not trusted from the source — a topic that simply stops is the failure we care about.

---

## Heartbeat → deadman handshake

```
phone ──UDP {"hb", seq, t}──► bridge        ~50 Hz
                                  │ records last_hb = clock.now()
                                  │
       ◄──UDP telemetry frame────┘           telemetry_rate_hz, includes hb_age + armed
```

Deadman timer (runs at `telemetry_rate_hz` or faster):

```python
def on_timer():
    age = clock.now() - last_hb
    publish(heartbeat_age, age)

    if age > heartbeat_timeout_sec and not kill_latched:
        kill_latched = True
        publish(kill, Bool(data=True))          # software kill
        log.warn(f"deadman: no heartbeat for {age:.2f}s -> KILL")

    # latched kill does NOT auto-clear on heartbeat return unless auto_clear_kill
    if kill_latched and age <= heartbeat_timeout_sec and auto_clear_kill:
        kill_latched = False
        publish(kill, Bool(data=False))
```

Re-arm path (recommended, `auto_clear_kill=false`): heartbeats returning is **not** enough.
The operator must press arm on the phone, which sends an acked `arm` command; only then does
the bridge publish `kill=false`. This prevents a flapping link from auto-re-energizing thrusters.

---

## Command channel (WebSocket, acked)

Every inbound command is echoed with its `seq` and an outcome:

```jsonc
// in
{ "type": "estop",         "seq": 1042, "t": 1782337255.98 }
{ "type": "arm",           "seq": 1043, "t": 1782337260.10 }
{ "type": "mission_start", "seq": 1044, "t": 1782337262.00, "args": {"plan": "lawnmower_1"} }
{ "type": "marker",        "seq": 1045, "t": 1782337270.33, "args": {"label": "saw target"} }

// out (ack)
{ "ack": 1042, "ok": true }
{ "ack": 1044, "ok": true,  "detail": "mission goal accepted" }
{ "ack": 1044, "ok": false, "detail": "mission server unavailable" }
```

| command | bridge action |
|---|---|
| `estop` | publish `kill=true`, latch; ack |
| `arm` | clear latch → `kill=false` (only path that re-energizes); ack |
| `disarm` | publish `kill=true`, latch; ack |
| `mission_start` | send action goal; ack on accept, surface reject |
| `mission_stop` | cancel goal; ack |
| `marker` | write a stamped marker to log + republish on `/aquapad/marker` for the bag; ack |

Unknown `type`, or any command while kill is latched (except `arm`), → `ok:false` with a reason
the HUD shows. Safety commands are never silently dropped.

---

## QoS

- `/barracuda/kill`: **reliable**, `transient_local` (depth 1) — a late-joining thruster node still sees the latest kill state.
- telemetry subscriptions: **best-effort**, `keep_last` depth 1 — latest-wins, matches sensor publishers.
- twist setpoint (option A): best-effort, depth 1.

---

## Outbound telemetry frame (bridge → phone, UDP)

```json
{ "t": 1782337256.01, "seq": 88123,
  "armed": true, "kill_latched": false, "hb_age": 0.018,
  "depth": 1.8, "rpy": [0.01,-0.02,1.57],
  "dvl_vel": [0.12,0.0,-0.01], "batt": 0.86,
  "health": {"dvl":"ok","imu":"ok","pose":"ok","cam":"stale"} }
```

`seq` lets the phone detect drops/reorder; `hb_age`/`kill_latched`/`armed` let the HUD render the
true safety state rather than inferring it.

---

## MVP shortcut vs. full node

- **MVP:** skip this node entirely — run `rosbridge_server` and have AquaPad talk JSON-over-WebSocket
  directly to `/barracuda/kill` and the telemetry topics. Proves the loop; **no UDP, no deadman**
  (so MVP relies solely on the thruster `cmd_timeout_sec` floor — acceptable on the bench, not in water).
- **Full `aquapad_bridge`:** adds UDP high-rate telemetry, the heartbeat deadman, command acks, and
  bridge-side staleness/health. This is what makes the in-water safety story true.

---

## Open items to confirm against the real stack

1. Exact battery topic name/type on Barracuda.
2. Mission interface — action type & server name (or topic) for start/stop.
3. Whether `/barracuda/kill` is `transient_local` on the thruster side (match it).
4. The thruster node's actual `cmd_timeout_sec` value, to set `heartbeat_timeout_sec` safely below it.
