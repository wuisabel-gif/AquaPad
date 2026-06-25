# Profiles

A **profile** is the only thing that ties AquaPad to a specific robot. Everything else
(app, simulator, bridge) is robot-agnostic and reads the profile at startup. To support a
new vehicle, write a new YAML file here — no code changes.

A profile declares four things:

| section | what it does |
|---|---|
| `network` | ports/rates for the phone ↔ bridge link |
| `safety`  | kill topic + deadman timing + re-arm policy |
| `telemetry` | map each AquaPad HUD field → a ROS topic, type, and value path |
| `mission` | how `mission_start` / `mission_stop` reach the robot |

The **canonical AquaPad field names** (the keys the app/HUD understand) are fixed:
`depth`, `rpy`, `dvl_vel`, `batt`, plus a free-form `health` map. A profile's job is to say
*where each of those comes from on this particular robot*. A field you omit simply doesn't
show in the HUD.

Value paths are dotted (`twist.twist.linear`); `extract: quat_to_euler` is a built-in
helper for turning a pose/IMU quaternion into `[roll, pitch, yaw]`. `fallback:` gives a
second source used when the primary goes stale.

See `barracuda.yaml` (a real robot) and `generic_auv.yaml` (a minimal starting template).
