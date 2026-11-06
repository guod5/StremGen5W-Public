#!/system/bin/sh
# Remove only Stremio Auto's own per-UID iptables chain on uninstall/update.
# Adapted from KramerTech/gen5-SmartTube (MIT license).
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
for ipt in iptables ip6tables; do
    $ipt -D OUTPUT -j stremio_net 2>/dev/null
    $ipt -F stremio_net 2>/dev/null
    $ipt -X stremio_net 2>/dev/null
done
exit 0
