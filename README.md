# AssetScanner — Android App (Kotlin)

A text/barcode-based asset lookup app that matches scanned input against a CSV database,
transmits the string to a Bluetooth SPP slave device, logs found assets in real time,
and captures details for new ones.

---

## Features

| Feature | Implementation |
|---|---|
| Read source CSV | Storage Access Framework + OpenCSV |
| Persist source CSV across app restarts | `SharedPreferences` URI storage |
| "Found" floating notification | Material `Snackbar` overlay |
| Duplicate detection | Checked against output CSV before logging |
| Output matched asset to CSV in real time | `FileWriter(append=true)` — writes on every submission |
| New asset detail screen | Second `Activity` with Location, Make, Model, Serial fields |
| Bluetooth SPP transmission | Classic BT RFCOMM socket, ASCII encoded, `\r` terminated |
| BT fallback for HC-05/HC-06 modules | Hidden `createRfcommSocket` API on channel 1 |
| Bluetooth permission handling | API-split for Android 11 and below vs Android 12+ |

---

## Project Structure

```
AssetScanner/
├── build.gradle                                         ← project-level Gradle
├── settings.gradle
└── app/
    ├── build.gradle                                     ← dependencies (OpenCSV, Material)
    └── src/main/
        ├── AndroidManifest.xml                          ← permissions (storage + Bluetooth)
        ├── java/com/example/assetscanner/
        │   ├── MainActivity.kt                          ← main search screen
        │   ├── AddAssetActivity.kt                      ← new asset detail entry screen
        │   ├── CsvManager.kt                            ← CSV read/write + URI persistence
        │   ├── BluetoothSppManager.kt                   ← BT Classic SPP connection + send
        │   └── models/
        │       └── Asset.kt                             ← data model
        └── res/
            ├── layout/
            │   ├── activity_main.xml
            │   └── activity_add_asset.xml
            ├── menu/
            │   └── main_menu.xml                        ← overflow menu (CSV + BT actions)
            └── values/
                └── strings.xml
```

---

## Setup in Android Studio

1. Create a new **Empty Views Activity** project in Android Studio (Kotlin, min SDK 26)
2. Copy all files from this scaffold into the matching folder locations
3. Sync Gradle — key dependencies in `app/build.gradle`:
   ```gradle
   implementation 'com.opencsv:opencsv:5.9'
   implementation 'com.google.android.material:material:1.11.0'
   ```
4. Run on a physical device (Bluetooth does not work on emulators)

> Android Studio will auto-generate `gradlew`, `local.properties`, and `.idea/` — 
> you don't need to copy those from here.

---

## How to Use the App

### First launch — Load your source CSV
- Tap the **⋮ menu** (top right) → **Load / Change CSV**
- Pick your `.csv` file from the file picker (Downloads, Google Drive, etc.)
- The app reads the **first column** of every row, skipping the header
- Status shows: `✓ Loaded 40000 records`
- The file is remembered — on next launch it reloads automatically, no re-picking needed

### Connect to your Bluetooth SPP device
- Pair the device first via **Android Settings → Bluetooth** (one-time step)
- Tap **⋮ menu → Connect Bluetooth**
- Select your device from the list of paired devices
- Status shows: `🔵 Bluetooth: Connected`
- On Android 12+, the app will request `BLUETOOTH_CONNECT` permission on first use

### Search / scan an asset ID
- Type or paste an asset ID into the search field and tap **Search**
- Works with a **Bluetooth barcode scanner** — scanners emulate keyboard input
  and typically send a newline which triggers the search automatically
- The asset ID string is **immediately transmitted to the SPP slave device** as
  ASCII bytes (`\r` terminated), regardless of whether a match is found

### Match found ✓
- A green Snackbar floats at the bottom: `✓ FOUND — 'ABC123' logged`
- The asset ID is written immediately to `scanned_assets.csv`
- If the same ID is scanned again, an orange warning appears: `⚠ FOUND — already logged`

### Match not found ✗
- The **Add New Asset** screen opens automatically
- Fill in: **Location**, **Make**, **Model**, **Serial Number**
- Tap **Save Asset** — all fields are written to `scanned_assets.csv` immediately
- Tap the back arrow to cancel without saving

---

## File Formats

### Source CSV (your lookup reference — read only)
The app read the columns for asset number, serial number, department and barcode respectively.
A header row is expected and will be skipped.

```
asset_id,description,department
ABC123,Dell Desktop,IT
XYZ789,HP Printer,Finance
NB-042,MacBook Air,Design
```

### Output CSV (scanned_assets.csv)
Saved to: `/sdcard/Android/data/com.example.assetscanner/files/scanned_assets.csv`
Accessible via Android's Files app or USB connection to a PC.

```
asset_id,location,make,model,serial_number
ABC123,,,,
XYZ789,Warehouse B,HP,LaserJet Pro,SN-HP-9876
NEW001,Reception,Apple,MacBook Air,C02XG1JUJG5J
```

- **Found assets** → only `asset_id` is populated (location/make/model/serial left blank)
- **New assets** → all 5 columns populated from the detail entry screen

---

## Bluetooth SPP Notes

- The app connects as **master** to your device as **slave**
- Uses the universal SPP UUID: `00001101-0000-1000-8000-00805F9B34FB`
- Strings are sent as **US-ASCII** bytes with `\r\n` appended
- To change the line terminator (e.g. to `\r` only or none), edit `LINE_TERMINATOR`
  at the top of `BluetoothSppManager.kt`
- If connection fails with the standard method, the app automatically retries using
  a direct RFCOMM channel 1 fallback — this fixes most HC-05 / HC-06 issues
- If Bluetooth send fails mid-session, an orange warning Snackbar appears but the
  app continues scanning — the workflow is never blocked by BT errors

### Bluetooth permission behaviour by Android version

| Android version | Permissions used |
|---|---|
| Android 11 and below (API ≤ 30) | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_COARSE_LOCATION` |
| Android 12+ (API 31+) | `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` |

---