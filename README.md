# StremGen5W

StremGen5W is a community-built Android media client for compatible Hyundai Motor Group Gen5 head units with NEMESIS installed. It provides a Stremio account sign-in, synced library and add-on catalog support, and a custom media player.

**Public release:** v1.0.2

## Features
- Sign in to your own Stremio account to sync your library, Continue Watching, and installed add-ons.
- Browse movie, series, and compatible add-on catalogs.
- Play supported streams with audio/subtitle track selection, playback controls, video resizing, and video-only picture brightness adjustment.
- Android 4.4 (API 19) ARMv7 build with FFmpeg audio support and modern TLS support for compatible servers.
- NEMESIS USB installer, APK, and buildable source code.

## Install
The release includes a NEMESIS USB ZIP. Extract its contents to the USB drive root, then run the installer from NEMESIS. The installer can perform a clean reinstall of the app package and validates the Android package version after installation.

A clean reinstall clears locally stored login information and caches. Every user signs in with their own account. The APK contains **no preconfigured user, email address, credentials, Stremio authorization token, or personal library data**.

## Build
GitHub Actions builds the APK using Java 17, the Android SDK, an ARMv7 FFmpeg audio library, and Gradle. It generates NEMESIS installer and source ZIP artifacts. See `.github/workflows/build.yml` and `tools/package_nemesis.py`.

## Compatibility and limitations
- Developed for compatible Android 4.4 based head units. Other devices have not been validated.
- Add-on endpoints are third-party services and may have TLS, network, or availability restrictions.
- Android network routing **has not been verified to be phone-only**. Wireless CarPlay by itself does not establish that app traffic uses phone data rather than the vehicle's connection. Check data usage and network policies before streaming.
- Use responsibly. Do not watch or operate video while driving.

## Notices
StremGen5W is an independent community project. It is **not affiliated with or endorsed by** Stremio, Hyundai, Kia, or NEMESIS. The official Stremio icon is used as identifying artwork and belongs to its trademark owner; follow the relevant trademark rules before redistribution. FFmpeg notices and licensing materials are included under `app/src/main/assets`.

## Privacy
This source release includes no Git commit history or device-generated account data. Do not commit local Android caches, API keys, signing keys, screenshots containing personal information, or debug logs to a public repository. Users are responsible for their own media sources and lawful content access.
