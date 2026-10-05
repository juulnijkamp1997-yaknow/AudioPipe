# AudioPipe

A powerful Android audio routing utility that **captures audio from individual apps** and routes it to **specific output devices** (like Bluetooth headphones or the speaker), effectively bypassing the system's default audio routing.

> **Use Case:** Listen to Spotify through your AirPods while Android Auto plays navigation through the car speakers.

---

## Root mode (this fork)

On a rooted phone (Magisk, KernelSU, APatch) AudioPipe runs its `appops` and `input` commands through `su`. Wireless debugging, an ADB key and Wi-Fi are then not needed. The app asks for root on launch; the status line reads "Connected via root" once it is granted. Without root it falls back to the original wireless-debugging route below.

### Bluetooth hub (the app's main screen)

Stock Android sends every sound to a connected Bluetooth speaker. The hub turns that around, like a cast button for Bluetooth: tick the apps that may play on the speaker and switch the hub on. Those apps play on the Bluetooth speaker; everything else (TalkBack, notifications, ringtones, alarms, other apps) stays on the phone, and apps no longer pause each other. Similar to Samsung's "Separate app sound".

Nothing is captured or replayed. A small Java process runs as root through `app_process` (`root/HubDaemon.java`) and:

- registers a dynamic audio policy with a `RENDER` mix that matches the chosen apps' uids and targets the connected Bluetooth A2DP device, so Android's audio policy manager routes those apps itself; the policy belongs to the process and disappears with it;
- makes the phone (speaker, or wired or USB headphones) the preferred device for the other routing strategies with `setPreferredDevicesForStrategy`; strategies that carry calls are left alone;
- turns on multi audio focus and turns compressed offload playback off while it runs (offloaded tracks never match a mix).

It needs Developer options, "Disable Bluetooth A2DP hardware offload", and a restart. With hardware offload, Bluetooth is played through the phone's main output, and a mix on that output would pull the phone's own sounds to the speaker too; the hub checks the audio policy dump after every registration and stops routing if that happens. The app talks to the root process through files in its own files directory (`hub.conf`, `hub.stop`, `hub.status`), so only starting it needs root. The older capture-based screen and the "Speaker mode" switch are no longer opened from the launcher.

### Builds

Every push to `main` builds a debug APK with GitHub Actions and publishes it under Releases. The signing key is created on the first run and kept in the Actions cache, never in the repository, so new builds install as updates. A cache entry unused for seven days is removed; the build after that gets a new key, and that one update needs an uninstall first.

This fork also improves TalkBack support: labelled pickers and sliders, section headings, spoken status changes, and a balance slider that reads left, center or right.

Note: Android 11 or higher is required (`minSdk 30`).

---

## ✨ Features

- **Per-App Audio Capture** – Select any installed app and capture its audio output.
- **Custom Output Routing** – Route captured audio to Bluetooth, wired headphones, USB headsets, or the built-in speaker.
- **Live Volume & Balance Control** – Adjust volume and stereo balance on the fly.
- **Latency Monitoring** – Real-time latency display in the notification.
- **Wireless ADB Integration** – Uses self-to-self ADB over wireless debugging to control `appops` permissions dynamically.

---

## 🛠 Requirements

| Requirement | Details |
|-------------|---------|
| **Android Version** | 10 (Q) or higher |
| **Wireless Debugging** | Must be enabled in Developer Options |
| **ADB Key** | Import an existing `adbkey` or pair via Shizuku first |

---

## 📦 Installation

1. **Build from source:**
   ```bash
   ./gradlew assembleDebug
   ```
2. Install the APK on your device:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. Enable **Wireless Debugging** in Developer Options.
4. Import your ADB private key (usually `~/.android/adbkey`) or pair via Shizuku.

---

## 🚀 Usage

1. Launch **AudioPipe**.
2. If not connected, tap **Setup** and import your ADB key.
3. Select the **app** you want to capture (e.g., Spotify).
4. Select the **output device** (e.g., Bluetooth headphones).
5. Adjust **volume** and **balance** as desired.
6. Tap **Start** to begin audio routing.

The app will:
- Deny the selected app's `PLAY_AUDIO` permission (so it doesn't play to the system).
- Capture its audio via `MediaProjection`.
- Play the captured audio to your chosen output device.

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        MainActivity                         │
│  - App selection, output selection, volume/balance controls │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     SoundMasterService                      │
│  - Foreground service with MediaProjection                  │
│  - Manages PlayBackThread instances per app                 │
└─────────────────────────────┬───────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│  PlayBackThread │ │  PlayBackThread │ │  PlayBackThread │
│  (per app)      │ │  (per app)      │ │  (per app)      │
│  - AudioRecord  │ │  - AudioRecord  │ │  - AudioRecord  │
│  - AudioPlayer  │ │  - AudioPlayer  │ │  - AudioPlayer  │
└─────────────────┘ └─────────────────┘ └─────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│                       ShellExecutor                         │
│  - ADB connection via AdbClient                             │
│  - Runs shell commands (appops set, etc.)                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 📁 Project Structure

```
app/src/main/java/com/asdfg/soundmaster/
├── MainActivity.kt           # Main UI and controls
├── adb/
│   ├── AdbClient.kt          # ADB protocol implementation
│   ├── AdbKey.kt             # RSA key management
│   ├── AdbMdns.kt            # mDNS discovery for wireless debugging
│   ├── AdbMessage.kt         # ADB message format
│   ├── AdbProtocol.kt        # ADB protocol constants
│   └── ShellExecutor.kt      # High-level shell command execution
└── audio/
    ├── AudioPlayer.kt        # AudioTrack wrapper with volume/balance
    ├── PlayBackThread.kt     # Audio capture and playback thread
    └── SoundMasterService.kt # Foreground service orchestrating capture
```

---

## ⚠️ Known Limitations

- **MediaProjection Required** – A screen capture permission dialog appears on each start.
- **No Pairing** – This app cannot pair with wireless debugging directly; use Shizuku or import an existing key.
- **Media Buttons** – Hardware media button forwarding is not currently functional.

---

## 📄 License

This project is provided as-is for educational purposes.
