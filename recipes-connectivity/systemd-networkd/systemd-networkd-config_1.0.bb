SUMMARY = "systemd-networkd configuration for wlan0 and SocketCAN (can0)"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://10-wlan0.network \
    file://20-can0.network \
    file://systemd-networkd-wait-online.override.conf \
"

S = "${WORKDIR}"

inherit allarch

do_install() {
    install -d ${D}${sysconfdir}/systemd/network
    install -m 0644 ${WORKDIR}/10-wlan0.network ${D}${sysconfdir}/systemd/network/10-wlan0.network
    install -m 0644 ${WORKDIR}/20-can0.network ${D}${sysconfdir}/systemd/network/20-can0.network

    install -d ${D}${sysconfdir}/systemd/system/systemd-networkd-wait-online.service.d
    install -m 0644 ${WORKDIR}/systemd-networkd-wait-online.override.conf \
        ${D}${sysconfdir}/systemd/system/systemd-networkd-wait-online.service.d/override.conf
}

FILES:${PN} += " \
    ${sysconfdir}/systemd/network/10-wlan0.network \
    ${sysconfdir}/systemd/network/20-can0.network \
    ${sysconfdir}/systemd/system/systemd-networkd-wait-online.service.d/override.conf \
"
