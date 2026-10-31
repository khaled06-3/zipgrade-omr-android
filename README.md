# ZipGrade OMR CV Scanner — Native Android & PWA Repository

This repository contains the **Android Handheld Optical Mark Recognition (OMR) Computer Vision Scanner** (`com.zipgrade.omrscanner`), featuring a 5-stage computer vision pipeline:

1. **Stage 1 — Integral-Image Local Adaptive Thresholding** (`windowRadius = 28`)
2. **Stage 2 — Global 6-Fiducial Registration Lock** (`TL, TR, ML, MR, BL, BR`)
3. **Stage 3 — Split Upper/Lower Dual-Quad Perspective Homography** (`600×780` canonical space)
4. **Stage 4 — 7-Digit Student ID Column Decoder** (Forms `922412` & `6353B`)
5. **Stage 5 — Dynamic Question Count (1..30Q, 4 or 5 Options) Circular ROI Integrator**

---

## Automated GitHub Actions APK Build (`.github/workflows/android-apk.yml`)

Every push to `main` automatically triggers the **Build Android OMR Scanner APK** GitHub Action:
- Sets up **JDK 17 (Temurin)**, **Android SDK (API 34)**, and **Gradle 8.7**
- Runs `gradle assembleDebug --no-daemon`
- Publishes `zipgrade-omr-scanner-debug-apk` (`app-debug.apk`) directly in the GitHub Actions **Artifacts** tab for instant download to your Android device.

---

## Option 1: Instant Android Install (Progressive Web App — Zero Build Needed)

1. Open **undefined?mode=android** in **Google Chrome on your Android phone**.
2. Tap **"Install Android App"** in the top bar (or open Chrome's `⋮` menu and tap **"Add to Home screen / Install app"**).
3. Launch **OMR Scanner** from your Android home screen in full-screen standalone mode with 100% offline Workbox Service Worker caching.

---

## Option 2: Compile Locally in Android Studio

1. Clone this repository and open it in **Android Studio (Ladybug / Koala or newer)**.
2. Let Gradle sync (`compileSdk = 34`, `minSdk = 26`).
3. Connect your Android phone via USB debugging or build from terminal:
   ```bash
   gradle assembleDebug
   ```
4. Install the generated APK:
   `app/build/outputs/apk/debug/app-debug.apk`
