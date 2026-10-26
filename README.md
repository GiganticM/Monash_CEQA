# MH CEQA - Android Asset Scanner

A rugged Android barcode and asset inspection terminal application built for **Clinical Engineering Quality Assurance (CEQA)**. Designed specifically for handheld mobile terminals (e.g. ScanSKU) and mobile Android devices, the application provides high-speed barcode scanning against large asset reference databases (40k+ records), dual-mode QA and Inspection workflows, hardware scanner broadcast integration, asset condition scoring, Bluetooth SPP transmission, and automated Google Drive cloud synchronization.

- **Package:** `me.rxue.mhceqa`
- **Current Version:** `2.0` (versionCode 3)
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 36 (Android 14+)
- **Architecture:** Jetpack Compose + Kotlin Coroutines + Material 3

---

## What's New in Version 2.0

* **Inspection Only (IO) Mode:** Specialized inspection session mode with asset condition scoring (`-2`, `-1`, `0`, `+1`, `+2`).
* **Condition Scoring Workflow:**
  * First scan processes and logs the asset as found.
  * Second scan triggers a touch-friendly Compose dialog to assign or update the condition score.
  * Integrated scoring controls on the New Asset registration screen.
  * Scores are persisted to output CSV files while keeping the operator LOG tab clean and distraction-free.
* **Dirty-Data / Ambiguity Filter:** Safeguard against uncleaned reference databases. Scans matching more than 5 distinct assets (such as generic `"barcode"` labels) are rejected with a warning dialog.
* **Dual-File CSV Architecture & Google Drive Upload:**
  * Clean separation of standard scans and newly registered assets (`_NEW.csv`).
  * Cloud sync automatically pairs and uploads both files together with hash-based change detection and retry handling.
  * Automatic local archiving upon session completion.
* **Source Asset Cloud Update:** Background checks and silent updates for master asset lists hosted on Google Drive.
* **Enhanced Log Experience:** Reverse chronological sorting, interactive expand/collapse for new asset specs (Department, Make, Model, S/N), and zebra striping.
* **Modern Jetpack Compose UI:** Complete modernization of UI screens, minimalist Monash Health branding, and fast startup with splash screen.

---

## Key Features

### 1. Dual Working Sessions
* **QA Mode:** Full medical asset Quality Assurance verification with routine inspection period indicators.
* **Inspection Only (IO) Mode:** Rapid device condition rating workflow without unnecessary Bluetooth transmissions.
* **Multi-Technician Support:** Supports single or grouped operator initials (e.g. `RX AS` or `RX`). Scans are merged chronologically across sessions for operators working together throughout the day.

### 2. High-Performance Asset Lookup & Smart Filtering
* **O(1) In-Memory Lookup:** Powered by indexed `HashMap` lookup against asset numbers, serial numbers, and barcodes.
* **AssetGenius Barcode Parsing:** Automatically handles 35-character composite barcode strings.
* **High-Ambiguity Rejection (> 5 Matches):** Rejects invalid or dirty barcode scans matching more than 5 distinct assets.
* **Picker Dialog (2–5 Matches):** Displays an asset selection dialog when duplicate identifiers match a small set of records.

### 3. Asset Condition Scoring (IO Session)
* Assigns ratings across 5 standardized scores:
  * `+2`: Excellent / Like New
  * `+1`: Good / Minor Wear
  * ` 0`: Standard / Operational
  * `-1`: Fair / Needs Attention
  * `-2`: Poor / Critical Defect
* Accessible directly on second scan or upon manual creation of unlisted assets.

### 4. Hardware Scanner & Broadcast Integration
* Built-in hardware laser/imager integration via Android broadcast intents (`BarcodeHelper`).
* Hardware scanning directly populates focused input fields on the **Add New Asset** screen (e.g. scanning the physical device Serial Number barcode into the S/N field).
* Hardware key event fallback (`dispatchKeyEvent`) for keyboard wedge scanners.

### 5. Automated Google Drive Cloud Sync
* **Records Upload:** Dispatches daily output CSV files to Google Drive via secure Apps Script webhooks.
* **Atomic Dual-File Sync:** Transmits both base records and `_NEW.csv` records simultaneously.
* **Unmodified Bypass:** Skips sync operations when files have had no new scan modifications.
* **Daily Archive Management:** Automatically archives daily output files into an `Archive/` directory once synced.
* **Source Asset List Sync:** Periodically queries Google Drive folder ID for updated master asset lists.

