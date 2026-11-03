# Changelog

## 0.1.0 — unreleased

First public version.

**App**
- foxsinad's summit workflow page in a WebView, with foxsinad's engine running
  on the phone under Chaquopy; the page talks to it through
  `window.FoxsinadBridge`.
- PicoFox over Android's USB link (SCPI, firmware 0.4.0); radio audio from a
  USB sound card through AudioRecord, in 0.25 s blocks.
- Listening along on the phone's own speaker, never the USB sound card.
- A session's files go out through Android's share sheet, or are saved to
  Download/FoxySINAD/<session>, where the Files app and a computer can reach them.
- The build takes the engine, page and driver from the foxsinad and
  foxsinad-firmware checkouts and logs their commits at startup.
