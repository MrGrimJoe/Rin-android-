# Rin — Serverless Zero-Trust Local Mesh

> **True zero-cloud, multi-device continuous computing mesh.**
> No centralized servers. No user accounts. No telemetry. Pure cryptographically verified peer-to-peer transport.

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
  - **Device Revocation**: Cryptographically signed revocation notices to instantly purge decommissioned devices.
- 🔍 **Live Packet Inspector**:
  - Inspect sequence IDs, transport rails, latency measurements, and cryptographic signatures in real time.

---

## 🏗️ Architecture Milestones & Multi-Platform Support

### 1. Windows & Shared C++ Native Core Interoperability (`RinNativeCoreBridge`)
- **Native JNI Binding Hook**: `RinNativeCoreBridge` establishes the JNI runtime bridge to `librin_core.so` (built from the shared C++ core in §09).
- **Graceful Fallback**: Dynamically falls back to high-performance Kotlin/JVM SIMD cryptography if native binaries are absent, maintaining 100% ABI compatibility across `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
- **Wire Compatibility**: Guarantees identical packet binary alignment (`0x52494E31` magic header) and SHA-256 chunk hashing across Windows and Android runtimes.

### 2. Off-Grid Wi-Fi Direct Autonomous Mesh (`WifiDirectMeshManager`)
- Enables two or more phones in the wilderness (no router, no cell signal) to establish high-speed direct 802.11 P2P connections.
- Implements `WifiP2pManager` discovery, group formation (`isGroupOwner`), and automatic routing over autonomous `192.168.49.x` subnet.

### 3. Internet Traversal via STUN / UDP Hole Punching (`StunHolePunchEngine`)
- **RFC 5389 STUN Resolution**: Queries standard public zero-logging STUN servers to detect public reflexive IP addresses and port mappings across cellular data (5G/LTE) and distinct home firewalls.
- **UDP NAT Pinhole Probe**: Implements direct UDP packet burst handshakes (`RIN_HOLE_PUNCH:<token>`) to punch through symmetric and restricted cone NATs without routing data through third-party servers.

---

## 🔌 Protocol Specification & Wire Format

### Network Ports
- **TCP Control & Transfer Socket**: `45990` (Configurable)
- **UDP Discovery Beacon Port**: `45991`
- **mDNS Service Type**: `_rin._tcp.`
- **STUN Resolvers**: `stun.l.google.com:19302`, `stun.cloudflare.com:3478`

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
| `JOIN_REQUEST` | Sent during QR pairing to exchange device public keys |
| `JOIN_ACCEPT` | Response confirming device authorization in the mesh |
| `CLIPBOARD_SYNC` | AES-encrypted text synced to clipboard |
| `BROWSER_HANDOFF` | AES-encrypted URL to open in peer's browser |
| `FILE_START` | File metadata (fileId, fileName, size, mimeType, totalChunks, sha256) |
| `FILE_CHUNK` | Indexed 64 KB chunk transfer |
| `FILE_COMPLETE` | Confirmation and checksum verification |
| `HEARTBEAT` | Ping packet to measure rail latency and verify connectivity |
| `REVOCATION` | Broadcast signed by host to remove a compromised device |

---

## 🛠️ Developing a Windows / Desktop Client

To build a Windows native client (C++ / Rust / C#):
1. Listen on TCP `45990` and UDP `45991`.
2. Generate an ECDSA P-256 (`secp256r1`) keypair upon first setup.
3. Broadcast UDP beacons formatted as `RIN_DISCOVER:<MeshName>:<DeviceName>:<Port>:<PublicKeyPrefix>` to `255.255.255.255:45991`.
4. Handle incoming TCP connections, decrypt payload using AES-256-GCM derived key (`PBKDF2WithHmacSHA256`), and verify the ECDSA signature.
