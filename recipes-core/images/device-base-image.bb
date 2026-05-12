SUMMARY = "Device Base Linux image"

IMAGE_INSTALL = "packagegroup-core-boot \
                 kernel-modules \
                 linux-firmware-rpidistro-bcm43455 \
                 linux-firmware-rpidistro-bcm43456 \
                 wpa-supplicant \
                 wpa-supplicant-cli \
                 wifi-setup \
                 iw \
                 systemd-networkd-config \
                 libubootenv-bin \
                 rauc \
                 rauc-hawkbit-updater \
                 rauc-hawkbit-identity \
                 rauc-mark-good \
                 device-base-overlays \
                 ${CORE_IMAGE_EXTRA_INSTALL}"
IMAGE_FEATURES += "ssh-server-openssh allow-root-login read-only-rootfs overlayfs-etc"

# A/B dual-rootfs partition layout
WKS_FILE = "device-base-dual.wks.in"
IMAGE_FSTYPES:append = " ext4"

IMAGE_LINGUAS = " "

LICENSE = "MIT"

inherit core-image

# Ensure systemd-networkd support is built in the systemd package.
PACKAGECONFIG:append:pn-systemd = " networkd"

#set rootfs to 200 MiB by default
IMAGE_OVERHEAD_FACTOR ?= "1.0"
IMAGE_ROOTFS_SIZE ?= "204800"

ROOT_PASSWORD_HASH ?= ""

# Set ROOT_PASSWORD_HASH in build-rpi/conf/local.conf to avoid committing secrets
# in this layer. Example:
# ROOT_PASSWORD_HASH = "$6$...generated-with-openssl-passwd-6..."
python set_root_password_hash () {
    import os
    import re

    root_password_hash = d.getVar("ROOT_PASSWORD_HASH") or ""
    if not root_password_hash:
        return

    shadow_path = os.path.join(d.getVar("IMAGE_ROOTFS"), d.getVar("sysconfdir").lstrip("/"), "shadow")
    if not os.path.exists(shadow_path):
        return

    with open(shadow_path, "r", encoding="utf-8") as f:
        shadow_data = f.read()

    shadow_data = re.sub(r"^root:[^:]*:", "root:%s:" % root_password_hash, shadow_data, count=1, flags=re.MULTILINE)

    with open(shadow_path, "w", encoding="utf-8") as f:
        f.write(shadow_data)
}

ROOTFS_POSTPROCESS_COMMAND += " set_root_password_hash;"

ROOT_SSH_AUTHORIZED_KEYS ?= ""

# Inject SSH authorized keys safely at build time
python set_ssh_authorized_keys () {
    import os

    ssh_keys = d.getVar("ROOT_SSH_AUTHORIZED_KEYS") or ""
    if not ssh_keys:
        return

    ssh_dir = os.path.join(d.getVar("IMAGE_ROOTFS"), "home", "root", ".ssh")
    if not os.path.exists(ssh_dir):
        os.makedirs(ssh_dir, mode=0o700)

    keys_file = os.path.join(ssh_dir, "authorized_keys")
    with open(keys_file, "w", encoding="utf-8") as f:
        f.write(ssh_keys + "\n")
    
    os.chmod(keys_file, 0o600)
}

ROOTFS_POSTPROCESS_COMMAND += " set_ssh_authorized_keys;"

# Create the /data mountpoint anchor on the root filesystem so WIC can mount the 4th partition there
create_data_mountpoint () {
    install -d ${IMAGE_ROOTFS}/data
    install -d ${IMAGE_ROOTFS}/data/rauc
}

ROOTFS_POSTPROCESS_COMMAND += " create_data_mountpoint;"

# Disable SSH password authentication for production security
disable_ssh_passwords () {
    if [ -f ${IMAGE_ROOTFS}/etc/ssh/sshd_config ]; then
        sed -i -e 's/^[#[:space:]]*PasswordAuthentication.*/PasswordAuthentication no/' ${IMAGE_ROOTFS}/etc/ssh/sshd_config
        sed -i -e 's/^[#[:space:]]*PermitEmptyPasswords.*/PermitEmptyPasswords no/' ${IMAGE_ROOTFS}/etc/ssh/sshd_config
    fi
}

