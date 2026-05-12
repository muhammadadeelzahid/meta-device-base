SUMMARY = "CLI tool to configure Wi-Fi credentials"
DESCRIPTION = "Provides the wifi-setup command to dynamically add and save Wi-Fi credentials using wpa_cli"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COREBASE}/meta/COPYING.MIT;md5=3da9cfbcb788c80a0384361b4de20420"

SRC_URI = "file://wifi-setup.sh"

S = "${WORKDIR}"

do_install() {
    install -d ${D}${bindir}
    install -m 0755 ${WORKDIR}/wifi-setup.sh ${D}${bindir}/wifi-setup
}

RDEPENDS:${PN} = "wpa-supplicant wpa-supplicant-cli bash"
