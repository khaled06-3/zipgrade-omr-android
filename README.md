# OMR Scanner (ZipGrade CV) — Native Android Application

> **📲 Direct Android APK Download (Ready to Install)**
> - **Download APK (v1.0.0, Signed 249 KB)**: [**`zipgrade-omr-scanner.apk`**](https://github.com/khaled06-3/zipgrade-omr-android/raw/main/zipgrade-omr-scanner.apk)
> - **Mirror in Repository**: [`releases/zipgrade-omr-scanner.apk`](https://github.com/khaled06-3/zipgrade-omr-android/raw/main/releases/zipgrade-omr-scanner.apk)
> - **GitHub Releases Page**: [Releases (`v1.0.0`)](https://github.com/khaled06-3/zipgrade-omr-android/releases)

Handheld Optical Mark Recognition (OMR) Android Scanner built with a **5-Stage Computer Vision Pipeline**:

1. **Stage 1 — Integral Image Adaptive Thresholding (`O(1)` Box Filter)**
   - Converts frames to luminance grayscale (`0.299R + 0.587G + 0.114B`) and runs a sliding-window local mean threshold (`window = W/28`, `k = 0.82`) to eliminate uneven shadows and desk lighting gradients.
2. **Stage 2 — 4-Corner Fiducial Registration Block Detection**
   - Scans the 4 corner regions (`TL`, `TR`, `BR`, `BL`) for solid black square registration anchors.
3. **Stage 3 — Perspective Bilinear Homography Mapping**
   - Maps normalized sheet coordinates `(u, v)` onto the arbitrary 4-corner quadrilateral perspective plane.
4. **Stage 4 — Dynamic Multi-Column Question Layout (`1–30 Questions`)**
   - Supports instant switching between **10Q**, **16Q**, **20Q**, **30Q**, or any custom question count (`1..30`) across single-column and multi-column (`Q1–Q13` left, `Q14–Q26` middle, `Q27–Q30` right) answer sheets.
5. **Stage 5 — Optical Mark Density Scoring & 7×10 Student ID Decoder**
   - Evaluates bubble fill density for `A / B / C / D` choices, flags blank (`?`) and multiple-mark (`*`) anomalies, and decodes the 7-digit Arabic Student ID matrix (`0–9 × 7 columns`).

---

## How to Install the APK on Your Android Phone

1. Tap [**Download `zipgrade-omr-scanner.apk`**](https://github.com/khaled06-3/zipgrade-omr-android/raw/main/zipgrade-omr-scanner.apk) on your Android device.
2. Open the downloaded `zipgrade-omr-scanner.apk` file and tap **Install** (if prompted by Android, allow installing from your browser).
3. Open **ZipGrade OMR CV** to:
   - Scan live with **CAMERA SCAN** or pick any photo from WhatsApp/Gallery with **PICK PHOTO**.
   - Test the bundled **16Q Exam Sheet** and **10Q Exam Sheet** offline.
   - Switch between **10Q / 16Q / 20Q / 30Q** (or `-1Q` / `+1Q`) and tap **EDIT ANSWER KEY** to customize the grading key.
