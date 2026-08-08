# ssHeadUnit

An Android application that turns a tablet into an **Android Auto head unit** — the same role a
factory car head unit plays. Plug a phone into the tablet with a USB cable and the phone starts
Android Auto and projects its interface onto the tablet, with touch input sent back to the phone.

## How it works

A car head unit is the USB *host* and the phone is the *accessory*:

1. **AOAP** – the tablet asks the phone to switch into Android Open Accessory mode by sending the
   accessory identification strings a head unit sends (`Android` / `Android Auto` / version
   `2.0.1`). The phone re-enumerates with a Google vendor id and exposes two bulk endpoints.
2. **Version negotiation** – the phone sends a version request on the control channel and the head
   unit answers with its protocol version.
3. **TLS handshake** – the head unit is the TLS server; the handshake records are tunnelled inside
   `SSL_HANDSHAKE` control messages. Every later frame payload is encrypted.
4. **Service discovery** – the head unit advertises its channels: video, media/speech/system audio,
   touch input and sensors (driving status and night mode).
5. **Channel setup** – the phone opens channels, negotiates the A/V configuration and then streams
   H.264 video and PCM audio. The head unit acknowledges media and forwards touch events.

## Project layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/ssheadunit/transport` | USB host transport and AOAP accessory-mode switch |
| `app/src/main/java/com/ssheadunit/protocol` | Frame codec, minimal protobuf codec, message builders, TLS layer |
| `app/src/main/java/com/ssheadunit/session`  | Session state machine, controller, credentials |
| `app/src/main/java/com/ssheadunit/av`       | H.264 decoding (`MediaCodec`) and PCM playback (`AudioTrack`) |
| `app/src/main/java/com/ssheadunit/ui`       | Full screen projection activity and foreground service |
| `app/src/main/java/com/ssheadunit/util`     | Diagnostic logging switch |
| `app/src/test`                            | JVM unit tests for the protocol layer |

## Building

```bash
./gradlew assembleDebug      # or: gradle assembleDebug
./gradlew assembleRelease    # creates an unsigned release APK
./gradlew test               # JVM unit tests for the protocol layer
```

The build needs the Android SDK (compile SDK 35) and access to `dl.google.com` for the Android
Gradle Plugin. The app itself has no runtime dependencies beyond the Android platform.
The GitHub Actions workflow builds the release variant and uploads the unsigned APK as a workflow
artifact.

## Requirements

* A tablet with **USB host (OTG) support** running Android 7.0 (API 24) or newer.
* A USB cable and a phone with Android Auto.
* Head unit TLS credentials (see below).

## Head unit credentials

A phone only projects to a head unit whose certificate it accepts. On its first load, ssHeadUnit
creates a passwordless `headunit.p12` TLS identity in its private app storage. You can instead
provide a custom identity through the assets folder:

* `app/src/main/assets/headunit.p12` – PKCS#12 keystore containing the head unit certificate and
  private key.
* `app/src/main/assets/headunit.pwd` – the keystore password (optional; empty when absent).

The generated identity is retained until the app's data is cleared.

## Usage

1. Install the APK on the tablet and grant the USB permission when prompted.
2. Before connecting, use **Settings** to select landscape or portrait display orientation, pick a
   display DPI, or turn on debug logging.
3. Connect the phone. The tablet requests accessory mode, and Android Auto starts on the phone.
4. The projected UI is shown full screen; touches are forwarded to the phone.
5. Unplugging the phone ends the session and stops the foreground service.

## Third party wireless adapters

Wireless Android Auto dongles (for example the Mayton **AutoPro X**) plug into the USB port of a
head unit and *impersonate a phone*: they speak AOAP towards the head unit and bridge to the real
phone over Wi-Fi and Bluetooth. From this app's point of view they are just another accessory, so
no separate protocol is needed — but they enumerate differently from a phone:

* They appear under their own vendor and product ids rather than a Google one. The ids published
  by third party research for this adapter family are `05ac:12a8` (normal mode) and `0525:a4a7`
  (CDC/A2A reset mode); both are in `usb_device_filter.xml`, but they come from a sibling model and
  may differ per unit.
* They reboot and re-enumerate after accepting the accessory strings, sometimes as a different USB
  device, which the app now waits for explicitly.
* They can expose several interfaces (a CDC serial one among them), so the interface that carries
  the session is selected by capability — an AOAP style vendor specific interface with one bulk IN
  and one bulk OUT endpoint is preferred — rather than by taking the first bulk pair.

Because of this, device detection is capability based and the ids above are only a fast path. There
is **no vendor published protocol specification** for these adapters, and no official support: the
adapter also validates the head unit certificate, so a generated `headunit.p12` may still be
rejected. Every stage of the session is now bounded by a timeout, so an adapter that stops
answering ends the session with a reason on screen instead of hanging.

If a connection still fails, turn on **Settings → Debug logging**. The USB descriptors, the selected
interface, the negotiated protocol version, the session phase and the watchdog that fired are saved
in the app's private debug log. Open **Settings → View debug log** to read it.

## Notes

* Debug logging is off by default; warnings and errors are always logged.
* Video is projected at 1280x720 by default; the resolution, DPI and head unit identity can be
  changed in `HeadUnitConfig` / `HeadUnitController`.
* The wireless (Wi-Fi) projection transport is not implemented; the app uses USB only.
* Android Auto is a trademark of Google LLC. This project is not affiliated with or endorsed by
  Google, and you are responsible for using it in compliance with the applicable terms.
