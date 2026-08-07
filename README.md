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
| `app/src/test`                            | JVM unit tests for the protocol layer |

## Building

```bash
./gradlew assembleDebug      # or: gradle assembleDebug
./gradlew test               # JVM unit tests for the protocol layer
```

The build needs the Android SDK (compile SDK 35) and access to `dl.google.com` for the Android
Gradle Plugin. The app itself has no runtime dependencies beyond the Android platform.

## Requirements

* A tablet with **USB host (OTG) support** running Android 7.0 (API 24) or newer.
* A USB cable and a phone with Android Auto.
* Head unit TLS credentials (see below).

## Head unit credentials

A phone only projects to a head unit whose certificate it accepts. ssHeadUnit therefore loads its
TLS identity from the assets folder and **no credentials are bundled with this repository**:

* `app/src/main/assets/headunit.p12` – PKCS#12 keystore containing the head unit certificate and
  private key.
* `app/src/main/assets/headunit.pwd` – the keystore password (optional; empty when the file is
  absent).

Without these files the app starts, detects the phone and reports that credentials are missing.

## Usage

1. Install the APK on the tablet and grant the USB permission when prompted.
2. Connect the phone. The tablet requests accessory mode, and Android Auto starts on the phone.
3. The projected UI is shown full screen; touches are forwarded to the phone.
4. Unplugging the phone ends the session and stops the foreground service.

## Notes

* Video is projected at 1280x720 by default; the resolution, DPI and head unit identity can be
  changed in `HeadUnitConfig` / `HeadUnitController`.
* The wireless (Wi-Fi) projection transport is not implemented; the app uses USB only.
* Android Auto is a trademark of Google LLC. This project is not affiliated with or endorsed by
  Google, and you are responsible for using it in compliance with the applicable terms.
