#!/usr/bin/env python3
"""Build Stremio Auto for NEMESIS USB with an opt-in, one-confirmation clean reinstall.

NEMESIS USB type=execute runs the short script as root after a Yes/No prompt.
The script only removes the package ID stremiogen5w, preflights the replacement
zip first, and refuses to operate before Android has fully booted.
"""
import pathlib
import re
import sys
import zipfile

apk = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
if not apk.is_file():
    raise SystemExit("APK missing")

gradle = pathlib.Path("app/build.gradle").read_text()
match = re.search(r"versionName\s+'([^']+)'", gradle)
version = match.group(1) if match else "development"
code_match = re.search(r"versionCode\s+(\d+)", gradle)
if not code_match:
    raise SystemExit("APK versionCode missing")
version_code = int(code_match.group(1))

package = out / "stremio-auto-nemesis-package.zip"
manifest = (
    "[nemesis]\n"
    "version=1.1\n"
    "id=stremiogen5w\n"
    "name=Stremio Auto\n"
    "type=application,service\n"
    "package-version=" + version + "\n"
    "short-desc=Stremio Auto with Wi-Fi and hotspot guard\n"
    "os=any\n"
    "models=any\n"
    "preuninstall=scripts/preun.sh\n\n"
    "[app.0]\n"
    "kind=apk\n"
    "path=app/StremioAuto.apk\n"
    "package=com.gen5.stremio\n"
    "component=com.gen5.stremio/com.gen5.stremio.MainActivity\n\n"
    "[service.0]\n"
    "name=netguard\n"
    "kind=sh\n"
    "path=svc/netguard.sh\n"
    "uid=root\n"
    "mode=restart\n"
)
with zipfile.ZipFile(package, "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("NEMESIS.data", manifest)
    archive.write(apk, "app/StremioAuto.apk")
    archive.write("svc/netguard.sh", "svc/netguard.sh")
    archive.write("scripts/preun.sh", "scripts/preun.sh")
    archive.write("notices/NETGUARD_LICENSE.txt", "notices/NETGUARD_LICENSE.txt")
with zipfile.ZipFile(package) as archive:
    if archive.testzip() is not None:
        raise SystemExit("Invalid inner package ZIP")
    assert "NEMESIS.data" in archive.namelist()
    assert "app/StremioAuto.apk" in archive.namelist()
    assert {"svc/netguard.sh", "scripts/preun.sh", "notices/NETGUARD_LICENSE.txt"}.issubset(archive.namelist())
    assert "type=application,service" in archive.read("NEMESIS.data").decode("utf-8")
    assert "uid=root" in archive.read("NEMESIS.data").decode("utf-8")
    assert b"APP_PKG=com.gen5.stremio" in archive.read("svc/netguard.sh")
    assert b"CHAIN=stremio_net" in archive.read("svc/netguard.sh")
    assert b"iptables ip6tables" in archive.read("svc/netguard.sh")

# This shell is invoked only by NEMESIS USB type=execute after the user's Yes.
# Do not execute in recovery (Android down); do not touch any unrelated package.
clean_install = """#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
NEMESIS=/system/xbin/nemesis
PKG=packages/stremio-auto.zip
APP=com.gen5.stremio
EXPECTED_CODE=__VERSION_CODE__
LOG=/data/nemesis/stremio-auto-install.log

say() {
  echo "$1"
  echo "$1" >> "$LOG"
}

if [ ! -x "$NEMESIS" ]; then
  echo "NEMESIS command missing. Installation cancelled."
  exit 10
fi
if [ "$(/system/bin/getprop sys.boot_completed)" != "1" ]; then
  say "Android is not fully booted. No apps changed."
  exit 11
fi
if [ ! -r "$PKG" ]; then
  say "Replacement Stremio package missing. Existing app left intact."
  exit 12
fi

INFO=$("$NEMESIS" pkg-info "$PKG" 2>&1)
RESULT=$?
if [ "$RESULT" -ne 0 ]; then
  say "Replacement failed NEMESIS preflight. Existing app left intact."
  exit 13
fi
case "$INFO" in
  *"id=stremiogen5w"*) ;;
  *) say "Unexpected package ID. Existing app left intact."; exit 14 ;;
esac
case "$INFO" in
  *"locked=1"*) say "Package is locked. No apps changed."; exit 15 ;;
esac

say "Stremio package verified. Installing Android versionCode $EXPECTED_CODE."
/system/bin/am force-stop "$APP" >/dev/null 2>&1
case "$INFO" in
  *"installed=1"*)
    say "Uninstalling ONLY stremiogen5w (login and artwork cache will reset)."
    "$NEMESIS" uninstall stremiogen5w
    if [ "$?" -ne 0 ]; then
      say "Stremio uninstall failed; replacement not attempted."
      exit 16
    fi
    ;;
  *) say "No existing Stremio package registered; installing fresh." ;;
esac
"$NEMESIS" install "$PKG"
if [ "$?" -ne 0 ]; then
  say "Stremio install failed. Recover using a regular NEMESIS install."
  exit 17
fi
# A successful installer command alone does not prove the new APK took effect.
DUMP=$(/system/bin/dumpsys package "$APP" 2>&1)
case "$DUMP" in
  *"versionCode=$EXPECTED_CODE"*)
    say "VERIFIED: Android installed Stremio Auto versionCode $EXPECTED_CODE."
    ;;
  *)
    say "INSTALL VERIFICATION FAILED: Android did not report versionCode $EXPECTED_CODE."
    say "The previous app may still be installed. Check this installer log."
    exit 18
    ;;
esac
say "Stremio Auto clean installation verified."
exit 0
"""
clean_install = clean_install.replace("__VERSION_CODE__", str(version_code))
usb = out / "stremio-auto-nemesis-usb.zip"
with zipfile.ZipFile(usb, "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr(
        "NEMESIS.data",
        "[nemesis]\nversion=" + version + "\ntype=execute\npath=NEMESIS.sh\n",
    )
    archive.writestr("NEMESIS.sh", clean_install)
    archive.write(package, "packages/stremio-auto.zip")

with zipfile.ZipFile(usb) as archive:
    if archive.testzip() is not None:
        raise SystemExit("Invalid USB ZIP")
    required = {"NEMESIS.data", "NEMESIS.sh", "packages/stremio-auto.zip"}
    if set(archive.namelist()) != required:
        raise SystemExit("USB package has unexpected contents")
print(usb)
