# MH CEQA — Android Asset Scanner (Kotlin)

An Android app built for **Monash Health CEQA** to perform QA scanning of medical assets. Operators scan asset IDs against a reference CSV, log found assets in real time, record new ones manually, and optionally transmit scan data to a Bluetooth SPP device.

- **Package:** `org.monash.ceqa`
- **Min SDK:** 26 (Android 8.0)  
- **Target SDK:** 36  
- **Version:** 1.5.2

---

## Features

| Feature | Detail |
|---|---|
| Session login | Operator initials + QA location captured before scanning begins |
| Source CSV lookup | Matches asset number, serial number, or barcode against reference database |
| Fast in-memory lookup | `HashMap` — O(1) lookups against 40k+ records |
| Hardware barcode scanner support | `dispatchKeyEvent` buffer — works on Android 9+ without soft keyboard |
| Bluetooth SPP auto-reconnect | Daemon reader thread detects disconnect; 10-second retry loop with pulse animation |
| Real-time scan log (LOG tab) | Terminal-style RecyclerView with FOUND / NEW badges and session headers |
| Sticker colour indicator | Yellow (12mth) / White (24mth) / Green (60mth) dot per routine period |
| New asset entry screen | Department, Make, Model, Serial — with unsaved-changes warning on back |
| Output CSV with session rows | Session header rows distinguish scanner sessions within a single day's file |
| Log record deletion | Long-press to enter selection mode; delete selected rows from CSV and log |
| Configurable settings | Font size, snackbar duration, output folder, scanner name prefix |
| Auto end-of-day session close | Session automatically ends if the app is opened on a new date |

---

## App Flow

```
Launch → SessionActivity (initials + location)
              ↓  Start Session
         MainActivity (SCAN tab / LOG tab)
              ↓  Asset not found
         AddAssetActivity (manual entry)
              ↓  End Session
         SessionActivity
```

---

## Project Structure

```
AssetScanner/
├── build.gradle
├── settings.gradle
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/org/monash/ceqa/
        │   ├── SessionActivity.kt        ← launcher; captures initials + location
        │   ├── MainActivity.kt           ← SCAN + LOG tabs, BT, CSV search
        │   ├── AddAssetActivity.kt       ← new asset manual entry screen
        │   ├── CsvManager.kt             ← source CSV loading, output CSV read/write
        │   ├── BluetoothSppManager.kt    ← BT Classic SPP connect/send/reconnect
        │   ├── ScanLogAdapter.kt         ← RecyclerView adapter for LOG tab
        │   ├── SettingsActivity.kt       ← font size, snackbar duration, output folder
        │   └── models/
        │       └── Asset.kt              ← data model (includes isSessionHeader flag)
        └── res/
            ├── layout/
            │   ├── activity_session.xml
            │   ├── activity_main.xml
            │   ├── activity_add_asset.xml
            │   ├── activity_settings.xml
            │   └── item_scan_log.xml
            ├── menu/
            │   ├── main_menu.xml         ← End Session (red), CSV, BT, Settings
            │   └── settings_menu.xml     ← Save action
            ├── drawable/
            │   ├── ic_app_logo.png
            │   ├── ic_settings.xml
            │   └── circle_dot.xml        ← sticker colour dot
            └── values/
                ├── strings.xml
                ├── colors.xml
                └── ic_launcher_background.xml
```

---

## Setup

1. Open in **Android Studio** (Hedgehog or later)
2. Sync Gradle — key dependencies in `app/build.gradle`:
   ```gradle
   implementation libs.material
   implementation libs.androidx.appcompat
   implementation libs.androidx.activity.ktx
   implementation libs.androidx.documentfile
   implementation libs.opencsv
   implementation libs.androidx.recyclerview
   ```
3. Build and install on a **physical device** (Bluetooth does not work on emulators)
4. Minimum tested device: 480×854, 4.6", Android 9 (API 28)

---

## How to Use

### Starting a session
1. Launch the app — the **Session screen** appears
2. Enter your **initials** (required, max 15 chars, auto-uppercased)
3. Enter your **QA location** (e.g. `MHCE`) — optional, a confirmation dialog appears if blank
4. Tap **Start Session** — a session header row is written to the output CSV

### Loading the asset reference list
- Tap **⋮ menu → 📂 Load Asset List (.csv)**
- Pick your `.csv` file — the app reads columns: `asset_number`, `serial`, `department`, `barcode`, `routine_period`
- Status shows: `✓ Loaded 12345 records`
- The URI is remembered and reloads automatically on next launch

