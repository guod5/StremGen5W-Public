#!/system/bin/sh
# Per-UID NEMESIS firewall for Stremio Auto on Gen5W Android 4.4.
# Adapted from Sean Kramer's MIT-licensed gen5-SmartTube netguard service.
# Requires a running NEMESIS root service; DNS through netd and external VLC
# are outside this app-UID restriction.
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

APP_PKG=com.gen5.stremio
CHAIN=stremio_net
ALLOW="lo wlan+ bnep+ bt-pan+ usb+ rndis+"
INTERVAL=${NETGUARD_INTERVAL:-15}
log() { echo "$(date '+%m-%d %H:%M:%S') $1"; }

# CAP_NET_ADMIN (bit 12) in /proc/self/status CapEff.
can_admin() {
    while read _k _v; do
        [ "$_k" = CapEff: ] || continue
        _d=${_v%???}
        _d=${_d#"${_d%?}"}
        case "$_d" in [13579bdfBDF]) return 0 ;; esac
        return 1
    done < /proc/self/status
    return 1
}

# Android assigns a new UID after reinstall; refresh it every 15 seconds.
app_uid() {
    while read _p _u _rest; do
        [ "$_p" = "$APP_PKG" ] && { echo "$_u"; return 0; }
    done < /data/system/packages.list
    return 1
}

apply() {
    _ok=0
    for _ipt in iptables ip6tables; do
        $_ipt -N $CHAIN 2>/dev/null
        if ! $_ipt -F $CHAIN; then _ok=1; continue; fi
        $_ipt -A $CHAIN -m owner ! --uid-owner "$1" -j RETURN || _ok=1
        for _i in $ALLOW; do
            $_ipt -A $CHAIN -o "$_i" -j RETURN || _ok=1
        done
        $_ipt -A $CHAIN -j REJECT 2>/dev/null || $_ipt -A $CHAIN -j DROP || _ok=1
        $_ipt -C OUTPUT -j $CHAIN 2>/dev/null || $_ipt -I OUTPUT 1 -j $CHAIN || _ok=1
    done
    return $_ok
}

applied() {
    for _ipt in iptables ip6tables; do
        $_ipt -C OUTPUT -j $CHAIN 2>/dev/null || return 1
        $_ipt -C $CHAIN -m owner ! --uid-owner "$1" -j RETURN 2>/dev/null || return 1
    done
}

if ! can_admin; then
    log "no CAP_NET_ADMIN (ADB install?); exiting for NEMESIS svcmgr to restart"
    sleep 3
    exit 1
fi

uid=
last=
while :; do
    if u=$(app_uid); then
        if [ "$u" != "$uid" ] || ! applied "$u"; then
            if apply "$u"; then state="blocking non-Wi-Fi for uid $u"; uid=$u
            else state="WARN: firewall rules incomplete for uid $u"; uid=; fi
            [ "$state" = "$last" ] || log "$state"
            last=$state
        fi
    elif [ "$last" != waiting ]; then
        log "$APP_PKG not installed yet"
        last=waiting
    fi
    sleep $INTERVAL
done
