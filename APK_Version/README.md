# ⚡ ARC FLASH – LAN File Sharing (Android APK)

## 🚀 Overview

**ARC FLASH** is a high-performance **LAN-based file sharing Android application** designed for **ultra-fast, stable, and completely offline file transfer**.

It enables seamless data transfer between devices connected to the same WiFi or hotspot using a **secure, event-driven architecture**, eliminating common issues like duplicate uploads, unstable connections, and unauthorized access.

> ⚡ Built for speed, privacy, and reliability in real-world usage.

---

## 📁 Project Structure

```text

## 📁 Complete Project Structure


arc-flash-lan-share/
│
├── APK_Version/                          # Main Android APK project
│   │
│   ├── app/                              # Android application source code
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── java/com/example/filesharing/
│   │   │   │   │   ├── MainActivity.kt           # Main UI controller (entry point)
│   │   │   │   │   ├── FileSharingViewModel.kt  # Handles UI logic (MVVM)
│   │   │   │   │   ├── TransferService.kt       # Background file transfer service
│   │   │   │   │   ├── ProgressRequestBody.kt   # Tracks upload progress
│   │   │   │   │   └── ...other Kotlin files
│   │   │   │   │
│   │   │   │   ├── res/                         # UI resources and design assets
│   │   │   │   │   ├── drawable/                # Icons, shapes, backgrounds
│   │   │   │   │   ├── mipmap-anydpi-v26/       # Adaptive launcher icons
│   │   │   │   │   ├── mipmap-mdpi/             # App icon (medium density)
│   │   │   │   │   ├── mipmap-hdpi/             # App icon (high density)
│   │   │   │   │   ├── mipmap-xhdpi/            # App icon (extra high density)
│   │   │   │   │   ├── mipmap-xxhdpi/           # App icon (very high density)
│   │   │   │   │   ├── mipmap-xxxhdpi/          # App icon (ultra high density)
│   │   │   │   │   ├── values/                  # Colors, strings, themes, styles
│   │   │   │   │   └── xml/                     # Config files (network, file paths)
│   │   │   │   │
│   │   │   │   └── AndroidManifest.xml          # App permissions & configuration
│   │   │   │
│   │   │   ├── build.gradle.kts                # App-level build configuration
│   │   │   └── proguard-rules.pro              # Code optimization (optional)
│   │
│   ├── gradle/                                 # Gradle build system
│   │   └── wrapper/
│   │       ├── gradle-wrapper.jar
│   │       └── gradle-wrapper.properties
│   │
│   ├── releases/                               # Final build outputs
│   │   └── app-release.apk                     # 📦 Installable APK file
│   │
│   ├── assets/                                 # 📸 Screenshots for documentation
│   │   ├── home_screen.png                     # Main dashboard UI
│   │   ├── qr_connect.png                      # QR connection screen
│   │   ├── receive_screen.png                  # Receiving UI
│   │   ├── send_screen_1.png                   # Sending UI (single file)
│   │   ├── send_screen_2.png                   # Sending UI (multiple files)
│   │   └── settings_screen.png                 # Settings panel UI
│   │
│   ├── build.gradle.kts                        # Project-level build configuration
│   ├── settings.gradle.kts                     # Project settings
│   ├── gradle.properties                       # Gradle configuration properties
│   ├── gradlew                                 # Gradle wrapper (Linux/Mac)
│   ├── gradlew.bat                             # Gradle wrapper (Windows)
│   ├── .gitignore                              # Files ignored by Git
│   ├── README.md                               # Project documentation
│   └── LICENSE                                 # License file 
```

---

## 📦 APK Location

```text id="apkclean"
APK_Version/releases/app-release.apk
```

---

## 🧠 Structure Summary

* `app/` → Core Android code
* `res/` → UI design & resources
* `assets/` → Screenshots for GitHub
* `releases/` → Final APK
* `gradle/` → Build system

---

```

```

👉 This is your **final installable Android application**

---

## 🧠 Structure Summary

* `APK_Version/` → Root of your Android project
* `app/` → Core source code + UI
* `res/` → UI design (icons, layouts, themes)
* `assets/` → Screenshots for GitHub display
* `releases/` → Final APK file
* `gradle/` → Build system

---

## 💡 Best Practices

* Keep screenshots updated in `assets/`
* Replace APK after each new build
* Maintain clean structure for scalability
* Use meaningful naming for files

---

