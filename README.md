<div align="center">

![LeviLauncher Modder Logo](https://avatars.githubusercontent.com/u/78095377?s=200&v=4)

# LeviLauncher Modder

![Banner](https://camo.githubusercontent.com/bd7bd77cb422a267057d9863095b239b096d4f46dc831a37b45867a9acfad697/68747470733a2f2f63617073756c652d72656e6465722e76657263656c2e6170702f6170693f747970653d576176696e6726636f6c6f723d74696d654772616469656e74266865696768743d33303026616e696d6174696f6e3d66616465496e2673656374696f6e3d68656164657226746578743d4c6576694d4326666f6e7453697a653d313230)

**A lightweight Android launcher for Minecraft: Bedrock Edition**

[![GitHub Release](https://img.shields.io/github/v/release/sang765/LeviDroid-Modder?style=flat-square&color=blue)](https://github.com/sang765/LeviDroid-Modder/releases)
[![License: Apache 2.0](https://img.shields.io/github/license/sang765/LeviDroid-Modder)](https://github.com/sang765/LeviDroid-Modder/blob/main/LICENSE)
[![Issues](https://img.shields.io/github/issues/sang765/LeviDroid-Modder?style=flat-square&color=red)](https://github.com/sang765/LeviDroid-Modder/issues)
[![Stars](https://img.shields.io/github/stars/sang765/LeviDroid-Modder?style=flat-square&color=yellow)](https://github.com/sang765/LeviDroid-Modder)
[![Downloads](https://img.shields.io/github/downloads/sang765/LeviDroid-Modder/total.svg)](https://github.com/sang765/LeviDroid-Modder/releases)
[![Android](https://img.shields.io/badge/Android-9.0%2B-green?style=flat-square&logo=android)](https://www.android.com/)

</div>

---

## Introduction

LeviLauncher Modder is a lightweight, open-source Android launcher specifically designed for legitimate players of Minecraft: Bedrock Edition (MCBE). It provides a flexible and user-friendly alternative to the standard Google Play installation, allowing you to manage multiple game versions and extend functionality with external modules.

LeviLauncher Modder is an independent fork of the original [LeviLauncher](https://github.com/LiteLDev/LeviLaunchroid) by the [LeviMC team](https://github.com/LiteLDev), maintained by [sang765](https://github.com/sang765) in this repository.

LeviLauncher Modder enables you to import your official Minecraft APK and run it directly without requiring system installation. The launcher supports loading external native modules to enhance gameplay, provides robust multi-version management with complete isolation between installations, and includes built-in tools for managing resource packs and worlds. Whether you're looking to organize different game versions, test modifications, or optimize your gaming experience, LeviLauncher Modder offers the flexibility you need.

### Key Features From Original App

- **APK Import & Installation-Free Launching** – Import your official Minecraft APK and run it directly without system installation
- **SO Module Loading** – Load external native SO modules to extend or enhance Minecraft features and performance
- **Multi-Version Management & Version Isolation** – Manage multiple Minecraft versions independently, with separated configurations and data
- **Multiple Xbox Account Management** – Manage multiple Xbox accounts inside the launcher and switch between them seamlessly
- **Resource Pack & World Management** – Import, export, and back up your resource packs and worlds with the built-in manager

---

## Key Features From LeviLauncher Modder

LeviLauncher Modder keeps the original feature set and adds changes made for this community. Compared to [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid):

- **No Google Play Store requirement** – The original blocks launching unless Minecraft was installed from Google Play. This fork only requires Minecraft to be installed from *any* source, and imported instances (see APK import above) can launch even when Minecraft is uninstalled entirely, because the launcher runs the game from its own APK copy.
- **Patched Minecraft (MCPatch) support** – Patched builds ship injected native libraries that normally only resolve inside the installed patched app. The launcher loads those libraries itself, so a patched APK can be imported and launched directly, with no patched app installed on the device.
- **Copyable crash logs** – The game crash dialog includes a button that copies the full log to the clipboard, making it easy to report issues.
- **CurseForge made optional** – CurseForge browsing is hidden automatically in builds compiled without a CurseForge API key, instead of being always present and failing on requests.
- **Wallpaper accent color** – The theme color settings include a "Use wallpaper color" option that picks the accent color from your wallpaper. On Android 11 and below, this asks for Storage access when the wallpaper cannot be read.
- **Rebranded UI and credits** – The app is named "LeviLauncher Modder", with a credit card for [sang765](https://github.com/sang765) on the About screen.

---

## System Requirements

Before installing LeviLauncher Modder, ensure your device meets the following minimum specifications:

- **Operating System:** Android 9.0 (API 28) or higher
- **Device Architecture:** ARM64 (v8a)
- **RAM:** Minimum 1 GB available RAM (2 GB or more recommended)
- **Storage:** At least 2 GB of available storage for Minecraft and game data
- **License Requirement:** You must own a licensed copy of Minecraft Bedrock Edition purchased from Google Play

> **Note:** For optimal performance and stability, we recommend Android 9.0 or higher with at least 3 GB of available RAM and 5 GB of free storage.

---

## Installation

### Prerequisites

Before proceeding with LeviLauncher Modder installation, ensure that you have your own licensed copy of Minecraft Bedrock Edition — either already installed on the device (from any source) or available as an `.apk`/`.apks` file to import into the launcher.

### Installation Steps

1. Visit the [Releases Page](https://github.com/sang765/LeviDroid-Modder/releases) and download the latest release. Each release ships three APKs: `-arm64-v8a` for most modern devices, `-armeabi-v7a` for older 32-bit devices, and `-universal` which bundles both if you are unsure
2. Open your device Settings and navigate to Security or Applications
3. Enable "Unknown Sources" or "Allow installation from unknown sources" to permit APK installation
4. Locate the downloaded APK file using your file manager and tap to install
5. Grant the necessary permissions when prompted during installation
6. Once installed, open LeviLauncher Modder from your application drawer

> **Important:** LeviLauncher Modder requires a legitimate, licensed copy of Minecraft Bedrock Edition. Do not use this launcher with pirated or unauthorized versions of the game. Ensure your Minecraft license is valid and properly linked to your Microsoft account.

---

## Development Setup

If you want to build LeviLauncher Modder from source or contribute to development, follow these steps to set up your development environment:

### Prerequisites

- Git installed on your system
- Android Studio (latest version recommended)
- Java Development Kit (JDK) 21 or higher
- Android SDK with API level 28 or higher

### Setup Instructions

1. Clone the LeviLauncher Modder repository:

   ```bash
   git clone https://github.com/sang765/LeviDroid-Modder.git
   ```

2. Open the project directory in Android Studio

3. Allow Android Studio to download and install required dependencies and build tools

4. Wait for Gradle to complete the initial sync process

5. Connect your Android device or start an emulator (API 28+)

6. Click the "Run" button in Android Studio to build and deploy to your device

7. The app will launch automatically on successful build completion

> **Build Tip:** For faster builds during development, use `Build > Make Project` to compile incrementally instead of full rebuilds.

---

## Contribution Guidelines

We welcome contributions from the community to improve LeviLauncher Modder. To ensure a high-quality codebase and smooth collaboration, please adhere to the following guidelines:

### Code Quality

Write clean, modular code with descriptive variable names and consistent formatting. Follow Kotlin and Java style guidelines established in the project. Ensure your code is readable and well-structured for future maintainers.

### Commit Structure

Use small, focused commits with clear and descriptive messages. Each commit should address a single feature or bug fix. Example: "Fixed memory leak in version manager" or "Added support for ARM32 architecture".

### Documentation

Add comments for complex logic and update relevant documentation in the repository. If you add new features, update the README and any related documentation files.

### Performance

Optimize all additions to maintain low latency and smooth performance. Test your changes thoroughly to ensure they don't introduce lag or performance regressions.

### Testing

Test all changes on multiple devices and Android versions to ensure compatibility and stability.

### Pull Requests

Submit PRs with a detailed description of changes, including the problem solved or feature added. Reference any related issues and provide screenshots or videos if your changes affect the UI.

### Community Standards

Follow our Code of Conduct to maintain a respectful and inclusive environment. Be constructive in feedback, respect others' work, and communicate professionally with all contributors.

**Before Submitting:** Run a full build cycle and test on at least one device to minimize errors. We review all contributions promptly and appreciate your efforts to enhance LeviLauncher Modder.

---

## Usage Guidelines

LeviLauncher Modder is designed for legitimate players of Minecraft Bedrock Edition. Please respect the following guidelines and terms of use:

### Permitted Uses

- Modify LeviLauncher Modder for personal gameplay and to test new features
- Create educational content (videos, tutorials, blog posts) showcasing LeviLauncher Modder's capabilities
- Fork the repository for learning purposes or to create derivative projects, provided you comply with the Apache License 2.0
- Share your modified versions with others as long as you comply with the Apache License 2.0 terms

### Prohibited Uses

- Do not claim LeviLauncher Modder as your own without crediting the original LeviLauncher project and its authors
- Do not use LeviLauncher Modder to violate Mojang or Microsoft's user agreements

> **Disclaimer:** The authors and contributors of LeviLauncher Modder are not responsible for bans, damages, or issues arising from the use of this software. Use it at your own risk and in accordance with Minecraft's terms of service.

For full legal details, see the LICENSE and NOTICE files in the repository.

---

## Credits & Acknowledgements

LeviLauncher Modder would not be possible without the contributions and support of many talented individuals and organizations:

### Special Thanks To

- **[sang765](https://github.com/sang765)** – Maintainer of this LeviLauncher Modder fork
- **[LeviMC Organization](https://github.com/LiteLDev)** – For creating and maintaining the original [LeviLauncher](https://github.com/LiteLDev/LeviLaunchroid) project this fork is based on
- **Android Community** – For excellent documentation, libraries, and tools that made this launcher possible
- **Open Source Community** – For all the libraries, frameworks, and tools that power this project
- **Contributors** – A heartfelt thank you to all [contributors of the original project](https://github.com/LiteLDev/LeviLaunchroid/graphs/contributors) who have continuously improved and maintained LeviLauncher through their time and expertise

---

## Contact & Support

**Maintainer:** [sang765](https://github.com/sang765)

**Project Repository:** [https://github.com/sang765/LeviDroid-Modder](https://github.com/sang765/LeviDroid-Modder)

**Original Repository:** [https://github.com/LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid)

**Report Issues:** [GitHub Issues Page](https://github.com/sang765/LeviDroid-Modder/issues)

**For support and questions:** Please create an issue on this GitHub repository

---

<div align="center">

[![GitHub Release](https://img.shields.io/github/v/release/sang765/LeviDroid-Modder?style=flat-square&color=blue)](https://github.com/sang765/LeviDroid-Modder/releases)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](https://www.apache.org/licenses/LICENSE-2.0)
[![Issues](https://img.shields.io/github/issues/sang765/LeviDroid-Modder?style=flat-square&color=red)](https://github.com/sang765/LeviDroid-Modder/issues)
[![Stars](https://img.shields.io/github/stars/sang765/LeviDroid-Modder?style=flat-square&color=yellow)](https://github.com/sang765/LeviDroid-Modder)
[![Downloads](https://img.shields.io/github/downloads/sang765/LeviDroid-Modder/total.svg)](https://github.com/sang765/LeviDroid-Modder/releases)
[![Android](https://img.shields.io/badge/Android-9.0%2B-green?style=flat-square&logo=android)](https://www.android.com/)

**Made with ❤️ by [sang765](https://github.com/sang765)**

</div>
