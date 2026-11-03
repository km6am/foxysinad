# Foxy SINAD

An Android app for measuring a radio's 12 dB SINAD sensitivity, and how much
it loses on an RF-busy summit (desense). The phone drives a PicoFox signal
source and measures the radio's audio through a USB sound card, using
[foxsinad](https://github.com/km6am/foxsinad)'s engine and page.

- [Hardware](#hardware)
- [Install](#install)
- [Getting runs off the phone](#getting-runs-off-the-phone)
- [Build](#build) · [Release](#release) (developers)

## Hardware

```
        ┌─ PicoFox r3 ─ SMA ─ step attenuator + fixed pads ─ radio antenna jack   (cabled)
phone ─ hub                                                  or an antenna          (over the air)
        └─ UFO202, LINE ─ left (white) RCA ─ 3.5 mm plug ─ radio earphone jack
```

| Part | Needs | Tested with |
|---|---|---|
| Phone | Android 13 or later, 64-bit ARM (arm64-v8a), USB host (OTG) | Pixel 10 Pro, Android 17 |
| USB hub | USB-C plug, two ports | a bus-powered hub (no charging input) |
| Signal source | PicoFox rev 3 with [foxsinad-firmware](https://github.com/km6am/foxsinad-firmware) 0.4.0, in a shielded enclosure (below) | |
| Sound card | USB Audio Class stereo line input, 48 kHz, 16-bit | Behringer UFO202 |
| Audio cable | 3.5 mm plug to RCA | |
| Attenuation | about 120 dB in total, every dB of it known | manual step attenuator + fixed SMA pads |

- **The PicoFox needs extra shielding.** A test at about −120 dBm is easily
  swamped by the PicoFox's own leakage: at ATT 0 it puts out about +12 dBm,
  so a leak 130 dB down is as strong as the test signal. Put it in a closed
  metal enclosure, and treat its USB cable as a leak path too. Check the
  shielding with the leak test (foxsinad's
  [bring-up guide](https://github.com/km6am/foxsinad/blob/main/docs/bringup-audio.md#4-leak-test-dont-skip)):
  tone on at ATT 0 and the radio's input terminated, SINAD must stay at the
  noise reading (under about 1 dB). If it doesn't, sweeps read too good.
- **UFO202 switch on LINE.** PHONO applies RIAA equalization and skews SINAD.
- **Audio goes in on the left channel** (white RCA). That's the only
  channel measured.
- **Use the radio's earphone jack, never the speaker terminals.** Some
  radios drive the speaker from both sides (bridged), and the cable's
  grounded sleeve can damage them. Check each radio before plugging in.
- **Attenuation:** the PicoFox's own attenuator covers 31.75 dB (about +12
  to −20 dBm out). The external attenuation has to bring the radio's 12 dB
  SINAD point into that range: a UV-K5, at about −124 dBm, sat mid-range
  with 119 dB. The app asks for the external total before each sweep. Its
  result is only as accurate as that number.
- **Cabled or over the air.** Cabled: the attenuators go into the radio's
  antenna jack, the PicoFox sends no Morse ID, and its output must go only
  into a load or test equipment, never an antenna. Over the air: the
  attenuators feed an antenna, the PicoFox needs your callsign, and sends a Morse ID every
  9 minutes. Levels are then the PicoFox's output (or, with the external
  attenuation entered, the level into the antenna), never the level at the
  radio, since the path loss isn't known.
- **Power:** a bus-powered hub runs the PicoFox and the sound card from the
  phone's battery, and the phone doesn't charge during a run. The screen
  stays on while the app is open; there's no background mode yet.

## Install

The app isn't on Google Play. Install the APK from the
[latest release](https://github.com/km6am/foxysinad/releases/latest).

On the phone, in Chrome:

1. Open the release page and tap `foxysinad-<version>.apk` under
   **Assets**. If Chrome warns that the file may be harmful, tap
   **Download anyway**.
2. Open the downloaded file, from the notification or Files → Downloads.
3. The first time, Android says it can't install apps from this source.
   Tap **Settings**, turn on **Allow from this source**, then go back.
4. Tap **Install**. Google Play Protect may offer to scan an app from an
   unknown developer; that's fine.
5. Open **Foxy SINAD** and allow the microphone. Android asks this for any
   audio input, including the USB sound card.
6. Plug the hub, PicoFox and sound card in. The first time the app connects
   after the PicoFox is plugged in, Android asks whether Foxy SINAD may use
   it; allow it.

From a computer instead, with USB debugging on:

```bash
adb install foxysinad-0.1.0.apk
```

**Check the download** (optional). Its SHA-256 is in the release notes.
macOS and Linux: `shasum -a 256 foxysinad-0.1.0.apk`; Windows:
`certutil -hashfile foxysinad-0.1.0.apk SHA256`. The signing certificate's
SHA-256 is `9c2ee2fb6830c19cbc8b264d25cd54a40f2d0fabe6b20dd94aba4db0be0004c5`
(`apksigner verify --print-certs`, from the Android SDK build-tools).

**Updates** install the same way, over the old version, and keep your runs
and settings. Android only accepts an update signed with that same
certificate. **Uninstalling deletes every run**: save or share them first.

## Getting runs off the phone

Each session is a folder of CSVs (one per sweep or record) in the app's
private storage. Two ways out, on the Finish step and in the Sessions tab:

- **Share files** opens Android's share sheet (Drive, email, Quick Share
  and so on).
- **Save to Downloads** copies them to `Download/FoxySINAD/<session>/`,
  where the Files app and a computer on USB can see them. Saving again
  replaces the earlier copies.

The Sessions tab also continues a saved session (another radio, the same
folder) or deletes one, after asking.

## Build

```bash
git clone --recursive https://github.com/km6am/foxsinad ~/foxsinad
git clone https://github.com/km6am/foxsinad-firmware ~/foxsinad-firmware
git clone https://github.com/km6am/foxysinad ~/foxysinad
cd ~/foxysinad && gradle assembleDebug     # JDK 17; Android SDK in local.properties
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build is `com.km6am.foxysinad.debug` ("Foxy SINAD debug"). It
installs beside the release with its own runs, and it's the only build with
WebView debugging. Chaquopy needs a Python 3.10 on the build machine
(default `~/miniconda3/envs/chaq310`, or set `FOXYSINAD_BUILD_PYTHON`).
When the phone's one USB port holds the hub, install over Wi-Fi adb.

How it fits together: the app (`app/`) is foxsinad's page in a WebView,
talking to foxsinad's engine (Python 3.10 under Chaquopy, with numpy
1.23.3 and SciPy 1.8.1) through `window.FoxsinadBridge`. Nothing is
copied into this repo. The build takes the engine, analysis, calibration
table and page from the foxsinad checkout, and the PicoFox driver
(`tools/foxctl.py`) from foxsinad-firmware. To use other checkouts, pass
`-Pfoxsinad=/path -PfoxsinadFirmware=/path`. The build records both
checkouts' commits in `foxsinad_build.py` (`+dirty` if uncommitted), and the
app logs them at startup. The phone-only Python is `phone.py` (the bridge)
and `phone_engine.py`, an Engine subclass that opens the PicoFox over
Android's USB link and feeds the analyzer from AudioRecord. `icon/` holds
the launcher icon source (108×108 adaptive canvas). foxsinad's CLAUDE.md
lists the API this app relies on.

## Release

Signing, once per maintainer. The key and its passwords never enter the
repo. Keep a backup of the keystore: updates only install over an APK
signed with the same key.

```bash
keytool -genkeypair -v -keystore ~/.android/foxysinad-release.jks -alias foxysinad -keyalg RSA -keysize 4096 -validity 10000
```

Then in `~/.gradle/gradle.properties` (not this repo's):

```properties
foxysinad.keystore=/Users/you/.android/foxysinad-release.jks
foxysinad.storePassword=...
```

For each release:
1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run `gradle assembleRelease`. It writes
   `app/build/outputs/apk/release/app-release.apk`.
3. Attach that file to a GitHub release as `foxysinad-<version>.apk`, with
   its SHA-256 in the notes.

## License

MIT (see [LICENSE](LICENSE)). The app bundles foxsinad (MIT) and its
sinad_meter submodule (MIT) at build time. The firmware it drives is
CC BY-NC-SA 4.0, a derivative of AI6YM's PicoFox-Firmware.

SINAD figures are estimates from a sound-card analyzer, not calibrated
laboratory measurements. Cabled mode sends no Morse ID: use it only into a
load or test equipment, never an antenna.
