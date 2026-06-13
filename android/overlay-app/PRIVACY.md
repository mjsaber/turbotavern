# Turbo Tavern — Privacy Policy

_Last updated: 2026-06-13_

Turbo Tavern is an unofficial overlay assistant for Hearthstone Battlegrounds. This policy covers two builds:
- **Turbo Tavern (Google Play)** — overlay only.
- **Turbo Tavern (sideload, open-source)** — overlay + an optional "disconnect" feature.

## Summary
**Turbo Tavern does not collect, store off your device, sell, or transmit your personal data.** All processing happens on your device.

## What the app accesses, and why
1. **Screen contents (screen capture / MediaProjection).** When you tap Start and allow screen capture, the app reads the current screen to recognize Hearthstone hero and trinket names (on-device OCR) and draw tier badges over them. Screen images are processed in memory on your device — never saved, never uploaded.
2. **App usage / foreground app (Usage Access).** Used only to detect whether Hearthstone is the app in front, so the overlay appears only during Hearthstone. Read on-device; not stored or uploaded.
3. **Display over other apps.** Used only to draw the tier badges. No data is read from other apps.

## What the app does NOT do
- No account, login, or sign-up.
- No analytics, advertising, or tracking SDKs.
- No personal data collected or transmitted off the device.
- No screen recordings or screenshots are saved.

## Sideload build only: the "disconnect" (拔线) feature
The open-source sideload build includes an optional feature that uses Android's VpnService to route Hearthstone's own network traffic **locally** so it can momentarily disconnect a Battlegrounds combat. This runs entirely on your device; it does not send your traffic to any external server and does not inspect, store, or transmit your data. **Note:** using gameplay-affecting tools may violate Blizzard's Terms of Service and could risk your game account — use at your own risk. The Google Play build does **not** include this feature.

## Third-party components (all on-device)
- Google ML Kit Text Recognition (on-device OCR)
- ONNX Runtime + PaddleOCR PP-OCRv5 models (on-device OCR)
- (sideload build) mihomo, GPL-3.0, for the local VPN

## Data Safety (Google Play) — intended answers
- **Data collected:** none.
- **Data shared:** none.
- **Processed on-device only:** screen contents + foreground-app signal, used transiently to render the overlay; not stored, not transmitted.

## Children
Turbo Tavern is not directed at children under 13.

## Hearthstone
Turbo Tavern is unofficial and not affiliated with, endorsed by, or sponsored by Blizzard Entertainment, Inc. Hearthstone is a trademark of Blizzard Entertainment, Inc.

## Contact
> SET BEFORE PUBLISHING: your support email.

## Hosting
This document must be published at a public URL, and that URL entered in Google Play Console (Store listing → Privacy policy) before release.
