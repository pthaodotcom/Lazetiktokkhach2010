# Laze

**Laze** is a production-ready, ultra-lightweight Android utility app designed to automate the viewing experience on short-video platforms. By monitoring playback progress, Laze automatically swipes to the next video when the current one ends, allowing for a seamless, hands-free experience.

## Core Functionality

### 1. Slider-Sync Logic
-   **Smart Detection**: Uses an Accessibility Service to monitor the screen for progress bars on supported platforms:
    -   **YouTube Shorts**
    -   **TikTok**
    -   **Instagram Reels**
-   **Auto-Swipe**: Triggers a vertical swipe gesture exactly when the progress bar reaches 100% (`rangeInfo.current == max`).

### 2. Point-to-Map Overlay
-   **Manual Anchoring**: Includes a floating overlay bubble (managed via `WindowManager`) that allows users to manually "anchor" the location of the progress bar if automatic detection needs a hint.
-   **Persistence**: Saves $(x, y)$ coordinates in `SharedPreferences` for future sessions.

### 3. Human Emulation
-   **Anti-Detection**: Randomized swipe gestures to mimic natural human behavior and bypass bot detection algorithms.
    -   **Duration**: Randomized between **250ms** and **450ms**.
    -   **Coordinates**: Start and end points vary by **$\pm 15\%$** on each swipe.

## Technical Constraints & Architecture

-   **Ultra-Lightweight**: strict target APK size of **< 1.5MB**.
-   **Native Stack**: Built entirely with **Kotlin** and **XML Layouts**. No heavy frameworks like Jetpack Compose or Flutter.
-   **Optimization**: Aggressive **R8/ProGuard** rules enabled (`minifyEnabled true`, `shrinkResources true`) to strip unused code and resources.
-   **Vector Graphics**: All UI elements use vector XML to minimize tangible asset size.

## Setup & Usage

1.  **Install**: Install the APK on your Android device.
2.  **Permissions**:
    -   Open Laze.
    -   Tap **Enable Overlay** to grant `SYSTEM_ALERT_WINDOW` permission (for the floating bubble).
    -   Tap **Enable Accessibility Service** to grant `BIND_ACCESSIBILITY_SERVICE` permission (for screen monitoring and auto-swiping).
3.  **Laze**: Open your favorite short-video app and let Laze handle the scrolling.

## Disclaimer
This application is for educational and personal utility purposes only. Use responsibly and in accordance with the terms of service of the target applications.