ROOTFS_POSTPROCESS_COMMAND += " disable_ssh_passwords;"

enable_systemd_unit() {
    unit_name="$1"
    target_name="$2"
    install -d "${IMAGE_ROOTFS}/etc/systemd/system/${target_name}.wants"
    ln -sf "/usr/lib/systemd/system/${unit_name}" "${IMAGE_ROOTFS}/etc/systemd/system/${target_name}.wants/${unit_name}"
}

configure_systemd_network_stack() {
    enable_systemd_unit systemd-networkd.service multi-user.target
    enable_systemd_unit systemd-resolved.service sysinit.target
    enable_systemd_unit systemd-networkd-wait-online.service network-online.target
    enable_systemd_unit wpa_supplicant@wlan0.service multi-user.target

    # Remove legacy custom Wi-Fi units from previous images.
    rm -f "${IMAGE_ROOTFS}/etc/systemd/system/multi-user.target.wants/wpa_supplicant-wlan0.service"
    rm -f "${IMAGE_ROOTFS}/etc/systemd/system/multi-user.target.wants/wifi-dhcp-wlan0.service"

    # Force-disable competing network managers.
    ln -sf /dev/null "${IMAGE_ROOTFS}/etc/systemd/system/dhcpcd.service"
    ln -sf /dev/null "${IMAGE_ROOTFS}/etc/systemd/system/networking.service"
}

ROOTFS_POSTPROCESS_COMMAND += " configure_systemd_network_stack;"

# Ensure /boot (FAT) is mounted so fw_printenv/fw_setenv can read the U-Boot
# environment (uboot.env) required by rauc-mark-good and other RAUC slot operations.
mount_boot_partition () {
    if ! grep -q '^[^#]*\s/boot\s' ${IMAGE_ROOTFS}/etc/fstab 2>/dev/null; then
        echo "LABEL=boot /boot vfat defaults,noatime 0 2" >> ${IMAGE_ROOTFS}/etc/fstab
    fi

    # Ensure rauc-mark-good waits for /boot to be mounted.
    install -d ${IMAGE_ROOTFS}/etc/systemd/system/rauc-mark-good.service.d
    cat > ${IMAGE_ROOTFS}/etc/systemd/system/rauc-mark-good.service.d/wait-for-boot.conf <<EOF
[Unit]
RequiresMountsFor=/boot
After=boot.mount
EOF
}

ROOTFS_POSTPROCESS_COMMAND += " mount_boot_partition;"

IMAGE_ROOTFS_EXTRA_SPACE:append = "${@bb.utils.contains("DISTRO_FEATURES", "systemd", " + 4096", "", d)}"

# -------------------------------------------------------------
# OverlayFS Configuration
# -------------------------------------------------------------
inherit overlayfs-etc

# Configure the special /etc overlay (Intercepts early boot before systemd)
OVERLAYFS_ETC_MOUNT_POINT = "/data"
OVERLAYFS_ETC_FSTYPE = "ext4"
OVERLAYFS_ETC_DEVICE = "/dev/mmcblk0p4"
OVERLAYFS_ETC_USE_ORIG_INIT_NAME = "1"

# -------------------------------------------------------------
# QEMU Simulation Overrides
# -------------------------------------------------------------
# QEMU boots a flat ext4 rootfs with no partition table, so the
# /data partition (/dev/mmcblk0p4) and OverlayFS mounts do not apply.
IMAGE_FEATURES:remove:qemuall = "read-only-rootfs overlayfs-etc"
IMAGE_INSTALL:remove:qemuall = "device-base-overlays rauc rauc-hawkbit-updater rauc-hawkbit-identity rauc-mark-good"
OVERLAYFS_ETC_DEVICE:qemuall = ""
