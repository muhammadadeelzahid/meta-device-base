# Device Base Platform

A reference Yocto layer for building a secure, OTA-updatable Linux platform for Raspberry Pi 4 / 5. `meta-device-base` produces a flashable image and a signed update bundle
that together form a complete embedded Linux platform with field-update
capability.

---

## Features

- **A/B atomic updates** — two rootfs slots managed by RAUC; U-Boot
picks the active slot from `BOOT_ORDER` with a three-strike fallback
so a bad update automatically rolls back.
- **Signed OTA via Eclipse hawkBit** — bundles are signed at build
time and delivered through a self-hosted hawkBit server using the
DDI HTTP API.
- **Immutable rootfs + persistent overlay** — each slot is mounted
read-only; `/etc`, `/var`, and `/home` are layered on top using
OverlayFS backed by a dedicated `data` partition, so user state
survives updates without compromising the rootfs.
- **systemd-native networking** — `wpa_supplicant@wlan0.service` for
Wi-Fi, `systemd-networkd` for DHCP/IP, `systemd-resolved` for DNS,
and `systemd-networkd-wait-online` scoped to `wlan0` so
`network-online.target` reflects real connectivity.
- **Per-device identity** — each device automatically derives a unique  
hawkBit target name from its hardware serial number on every boot.

---

## Tech stack


| Layer               | Components                                       |
| ------------------- | ------------------------------------------------ |
| Build system        | Yocto Project (Poky, scarthgap)                  |
| Image               | WIC, ext4, vfat, OverlayFS                       |
| Bootloader          | U-Boot, libubootenv                              |
| Update engine       | RAUC, meta-rauc, dm-verity                       |
| OTA server / client | Eclipse hawkBit, rauc-hawkbit-updater            |
| Init / services     | systemd 255+, systemd-networkd, systemd-resolved |
| Wi-Fi               | wpa_supplicant (template unit)                   |
| Remote access       | OpenSSH (key-only)                               |
| Hardware            | Raspberry Pi 4 / 5                               |


---

Image partition layout (`device-base-dual.wks.in`):


| #   | Label     | Type | Mountpoint | Purpose                                      |
| --- | --------- | ---- | ---------- | -------------------------------------------- |
| 1   | `boot`    | FAT  | `/boot`    | Pi firmware, kernel, `boot.scr`, `uboot.env` |
| 2   | `rootfsA` | ext4 | `/`        | RAUC slot A                                  |
| 3   | —         | ext4 | (inactive) | RAUC slot B                                  |
| 4   | `data`    | ext4 | `/data`    | OverlayFS upper dirs + RAUC state            |


`/boot` is auto-mounted at runtime so RAUC can read and update
`uboot.env` via `fw_printenv` / `fw_setenv`.

---

## Getting started

### Clone the layer alongside Poky and dependencies

```bash
git clone https://git.yoctoproject.org/poky -b scarthgap
git clone https://git.openembedded.org/meta-openembedded -b scarthgap
git clone https://github.com/agherzan/meta-raspberrypi -b scarthgap
git clone https://github.com/rauc/meta-rauc -b scarthgap
git clone https://github.com/rauc/meta-rauc-community -b scarthgap
git clone https://github.com/muhammadadeelzahid/meta-device-base
```

