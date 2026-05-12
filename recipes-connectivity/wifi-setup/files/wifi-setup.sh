#!/bin/bash
# wifi-setup: CLI tool to add or update Wi-Fi credentials via wpa_cli

if [ "$#" -ne 2 ]; then
    echo "Usage: wifi-setup <SSID> <PASSWORD>"
    echo "Example: wifi-setup \"My Home Network\" \"password123\""
    exit 1
fi

SSID="$1"
PASSWORD="$2"
INTERFACE="wlan0"

echo "Configuring Wi-Fi for SSID: $SSID..."

# Check if wpa_cli can connect to wpa_supplicant
if ! wpa_cli -i $INTERFACE ping >/dev/null 2>&1; then
    echo "Error: wpa_supplicant is not running or not accessible on $INTERFACE."
    echo "Please ensure the Wi-Fi service is running."
    exit 1
fi

# list_networks output is tab-separated:
# network id \t ssid \t bssid \t flags
NETWORK_ID=$(wpa_cli -i $INTERFACE list_networks | awk -F'\t' -v ssid="$SSID" 'NR>1 { if ($2 == ssid) print $1 }' | head -n 1)

if [ -z "$NETWORK_ID" ]; then
    echo "Adding a new network profile..."
    NETWORK_ID=$(wpa_cli -i $INTERFACE add_network)
    if [ "$NETWORK_ID" = "FAIL" ] || [ -z "$NETWORK_ID" ]; then
        echo "Error: Failed to allocate a new network block."
        exit 1
    fi
else
    echo "Network already exists (ID: $NETWORK_ID). Updating credentials..."
fi

# Update credentials. SSID and PSK must be wrapped in escaped quotes for wpa_cli.
wpa_cli -i $INTERFACE set_network "$NETWORK_ID" ssid "\"$SSID\"" > /dev/null
wpa_cli -i $INTERFACE set_network "$NETWORK_ID" psk "\"$PASSWORD\"" > /dev/null

# Set the highest priority to ensure this network is preferred
wpa_cli -i $INTERFACE set_network "$NETWORK_ID" priority 100 > /dev/null

# Select and enable network (this will force the device to connect to it if available)
echo "Applying settings and connecting..."
wpa_cli -i $INTERFACE select_network "$NETWORK_ID" > /dev/null
wpa_cli -i $INTERFACE enable_network "$NETWORK_ID" > /dev/null

# Save the configuration back to /etc/wpa_supplicant/wpa_supplicant-wlan0.conf
echo "Saving configuration persistently..."
wpa_cli -i $INTERFACE save_config > /dev/null

# Force wpa_supplicant to reload and associate
wpa_cli -i $INTERFACE reconfigure > /dev/null

echo ""
echo "Wi-Fi credentials saved successfully!"
echo "Check connection status using:  wpa_cli -i $INTERFACE status"
