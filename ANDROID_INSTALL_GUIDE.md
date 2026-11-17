# QB Player - Android & Oppo Pad Installation Guide 📱📲

QB Player by Naqeeb is fully optimized for **Android Smartphones** and **Android Tablets** (including the **Oppo Pad**, Oppo Pad Air, and Oppo Pad 2).

---

## 🌟 Why QB Player on Oppo Pad & Mobile?
- **Zero Ads**: Unpause movies without seeing full-screen commercials or unskippable video ads.
- **Tablet Large Screen UI**: Enhanced seekbars, large touch controls, and high-visibility OSD overlays.
- **ColorOS Multi-Window & Split Screen**: Run QB Player alongside notes, browser, or messaging apps on your Oppo Pad.
- **Hardware Acceleration**: Smooth 4K, 1080p, 60fps playback with low battery consumption.
- **USB OTG & SD Card Support**: Plug a USB-C flash drive or external SSD into your phone or Oppo Pad and play movies directly without transferring them.
- **Picture-in-Picture (PiP)**: Swipe up to go Home and the video automatically continues playing in a floating mini-window.

---

## 🚀 How to Install on Mobile & Oppo Pad

### Method 1: Automated Cloud Build via GitHub (Easiest & Recommended) ☁️

You don't need Android Studio or SDK installed on your PC. GitHub Actions will build the `.apk` for you in the cloud!

1. On your PC, double-click **`Push-To-GitHub.bat`** (or push using git).
2. GitHub Actions will automatically start building `QB-Player-v1.0-Naqeeb.apk`.
3. Open your GitHub repository on your **Mobile** or **Oppo Pad** browser:
   `https://github.com/<your-username>/QB-player-by-Naqeeb/releases`
4. Tap **`QB-Player-v1.0-Naqeeb.apk`** to download it.
5. Tap the downloaded APK to install.
   *(If prompted by Android/ColorOS, allow "Install from Unknown Sources").*
6. Done! QB Player will be installed in your app drawer with its native logo.

---

### Method 2: Instant 10-Second Install via Browser (PWA) ⚡

Because QB Player is built with Progressive Web App (PWA) support, you can install it instantly without even compiling an APK:

1. Host QB Player (e.g. on GitHub Pages, Netlify, Vercel, or run `python server.py` on your PC connected to the same Wi-Fi).
2. On your **Mobile** or **Oppo Pad**, open the player URL in **Google Chrome**, **Brave**, or the **Oppo Browser**.
3. Tap the **three dots menu (⋮)** in the top right corner.
4. Tap **"Install app"** or **"Add to Home Screen"**.
5. Android will generate a native standalone app icon on your home screen and app drawer. It opens in 100% immersive fullscreen with no browser address bars!

---

### Method 3: Local Build via Android Studio 💻

If you have Android Studio installed on your computer:

1. Launch **Android Studio**.
2. Click **Open** and select the folder:
   `c:\Users\levi\.gemini\antigravity\scratch\QB Player\android`
3. Wait for Gradle sync to complete.
4. Click **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)**.
5. Once compiled, click **"locate"** in the pop-up notification.
6. Transfer the generated `app-debug.apk` to your Oppo Pad or phone via USB cable or Quick Share / LocalSend.
7. Tap the file on your device to install.

---

## 👆 Touch Gestures on Mobile & Oppo Pad

| Gesture | Action |
| :--- | :--- |
| **Swipe Up / Down on Left Side** | Brightness (0% to 100%) with visual sun OSD |
| **Swipe Up / Down on Right Side** | Volume + **200% Audio Booster** with speaker OSD |
| **Swipe Left / Right Anywhere** | Quick seek with `+00:15` or `-00:10` time difference |
| **Double Tap Left** | Jump backward 10s (`<< 10s`) |
| **Double Tap Right** | Jump forward 10s (`10s >>`) |
| **Double Tap Center** | Play / Pause |
| **Lock Icon (Top Bar)** | Freezes screen touches (Kid Lock) so movie is not interrupted |
| **Aspect Icon** | Cycles Fit, Fill/Crop, Stretch, 16:9, and 4:3 |
| **Subtitle Icon** | Adjust subtitle timing offset (`+0.1s` / `-0.1s`) and font size |
| **Audio Icon** | Switch dual audio tracks or load external audio dubs |