Add the layers to `bblayers.conf` and configure `local.conf` (see
[Configuration](#configuration)).

---

## Build

```bash
source poky/oe-init-build-env build-rpi
bitbake device-base-image     # flashable .wic.bz2
bitbake device-base-bundle    # signed .raucb (for OTA delivery)
```

Artifacts:

```
build-rpi/tmp/deploy/images/raspberrypi4-64/
├── device-base-image-raspberrypi4-64.rootfs.wic.bz2
├── device-base-image-raspberrypi4-64.rootfs.wic.bmap
└── device-base-bundle-raspberrypi4-64.raucb
```

---

## Flash

```bash
lsblk -p -o NAME,SIZE,RM,MODEL,MOUNTPOINT     # find the SD card
sudo umount /dev/sdX*  2>/dev/null || true
sudo wipefs -af /dev/sdX
sudo bmaptool copy \
  build-rpi/tmp/deploy/images/raspberrypi4-64/device-base-image-raspberrypi4-64.rootfs.wic.bz2 \
  /dev/sdX
sync
```

After boot, verify on the Pi:

```bash
cat /etc/issue                       # Device Base Platform <version>
networkctl status wlan0              # routable, online
rauc status                          # slot states
mount | grep /boot                   # /boot must be mounted
```

---

## OTA updates

1. Build a new bundle: `bitbake device-base-image && bitbake device-base-bundle`.
2. In hawkBit:
  - **Upload** → create a Software Module, attach the `.raucb`.
  - **Distributions** → create a Distribution Set referencing the module.
  - **Deployment** → drag the Distribution Set onto the target.
3. Watch on the Pi: `journalctl -u rauc-hawkbit-updater -f`.

The updater downloads, verifies, installs into the inactive slot, and
the system reboots into the new slot. `rauc-mark-good` runs on first
boot to lock the slot in; if it fails three times, U-Boot rolls back
automatically.

`rauc-hawkbit-updater` does not auto-reboot by default. To enable, set
in `config.conf`:

```
post_update_reboot = true
```

---

## Configuration

All build-time configuration lives in `build-rpi/conf/local.conf`:

```bitbake
DISTRO  = "device-base"
MACHINE = "raspberrypi4-64"
RPI_USE_U_BOOT = "1"

# Wi-Fi credentials baked into the image
WIFI_SSID = "your-ssid"
WIFI_PSK  = "your-psk"

# hawkBit server
HAWKBIT_SERVER_URL    = "192.168.1.10:8080"
HAWKBIT_GATEWAY_TOKEN = "your-gateway-token"

# Optional: SSH and root password
ROOT_SSH_AUTHORIZED_KEYS = "ssh-ed25519 AAAAC3Nz... user@host"
ROOT_PASSWORD_HASH       = "$6$..."
```

> `WIFI_SSID` / `WIFI_PSK` are placeholders for the variables consumed
> by `recipes-connectivity/wpa-supplicant/files/wpa_supplicant.conf`.
> Adapt the variable names to match your network type
> (e.g. WPA2-Personal vs WPA2-Enterprise).

---

## Security

### Update integrity

- Every `.raucb` bundle is signed at build time with a development
signer key.
- The matching CA certificate is installed at `/etc/rauc/ca.cert.pem`
in every rootfs slot. RAUC verifies that the bundle’s signer chains
to this CA before installing.
- Bundles use the **verity** format, so the rootfs image inside the
bundle is integrity-protected by dm-verity at install time.
- Authentication to the hawkBit server uses a **gateway token**
(tenant-wide), not a per-target token.

### Hardening

- Rootfs is **read-only**; persistent state goes through the OverlayFS
on `/data`.
- SSH password authentication and empty passwords are **disabled**.
- A root password hash and authorized SSH key can be injected at build
time via `local.conf` so credentials never appear in source control.

### PKI files in this layer

`recipes-core/rauc/files/ca.cert.pem`
`files/rauc-keys/development-1.cert.pem`
`files/rauc-keys/development-1.csr.pem`
`files/rauc-keys/development-1.key.pem`
`files/rauc-keys/development-ca.key.pem`

```
files/rauc-keys/*.key.pem
```

## Project structure

```
meta-device-base/
├── conf/distro/device-base.conf            # DISTRO definition
├── files/rauc-keys/                        # RAUC dev PKI (signer + CA)
├── recipes-bsp/rpi-u-boot-scr/             # RAUC-aware boot.cmd.in
├── recipes-connectivity/
│   ├── systemd-networkd/                   # 10-wlan0.network + wait-online override
│   └── wpa-supplicant/                     # per-interface config + template unit
├── recipes-core/
│   ├── bundles/device-base-bundle.bb       # signed .raucb recipe
│   ├── images/device-base-image.bb         # main image + postprocess
│   ├── rauc/                               # system.conf, CA cert
│   └── rauc-hawkbit-identity/              # identity service + config template
└── README.md
```

---

## License

This layer is licensed under the MIT License. See [COPYING.MIT](COPYING.MIT).

## Acknowledgements

- [Yocto Project](https://www.yoctoproject.org/)
- [meta-raspberrypi](https://github.com/agherzan/meta-raspberrypi)
- [RAUC](https://rauc.io/) and [meta-rauc](https://github.com/rauc/meta-rauc)
- [Eclipse hawkBit](https://www.eclipse.org/hawkbit/)
- The RAUC and hawkBit example deployments that this project is modeled on.

