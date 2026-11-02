# Foxy SINAD

Android field kit for the foxsinad desense measurements: a Pixel phone on a
USB hub drives the PicoFox (foxsinad_fw 0.4.0, SCPI over USB CDC) and reads
receiver audio from a USB sound card (Behringer UFO202).

Status: the app (`app/`) is foxsinad's bench page in a WebView, talking to
foxsinad's bench engine (Python, under Chaquopy: Python 3.10, numpy 1.23.3,
SciPy 1.8.1) through `window.FoxsinadBridge`, the transport the page already
has for Android. Build with `gradle assembleDebug`; it needs a Python 3.10 on
the Mac for Chaquopy (default `~/miniconda3/envs/chaq310`, or set
`FOXYSINAD_BUILD_PYTHON`).

No copies: the build takes the engine, analysis, calibration table and page
from the `~/foxsinad` checkout and the PicoFox driver (`tools/foxctl.py`) from
`~/foxsinad-firmware` (`-Pfoxsinad=` / `-PfoxsinadFirmware=` for others), and
records their commits in `foxsinad_build.py` (`+dirty` if uncommitted), which
the app logs at startup. The phone-only Python is `phone.py` (the bridge) and
`phone_engine.py`, an Engine subclass that opens the PicoFox over Android's
USB link and feeds the analyzer from AudioRecord.

- `icon/` - launcher icon source (fox with a radar dish), 108x108 adaptive canvas.

Depends on [foxsinad](https://github.com/km6am/foxsinad) (the Python engine,
analyzer and UI page) and
[foxsinad-firmware](https://github.com/km6am/foxsinad-firmware) (the PicoFox
driver); see foxsinad's CLAUDE.md for the API other repos rely on.

## Build

```bash
git clone --recursive https://github.com/km6am/foxsinad ~/foxsinad
git clone https://github.com/km6am/foxsinad-firmware ~/foxsinad-firmware
git clone https://github.com/km6am/foxysinad ~/foxysinad
cd ~/foxysinad && gradle assembleDebug     # JDK 17; Android SDK in local.properties
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Checkouts elsewhere: `-Pfoxsinad=/path -PfoxsinadFirmware=/path`. When the
phone's one USB port holds the hub, install over Wi-Fi adb.

## Release

Users install the signed APK from the GitHub release: open it on the phone
and allow the browser (or Files) to install apps, or follow the repo with
Obtainium for updates. Runs are shared from the Finish step.

Signing, once per maintainer. The key and its passwords never enter the repo;
keep a backup of the keystore, since updates only install over an APK signed
with the same key:

```bash
keytool -genkeypair -v -keystore ~/.android/foxysinad-release.jks -alias foxysinad -keyalg RSA -keysize 4096 -validity 10000
```

Then in `~/.gradle/gradle.properties` (not this repo's):

```properties
foxysinad.keystore=/Users/you/.android/foxysinad-release.jks
foxysinad.storePassword=...
```

`gradle assembleRelease` writes `app/build/outputs/apk/release/app-release.apk`.
A phone with the debug build has to uninstall it first (different key), which
deletes its runs: share them before.

## License

MIT (see [LICENSE](LICENSE)). The app bundles foxsinad (MIT) and its
sinad_meter submodule (MIT) at build time. The firmware it drives is
CC BY-NC-SA 4.0, a derivative of AI6YM's PicoFox-Firmware.

SINAD figures are estimates from a sound-card analyzer, not calibrated
laboratory measurements. Cabled mode sends no Morse ID: use it only into a
load or test equipment, never an antenna.