### 6. Bluetooth SPP Master Output
* Connects to Bluetooth Classic Serial Port Profile (SPP) devices (e.g. test equipment, label printers).
* Automatic reconnect daemon with exponential backoff and connection pulse indicators.
* Automatically muted in IO session mode.

---

## App Flow

```
                     ┌────────────────────────┐
                     │     Splash Screen      │
                     └───────────┬────────────┘
                                 ▼
                     ┌────────────────────────┐
                     │     Session Login      │
                     │  (Initials + Location  │
                     │    + QA / IO Mode)     │
                     └───────────┬────────────┘
                                 │ Start Session
                                 ▼
                     ┌────────────────────────┐
        ┌───────────►│   Main Scanner Screen  │◄────────────┐
        │            │  (SCAN Tab / LOG Tab)  │             │
        │            └───────────┬────────────┘             │
        │                        │                          │
        │         ┌──────────────┴──────────────┐           │
        │  Barcode Scan                  Search Submitted   │
        │         │                             │           │
        │         ▼                             ▼           │
        │   ┌──────────────┐             ┌──────────────┐   │
        │   │ > 5 Matches? ├─ Yes ──────►│ Reject Alert │───┤
        │   └──────┬───────┘             └──────────────┘   │
        │          │ No                                     │
        │   ┌──────▼───────┐                                │
        │   │ 2–5 Matches? ├─ Yes ──────►[ Asset Picker ]───┤
        │   └──────┬───────┘                                │
        │          │ Single Match                           │
        │   ┌──────▼──────────────────┐                     │
        │   │ Already Logged?         │                     │
        │   │ (1st vs 2nd Scan in IO) │                     │
        │   └──────┬──────────────────┘                     │
        │          ├─ 1st Scan ─────────► Log Found ────────┤
        │          │                      (Banner/CSV)      │
        │          └─ 2nd Scan (IO) ────►[Condition Score]──┤
        │                                 (-2 to +2 Dialog) │
        │   Asset Not Found                                 │
        │          ▼                                        │
        │   ┌─────────────────────────┐                     │
        │   │   Add New Asset Screen  │                     │
        │   │ (Dept, Make, Model, S/N,│                     │
        │   │    IO Condition Score)  ├─────────────────────┘
        │   └─────────────────────────┘
        │
        │ End Session
        ▼
┌───────────────────────────────┐
│ End Session & Cloud Sync      │
│ (Google Drive Webhook Sync    │
│  + Move to Archive Folder)    │
└───────────────────────────────┘
```

---

## CSV Specifications

### 1. Source Reference CSV (Read Only)
Column order: `asset_number`, `serial_number`, `department`, `barcode`, `routine_period`

```csv
asset_number,serial_number,department,barcode,routine_period
MH10001,SN-49102-A,Emergency,BC-10001,12
MH10002,SN-88219-B,ICU,BC-10002,24
MH10003,SN-33100-C,Biomedical,BC-10003,60
```

* **Routine Period Sticker Indicator:**
  * `12`: Yellow sticker (12 months)
  * `24`: White sticker (24 months)
  * `60`: Green sticker (60 months)

---

### 2. Output CSV Files

#### A. QA Session Files
* **Found Assets File:** `[prefix_]qa_DD-MM-YYYY.csv`
  ```csv
  asset_number,department,timestamp,row_type
  RX AS,MHCE,08:30:00,session
  MH10001,Emergency,08:31:15,found
  MH10002,ICU,08:35:42,found
  ```
* **New Assets File:** `[prefix_]qa_DD-MM-YYYY_NEW.csv` *(only generated if new assets are added)*
  ```csv
  asset_number,department,make,model,serial_number,timestamp,user_initials
  NEW9001,Cardiology,Philips,MX450,SN-PH-9921,08:40:10,RX
  ```

#### B. Inspection Only (IO) Session Files
* **IO Found Assets File:** `[prefix_]io_DD-MM-YYYY.csv`
  ```csv
  asset_number,department,timestamp,row_type,condition_score
  RX,MHCE,09:00:00,session,
  MH10001,Emergency,09:02:11,found,0
  MH10002,ICU,09:05:40,found,+1
  ```
