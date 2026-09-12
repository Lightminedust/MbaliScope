# MbaliScope

**A local network scanner that draws your LAN as a living star map.**

MbaliScope discovers the devices on your network, works out what it can honestly say
about each one, and renders the result as a slowly drifting astronomical chart in
JavaFX. Your machine and your router are separate systems; their ports, peripherals,
services and clients orbit them.

> **Screenshot** — not included yet. Run the app, take a capture of the window and drop
> it in `docs/screenshot.png`, then reference it here.

---

## What makes it different from a port scanner

Most network tools print a table. This one tries to make the shape of a network
legible, and a handful of rules fell out of building it:

- **The map has no centre.** Your computer is not the middle of your network — the
  router is what everything reaches the internet through, and it is a device in its own
  right. Both are drawn as peer systems, kept apart by a provable minimum distance.
- **The orbit *is* the link.** If something revolves around a device, it belongs to it.
  No line is drawn to say so twice. Lines are reserved for relations no orbit can
  express — between two separate systems.
- **Sizes are true.** Objects have a size in world units, not in screen pixels. Zoom in
  and a device grows, exactly like a body on a solar chart. Nothing inflates as you
  zoom out.
- **Size means what is known.** A machine with twelve identified ports, attached
  hardware and a real name is drawn larger than an address whose network card has not
  even been resolved. Equal sizes would claim equal weight, which would be false.
- **When nothing is known, it says so.** A device that answers no naming protocol is
  labelled with the reason — not with a blank or a fabricated name. A device whose
  attachment cannot be established orbits nothing at all.

## What it discovers, and what each source actually proves

| Source | What it establishes |
| --- | --- |
| TCP connect on 13 common ports | open / closed / filtered — a refused connection still proves a host is alive |
| ARP table (`arp -a`) | IPv4 neighbours that answered at link level, even with every port filtered |
| Neighbour table (`netsh` / `ndp` / `ip -6 neigh`) | IPv6 neighbours, where most modern devices actually live |
| Routing table | the *real* default gateway, which is often not `.1` |
| `netstat` + `tasklist` | listening ports and the process that owns each one |
| `Get-PnpDevice` | attached hardware (controllers, audio, Bluetooth…) |
| SSDP / UPnP | friendly name, manufacturer and model, for equipment that implements it |
| Reverse DNS | a hostname, when a PTR record exists |
| MAC address | vendor OUI prefix, and detection of randomised addresses |

Addresses belonging to the same physical machine are merged by MAC — on a real home
network, fifteen addresses turned out to be three machines, because a phone holds its
link-local address plus several privacy addresses at once.

## Known limitations

These are stated because the alternative is a map that lies:

- **Most modern phones randomise their MAC address.** The locally-administered bit is
  set, so no vendor can be derived and the address will change. They are labelled as
  such rather than guessed at.
- **Reverse DNS returned nothing** for LAN peers on the network this was built against,
  even where another tool resolved a name. Only the gateway answered.
- **Attachment to the gateway is deduced, not observed.** ARP/NDP proves a device shares
  the same link, and the routing table proves the default route — but not that the
  device is physically attached to that access point rather than to a switch or a
  repeater behind it.
- **Hardware inventory and port ownership are Windows-only** today. The ARP, neighbour
  and routing readers have Linux and macOS branches, but only the Windows path has been
  exercised on real hardware.
- **IPv4 sweeping is limited to a single subnet**, and networks larger than `/16` are
  rejected outright rather than silently scanning millions of addresses.

## Requirements

- **JDK 21** — the Gradle toolchain will fetch one automatically if needed
- No manual JavaFX installation: the `org.openjfx.javafxplugin` adds JavaFX 21.0.5 with
  the right platform classifier

## Build and run

```bash
./gradlew run        # launch the application
./gradlew test       # run the test suite
./gradlew build      # compile, test and assemble
```

On Windows, use `gradlew.bat`.

To create a self-contained Windows application with the MbaliScope icon:

```powershell
.\gradlew.bat :app:packageWindows
```

The executable is written to `app\build\jpackage\MbaliScope\MbaliScope.exe`.

> **Note on the Gradle configuration cache.** The `run` task is explicitly declared
> incompatible with it, because `org.openjfx.javafxplugin` 0.1.0 (its last release)
> reads the project at execution time. The cache entry is discarded for that task only;
> the two "problems were found storing the configuration cache" messages printed by
> `run` are expected and do not indicate a failure.

## Using the map

| Action | Result |
| --- | --- |
| Scroll | zoom, with inertia — the view glides to its target instead of stopping dead |
| Drag | pan, following the cursor exactly |
| Hover | show a device's name and details |
| Click | pin the selection |
| Double-click | frame the whole map again |
| Search field | highlight matching ports, processes and hardware |
| Family toggles | show or hide adapters, peripherals, ports, services |

Device names are shown permanently only for system hubs; the rest appear on hover,
selection or search, so the map stays readable.

## Project layout

```
app/src/main/java/org/mbali/
├── App.java              JavaFX application, monitor UI, scan orchestration
├── model/                Device, NetworkTopology, PortEndpoint, NetworkAdapter…
├── service/              discovery: PortScanner, NetworkScanner, ArpTable,
│                         NeighborTable, RoutingTable, ListeningPorts, ProcessTable,
│                         HardwareInventory, ServiceProbe, NameResolver, ShellCommand
└── view/                 Camera, ConstellationLayout (pure geometry), ConstellationRenderer
```

`ConstellationLayout` holds no JavaFX dependency: it is pure geometry, which is what
makes the visual behaviour testable.

## Tests

**103 tests, 0 failures.**

```
model.NetworkTopologyTest        3     service.NetworkScannerTest      14
service.ArpTableTest             5     service.PortScannerTest          2
service.HardwareInventoryTest    6     service.ProcessTableTest         4
service.ListeningPortsTest       7     service.RoutingTableTest         4
service.NameResolverTest        16     service.ServiceProbeTest         2
service.NeighborTableTest        1     service.ShellCommandTest         3
service.SystemScannerTest        4     view.CameraTest                  9
                                       view.ConstellationLayoutTest    23
```

Visual behaviour is verified by **numerical sampling** rather than by eye: that two
systems never approach each other across thousands of sampled instants, that no object
ever crosses into the core of its device, that a client keeps following its gateway
while that gateway drifts, and that the camera glide is independent of frame rate.

## Safety and privacy

- **Only scan networks you own or are explicitly authorised to test.** Port scanning
  third-party networks is unlawful in many jurisdictions.
- The scan is deliberately bounded: a single subnet, `/16` maximum, a short connect
  timeout, and thirteen well-known ports.
- **MAC addresses, hostnames and hardware identifiers identify real devices and their
  owners.** Do not commit screenshots, logs or test fixtures containing real values from
  your own network.

## Notes

- Source comments and commit history are written in **French**; identifiers, tests and
  this README are in English.
- **No licence has been chosen yet.** Without one, default copyright applies and nobody
  may reuse the code. Add a `LICENSE` file before treating this as open source.
