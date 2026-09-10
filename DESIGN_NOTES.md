# Design notes

This file tracks non-obvious design decisions and known compromises,
mirroring `rin-windows/DESIGN_NOTES.md` where the two platforms share a
wire protocol.

## Discovery hardening (this pass)

Two things were deliberately left unfixed in earlier passes and flagged
for later, because they're wire-protocol-level and need Android and
Windows updated together to stay compatible: **auto-trust on
discovery**, and the **cleartext beacon** (mesh name + device name sent
unencrypted over UDP broadcast, readable by anyone on the LAN). Both are
fixed now, on both platforms, in lockstep. See `rin-windows/DESIGN_NOTES.md`'s
matching section for the C++ side of this — the two should be read
together, since the whole point is that they agree byte-for-byte on the
wire.

**Beacon wire format bumped to v2.** The old beacon broadcast
`{"mesh":<name>,"key":<pubkey>,"name":<device>,"port":...}` in the
clear. v2 (`UdpBeaconEngine.kt`) drops `mesh` and `name` entirely and
replaces them with a `meshTag`:
`base64(HKDF(meshKey, info="rin-beacon-tag-v1:"+bucket)[:9])`, where
`bucket = unixSeconds / 300`. Only a device that already holds the real
mesh secret can compute a matching tag, so a passive LAN observer now
sees an opaque blob instead of "mesh 'Family' has a device named
'Ali-Phone'". The tag rotates every 5 minutes so it isn't a long-lived
tracking identifier either. This is a **coordinated breaking bump, not
a soft migration** — v1 and v2 peers simply can't discover each other.
`CryptoEngine.deriveBeaconTag` must stay byte-for-byte identical to
Windows' `CryptoEngine::derive_beacon_tag` (same HKDF info string, same
9-byte truncation) or the two platforms silently stop discovering each
other. `stunIp`/`stunPort` stay in cleartext on the beacon — they're
NAT-traversal server hints, not identifying metadata about the mesh or
device, so they don't need to move behind the handshake the way mesh
name and device name did.

**A beacon/mDNS/HELLO match no longer grants trust.** It used to:
knowing the mesh name (or, now, guessing/replaying a meshTag) was
sufficient to get auto-added to the trusted-device list via
`handleDiscoveredPeer`'s old "auto-join" branch. Now a match only
triggers a **discovery challenge/response handshake** over TCP (two new
`PacketType` values, `DISCOVERY_CHALLENGE`/`DISCOVERY_RESPONSE`) where
each side proves it can actually encrypt/decrypt with the real mesh key
— not just produce a matching tag guess. Only on a valid echoed nonce
does a peer get promoted from "seen" to trusted (`promoteToTrusted`),
and that's also the point where its device name is finally revealed
(encrypted, inside the response). A lexicographic tie-break on public
keys (`localPublicKey > peerPublicKey` → initiate) stops both sides from
challenging each other simultaneously when they discover each other
around the same time — this comparison must match Windows'
`MeshEngine::on_peer_discovered` tie-break exactly, since both sides
independently compute it and need to agree on who initiates.

This fix is applied at the shared `onPeerDiscoveredNeedsVerification`
entry point, which `handleDiscoveredPeer`'s old auto-join branch and the
UDP beacon callback both funnel into — so the auto-trust hole is closed
**regardless of which channel discovered the peer** (UDP beacon, mDNS/
NSD, or the Wi-Fi-Direct `HELLO` probe). Concurrent discovery of the
same peer from more than one signal at once (e.g. UDP beacon racing an
mDNS resolution) is handled via `ConcurrentHashMap.putIfAbsent` on
`pendingChallenges`, reserving the slot atomically rather than as a
separate check-then-insert (which would let two challenges go out with
two different nonces, silently orphaning whichever response arrives
second — this exact race is why the equivalent Windows fix needed a
second pass, see its DESIGN_NOTES entry). A mismatched or
undecryptable `DISCOVERY_RESPONSE` deliberately does **not** clear the
pending entry — a stray or forged packet shouldn't be able to cancel a
legitimate in-flight handshake by arriving first.

**Test coverage:** `CryptoEngineDiscoveryTest.kt` covers the new crypto
primitives as plain JVM unit tests (no Robolectric needed) —
`deriveBeaconTag`'s determinism, rotation, and mesh-key-dependence;
`generateDiscoveryNonce` non-collision; and a full encrypt/decrypt
round trip for both challenge and response payloads through the real
`AES-256-GCM` mesh key, including confirming that a payload encrypted
with the *wrong* mesh key fails to decrypt (the actual security property
the whole handshake rests on). A full two-device integration test
mirroring `rin-windows/tests/test_discovery.cpp` (two real
`MeshRuntimeEngine`s over loopback TCP, one proving mutual trust with a
shared secret and one proving mutual rejection without one) is a clear
next step but needs `RinRepository`/`Context` mocking to set up
cleanly — flagged here rather than rushed.

**Known, deliberately out of scope for this pass:** the Wi-Fi-Direct
`HELLO` discovery probe (`triggerWifiDirectProbe`) still carries the
mesh name and device name in cleartext inside its (unencrypted, by
packet-type design) payload. Windows has no Wi-Fi Direct rail at all,
so this genuinely isn't a "both sides update together" wire change the
way the shared UDP beacon was — it's Android-only. The auto-trust fix
above still applies to peers discovered this way (they go through the
same challenge/response gate before being trusted), so a stranger can't
get auto-trusted via this channel either; only the cleartext-metadata
leak specifically remains open here. Worth closing in a follow-up pass
if Wi-Fi Direct proximity discovery turns out to matter in practice.

**Not verified by a real build in this pass** — this environment had no
Gradle wrapper jar and no network access to Google's Maven repo, so the
Kotlin changes above were written and reviewed carefully (brace/paren
balance checked, logic cross-checked line-by-line against the Windows
C++ it mirrors) but never actually compiled or run against a real
Gradle/Android toolchain. Verify with a real build before trusting this
in production.
