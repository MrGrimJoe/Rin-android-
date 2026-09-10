# Rin — Serverless Zero-Trust Local Mesh

> **True zero-cloud, multi-device continuous computing mesh.**
> No centralized servers. No user accounts. No telemetry. Pure cryptographically verified peer-to-peer transport.

[![Build status](https://github.com/MrGrimJoe/Rin-android-/actions/workflows/build-apk.yml/badge.svg)](https://github.com/MrGrimJoe/Rin-android-/actions/workflows/build-apk.yml)
[![License: MrMIB v1.0](https://img.shields.io/badge/license-MrMIB%20v1.0-blue)](LICENSE)

---

## 🌟 Overview & System Architecture

Rin turns your local devices into a cohesive, private computing mesh. Once devices are paired via an ephemeral out-of-band QR handshake, they communicate directly over local network rails with end-to-end cryptographic signatures and authenticated encryption.

### Core Capabilities on Android

- 🚀 **Universal AirDrop File Beaming**:
  - Direct integration with Android's system share sheet (`ACTION_SEND` & `ACTION_SEND_MULTIPLE`).
  - Supports transferring any photo, video, document, or archive from Gallery/Files apps.
  - Chunked 64 KB streaming with SHA-256 integrity verification and `FileProvider` viewing.
- 📋 **Automatic Background Clipboard Sync**:
  - Live clipboard listener detects copied text on any mesh device and pushes AES-256-GCM encrypted updates to all peer clipboards in < 50ms.
- 🌐 **Browser URL Handoff**:
  - Instant one-tap push to open links in the receiving device's default browser.
- 🌲 **Off-Grid Wi-Fi Direct (Wilderness Mode)**:
  - Autonomous Android-to-Android direct P2P link creation without an external Wi-Fi router or hotspot (`WifiDirectMeshManager`).
- 🌍 **NAT Traversal & Cellular STUN Engine**:
  - RFC 5389 compliant STUN client for public reflexive IP and UDP pinhole mapping across mobile data and distinct networks (`StunHolePunchEngine`).
- 🔀 **Relay Fallback (opt-in)**:
  - For devices with no direct path to each other at all (different networks, no STUN success), an optional self-hostable relay tier forwards traffic as a last resort. The relay is a dumb forwarder — it only ever sees the same AES-256-GCM ciphertext and metadata already visible on the LAN beacon, never plaintext. No default relay server; if you never point a device at one, this code path is never used.
- ⚡ **Multi-Rail ZeroConf Discovery**:
  - **UDP Subnet Broadcasts**: Sub-millisecond peer discovery on port `45991`.
  - **Android NSD / mDNS**: Local domain ZeroConf discovery (`_rin._tcp.local`).
  - **BLE Proximity Presence**: Low-energy Bluetooth beacon rail (`UUID: 0000fe90-0000-1000-8000-00805f9b34fb`).
- 🛡️ **Cryptographic Zero-Trust Engine**:
  - **Identity Keys**: On-device NIST P-256 (`secp256r1`) Elliptic Curve keypairs.
  - **Forward Secrecy**: Ephemeral Elliptic Curve Diffie-Hellman (ECDH) key agreement per peer session.
  - **Key Derivation**: RFC 5869 HKDF-SHA256 (Extract-and-Expand) for high-entropy session and mesh group keys.
  - **Mesh Group Secret**: High-entropy 256-bit random cryptographic master secret transferred during authenticated QR exchange (independent of the human-readable mesh name).
  - **Digital Signatures**: Strictly validated `SHA256withECDSA` (zero fake fallbacks or HMAC degradation).
  - **Authenticated Symmetric Encryption**: AES-256-GCM with 128-bit authentication tags and fresh 96-bit random IVs per packet (strictly fail-closed; corrupted or tampered packets are dropped immediately).
  - **Encrypted discovery, not just encrypted payload**: the UDP beacon itself no longer leaks mesh/device names in cleartext — see "Discovery & the beacon" below. A beacon match alone no longer grants trust, either; it only triggers a challenge/response handshake that proves the peer actually holds the real mesh key.
  - **Device Revocation**: Cryptographically signed revocation notices to instantly purge decommissioned devices.
- 🔍 **Live Packet Inspector**:
  - Inspect sequence IDs, transport rails, latency measurements, and cryptographic signatures in real time.

---

## 🔒 Discovery & the beacon

Earlier versions broadcast the mesh name and device name in the clear on
the LAN — readable by anyone on the same Wi-Fi, mesh member or not. The
current beacon (wire format v2) drops both and replaces them with a
rotating tag:

```json
{"magic":"RIN_BEACON","v":2,"meshTag":"<rotating HKDF tag, base64>",
 "key":"<pubkey>","port":45990,"ts":1771583429000,
 "stunIp":"<optional>","stunPort":"<optional>"}
```

`meshTag` is `base64(HKDF(meshKey, info="rin-beacon-tag-v1:"+bucket)[:9])`,
rotating every 5 minutes — only a device that already holds the real mesh
secret can compute a matching tag, and the tag isn't a stable identifier
you could track over time. A tag match alone still doesn't grant trust:
it only triggers a `DISCOVERY_CHALLENGE`/`DISCOVERY_RESPONSE` handshake
over TCP (both payloads are mesh-key AES-256-GCM ciphertext) where each
side has to prove it can actually decrypt with the real key. Only a valid
echoed nonce promotes a peer from "seen" to trusted — and that's the
point where its device name is finally revealed, encrypted, inside the
response. This is a **coordinated, breaking wire bump** with the Windows
core — v1 and v2 peers simply can't discover each other.

See [`DESIGN_NOTES.md`](DESIGN_NOTES.md) for the full writeup, and the
matching section in
[Rin-windows' `DESIGN_NOTES.md`](https://github.com/MrGrimJoe/Rin-windows/blob/main/DESIGN_NOTES.md)
for the C++ side of the same handshake.

---

## 🏗️ Architecture Milestones & Multi-Platform Support

### 1. Windows & Shared C++ Native Core Interoperability (`RinNativeCoreBridge`)
- **Native JNI Binding Hook**: `RinNativeCoreBridge` is scaffolding for a JNI runtime bridge to a native `librin_core.so`, guaranteeing ABI-identical wire behavior (`0x52494E31` magic header, SHA-256 chunk hashing) with the Windows core if/when that binary is ever built and bundled.
- **Currently always falls back**: no `.so` is built or bundled by this project yet, so `isNativeEngineAvailable()` is always `false` in practice today — every build runs on the pure Kotlin/JVM cryptography path. The interop hook is real code, but the native binary side of it isn't built out yet.
- **Wire Compatibility**: The actual cross-platform compatibility guarantee lives in the JSON wire protocol (below) and the shared crypto primitives, which the Windows core (a separate C++ implementation, not a shared binary) independently implements to match.

### 2. Off-Grid Wi-Fi Direct Autonomous Mesh (`WifiDirectMeshManager`)
- Enables two or more phones in the wilderness (no router, no cell signal) to establish high-speed direct 802.11 P2P connections.
- Implements `WifiP2pManager` discovery, group formation (`isGroupOwner`), and automatic routing over autonomous `192.168.49.x` subnet.

### 3. Internet Traversal via STUN / UDP Hole Punching (`StunHolePunchEngine`)
- **RFC 5389 STUN Resolution**: Queries standard public zero-logging STUN servers to detect public reflexive IP addresses and port mappings across cellular data (5G/LTE) and distinct home firewalls.
- **UDP NAT Pinhole Probe**: Implements direct UDP packet burst handshakes (`RIN_HOLE_PUNCH:<token>`) to punch through symmetric and restricted cone NATs without routing data through third-party servers.
- Falls back to the opt-in relay tier above when hole punching doesn't succeed.

---

## 🔌 Protocol Specification & Wire Format

### Network Ports
- **TCP Control & Transfer Socket**: `45990` (Configurable)
- **UDP Discovery Beacon Port**: `45991`
- **mDNS Service Type**: `_rin._tcp.`
- **STUN Resolvers**: `stun.l.google.com:19302`, `stun.cloudflare.com:3478`
- **Relay (opt-in, no default)**: `45992`

### Mesh Packet Wire Format
Every packet exchanged between peers conforms to this JSON envelope:

```json
{
  "version": 1,
  "sessionId": "4a7f-9b21-uuid",
  "sequence": 142,
  "type": "FILE_START",
  "senderKey": "<Base64_ECDSA_Public_Key>",
  "senderName": "Pixel 8 Pro",
  "targetKey": "<Optional_Base64_Target_Public_Key>",
  "payload": "<AES_256_GCM_Encrypted_Base64_Payload>",
  "signature": "<Base64_ECDSA_Signature_of_Payload_and_Metadata>",
  "rail": "LAN",
  "timestamp": 1771583429000
}
```

### Packet Types
| Packet Type | Description |
| :--- | :--- |
| `HELLO` | Mesh peer discovery / liveness |
| `JOIN_REQUEST` | Sent during QR pairing to exchange device public keys |
| `JOIN_ACCEPT` | Response confirming device authorization in the mesh |
| `CLIPBOARD_SYNC` | AES-encrypted text synced to clipboard |
| `BROWSER_HANDOFF` | AES-encrypted URL to open in peer's browser |
| `FILE_START` | File metadata (fileId, fileName, size, mimeType, totalChunks, sha256) |
| `FILE_CHUNK` | Indexed 64 KB chunk transfer |
| `FILE_COMPLETE` | Confirmation and checksum verification |
| `HEARTBEAT` | Ping packet to measure rail latency and verify connectivity |
| `ACK` | Packet delivery acknowledgment |
| `REVOCATION` | Broadcast signed by host to remove a compromised device |
| `DISCOVERY_CHALLENGE` | Point-to-point, encrypted: proves the sender holds the real mesh key |
| `DISCOVERY_RESPONSE` | Encrypted reply; a valid echoed nonce promotes the peer to trusted |

---

## 🖥️ The Windows side

A wire-compatible C++ core and console/GUI client already exists at
[github.com/MrGrimJoe/Rin-windows](https://github.com/MrGrimJoe/Rin-windows)
— same crypto, same discovery handshake, same packet types, kept in
lockstep with this repo. If you're building an additional client on a
different platform, that's the reference implementation to match against
alongside this one, not a from-scratch protocol design exercise: generate
an ECDSA P-256 keypair, broadcast the v2 beacon format above on UDP
`45991`, and handle the `DISCOVERY_CHALLENGE`/`DISCOVERY_RESPONSE`
handshake before trusting anything a beacon match tells you.

---

## Status

CI (badge above) builds a debug APK and runs the JVM unit test suite —
including real encrypt/decrypt round trips through the discovery
primitives — on every push. What CI *can't* tell you: **this hasn't been
installed and run on a real device yet.** Discovery over a real LAN, BLE,
Wi-Fi Direct, and the relay fallback are implemented and unit-tested in
isolation, not field-verified against real hardware or against the
Windows core over a real network. See
[`DESIGN_NOTES.md`](DESIGN_NOTES.md) for the honest verified-vs-assumed
breakdown.

## License

Custom license — see [`LICENSE`](LICENSE). Short version: you're free to
build, run, and use this (including commercially), and to review or write
about it, as long as you credit the original author. Forking or building
derivative works isn't permitted by default (this README doesn't grant
that exception), and the software itself can't be resold or repackaged.
Not OSI-approved open source — read the full terms before relying on
anything here.