### Scanning assets
- Type or scan into the search field — hardware scanners work via `dispatchKeyEvent`
- Tap **Search** or press Enter
- **Found:** green snackbar `✓ FOUND — MH12345 logged`, entry appears in LOG tab
- **Already logged:** orange snackbar `⚠ Already logged — MH12345`
- **Not found:** opens **Add New Asset** screen for manual entry

### LOG tab
- Shows all scans for the session including session header rows (teal)
- **Long-press** a row to enter selection mode → select rows → **Delete** to remove from log and CSV
- Session header rows cannot be selected or deleted

### Bluetooth
- Pair device first via **Android Settings → Bluetooth**
- Tap **⋮ menu → 🔵 Connect Bluetooth** → select device
- The app auto-reconnects if disconnected; a pulsing **RECONNECT** button appears
- Scanned asset numbers are transmitted as ASCII with `\r` terminator

### Ending a session
- Tap **End Session** (red, in toolbar) → confirm dialog
- Returns to Session screen; session date is cleared
- Sessions also auto-end overnight if the date changes

---

## CSV Formats

### Source CSV (read only)

Column order: `asset_number`, `serial_number`, `department`, `barcode`, `routine_period`

```
asset_number,serial_number,department,barcode,routine_period
MH12345,SN-ABC-001,ICU,BC-001,12
MH12346,SN-ABC-002,ED,BC-002,24
MH12347,SN-ABC-003,Pharmacy,BC-003,60
```

`routine_period` values: `12` = yellow sticker, `24` = white sticker, `60` = green sticker, blank/other = no dot.

### Output CSV (written by app)

Filename: `qa_assets_DD-MM-YYYY.csv` (or `scanner_1_qa_assets_DD-MM-YYYY.csv` if scanner name is set)

```
asset_number,department,make,model,serial_number,timestamp,row_type
RX,MHCE,,,, 08:01:23,session
MH12345,ICU,,,, 08:05:11,
MH12346,ED,,,, 08:06:44,
NEW001,WARD4,GE,Monitor,SN-GE-001,08:10:02,
```

- **Session rows** — `row_type = session`; `asset_number` = operator initials, `department` = location
- **Found asset rows** — `row_type` blank; only `asset_number`, `department`, `timestamp` populated
- **New asset rows** — all columns populated from the Add Asset screen

---

## Settings

| Setting | Default | Description |
|---|---|---|
| Scanner name | _(blank)_ | Prefix added to output filename, e.g. `scanner_1` |
| Output folder | App external files dir | Custom folder via system folder picker |
| QA record font size | Medium | Small / Medium / Large in LOG tab |
| Scan result duration | 5 seconds | How long the found/not-found snackbar stays visible (2–10s) |

---

## Bluetooth Notes

- Connects as **master** to slave device (HC-05, HC-06, or any SPP device)
- SPP UUID: `00001101-0000-1000-8000-00805F9B34FB`
- Line terminator: `\r` (carriage return only) — edit `LINE_TERMINATOR` in `BluetoothSppManager.kt`
- Fallback to `createRfcommSocket(1)` if standard UUID connection fails
- Reader thread detects disconnection within 1–2 seconds on all Android versions

### Permissions by Android version

| Android version | Permissions |
|---|---|
| ≤ Android 11 (API 30) | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_COARSE_LOCATION` |
| Android 12+ (API 31+) | `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` |

---

## SharedPreferences Keys

All stored under `"asset_scanner_prefs"`:

| Key | Type | Description |
|---|---|---|
| `session_active` | Boolean | Whether a session is currently active |
| `session_user_initials` | String | Operator initials for current session |
| `session_qa_location` | String | QA location for current session |
| `session_date` | String | Date of current session (`dd-MM-yyyy`) |
| `source_uri` | String | URI of the loaded source CSV |
| `output_folder_uri` | String | URI of the custom output folder |
| `scanner_name` | String | Scanner name prefix for output filename |
| `log_font_size` | Float | Font size for LOG tab (12 / 15 / 17) |
| `snackbar_duration` | Int | Snackbar display duration in milliseconds |
| `last_bt_device_address` | String | MAC address of last connected BT device |
| `bt_was_connected_on_pause` | Boolean | Whether BT was connected when app was paused |
