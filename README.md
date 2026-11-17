# QB Player by Naqeeb 🎬⚡
> **The Ultimate 100% Ad-Free MX Player Clone with Gestures, Subtitles Sync, Dual Audio, and Smart Resume.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Zero Ads](https://img.shields.io/badge/Ads-0%25%20Zero%20Ads-brightgreen.svg)]()
[![Platform: Web%20%7C%20Windows%20%7C%20Android](https://img.shields.io/badge/Platform-Web%20%7C%20Windows%20%7C%20Android%20PWA-cyan.svg)]()

---

## 🚫 Why QB Player? (The MX Player Ad-Free Solution)
Modern versions of MX Player (now owned by Amazon) are filled with intrusive, unskippable video ads, full-screen interstitial ads, and banner popups whenever you pause and unpause video playback. 

**QB Player by Naqeeb** solves this by delivering the pure, high-performance media player experience you love from MX Player, completely free of ads, tracking, or interruptions.

---

## ✨ Features

### 1. 🛡️ 100% Ad-Free Experience
- **Zero Ads**: No banner ads, no video ads, no interstitial popups.
- **Instant Unpause**: Pausing and unpausing is instantaneous with zero latency or commercial delays.

### 2. 👆 MX Player Signature Gestures (Touch & Mouse)
- **Left Side Vertical Drag**: Screen brightness control (0% to 100%) with On-Screen Display (OSD) slider and dark overlay filter.
- **Right Side Vertical Drag**: Volume control up to **200% HW+ Audio Boost** with dynamic OSD meter.
- **Horizontal Drag**: Real-time seeking with scrub delta indicator (`+00:15` or `-00:10`), target timestamp, and progress preview.
- **Double Tap Left (30%)**: Quick rewind 10 seconds (`<< 10s`) with animated ripple effect.
- **Double Tap Right (30%)**: Quick forward 10 seconds (`10s >>`) with animated ripple effect.
- **Double Tap Center**: Instant Play / Pause toggle with centered pulse animation.
- **Pinch-to-Zoom / Aspect Ratio**: Cycle between **Fit to Screen**, **Crop/Fill**, **Stretch**, **16:9**, **4:3**, and **100% Original**.
- **Kid Lock / Touch Lock**: Screen lock button in the top bar freezes all gestures and controls to prevent accidental taps.

### 3. 💬 Subtitles System & Real-Time Sync
- **Format Support**: Parses `.srt` (SubRip), `.vtt` (WebVTT), and `.ass` / `.ssa` (SubStation Alpha).
- **Subtitle Synchronization**: Real-time delay adjustment (`-10.0s` to `+10.0s` in `0.05s` / `0.1s` increments) so subtitles perfectly match dialogue.
- **Customizable Styling**:
  - Font size adjustment (16px to 48px)
  - Colors: High-contrast yellow, white, cyan, light green
  - Background styling: Text outline with deep drop-shadow, translucent background box, or solid black box
  - Dynamic vertical offset: automatically adapts when control bars are visible.

### 4. 🔊 Dual Audio & 200% HW+ Audio Booster
- **Dual Audio Detection**: Automatically detects multiple embedded audio streams in container files (e.g. English, Hindi, Spanish dubs).
- **External Audio Track Loader**: Allows loading an external audio file (`.mp3`, `.aac`, `.m4a`, `.wav`) to sync and play seamlessly as an alternate dub.
- **HW+ Audio Booster (up to 200%)**: Powered by Web Audio API `GainNode` and a dynamic compressor/limiter to amplify quiet movies up to 2x without audio clipping.
- **5-Band Equalizer**: Built-in presets: **Flat**, **Bass Boost**, **Clear Voice / Dialogue**, **Treble Boost**, and **Cinematic**.

### 5. ⚡ "Play Where Left" (Smart Resume Engine)
- Automatically remembers your exact playback position, file size, duration, volume, and subtitle preference in `localStorage` / IndexedDB.
- Floating resume prompt: *"Resume where you left off at 05:24?"* with **Resume** and **Start Over** options.
- **Recent Watch History**: Visual drawer displaying recently played videos with progress bars.

### 6. 🗂️ Playlist & File Management
- **File & Folder Selection**: Open single videos, multiple files, or entire folders.
- **Smart Drag-and-Drop**: Drag any video, subtitle (`.srt`), or audio track directly onto the player window.
- **URL Streamer**: Stream direct MP4, WebM, and online video streams.
- **Playlist Controls**: Next, Previous, Loop Single, Loop All, and Shuffle.

### 7. 💻 Cross-Platform & Installable (PWA)
- Works instantly on **Windows, macOS, Linux, Android, and iOS**.
- Can be installed directly as a standalone Progressive Web App (PWA) on desktop or mobile.

---

## ⌨️ Keyboard Shortcuts

| Shortcut | Action |
| :--- | :--- |
| `Space` or `K` | Play / Pause |
| `←` / `→` | Seek backward / forward 5s |
| `Shift` + `←` / `→` | Seek backward / forward 15s |
| `↑` / `↓` | Volume Up / Down (5%) |
| `M` | Mute / Unmute |
| `F` | Toggle Fullscreen |
| `S` | Toggle Subtitles On / Off |
| `C` | Cycle Aspect Ratio (Fit, Crop, Stretch, 16:9, etc.) |
| `L` | Lock / Unlock Screen |
| `?` (Shift + `/`) | Open Keyboard Shortcuts Guide |

---

## 🚀 How to Run

### Method 1: Instant Launch (Double-click)
On Windows, double-click **`Start-QB-Player.bat`**. It starts an optimized streaming server with HTTP Byte-Range support and opens your default browser.

### Method 2: Python Command Line
```bash
python server.py
```
Open [http://localhost:8080](http://localhost:8080) in your web browser (Chrome, Edge, Brave, etc.).

### Method 3: Direct File
Simply open `index.html` in any modern web browser.

---

## 📱 Installing on Android / Mobile

1. Open QB Player in Chrome on your Android device (hosted locally or via GitHub Pages / Vercel / Netlify).
2. Tap the three dots (⋮) menu in Chrome.
3. Tap **"Install app"** or **"Add to Home screen"**.
4. QB Player will install as a native-like standalone app on your Android device with full gesture support and offline playback!

---

## 👨‍💻 Author & Repository
- **Creator**: Naqeeb
- **Project Name**: `QB-player-by-Naqeeb`
- **GitHub Repository**: [https://github.com/your-username/QB-player-by-Naqeeb](https://github.com/your-username/QB-player-by-Naqeeb)
