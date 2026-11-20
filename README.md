# Layover

**Layover** is an offline, peer-to-peer (P2P) Android proximity chat application designed for crowded transit hubs like airports. It lets nearby users who opt into visibility discover one another and establish private, 1-on-1 encrypted chats without needing internet access or a central server.

---

## Key Features

- **Opt-In Proximity Discovery:** Scan for nearby users running Layover within short range using Bluetooth Low Energy (BLE), filtered strictly to opt-in app users.
- **1-on-1 Connection Flow:** Send and respond to connection requests (`Request` → `Requested` → `Accepted`) with deterministic client/server tie-breaking to avoid simultaneous connection collisions.
- **End-to-End Encryption (E2EE):** Ephemeral session key pairs for key exchange, message signing/verification, and forward secrecy.
- **Abuse & Spam Protection:** Local per-peer rate limiting, muting, and blocking to keep communication safe in high-density environments.
- **Hybrid Transport Roadmap:** Lightweight BLE discovery and control, designed to pair with high-throughput local Wi-Fi bursts for heavier payloads.
- **On-Demand Profile Avatars:** Lightweight user profiles and compressed thumbnails loaded cleanly over peer links without saturating BLE advertisements.

---

## Architecture & Development Roadmap

### Phase 1: Core BLE 1-on-1 MVP (Current)
- [ ] Dark radar-themed UI (`Splash`, `Scan`, `People Around`, `Profile`, and `1-on-1 Chat` screens)
- [ ] Runtime permission handling (`BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`)
- [ ] BLE service UUID advertising & filtered scanning for opt-in peers
- [ ] Deterministic tie-breaker & 1-on-1 connection handshake (`Request` / `Requested` / `Accepted`)
- [ ] Ephemeral key exchange + end-to-end encrypted short text messaging
- [ ] Local per-peer rate limiting and block/mute controls

### Phase 2: Polish & Hardening
- [ ] Safety number / key fingerprint verification UI
- [ ] On-demand compressed profile thumbnail transfer
- [ ] Background/screen-off stability and crowded-area field testing

### Phase 3: Wi-Fi Burst Transport
- [ ] Optional high-throughput Wi-Fi Direct / local socket upgrade for larger messages and media

---

## Tech Stack

- **Platform:** Android (Kotlin)
- **UI:** Jetpack Compose / AndroidX
- **Connectivity:** Android Bluetooth Low Energy (BLE) & Bluetooth Classic / Wi-Fi P2P APIs
- **Security:** Ephemeral asymmetric key exchange & authenticated symmetric encryption

---

## Getting Started

1. Clone the repository:
   ```bash
   git clone [https://github.com/YOUR_USERNAME/Layover.git](https://github.com/dstroke-afk/Layover.git)
Open the project in Android Studio and allow Gradle to sync.

Run the app on two physical Android devices with Bluetooth enabled (BLE advertising and scanning are not fully supported on standard Android emulators).

Grant the requested Bluetooth/Nearby Devices permissions, tap SCAN, and opt into visibility to discover nearby test devices.

License
This project is currently in active development.