* **IO New Assets File:** `[prefix_]io_DD-MM-YYYY_NEW.csv`
  ```csv
  asset_number,department,make,model,serial_number,timestamp,user_initials,condition_score
  NEW9002,Radiology,GE,Logiq E9,SN-GE-4102,09:12:33,RX,-1
  ```

---

## Project Structure

```
Monash_CEQA/
├── app/
│   ├── build.gradle                              ← Gradle build config (v2.0, SDK 36)
│   └── src/main/
│       ├── AndroidManifest.xml                   ← App permissions & activities
│       ├── java/me/rxue/mhceqa/
│       │   ├── MainActivity.kt                   ← Main scanning engine & navigation
│       │   ├── SessionActivity.kt                ← Session launcher & authentication
│       │   ├── AddAssetActivity.kt               ← New asset registration activity
│       │   ├── SettingsActivity.kt               ← App preferences & cloud settings
│       │   ├── BarcodeHelper.kt                  ← ScanSKU broadcast intent helper
│       │   ├── BluetoothSppManager.kt            ← Bluetooth Classic SPP master client
│       │   ├── CsvManager.kt                     ← Database indexing & CSV I/O
│       │   ├── DriveSyncManager.kt               ← Google Drive Webhook sync engine
│       │   ├── models/
│       │   │   └── Asset.kt                      ← Unified asset data model
│       │   └── ui/
│       │       ├── components/
│       │       │   ├── BtStatusChip.kt           ← Animated Bluetooth status badge
│       │       │   ├── ConditionScoreDialog.kt   ← Compose condition scoring dialog
│       │       │   ├── CustomSnackbarVisuals.kt  ← High-visibility scan banners
│       │       │   └── ScanLogCard.kt            ← Expandable terminal log cards
│       │       ├── screens/
│       │       │   ├── AddAssetScreen.kt         ← Compose new asset entry screen
│       │       │   ├── MainScannerScreen.kt      ← Compose scan & log dual tabs
│       │       │   ├── SessionScreen.kt          ← Compose session setup & mode select
│       │       │   └── SettingsScreen.kt         ← Compose settings & storage manager
│       │       └── theme/
│       │           ├── Color.kt                  ← Material 3 & Terminal color tokens
│       │           ├── Theme.kt                  ← Monash Health Compose theme
│       │           └── Type.kt                   ← Inter & JetBrains Mono typography
│       └── res/
│           ├── drawable/                         ← Vector icons & brand assets
│           ├── font/                             ← JetBrains Mono & Inter font files
│           ├── mipmap-*/                         ← Launcher icons
│           └── values/                           ← Colors, strings, and splash themes
├── gradle/
│   └── libs.versions.toml                        ← Version catalog
├── README.md
└── settings.gradle
```

---

## Settings & Preferences

All preferences are managed via [SettingsScreen.kt](file:///Users/rxue/Project/agy%20CEQA/Monash_CEQA/app/src/main/java/me/rxue/mhceqa/ui/screens/SettingsScreen.kt) and persisted in `asset_scanner_prefs`:

| Setting | Default | Description |
|---|---|---|
| **Scanner Prefix** | *(blank)* | Device identifier prefix added to CSV filenames (e.g. `1` produces `1_qa_...csv`). |
| **Storage Location** | Internal / Custom SAF | Directory where output CSV files and daily archives are saved. |
| **Google Drive Records Upload** | Configured | Automated Google Apps Script Webhook URL for daily record uploads. |
| **Source List Cloud Sync** | Folder ID configured | Syncs updated reference CSV files directly from Google Drive. |
| **Source List Auto-Check** | `false` | Automatically checks for updated source lists on app launch. |
| **Log Font Size** | `14sp` | Text scaling for the real-time LOG tab (`11sp` to `20sp`). |
| **Found Banner Duration** | `4s` | Display duration for scan feedback banners (`1s` to `10s`). |

---

## Building and Installing

### Prerequisites
* **Android Studio** Ladybug or newer
* **JDK:** Java 17 or Java 21 (configured as `JAVA_HOME`)
* **Android SDK:** Platform 36, Build-Tools 36.0.0

### Build Debug APK
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```
The output APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install to Handheld Terminal via ADB
```bash
# Check connected device
adb devices

# Install APK directly
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Copy APK to terminal Downloads folder
adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/mhceqa_v2.0.apk
```

---

## License & Credits

Developed by **Rui Xue** for **Monash Health Clinical Engineering**.
All rights reserved. Designed for internal medical device auditing and CEQA workflows.
