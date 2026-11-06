# Stremio Auto

**An unofficial, independent side project for Hyundai Motor Group Gen5 head units with NEMESIS (Android 4.4).**

Stremio Auto is a hobby project created to get a usable Stremio-style client running on older HMG head units. It started with extensive ChatGPT-assisted experimentation rather than professional Android development. It is **not an official Stremio app** and is **not affiliated with, sponsored by, or endorsed by Stremio**, Hyundai, Kia, or NEMESIS.

This is a work in progress. It is **not as feature-rich or polished as official Stremio apps**, and there are known limitations, bugs, and compatibility differences. The source is shared in the hope that other developers can improve it. Issues and pull requests are welcome.

**Latest release:** v1.1.1 · **GitHub project:** `Stremio-gen5-hmg-nemesis`

## What it can do

- Sign in to your own Stremio account and access compatible library, Continue Watching, add-on, movie, and series content.
- Play supported video sources inside the app with ExoPlayer, audio/subtitle options, controls, and resizing. Prefer supported English soundtracks automatically when the stream identifies their language; users can select another track manually.
- Render common country flags and language indicators as bundled images on Android 4.4 rather than relying on unsupported system flag emoji fonts.
- Use bundled FFmpeg audio codec support on Android 4.4 (API 19, ARMv7).
- Use VLC as an optional external fallback if installed. **VLC integration defaults off** because VLC has a separate Android UID and is not protected by Stremio Auto's network firewall. Enabling it shows a warning. With VLC off, Watch is larger.
- Install using a NEMESIS USB ZIP and build from the included Android source.
- Choose whether Stremio Auto can use the vehicle's internal modem with **Settings → Use Internal Modem** (off by default), backed by the NEMESIS network guard adapted from gen5-SmartTube.

Functionality depends on stream formats, third-party add-ons, network conditions, and the head unit's firmware. Not every feature from the official Stremio applications is implemented.

## First launch

On the first launch after installation, the app displays one combined **Welcome to Stremio Auto!** and **PARKED VEHICLE LOCK ENABLED** confirmation. The welcome says: *Before you start watching, check out Settings. You can control your internet connection, customize playback, and manage safety features.* The safety message says video playback is for use only while parked and must never be used while driving. After the user taps **I'm Parked — Let's Go**, the app saves the welcome as seen. Later launches show only the regular **PARKED VEHICLE REQUIRED** reminder (unless disabled in Settings). Exiting without confirming leaves the welcome pending. A clean reinstall resets the welcome preference.

## Installation

Download **Stremio-gen5-hmg-nemesis-v1.1.1-USB-Installer.zip** from GitHub Releases. Extract the three items inside it directly onto the root of your USB flash drive, then connect it to a compatible NEMESIS-enabled head unit and follow the installation prompt.

The NEMESIS installer checks the existing installation, removes the previous version when appropriate, and installs the new APK. **A clean reinstall clears the app's saved login, cached media metadata and preferences.** You will need to sign in again afterward.

The Android application ID (`com.gen5.stremio`) and NEMESIS package ID (`stremiogen5w`) are stable compatibility identifiers. Keep them unchanged to update existing installations rather than create duplicate apps.

## Wi-Fi / hotspot network restriction

The USB installer includes the **stremiogen5w/netguard** root service alongside Stremio Auto. It uses the per-app UID firewall approach introduced by [KramerTech's gen5-SmartTube](https://github.com/KramerTech/gen5-SmartTube). NEMESIS starts the service on boot. It applies IPv4 and IPv6 firewall rules and monitors Android's app UID after reinstalls.

In **Settings → Use Internal Modem**, **OFF (default)** keeps this firewall enabled to restrict Stremio Auto to Wi-Fi and Bluetooth/USB hotspots. **ON** removes Stremio Auto's firewall rules, allowing Android to use its normal network routing, including the car's built-in data connection. **This may be monitored by HMG.** ON allows the modem but does not force Android to select it. The service reads this separate saved preference every 2 seconds, so switching does not require an app restart or reinstall. Changes normally apply within a few seconds.
The permitted interfaces are Wi-Fi (`wlan+`), Bluetooth PAN (`bnep+`, `bt-pan+`), USB tethering (`usb+`, `rndis+`) and loopback (`lo`). The guard rejects normal outgoing connections from Stremio Auto's UID over telematics (`rmnet*`) and internal Ethernet (`eth0`). If no approved connection exists, normal app requests should fail while the firewall is operational. It does not create a phone internet connection or depend on Wireless CarPlay.

**Limitations:** When Use Internal Modem is OFF, protection works only while NEMESIS and its firewall service are functioning. After installation, reboot or switching modes, rules may take several seconds to be set up or removed. Android system DNS (`netd`) can resolve names through a different UID using the modem, and other apps including external VLC are not covered. It is **not an absolute whole-car no-modem guarantee**. VLC is therefore off by default and warns before opting in.

**NEMESIS 1.16 or newer is recommended** for service reliability. On-device behavior with NEMESIS 1.15 has not been verified. Check NEMESIS Manager → Services for `stremiogen5w/netguard` and its log at `/data/nemesis/logs/stremiogen5w/netguard.log`. If the service is stopped or the firewall reports incomplete rules, do not assume modem traffic is blocked. Uninstalling Stremio Auto removes its own firewall chain, without touching unrelated applications.

## Optional video codec patch

Stremio Auto bundles FFmpeg **audio** decoders, but its built-in ExoPlayer relies on the head unit's Android video decoders. Some Gen5W head units may benefit from the community [Video Codec Fix (gen5-codec-patch)](https://github.com/KramerTech/gen5-codec-patch), which addresses faulty media codec entries that can prevent third-party video players from working.

**This patch is optional and may not be required.** If video already plays correctly, you may not need it. It is not included with Stremio Auto or installed by our USB installer. The separate NEMESIS patch modifies system codec configuration, so review its compatibility notes and installation instructions before deciding to install it.

## Building from source

GitHub Actions builds using Java 17, the Android SDK, Gradle, and FFmpeg for Android 4.4 ARMv7. See `.github/workflows/build.yml` and `tools/package_nemesis.py`.

No account email, login token, personal library, or other user-specific credentials are bundled in the distributed app.

## Compatibility and limitations

- Intended for compatible Android 4.4 Hyundai Motor Group Gen5 head units running NEMESIS; compatibility with other head units is not established.
- Third-party add-ons or streaming endpoints may be unavailable or incompatible.
- Phone-only data routing has not been independently confirmed. Wireless CarPlay alone does not establish that the app uses a phone's data connection.
- **Use responsibly. Do not watch or operate video while driving.**

## Flag graphics

Country flags use locally bundled [Twemoji](https://github.com/jdecked/twemoji) artwork. Twemoji graphics are licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Copyright and credit to the Twemoji contributors. The images are used unmodified. Flag coverage is currently limited to the bundled set; other country codes remain visible as text.

## Attribution and notices

This community project is **completely unofficial**. Stremio and its name/logo are owned by their respective rights holders. The launcher uses a customized purple-to-blue Stremio-style design with an IONIQ-inspired pixel play triangle; it is an unofficial community modification and not an official Stremio logo. See `branding/README.md`. FFmpeg licensing notices are included in `app/src/main/assets`. The NEMESIS network guard is adapted from Sean Kramer's MIT-licensed SmartTube network service; the license is preserved in `notices/NETGUARD_LICENSE.txt`.

Please contribute improvements, bug reports, and pull requests. This project exists because the author wanted a working client on an older Android head unit and hopes other people can make it better.
