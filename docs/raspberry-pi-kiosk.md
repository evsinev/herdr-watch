## Kiosk display on a Raspberry Pi

The **Compact** view exists for small glance displays, and a Raspberry Pi wired to
an HDMI panel is the obvious host for one. The Pi runs **only the display** — the
backend stays on a real machine (it needs `ssh` fan-out to the fleet), and the Pi
points a browser at it over the network.

The catch: a desktop browser on a Pi 3 is the wrong tool. Firefox on X software-
renders every frame, and a dashboard that looks static to a human is anything but
static to a compositor. Use [**cog**](https://github.com/Igalia/cog) instead — the
single-window launcher for **WPE WebKit**, the WebKit port built for embedded
devices. It talks straight to DRM/KMS, so there is no X server, no Wayland
compositor, and no desktop session at all.

Measured on a Pi 3 (4 cores, 907 MiB), same dashboard, same backend:

|                     | Firefox + X + LightDM | cog on DRM/KMS |
| ------------------- | --------------------- | -------------- |
| load average (1 min)| 1.64                  | 0.11 – 0.23    |
| memory used         | 555 MiB               | ~300 MiB       |
| memory available    | ~350 MiB              | ~605 MiB       |

> One caveat that no renderer fixes: continuous CSS animations (`animate-pulse`,
> long `transition-*`) keep the compositor busy forever. WPE handles them far
> better than software-rendered Firefox, but on a glance display they are worth
> dropping regardless.

### Prerequisites

|                    |                                                                                                   |
| ------------------ | ------------------------------------------------------------------------------------------------- |
| **Hardware**       | Raspberry Pi 3 or newer, HDMI display                                                             |
| **Raspberry Pi OS** | **Trixie** (Debian 13) recommended — ships cog 0.18.4 + WPE WebKit 2.48                          |
| **KMS driver**     | `dtoverlay=vc4-kms-v3d` in `/boot/firmware/config.txt` (the default in current images)             |
| **Network**        | the backend reachable from the Pi — no SSH keys or `herdr` needed on the Pi itself                 |

Raspberry Pi OS Bookworm carries cog 0.16.1 + WPE 2.38; it works, but the engine is
two years older. Nothing has to be compiled either way — Debian's `cog` package
already ships the DRM platform module (`libcogplatform-drm.so`) alongside the
Wayland and headless ones.

### Install

```bash
sudo apt install cog

# free the display: no display manager, no graphical target
sudo systemctl disable --now lightdm
sudo systemctl set-default multi-user.target
```

Smoke-test it from a local console (not over SSH — cog needs the tty):

```bash
cog --platform=drm http://BACKEND_HOST:8080/
```

Replace `BACKEND_HOST:8080` with wherever herdr-watch is listening. Switch to the
**Compact** view once from the top nav; if you want the Pi to come up in Compact
without human help, persisting the view choice in the frontend is a small change
worth making.

### Run it as a service

`/etc/systemd/system/herdr-kiosk.service`:

```ini
[Unit]
Description=herdr-watch kiosk (cog / WPE WebKit on DRM)
Documentation=https://github.com/evsinev/herdr-watch
After=network-online.target systemd-user-sessions.service getty@tty1.service
Wants=network-online.target
# The kiosk and getty cannot own tty1 at the same time
Conflicts=getty@tty1.service
# A kiosk must retry forever instead of giving up after 5 restarts
StartLimitIntervalSec=0

[Service]
Type=simple
User=pi
SupplementaryGroups=video render input tty

# Keep WebKit caches on tmpfs so they don't wear out the SD card
RuntimeDirectory=cog
RuntimeDirectoryMode=0700
ExecStartPre=/bin/mkdir -p /run/cog/cache
Environment=HOME=/home/pi
Environment=XDG_RUNTIME_DIR=/run/cog
Environment=XDG_CACHE_HOME=/run/cog/cache

# Cap the mode at 720p@30 — halves the compositing work on VideoCore IV
Environment=COG_PLATFORM_DRM_MODE_MAX=1280x720@30

ExecStart=/usr/bin/cog --platform=drm http://BACKEND_HOST:8080/

Restart=always
RestartSec=3

# Safety net against WebKit creep over a long-lived SSE session
MemoryAccounting=yes
MemoryHigh=420M
MemoryMax=520M
# WebKit is multi-process: kill the whole cgroup, let Restart bring it back
OOMPolicy=stop

TTYPath=/dev/tty1
StandardInput=tty-force
StandardOutput=journal
StandardError=journal
TTYReset=yes
TTYVTDisallocate=yes

[Install]
WantedBy=multi-user.target
```

Three settings that are easy to get wrong:

- **`StartLimitIntervalSec=0` belongs in `[Unit]`.** Without it systemd gives up
  after five restarts in ten seconds and the wall stays dark until someone
  notices — exactly what happens while the backend host reboots.
- **`OOMPolicy=stop`** — WebKit is several processes in one cgroup. Without this a
  web process killed on the memory limit leaves a live but empty `cog` behind.
- **`MemoryHigh` below `MemoryMax`** is not just headroom: WebKit reads memory
  pressure from the cgroup and starts dropping its own caches at `MemoryHigh`,
  so `MemoryMax` usually never gets hit.

Optional nightly restart — cheap insurance against slow leaks.
`/etc/systemd/system/herdr-kiosk-restart.service`:

```ini
[Unit]
Description=Restart herdr-watch kiosk

[Service]
Type=oneshot
ExecStart=/usr/bin/systemctl restart herdr-kiosk.service
```

`/etc/systemd/system/herdr-kiosk-restart.timer`:

```ini
[Unit]
Description=Nightly restart of herdr-watch kiosk

[Timer]
OnCalendar=*-*-* 04:00:00
AccuracySec=1min
# Don't fire a missed restart on boot — the kiosk just started anyway
Persistent=false

[Install]
WantedBy=timers.target
```

Enable everything:

```bash
sudo systemctl daemon-reload
sudo systemctl disable --now getty@tty1
sudo systemctl enable --now herdr-kiosk.service
sudo systemctl enable --now herdr-kiosk-restart.timer
```

### Tuning the Pi for 24/7 duty

**Console blanking** — cog holds DRM master so the kernel won't blank the console,
but add `consoleblank=0` to `/boot/firmware/cmdline.txt` anyway so the screen
doesn't flash between restarts.

**Swap off.** On Raspberry Pi OS swap is a *file* managed by `dphys-swapfile`, not
an `/etc/fstab` entry — that is what the `# a swapfile is not a swap partition`
comment in the stock fstab means. Turning it off cuts SD-card wear and makes
memory behaviour predictable:

```bash
sudo dphys-swapfile swapoff
sudo dphys-swapfile uninstall     # removes /var/swap
sudo systemctl disable dphys-swapfile
```

The order matters: `uninstall` deletes the file, `disable` stops the service from
recreating it at boot. Verify with `swapon --show` (silent), `free -h`
(`Swap: 0B`), and `ls /var/swap` (gone). Note the trade-off — with no swap the
kernel has nowhere to evict anonymous pages, so memory exhaustion goes straight
to the OOM killer with no slow-and-thrashing middle stage. That is why the cgroup
limits above stop being belt-and-braces and become the thing standing between a
leak and a random process getting shot.

**Trimming services** is optional but free: `bluetooth`, `avahi-daemon`,
`ModemManager`, `triggerhappy`, and `cups` are all pointless on a display-only
Pi and give back 20–30 MiB. If the Pi runs for months, `log2ram` keeps the journal
off the SD card.

### Verify

```bash
systemctl status herdr-kiosk              # state + current memory
systemctl show herdr-kiosk -p MemoryPeak  # check again after 24 h
journalctl -u herdr-kiosk -f
systemd-cgtop -m                          # live per-cgroup memory
```

`ps -eo pid,rss,pcpu,comm --sort=-rss | head` shows the real process tree:
`cog` plus `WPEWebProcess` (the renderer, the largest) and `WPENetworkProcess`.
On an idle dashboard all three should sit at ~0 % CPU. If `MemoryPeak` is still
near its day-one value after 24 hours, there is no leak and the limits will never
fire.

### Troubleshooting

| symptom                                | cause                                              | fix                                                                                                                     |
| -------------------------------------- | -------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `failed to become DRM master`          | something else owns the display                    | `systemctl disable --now lightdm`; check with `sudo lsof /dev/dri/card*`                                                 |
| black screen, no errors                | no KMS driver                                      | `dtoverlay=vc4-kms-v3d` in `config.txt`; confirm GL works with `kmscube`                                                 |
| runs, but keyboard/touch does nothing  | libinput has no logind session, so no ACLs on `/dev/input/*` | add `PAMName=login` to `[Service]`; if that still fails, drop `User=`/`SupplementaryGroups=` and run as root |
| wrong resolution or refresh rate       | `COG_PLATFORM_DRM_VIDEO_MODE` only accepts `WxH`   | use `COG_PLATFORM_DRM_MODE_MAX` for `WxH@R` forms like `1280x720@30`                                                     |
| dies after a backend outage            | systemd start-rate limit                           | `StartLimitIntervalSec=0` in `[Unit]`, not `[Service]`                                                                   |

If wrestling with DRM permissions is not worth it, `cage` (a Wayland kiosk
compositor, also in apt) plus `cog --platform=wl` is the easy path: slightly more
memory, far fewer sharp edges.
