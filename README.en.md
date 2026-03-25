# TwinAudio - Dual Audio Synchronous Output Module

[简体中文](README.md)

<div align="center">

![Android](https://img.shields.io/badge/Android-13%2B-green.svg)
![LSPosed](https://img.shields.io/badge/LSPosed-Required-blue.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple.svg)

*Break Android wired-exclusive behavior and enable synchronized Bluetooth + wired dual output*

[Features](#features) • [Architecture](#architecture) • [Installation](#installation) • [How It Works](#how-it-works) • [FAQ](#faq)

</div>

---

## Project Overview

**TwinAudio** is an Android 13+ dual-audio output module based on the **LSPosed/Xposed** framework.  
It uses a Java-layer Hook approach to output audio to Bluetooth and wired devices (USB-C/AUX) simultaneously, with millisecond-level physical delay alignment.

### Core Goals

- Remove system limitation: stop Android's default "disconnect Bluetooth when wired is plugged" behavior
- Dual-channel output: Bluetooth + wired output at the same time, no manual switching
- Delay compensation: compensate Bluetooth latency (~150-200 ms) using a silent prefill strategy
- Independent volume control: separate wired volume control without affecting Bluetooth volume

### Typical Use Cases

- Bluetooth speaker + wired sound system for a wider stereo environment
- Bluetooth headset + wired recording monitor for real-time sync
- Car Bluetooth + AUX fallback output for reliability
- Gaming split: Bluetooth headset + wired monitor speaker

---

## Features

### 1. Reverse Routing Architecture

Since wired output has near-zero latency while Bluetooth has intrinsic transmission delay, TwinAudio uses a reverse strategy:

- **Bluetooth as primary path**: force all `USAGE_MEDIA` streams to Bluetooth
- **Wired as secondary path**: capture playback with `AudioRecord`, then forward to wired output

### 2. Four Core Technical Chains

#### A. Physical Anti-Disconnect + State Lock
- Intercept Bluetooth disconnect calls in `AudioDeviceInventory`
- Read actual hardware connection state and block invalid disconnection
- Trigger `setActiveDevice` during engine startup to wake Bluetooth path
- Apply 800 ms debounce to avoid rapid plug/unplug route thrashing

#### B. Privileged Playback Capture + Anti-Feedback
- Hook `MediaProjectionManagerService.isValidMediaProjection` for dynamic capture credential spoofing
- Use `AudioPlaybackCaptureConfiguration.excludeUid()` to avoid self-capture loops

#### C. Stream Dispatch + Route Collision Avoidance
- Use `USAGE_GAME` disguise for relay `AudioTrack` to bypass strict "MEDIA must use Bluetooth" policy
- Force wired endpoint with `setPreferredDevice(usbDevice)`

#### D. Millisecond-Level Physical Sync
- Before writing real PCM data, write a silent buffer to `AudioTrack`
- Achieve deterministic physical delay alignment with effectively zero extra CPU overhead

### 3. Cross-Process Communication

- **App side**: Jetpack Compose UI + `SharedPreferences` persistence
- **System side**: `BroadcastReceiver` receives config updates and applies runtime changes
- **Auto sync**: open app after reboot to push stored config back to system hook

---

## Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                     TwinAudio Architecture                 │
├─────────────────────────────────────────────────────────────┤
│  App UI (Compose)  <---- Broadcast ---->  system_server   │
│       |                                   AudioService Hook│
│  SharedPreferences                          Xposed Layer    │
│       v                                                     │
│  Local Store: delayMs / volumeUsb / hookEnabled            │
│                                                             │
│  Bluetooth (Primary)  <-- USAGE_MEDIA route lock           │
│         │                                                   │
│  AudioRecord (Playback Capture, exclude self UID)          │
│         │                                                   │
│  AudioTrack (USAGE_GAME disguise) --> USB/AUX (Secondary)  │
└─────────────────────────────────────────────────────────────┘
```

---

## Installation

### Requirements

- Android 13 or later
- LSPosed or EdXposed
- Root access (required by LSPosed)

### Steps

1. **Build or download APK**

```bash
git clone https://github.com/yourusername/TwinAudio.git
cd TwinAudio
./gradlew assembleDebug
```

2. **Install and enable module**
- Install APK on your device
- Enable TwinAudio in LSPosed Manager
- Scope: **System Framework**

3. **Reboot device**
- Reboot to activate hooks

4. **Configure in app**
- Open TwinAudio app
- Adjust USB delay and volume
- Enable engine switch

---

## How It Works

1. Hook enters only for package `android` (`system_server`)
2. Intercept Bluetooth "make unavailable" calls if hardware is still connected
3. Lock media strategy to Bluetooth
4. Build playback capture config and exclude own process UID
5. Relay captured PCM into wired `AudioTrack` with `USAGE_GAME`
6. Write silent prefill bytes before real data for latency alignment
7. Apply runtime config via broadcast (`delayMs`, `volumeUsb`, `hookEnabled`)

---

## FAQ

### Q1: Bluetooth still disconnects after plugging USB. Why?
- Confirm module is enabled in LSPosed
- Confirm scope includes **System Framework**
- Reboot after activation
- Ensure engine switch is ON in TwinAudio app

### Q2: Audio is out of sync.
Tune **USB Delay**. Typical ranges:
- SBC: 150-200 ms
- AAC: 200-250 ms
- LDAC: 180-220 ms

### Q3: Wired output is too loud/quiet.
Use in-app **USB Volume** slider.
Bluetooth volume is controlled by phone volume keys.

---

## License

This project is licensed under the **MIT License**.

