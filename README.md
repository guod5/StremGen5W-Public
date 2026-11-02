# Stremio Auto

**An unofficial, independent side project for Hyundai Motor Group Gen5 head units with NEMESIS (Android 4.4).**

Stremio Auto is a hobby project created to get a usable Stremio-style client running on older HMG head units. It started with extensive ChatGPT-assisted experimentation rather than professional Android development. It is **not an official Stremio app** and is **not affiliated with, sponsored by, or endorsed by Stremio**, Hyundai, Kia, or NEMESIS.

This is a work in progress. It is **not as feature-rich or polished as official Stremio apps**, and there are known limitations, bugs, and compatibility differences. The source is shared in the hope that other developers can improve it. Issues and pull requests are welcome.

**Current release:** v2.0.4 · **GitHub project:** `Stremio-gen5-hmg-nemesis`

## What it can do

- Sign in to your own Stremio account and access compatible library, Continue Watching, add-on, movie, and series content.
- Play supported video sources inside the app with ExoPlayer, audio/subtitle options, controls, and resizing. Prefer supported English soundtracks automatically when the stream identifies their language; users can select another track manually.
- Render common country flags and language indicators as bundled images on Android 4.4 rather than relying on unsupported system flag emoji fonts.
- Use bundled FFmpeg audio codec support on Android 4.4 (API 19, ARMv7).
- Use VLC as an optional fallback if installed. The **VLC integration** switch under Settings defaults on; switch it off to hide VLC playback actions and enlarge the **Watch** button on stream cards.
- Install using a NEMESIS USB ZIP and build from the included Android source.

Functionality depends on stream formats, third-party add-ons, network conditions, and the head unit's firmware. Not every feature from the official Stremio applications is implemented.

## Installation

Download **Stremio-gen5-hmg-nemesis-v2.0.4-USB-Installer.zip** from GitHub Releases. Extract the three items inside it directly onto the root of your USB flash drive, then connect it to a compatible NEMESIS-enabled head unit and follow the installation prompt.

The NEMESIS installer checks the existing installation, removes the previous version when appropriate, and installs the new APK. **A clean reinstall clears the app's saved login, cached media metadata and preferences.** You will need to sign in again afterward.

The Android application ID and NEMESIS internal package ID intentionally stay the same as the earlier StremGen5W versions. They are internal compatibility identifiers, not the name displayed in the car, and allow existing installations to be replaced rather than duplicated.

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

This community project is **completely unofficial**. Stremio and its name/logo are owned by their respective rights holders. The launcher uses a customized purple-to-blue Stremio-style design with an IONIQ-inspired pixel play triangle; it is an unofficial community modification and not an official Stremio logo. See `branding/README.md`. FFmpeg licensing notices are included in `app/src/main/assets`.

Please contribute improvements, bug reports, and pull requests. This project exists because the author wanted a working client on an older Android head unit and hopes other people can make it better.