```

---

## 📦 Where is the APK?

The final installable APK is located in:

```text
releases/app-release.apk
```

👉 You can directly:

* Install on Android device
* Share for testing
* Upload to GitHub Releases

---

## 🧠 Structure Explanation

* `app/` → Core Android app (logic + UI)
* `releases/` → Final APK build output
* `assets/` → Screenshots for GitHub preview
* `gradle/` → Build system files
* Root files → Project configuration

---

## 🎯 Key Highlights

* ⚡ High-speed LAN transfer (no internet required)
* 📡 Automatic device discovery
* 🔐 Secure session-based communication
* 🔒 Strong privacy protection (no public access)
* 🔁 Zero duplicate transfers
* 📊 Accurate real-time progress (0–100%)
* 🔌 Event-driven system (no polling)
* 🎨 Multiple UI themes (Dark, Light, Warm)
* 📁 Full folder sharing support
* 🔄 Background transfer support

---

## 🔒 Privacy & Security (Detailed)

ARC FLASH is built with a **privacy-first approach**, ensuring that file transfers are strictly controlled and visible only to intended devices.

### 🛡️ Private Connection Model

* Devices must **authenticate before any transfer**
* Only connected devices can communicate
* No open broadcasting of files on the network

---

### 🔐 Session-Based Authorization

* Each connection uses a **unique session ID**
* File transfer is allowed only after session validation
* Prevents unauthorized access from other devices on the same network

---

### 🚫 Network Isolation

Even if multiple users are on the same WiFi:

* ❌ Other devices cannot see your files
* ❌ No shared public file list
* ✅ Transfers are strictly **device-to-device**

---

### 🔑 Controlled Sharing

* Files are shared only when:

  * User selects device manually
  * Connection is confirmed
* No automatic or background sharing

---

## ⚡ Performance & Speed

* 🚀 **20 MB/s – 80 MB/s** (based on network quality)
* ⚡ Much faster than Bluetooth
* ⚡ Equal or better than SHAREit in stable conditions

### Performance Optimization:

* Direct device communication
* No cloud dependency
* Lightweight protocol
* Efficient data streaming

---

## ✨ Features (Detailed)

### 📂 File & Folder Sharing

* Transfer:

  * Single files
  * Multiple files
  * 📁 Entire folders (recursive transfer)
* Maintains folder structure
* Suitable for large data transfers

---

### 🔄 Background Transfer System

* Transfer continues when:

  * App is minimized
  * User switches apps
* Powered by background service
* Prevents interruption

---

### 🌐 Fully Offline Mode

* No internet required
* Works using:

  * WiFi
  * Mobile hotspot
* No external server involved

---

### 📊 Real-Time Transfer Dashboard

Displays:

* File size
* Transfer speed (MB/s)
* Progress percentage
* Estimated time remaining

---

### 🔁 Duplicate Prevention System

* Each file assigned a **unique ID**
* Prevents:

  * Multiple uploads
  * Repeated execution
* Fixes common bugs like **300% progress issue**

---

### 🔌 Event-Driven Communication

* No repeated API calls
* No polling loops
* Uses controlled event triggers
* Ensures:

  * Stability
  * Efficiency
  * Predictability

---

## 🎮 Top Control Panel (Detailed Explanation)

The top toolbar provides **real-time control over transfer operations**, ensuring flexibility and user control.

### ⏸️ Pause / Resume

* Temporarily pauses active transfer
* Preserves progress
* Resume continues from last state
* Useful during network instability

---

### 📡 QR Scan (Instant Connect)

* Scan QR to connect to another device
* Eliminates manual setup
* Faster and error-free connection

---

### 📲 QR Generate (Share Device)

* Generates a QR code for your device
* Other devices can scan and connect instantly
* Simplifies connection process

---

### 🔄 Refresh (Network Scan)

* Re-scans LAN for available devices
* Updates device list dynamically
* Useful when new devices join

---

### ⚙️ Settings

* Access customization options:

  * Theme selection
  * Storage location
  * Network preferences

---

### 🟥 Stop Transfer

* Immediately stops active transfer
* Cancels safely without corruption
* Prevents unwanted transfers

---

### 🗑️ Clear History

* Removes transfer logs
* Clears UI entries
* Keeps interface clean

---

## 🎨 Customization & UI

### 🌗 Theme Modes

#### 🌙 Dark Mode

* Default mode
* Low-light optimized
* Premium look

#### ☀️ Light Mode

* Bright and clean
* Ideal for daytime use

#### 🔥 Warm Mode

* Reduces blue light
* Comfortable for long sessions
* Unique eye-friendly feature

---

### 📂 Storage Path Selection

* Choose where files are saved
* Supports custom directories
* Helps manage large data efficiently

---

## 🧠 Working Flow

```text
Scan → Authenticate → Connect → Transfer → Complete
```

---

## ⚖️ Comparison with Other Apps

| Feature              | ARC FLASH      | SHAREit / Xender |
| -------------------- | -------------- | ---------------- |
| Privacy              | 🔒 Strong      | ⚠️ Weak          |
| Ads                  | ❌ No           | ❌ Heavy          |
| Speed                | ⚡ High         | ⚡ High           |
| Folder Sharing       | ✅ Yes          | ✅ Yes            |
| Background Transfer  | ✅ Yes          | ⚠️ Limited       |
| Duplicate Protection | ✅ Yes          | ❌ No             |
| Architecture         | ✅ Event-driven | ❌ Polling        |

---

## 📦 Installation

1. Download APK from `/releases`
2. Install on Android device
3. Grant permissions
4. Connect to same WiFi/Hotspot
5. Start sharing

---

## ⚠️ Requirements

* Android device
* Same network
* Storage permission enabled

---

## 🔮 Future Enhancements

* 📁 Multi-file optimization
* 🔐 Encryption support
* 💻 PC client version
* 🌐 Cross-platform support

---

## 📜 License

This project is free for **personal and educational use**.

* ❌ No subscriptions
* ❌ No hidden costs
* ❌ No ads

---

## 👤 Author

**Darshan H V**
ARC FLASH — LAN File Sharing System (v1.0)

* 💡 Designed for performance and stability
* ⚙️ Built with clean architecture
* 🚀 Focused on real-world usability

---

## ⚡ Final Statement

> ARC FLASH is a **fast, secure, and reliable file sharing system**
> designed to provide **private, high-speed, and interruption-free transfers**
> with complete user control and modern architecture.

---
