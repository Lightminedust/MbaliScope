# MbaliScope

**A local network scanner and process observatory, drawn as living maps.**

MbaliScope looks at two things on your machine and renders each of them as a map rather than a table:

- **Network**: the devices on your local network, drawn as a constellation of particle spheres. Your computer and your router are separate systems, and their ports, peripherals, services and clients orbit them. Each sphere is built in layers that say what is known about the device.
- **Processus**: the processes running on your computer, drawn as a spacetime of particles. Memory gives each family its size, and measured CPU load gives it colour, speed and a black horizon that bends the space around it.

Both maps follow the same rule: **every visual property comes from a calculation, and anything simulated is labelled as such.** This README documents those calculations.
---

## Contents

1. [Principles](#principles)
2. [Requirements, build and run](#requirements-build-and-run)
3. [The network map](#the-network-map)
4. [The process spacetime](#the-process-spacetime)
5. [Camera and projection](#camera-and-projection)
6. [Controls](#controls)
7. [Measured or simulated](#measured-or-simulated)
8. [Project layout](#project-layout)
9. [Tests and verification](#tests-and-verification)
10. [Known limitations](#known-limitations)
11. [Safety and privacy](#safety-and-privacy)
12. [Notes](#notes)

---

## Principles

- **The map has no centre.** Your computer is not the middle of your network. The router is a device in its own right, and both are drawn as peer systems kept apart by a provable minimum distance.
- **The orbit *is* the link.** Anything that revolves around a device belongs to it. The only line that states a fact is the one between two systems, which an orbit cannot express; the lines that light up between neighbours are decorative and labelled as such.
- **Sizes are true.** Every object has a size in world units, not in screen pixels. Zooming in makes it grow, like a body on a star chart, and nothing inflates when you zoom out.
- **Size means something.** On the network map, size is how much is known about a device. On the process map, size is memory, to scale.
- **Things stay where they are.** Positions are derived from identifiers and kept from one reading to the next. A change glides into place instead of jumping or fading.
- **When nothing is known, the map says so.** A device that answers no naming protocol is labelled with the reason. A device whose attachment cannot be established orbits nothing.

---

## Requirements, build and run

- **JDK 21**: the Gradle toolchain downloads one if needed.
- **No manual JavaFX install**: `org.openjfx.javafxplugin` adds JavaFX 21.0.5 with the right platform classifier.
- **Windows** for process collection, hardware inventory and port ownership (see [limitations](#known-limitations)).

```bash
./gradlew run                          # launch on the network map
./gradlew run --args="--processes"     # launch directly on the process spacetime
./gradlew test                         # run the test suite
./gradlew build                        # compile, test and assemble
```

On Windows, use `gradlew.bat`.

**Self-contained Windows application**, with the MbaliScope icon:

```powershell
.\gradlew.bat :app:packageWindows
```

The executable is written to `app\build\jpackage\MbaliScope\MbaliScope.exe`.

**Render either map to PNG without opening a window.** This draws the real JavaFX canvas and reports the time per frame:

```bash
./gradlew :app:renderProcessPreview                 # process map, deterministic fixture
./gradlew :app:renderProcessPreview -PlivePreview   # process map, two real readings of this machine
./gradlew :app:renderNetworkPreview                 # network map, invented network, no scan
```

Images are written to `app/build/previews/`, which Git ignores. The process task also checks that pausing freezes the image while new readings keep arriving. The network task uses example addresses and locally-administered MAC addresses only: no real device appears in it.

> **Gradle configuration cache.** The `run` task is declared incompatible with it, because `org.openjfx.javafxplugin` 0.1.0 (its last release) reads the project at execution time. The cache is skipped for that task only; the "problems were found storing the configuration cache" messages printed by `run` are expected and do not indicate a failure.

---

## The network map

### What is discovered, and what each source proves

| Source | What it establishes |
| --- | --- |
| TCP connect to 13 ports (80, 443, 445, 22, 53, 139, 554, 631, 3306, 5000, 8080, 8443, 9100), 300 ms timeout, 96 hosts at a time | open, closed or filtered; a refused connection still proves a host is alive |
| ARP table (`arp -a`) | IPv4 neighbours that answered at link level, even with every port filtered |
| Neighbour table (`netsh` / `ndp` / `ip -6 neigh`) | IPv6 neighbours, where most modern devices live |
| Ping to `ff02::1` on each interface | wakes IPv6 hosts: every awake IPv6 host must answer the all-nodes address |
| Routing table | the real default gateway, which is often not `.1` |
| `netstat` + `tasklist` | listening ports and the process that owns each one |
| `Get-PnpDevice` | attached hardware (controllers, audio, Bluetooth…) |
| Bluetooth property `{83DA6326-97A6-4088-9453-A1923F573B29} 15` | whether a paired Bluetooth device is actually connected right now |
| SSDP / UPnP (1.2 s timeout, 2.5 s window) | friendly name, manufacturer and model, for equipment that implements it |
| Reverse DNS | a hostname, when a PTR record exists |
| MAC address | vendor OUI prefix, and detection of randomised addresses |

Addresses belonging to the same physical machine are **merged by MAC**. On a real home network, fifteen addresses turned out to be three machines, because a phone holds its link-local address plus several privacy addresses at once.

A **live pulse** measures latency to the gateway every 6 seconds: a TCP connect to port 80 with a 700 ms timeout.

### Who orbits whom

The dependencies come from the links in the model, never from the layout. The carrier of a device is the source of the link that targets it. Loops are cut, so every chain reaches a root.

A device is a **system root** if it is the observing machine, or if something depends on it. A device with no established attachment is placed in the **outer field**, beyond every system, rather than being attached to one without proof.

### Where systems sit

System roots are placed on a ring whose radius is **computed so that two systems can never come closer than a gap $G$**, whatever their drift.

With $n$ systems, each root takes a sector of $2\pi/n$, is scattered within it by a fraction $j = 0.34$, and scattered in radius by $s = 0.08$. The worst-case chord between two neighbours must exceed the gap plus both drifts:

$$
R_{\text{ring}} = \frac{G + 2\cdot\text{ROOT\_DRIFT}}{2\,(1 - s)\,\sin\!\left(\dfrac{\pi}{n}(1 - j)\right)}
\qquad G = \max\!\Big(260\,000,\ 80\,000\,\Big\lceil\frac{E_1 + E_2 + 30\,000}{80\,000}\Big\rceil\Big),\ \ \text{ROOT\_DRIFT} = 14\,000
$$

$E_1$ and $E_2$ are the extents of the two widest systems: their cortege, plus every carried device with its own extent. The gap grows in steps of 80 000, so a discovery that barely widens a system does not move the whole map.

The root with index $i$ is placed at angle $0.37 + i\cdot\frac{2\pi}{n} + (u - 0.5)\cdot\frac{2\pi}{n}\cdot j$ and radius $R_{\text{ring}}\,(1 - s + 2su)$, where $u$ is drawn from the device identifier. The phase of 0.37 keeps two systems off a perfect horizontal axis. A single system sits at the origin.

### Where members sit: rings with reserved places

Nothing overlaps, and this is computed rather than tuned. Every object has a **footprint** $f$ (its visible radius plus its maximum drift), and objects are laid on rings.

**A ring with reserved places.** A ring is cut into equal places of about 600 units. On a ring of inner radius $r$ (after flattening), an object needs a half-angle

$$
\alpha = \frac{(f + g/2)\,\pi}{2\,r}
$$

and reserves enough consecutive places to cover $2\alpha$. Two objects that share no place are separated by an angle $\theta \ge \alpha_a + \alpha_b$. Since a chord is at least $2\theta r/\pi$ for $\theta \le \pi$, their centres stay at least $f_a + f_b + g$ apart. From one ring to the next, the radius grows by twice the largest footprint plus the gap, so the radial distance alone keeps two rings apart whatever the angles, and rings may turn at different speeds. Device orbits are flattened by 0.74, which brings two points at most 0.74 times closer, so their distances are divided by 0.74.

**Stable places.** Each object first claims the place drawn from its identifier, then the neighbouring places on either side, then the next ring. A newcomer only moves an existing object if it takes exactly that object's place.

**The cortege** (network adapters, then peripherals, then ports) starts at $1.9$ times the largest core the device could reach, not its current core, so a new port does not push every ring outwards. A port and its service share one place: the service orbits inside it at $4\,320$ units.

| Satellite | Radius | Footprint | Gap |
| --- | --- | --- | --- |
| Network adapter | 3 000 | 3 500 | 1 800 |
| Peripheral | 2 600 | 3 100 | 1 800 |
| Port with its service | 2 200 (service 1 100) | 6 040 | 1 800 |

Rings turn at $\omega = 0.01\sqrt{40\,000 / R}$ rad/s, in a direction specific to the device, and each object also drifts by 250 – 500 around its place (services by 60 – 120).

**Carried devices** sit beyond their carrier's cortege, group by group: gateways, then LAN peers, then Bluetooth devices, then remote servers. Their footprint is $1.6\,r_{\text{core}}$ (spikes included) plus their drift of 1 500 – 3 000, and the ring step uses the largest core the type could reach.

| Group | First ring at least at | Radial scatter |
| --- | --- | --- |
| Gateway around its carrier | 30 000 | 6 000 |
| LAN peer, local host | 60 000 | 10 000 |
| Bluetooth device | 78 000 | 4 000 |
| Remote server | 112 000 | 12 000 |
| Outer field (unattached) | ring + 180 000 – 260 000, at random | — |

### Drift

Every device drifts along a Lissajous curve that never closes, around the **moving** position of its carrier. That recursion is what lets a client follow its gateway:

$$
x(t) = x_{\text{carrier}}(t) + x_0 + d\,\sin(f_x t + \varphi_x)
\qquad
y(t) = y_{\text{carrier}}(t) + y_0 + 0.74\,d\,\sin(f_y t + \varphi_y)
$$

For a device, $f_x = 0.011 + (h \bmod 7)\cdot 0.0013$ and $f_y = 0.008 + (h \bmod 5)\cdot 0.0017$, where $h$ is the identifier hash. The amplitude $d$ is 14 000 for a root and 1 500 – 3 000 for a carried device. Satellites turn on their ring and drift slightly around their place, at frequencies of 0.045 – 0.175.

**Exclusion barrier.** No satellite may enter the core of its device. The rings already start beyond it; as a safeguard, a satellite closer than $1.9 \times$ the core radius is pushed back onto that limit along the same ray.

### Size is what is known

$$
r_{\text{core}} = \Big(b + \min\big(k,\ 1.6\,b\big)\Big)\cdot\left(1 + \frac{h \bmod 25}{100}\right)
\qquad
k = 220\,n_{\text{ports}} + 180\,n_{\text{peripherals}} + 130\,n_{\text{adapters}} + 670\,[\text{named}]
$$

The base $b$ is 10 000 for the gateway, 9 000 for the local host, 3 800 for a LAN peer, 4 200 for a Bluetooth device and 2 900 for a remote server. The largest core a type can reach is $b \times 2.6 \times 1.24$. The variation is capped at 24 %, so that it only nuances the difference in knowledge instead of reversing it.

### Lines

Every line is a single thin stroke, clipped at the visible edge of both objects and travelled by one point of light.

- **Between two systems** (fact): drawn for every model link whose two ends are system roots. It fades out when its far end leaves the screen.
- **Between two devices** (decorative): two devices that are not carrier and carried are joined when they come close, and the line breathes at its own pace:

$$
t = 1 - \frac{d}{120\,000},\quad \text{strength} = t^2(3 - 2t),\quad
\alpha = (0.05 + 0.3\,g)\cdot\text{strength},\quad
g = \operatorname{smoothstep}\big(0.3,\ 0.75,\ \tfrac12 + \tfrac12\sin(0.14\,t_s + \varphi)\big)
$$

- **Inside a cortege** (decorative): like a neural network, every satellite may link to its two nearest neighbours, device included. Each wanted link opens with $g = \operatorname{smoothstep}(0.35, 0.75, \tfrac12 + \tfrac12\sin(0.22\,t_s + \varphi))$, and the displayed opacity eases towards it with $\tau = 0.7$ s, so a link never pops when the nearest neighbours change.

### How the network is drawn

The network uses the same plate style as the process view: a black ground, particles drawn with one `fillRect` each, thin lines, capitals and a red accent.

**A device is a sphere built in layers.** Each layer is shown only when the corresponding fact is known:

| Layer | Drawing | Meaning |
| --- | --- | --- |
| Blue shell | translucent radial gradient, surface particles, plexus of nearest neighbours above 16 px | the device itself |
| Four circles | circles of 50° angular radius, centred on the vertices of a tetrahedron, faint behind and sharp in front | the device's body |
| Cage and bubbles | a dodecahedron at $1.12\,r$, and a bubble on each of the 12 icosahedron vertices at $1.2\,r$ | a hub: other devices go through it |
| Gold molecules | one cluster of 12 particles per open port, 12 at most | open ports |
| Green nucleus | a dense cluster at the centre, radius $0.16\,r$ | the device returned a real name |
| Spikes | lines towards Fibonacci directions, each ending in a pearl | its links: $12 + 6\,n_{\text{orbiting}}$ for the gateway, $10 + 4\,n_{\text{adapters}} + 6\,n_{\text{orbiting}}$ for the local host, $8 + 4\,n_{\text{ports}}$ for a peer, capped at 96 |

- **Gateway**: cage, circles, molecules, nucleus and spikes, without a shell.
- **Local host**: every layer.
- **LAN peer**: a shell and circles, plus molecules if it has open ports, a nucleus if it is named, and spikes if either is true. An anonymous peer with nothing known keeps a bare shell with bubbles.
- **Remote server**: a white geodesic wire with a cage, structure without matter.
- **Bluetooth device**: a beacon. A bright cyan core, three rings on planes 60° apart that turn like a gyroscope, each carrying a pearl, and three waves of particles that leave the core and fade out at 2.6 radii. Its orbit is drawn in cyan, and it is never drawn smaller than 5 px. A dormant beacon stops emitting, its rings nearly stop, and it is drawn at 45 % opacity.

**The cortege uses the same shapes, smaller.**

| Satellite | Shape |
| --- | --- |
| Network adapter | blue shell with its four circles and bubbles |
| Peripheral | white geodesic wire (42 vertices, 120 edges) in a cage |
| Port | gold dotted sphere (the geodesic edges drawn in particles), gold molecules, green centre |
| Service | a small spray of 22 cyan spikes |

Every sphere turns: its points are rotated by a yaw $\psi = 0.1\,t_s$ (0.16 for satellites, 0.03 when dormant) and tilted by an angle drawn from its identifier. Below 7 px a device becomes a cloud of 48 points, and below 3 px a dot, in the colour of its type.

**Space.** Grains sit on a world lattice of about 12 px and drift with noise. A fixed nebula makes them denser or sparser, but never leaves a black hole: every grain is kept with a probability of at least one half. There is no vortex and no dark veil around devices any more; both left black patches when zooming in.

**Orbits** are drawn as dotted particles, one dot every 7 px and a brighter one every tenth: an ellipse for every carried device (cyan for Bluetooth), and a circle for every ring of a cortege.

**Observation sheet.** Clicking a device or a satellite replaces the *Réseau.Série* panel with a live viewport and a reading:

- **a device** is shown with its rings, its cortege at relative places, and the devices orbiting it pulled in to the edge of the viewport;
- **a satellite** is shown enlarged, linked to its device and to its port or service;
- **rows**: type, address, MAC, presence, ports, hardware, carrier and orbiting devices; or kind, device, label, detail and, for a port, owning process, listening address and whether the service was confirmed;
- **interpretation**: what the layers mean for that object (hub, identified or anonymous, open ports, dormant Bluetooth, confirmed or indicative service), and which lines are facts.

The panel otherwise shows the cortege counts (shown / known) with a live miniature of each shape, the census of LAN and Bluetooth devices, and the live pulse with its latency sparkline. The latency is repeated in a vertical red band, like the CPU band of the process view.

---

## The process spacetime

### Reading the processes

Processes are read every **3 seconds** on a virtual thread, through PowerShell:

```powershell
Get-CimInstance Win32_Process |
  Select-Object ProcessId, ParentProcessId, Name, CreationDate, WorkingSetSize, KernelModeTime, UserModeTime |
  ConvertTo-Csv -NoTypeInformation
```

- **Dates** are converted to UTC ISO-8601.
- **CPU times** are counted in units of 100 ns and added with overflow-checked arithmetic.
- **Unknown values** stay unknown (`Optional`), never zero.
- **MbaliScope's own tools** (the PowerShell it launches, and its children) are removed, otherwise the measuring instrument would appear as a busy process.

**Identity.** A PID alone is not an identity, because Windows reuses them. Two readings describe the same process only if both the PID and the start time match.

**Process tree.** A process is attached to its parent only if all of these hold:

1. the parent PID is known;
2. it is not the process's own PID (PID 0 is its own parent);
3. the parent exists in the reading (otherwise the process is an orphan and becomes a root);
4. the parent is a kernel process (PID 0 or 4), or did not start after the child.

An unknown date keeps the link. The kernel exception is measured: on a real machine, `Secure System` and `Registry` are dated 4.7 s before `System`.

### CPU, measured between two readings

$$
\text{cpuShare} = \frac{\Delta(\text{kernel} + \text{user time})}{\Delta t \cdot \text{cores}}
$$

The share is clamped to $[0, 1]$. It is **0** for PID 0 (idle time is not consumption), for the first reading, and for a PID recycled by a different process. Two derived quantities drive the visuals:

$$
\text{cores used} = \text{cpuShare}\cdot\text{cores}
\qquad
c = \min\!\left(1,\ \sqrt{\frac{\text{cores used}}{2}}\right)
$$

The **load** $c$ saturates at two full cores. The square root makes a tenth of a core clearly visible without letting several cores overflow the scale.

### Families

Processes are grouped by lower-case executable name. Each group is a **family**:

- its **principal** is the oldest member whose parent belongs to a different family, that is, the one that was launched;
- the other members are its **sub-processes**, drawn as satellites.

This is an executable grouping, not an application identity or a complete parent-child tree.

### Memory is space

$$
\text{memoryShare} = \frac{\sum \text{working sets}}{\text{total RAM}}
\qquad
r_{\text{body}} = \max\!\left(60,\ 16\,000\,\sqrt{\text{memoryShare}}\right)
$$

The **area**, not the radius, is proportional to memory: four times the memory gives twice the radius. Shared working-set pages can be counted in several processes, so the total is not the operating system's used-RAM counter.

### CPU is mass: the well

Each family digs a well into space. Its depth is measured in cores used:

$$
D = 16\,000\,\Big(1 - e^{-1.2\sqrt{\text{cores used}}}\Big)
$$

The shape of the well has a reach $R$ (where space becomes flat again), a throat $a$ and a pull $P$:

$$
R = \max\!\big(5\,500,\ 1.38\,r_{\text{system}},\ 2.8\,r_{\text{body}} + 1\,400\big)
\qquad
a = \max\!\big(0.7\,r_{\text{body}} + 250,\ 0.16\,R\big)
\qquad
P = 0.22\sqrt{D / 16\,000}
$$

The reach **does not depend on CPU**. A busier family deepens its well without changing its territory, and a test holds this.

**Height** is a softened gravitational potential, brought back to flat at the reach without a crease:

$$
h(\rho) = -D\,\frac{\left(1 - \rho^2/R^2\right)^2}{\sqrt{1 + \rho^2/a^2}}\qquad (\rho < R)
$$

**Pull.** Space is drawn toward the centre along each ray:

$$
r' = r\,\Big(1 - P\,\big(1 - r^2/R^2\big)^2\Big)
\qquad
\frac{dr'}{dr} \ \ge\ 1 - P \ > 0
$$

Because the derivative stays positive, **space tightens but never folds**, even where several wells overlap. The maps are applied one after another, and a composition of such maps cannot fold either. The pull is capped at $P_{\max} = 0.22$ for everything that must stay collision-free. The background dust applies it **four times**, so the pull there reaches up to $1 - 0.78^4 \approx 63\,\%$, which keeps the curvature legible from almost directly above.

A spatial hash of wells, rebuilt each frame, lets each point test only the wells of its cell. Wells that pull are registered with a margin of $0.7 \times$ the widest pulling reach, because a point pulled by one well can enter the next; wells that do not pull need no margin. A test checks that the index gives exactly the same result as testing every well.

### Orbits that never collide

Sub-processes are placed on rings, the oldest on the innermost ring, so a new process takes the outside without disturbing the others. All the gaps are widened by the most that pull and tilt can shrink them:

$$
k = \frac{1}{(1 - P_{\max})\,\sin 65^\circ} \approx 1.41
$$

**Ring radii.** $w_j$ is the widest satellite radius on ring $j$, and 650 is the free clearance between surfaces:

$$
O_0 = \big(1.4\,r_{\text{body}} + 650 + w_0\big)\,k
\qquad
O_j = O_{j-1} + \big(w_{j-1} + 650 + w_j\big)\,k
$$

**Filling a ring.** Each satellite of radius $r_i$ claims a slot of width $s_i = 2.4\,k\,r_i + 40$. A ring accepts satellites while $\sum s_i \le 2\,O_j$. Each satellite receives an angle proportional to its slot, and the angles are stretched to fill the full turn. With these widths, the chord between two neighbours always exceeds the sum of their radii.

**Checking at every reading.** A satellite keeps its exact ring and angle as long as it still touches no one:

$$
d\,(1 - P_{\max})\sin 65^\circ \ \ge\ r_a + r_b + 50
$$

$d$ is the chord $2\,O\sin(\Delta\theta/2)$ on the same ring, or $|O_a - O_b|$ between rings. The check demands **exactly what the construction guarantees, and no more**.

This is measured. With a stricter check (1.25 × the radii + 80), a freshly built ring was judged too tight at the next reading. Its satellites were ejected to a farther ring, which failed in turn, and one family grew by 8 000 units every three seconds while pushing its neighbours across the map. Newcomers first reuse the slots left by exited processes before a new outer ring is opened.

**Kepler.** Farther orbits are slower, and the load accelerates a whole family slightly:

$$
\omega(r) = \min\!\left(\frac{2\pi}{140},\ \ \frac{2\pi}{200}\sqrt{1 + 0.35\min\!\left(1, \tfrac{D}{16\,000}\right)}\left(\frac{8\,000}{r}\right)^{3/2}\right)
$$

Every satellite on a ring shares the same $\omega$, so neighbours keep their spacing forever. No orbit completes a turn in less than 140 seconds. Speeds are integrated over time, so a change in load cannot make a phase jump.

**Beat.** Each satellite also oscillates along its ray by $\sin\!\big(t(0.35 + 2.2c) + 7\theta_0\big)\cdot\min(0.3\,r,\ 250)$. That amplitude stays below half the 650 clearance, so the beat can never cause a contact.

### Placement of families

Each family reserves a territory that already contains its largest possible horizon, the one it would have at full load:

$$
T = \max\!\Big(5\,500,\ 1.38\,r_{\text{system}},\ 2.8\,r_{\text{body}} + 1\,400,\ 1.1\,H(r_{\text{body}}, 1)\Big)
$$

- **A newcomer** needs $d \ge T_a + T_b + 15\,000$ from every placed family. It takes the first free slot of a golden-angle spiral: $p_k = 3\,000\sqrt{k}\,\big(\cos(2.39996\,k),\ \sin(2.39996\,k)\big)$.
- **A family already on the map keeps its position** as long as $d \ge T_a + T_b$. The 15 000 spacing acts as a margin when a satellite is added.
- **A family that is really overlapped** steps away along the separating direction by the overlap plus 50, up to 24 steps, instead of jumping to a distant spiral slot.

This is measured on a real machine. Before this rule, 116 of 118 families jumped by 50 000 to 290 000 units at every reading. After it, 0 families and 0 satellites moved over 11 consecutive readings.

### The horizon

A busy family opens a black horizon. It grows with **load, not size**, so even a tiny process that consumes a lot is visible:

$$
H(r_{\text{body}}, c) =
\begin{cases}
0 & c < 0.1\\[4pt]
r_{\text{body}}\,(1.2 + 1.5\,c) + 6\,000\,c^{0.8} & c \ge 0.1
\end{cases}
$$

$H$ is in world units, so it scales with zoom like everything else. Because the territory already reserves $H(r, 1)$, **no horizon can ever overlap a neighbour's**. A test checks this at full load.

**Gravitational lens.** Whatever lies behind the horizon is pushed onto its edge, like a mirror. On screen, with the Einstein radius $\theta_E = 1.12\,H$, a point at distance $r$ from the centre appears at

$$
r' = r + w\left(\frac{r + \sqrt{r^2 + 4\theta_E^2}}{2} - r\right)
\qquad
w = \text{smoothstep}\big(2.6\,\theta_E \rightarrow 1.4\,\theta_E,\ r\big)
$$

The effect fades out smoothly within the family's territory. No circle outlines the horizon: its edge is drawn by the dust the lens piles up.

**Vortex.** Around each horizon, a cloud of particles is wound into spiral arms that turn and slide into the horizon. Each particle progresses from $s = 0$ at the edge to $s = 1$ at the horizon, with outer radius $R_v = H\,(2.3 + 0.7c)$:

$$
r(s) = H + (R_v - H)(1 - s)^{1.4}
\qquad
\theta = \theta_{\text{arm}} + \delta + \tau\,\ln\frac{R_v}{r} + \Omega\,t
$$

- **Smooth motion.** The angle depends only on the radius and on time, continuously, so a particle winds tighter as it approaches and never turns abruptly. An earlier version drew a random spin per fall ($\theta_0 + s\,p^2$) and made grains swerve in every direction.
- **Arms.** A family has 2 or 3 arms, plus one above load 0.5. Twist is $\tau = \pm(2 + 1.6c)$ and rotation $\Omega = \pm(0.12 + 0.7c)$ rad/s. The spread $\delta$ around an arm narrows near the horizon.
- **Speed.** A particle completes $(0.04 + 0.3c)\times[0.7, 1.3]$ falls per second.
- **Density.** Smoothed noise forms clumps along the arms. A horizon holds $(70 + 490c)\cdot\min(3, \max(1, H_{\text{px}}/50))$ particles, capped at 1 500, so large horizons stay legible.

### Space and dark matter

**Dust.** Background dust sits on a loose lattice whose world spacing keeps about 15 px between grains on screen:

$$
\text{spacing} = 1\,000\cdot 2^{\lceil \log_2\left(15 / (1\,000\cdot\text{zoom})\right) \rceil}
$$

- **Zooming**: the intermediate lattice fades in, so zooming never makes grains pop.
- **Drift**: each grain wanders by up to ±0.8 cell, following smoothed value noise, and twinkles.
- **Nebula**: two octaves of world-anchored noise (scales 42 000 and 13 000) set the local density, $\rho = \text{smoothstep}(0.28, 0.78, n)$. A grain is kept with probability $0.25 + 0.75\rho$, so space has dense regions and voids instead of a uniform scatter.
- **Wells**: grains near a well are pulled by it (four passes) and tinted by the curvature.

**Dark matter.** Invisible clumps gather around families that are dense in memory:

- **Count**: $\text{round}\big(6\min(1, 4\sqrt{\text{memoryShare}})\big)$ per family, plus 24 clumps wandering between systems.
- **Orbit**: at $T\,(0.45 + 1.1u)$, breathing by ±12 %, at 0.012 – 0.042 rad/s.
- **Effect**: they deepen space slightly without pulling it, so the dust shimmers but no orbit is ever disturbed.

### Families, sub-processes and colour

**A family** is a sphere filled with moving particles:

- **Particles**: laid on shells of radius $0.3 + 0.7\sqrt{u}$ over a Fibonacci sphere (golden-angle spiral), turning at $0.12 + 1.4c$ rad/s.
- **Plexus**: very thin lines join each surface point to its three nearest neighbours.
- **Spikes and sparks**: spikes reach $r\,\big(0.08 + (0.25 + 0.6c)\,u\big)$ beyond the surface and flicker faster with load; sparks leave the sphere and fade.

**Four load colours:**

| Load $c$ | 0 | ⅓ | ⅔ | 1 |
| --- | --- | --- | --- | --- |
| Name | rest | moderate | sustained | intense |
| Colour | `#3fe6ff` | `#7a5cff` | `#ff3dc8` | `#ff6a2b` |

Colours are interpolated between these stops. Load is eased over time (see [Easing](#easing)), so colour and horizon glide between readings.

**A sub-process** takes one of four shapes, chosen by its CPU as a percentage of one core, $\text{cpuShare}\cdot\text{cores}\cdot 100$:

| CPU (% of one core) | Shape | Geometry |
| --- | --- | --- |
| < 5 % | point cloud | Fibonacci sphere |
| 5 – 50 % | geodesic sphere, with arcs | icosahedron subdivided once: 42 vertices, 120 edges |
| 50 – 500 % | polygon cage, with shards | dual of the geodesic sphere: 80 vertices, 120 edges |
| > 500 % | dense globe | icosahedron subdivided twice: 162 vertices, 480 edges |

Below 7 px on screen, every shape is drawn as a point cloud: edges would not be legible.

### Links and packets (simulated)

Each sub-process may link to its two nearest neighbours, principal included. A link lights and fades on its own rhythm, faster when the processes are busy:

$$
g = \text{smoothstep}\Big(0.35,\ 0.75,\ \tfrac12 + \tfrac12\sin\big(t\,(0.16 + 1.6c) + \varphi\big)\Big)
$$

The visible opacity follows $g$ with an exponential ease of 0.7 s. A link above 0.55 carries a travelling signal. When two satellites on adjacent rings pass within $1.08\times$ their orbit gap, they exchange a **packet**. **Links and packets do not measure any real inter-process communication.**

### Easing

A reading arrives every 3 s, but the image is drawn many times per second. Every quantity follows its target with an exponential decay, so the motion is identical at any frame rate:

$$
x \leftarrow x + (x_{\text{target}} - x)\,\big(1 - e^{-\Delta t/\tau}\big)
$$

- **Depth and load**: $\tau = 1.4$ s.
- **Position after a layout change**: $\tau = 0.9$ s. The body glides; it never fades out and back in.
- **Appearance and disappearance**: $\tau = 0.22$ s.

### Rendering notes

- Particles are grouped into 49 colours × 6 opacity levels and drawn with one `fillRect` each. Merged into a single path, the same squares were rasterised in software and doubled the frame time (20 ms → 9.4 ms once split).
- Unit spheres, meshes and plexus neighbour pairs are computed once and cached.
- **Pause** stops the renderer's clock: the image is frozen while readings continue in the background.

---

## Camera and projection

**Glide.** Every gesture sets a target, and the view approaches it at a rate independent of frame rate. Dragging moves the view and its target together, so the map stays under the cursor:

$$
\text{progress} = 1 - e^{-\Delta t / 0.38}
$$

**Tilted orthographic projection (process view).** The process map is seen at an elevation $e \in [65^\circ, 90^\circ]$, 72° by default. The projection is orthographic, so the scale is identical in the foreground and in the background, and memory areas stay comparable:

$$
s_x = \frac{W}{2} + (x - t_x)\,z
\qquad
s_y = \frac{H}{2} - \big(-(y - t_y)\sin e + h\cos e\big)\,z
$$

Here $(t_x, t_y)$ is the point under the screen centre, $z$ the zoom and $h$ the height. The inverse maps a pixel back to the ground:

$$
x = t_x + \frac{s_x - W/2}{z}
\qquad
y = t_y + \frac{s_y - H/2}{z\sin e}
$$

Zooming anchors on the ground point under the cursor, and a test checks that this point stays exactly under the cursor after zooming and panning.

---

## Controls

| Action | Network | Processus |
| --- | --- | --- |
| Scroll | zoom, with inertia | zoom, anchored under the cursor |
| Left-drag | pan | pan |
| Right-drag | — | tilt the map |
| Hover | name and details | family or process details |
| Click | open the object's observation sheet | open the object's observation sheet |
| Double-click | frame the whole map | on a body: explore its family; on empty space: reframe |
| Search field | highlight matching ports, processes and hardware | highlight by executable name or PID; **Enter** flies to it |
| Kind toggles | show or hide network adapters, peripherals, ports, services | — |
| **Principaux** / **Vue d'ensemble** | — | frame the two largest families / all families |
| **Pause** | — | freeze the animation; readings continue |

On the network map, names are shown permanently only for system hubs; the others appear on hover, selection or search.

**Observation sheet (process view).** Clicking an object replaces the *Processus.Série* panel with a live sheet:

- **Viewport**: the object itself, enlarged and animated in step with the map. A family is shown with its horizon, its vortex and its satellites at their relative places. A sub-process is shown in its shape, linked to its family's sphere.
- **Measurements**:
  - for a family: memory and share of RAM, CPU as a share of the machine and in cores, process count, satellite shapes, horizon size, busiest sub-process;
  - for a sub-process: family, parent PID, memory, CPU as a percentage of one core, shape, ring and orbital period $2\pi/\omega$.
- **Interpretation**: plain sentences derived from the same thresholds as the colours. Load is *rest* below 0.1, then *moderate*, *sustained* and *intense* by thirds; the sheet also flags a heavy family (≥ 10 % of RAM), a small but active one, and a large family (≥ 10 sub-processes).

---

## Measured or simulated

| Visual | Source |
| --- | --- |
| Device positions, orbits, bands | computed from the discovered topology and identifiers |
| Device size and layers | measured knowledge (ports, peripherals, adapters, name, carried devices) |
| Line between two systems | the discovered topology |
| Lines between neighbouring devices and satellites | **simulated** |
| Family size | **measured** working sets, to scale |
| Family colour, particle speed, horizon, well depth | **measured** CPU between two readings |
| Sub-process shape | **measured** CPU, as a percentage of one core |
| Orbits, placement | computed, deterministic and stable |
| Lens, vortex | computed from the measured horizons |
| Dust drift and nebula density | **simulated** texture of space |
| Dark matter | **simulated**, placed around memory-dense families |
| Links and packets between processes | **simulated**, no IPC is captured |

---

## Project layout

```
app/src/main/java/org/mbali/
├── App.java                    JavaFX application, controls, scan and process-watch orchestration
├── MbaliScopeLauncher.java     neutral entry point for classpath distributions
├── model/                      Device, NetworkTopology, PortEndpoint, NetworkAdapter, Presence,
│                               ProcessSnapshot, ProcessTree…
├── service/                    discovery and reading: NetworkScanner, PortScanner, ArpTable,
│                               NeighborTable, RoutingTable, ListeningPorts, ProcessTable,
│                               HardwareInventory, BluetoothInventory, ServiceProbe,
│                               NameResolver, LivePulse, ProcessReader, SystemScanner, ShellCommand
└── view/
    ├── Camera.java             pan, zoom and glide
    ├── Perspective.java        tilted orthographic projection and its inverse
    ├── ConstellationLayout.java    network geometry (pure, no JavaFX)
    ├── ConstellationRenderer.java  network drawing: layered particle spheres, vortices, observation sheet
    ├── SpacetimeLayout.java    process geometry: families, wells, orbits, placement (pure, no JavaFX)
    ├── SpacetimeRenderer.java  process drawing: particles, horizons, lens, shapes, links
    ├── WindowChrome.java       custom title bar for the undecorated window
    └── AppIcon.java            brand icon at every size
app/src/test/java/org/mbali/view/SpacetimePreview.java      off-screen renderer used by renderProcessPreview
app/src/test/java/org/mbali/view/ConstellationPreview.java  off-screen renderer used by renderNetworkPreview
```

Both layouts hold **no JavaFX dependency**: they are pure geometry, which is what makes the visual guarantees testable.

The small icon sizes (16 – 64 px) use a simplified drawing of the brand: a thick gold ring, a central star and a cyan dot. Reduced to 32 px, the full logo, with its thin lines on a night-blue disc, became an almost black disc, invisible on the Windows taskbar.

---

## Tests and verification

**159 tests, 0 failures.**

| Suite | Tests | Suite | Tests |
| --- | --- | --- | --- |
| model.NetworkTopologyTest | 3 | service.NetworkScannerTest | 14 |
| model.ProcessSnapshotTest | 6 | service.PortScannerTest | 2 |
| model.ProcessTreeTest | 7 | service.ProcessReaderTest | 6 |
| service.ArpTableTest | 5 | service.ProcessTableTest | 4 |
| service.BluetoothInventoryTest | 6 | service.RoutingTableTest | 4 |
| service.HardwareInventoryTest | 7 | service.ServiceProbeTest | 2 |
| service.ListeningPortsTest | 7 | service.ShellCommandTest | 3 |
| service.NameResolverTest | 16 | service.SystemScannerTest | 4 |
| service.NeighborTableTest | 1 | view.CameraTest | 9 |
| view.ConstellationLayoutTest | 28 | view.PerspectiveTest | 2 |
| view.SpacetimeLayoutTest | 23 | | |

Visual behaviour is verified by **numerical sampling**, not by eye:

- **Network map**
  - two systems never approach each other, across thousands of sampled instants;
  - a crowded cortege (50 ports and their services, 12 peripherals, 4 adapters) never overlaps itself, and thirty clients of one gateway never touch;
  - adding a neighbour does not move a device already placed;
  - no object crosses into the core of its device;
  - a client keeps following its gateway while that gateway drifts;
  - the camera glide is independent of frame rate.
- **Process map: geometry**
  - a hundred satellites never collide, at any load, at the minimum tilt;
  - the grid never folds, even with overlapping wells;
  - the spatial index gives exactly the same result as testing every well.
- **Process map: stability**
  - adding a process moves no existing family;
  - inserting a child keeps every existing orbital slot;
  - process churn reuses vacant slots instead of growing forever;
  - a family with mixed satellite sizes keeps every place, reading after reading;
  - even at full load, two horizons never overlap.
- **Process map: measurement and projection**
  - CPU uses the interval between readings and rejects recycled PIDs;
  - projection and its inverse agree at every supported tilt.

The rendered result itself is checked on the PNGs produced by `renderProcessPreview` and `renderNetworkPreview`.

---

## Known limitations

- **Most modern phones randomise their MAC address.** The locally-administered bit is set, so no vendor can be derived and the address will change. Such devices are labelled that way rather than guessed at.
- **Reverse DNS returned nothing** for LAN peers on the network this was built against, even where another tool resolved a name. Only the gateway answered.
- **Attachment to the gateway is deduced, not observed.** ARP/NDP proves a device shares the same link, and the routing table proves the default route, but not that the device is physically attached to that access point rather than to a switch or repeater behind it.
- **Process reading, hardware inventory, Bluetooth and port ownership are Windows-only** today. The ARP, neighbour and routing readers have Linux and macOS branches, but only the Windows path has been exercised on real hardware.
- **IPv4 sweeping is limited to a single subnet**, and networks larger than `/16` are rejected outright rather than silently scanning millions of addresses.
- **CPU needs two readings.** The first reading shows every family at rest, and CPU appears 3 seconds later. A loading panel is shown while the first reading runs, then a smaller one until the CPU is measured.
- **Working sets overlap.** Shared pages can be counted in several processes, so memory totals are indicative.
- **Frame time depends on what is on screen.** A close view of large, busy families draws many more particles than the overview; on a modest GPU this can drop below 60 frames per second.

---

## Safety and privacy

- **Only scan networks you own or are explicitly authorised to test.** Port scanning third-party networks is unlawful in many jurisdictions.
- The scan is deliberately bounded: a single subnet, `/16` maximum, a 300 ms connect timeout, and thirteen well-known ports.
- **MAC addresses, hostnames, process names and hardware identifiers identify real devices and their owners.** Do not commit screenshots, logs or test fixtures containing real values from your own network or machine.

---

## Notes

- Source comments and commit history are written in **French**; identifiers, tests and this README are in English.
- **No licence has been chosen yet.** Without one, default copyright applies and nobody may reuse the code. Add a `LICENSE` file before treating this as open source.
