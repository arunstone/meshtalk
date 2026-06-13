# MeshTalk

An **offline mesh messenger** for Android — "WhatsApp, but with no internet." Phones nearby
discover each other and chat directly over Bluetooth / BLE / WiFi-hotspot, and messages
**relay hop-by-hop** through intermediate phones to reach people outside direct range.

Inspired by how Quick Share works (Bluetooth to find devices, WiFi to move data fast) and how
apps like Bridgefy / Briar form a mesh.

## Status
MVP (v0.1) — group text chat over a flooding mesh. Builds to an installable debug APK.

## How it works
- **Identity:** each install generates a random ID + display name (stored locally). It does
  **not** use the device MAC address — modern Android hides/randomizes that, so MAC can't
  identify a device. (A public/private keypair for end-to-end encryption is the next step.)
- **Transport:** [Google Nearby Connections](https://developers.google.com/nearby/connections/overview)
  with the `P2P_CLUSTER` strategy. Nearby auto-selects and upgrades between Bluetooth, BLE and
  a WiFi hotspot, and allows many simultaneous links per device (the basis for a mesh).
- **Mesh relay:** every message carries a unique id and a TTL (hop budget). On receipt a phone
  de-duplicates by id, shows the message, then re-broadcasts to its other peers with TTL−1.
  This floods the message across the network until the hop budget runs out.

## Project layout
```
app/src/main/java/store/anybodycan/meshtalk/
  Identity.java     # per-install id + display name (not MAC)
  Message.java      # message model + JSON (de)serialization, id + ttl
  MeshManager.java  # Nearby Connections wrapper: advertise/discover/connect/relay
  ChatAdapter.java  # chat bubble list
  MainActivity.java # permissions, UI, wiring
app/src/main/res/   # layout, theme, launcher icon
```

## Build
Headless build (no Android Studio needed) — requires JDK 17 + Android SDK (platform 34,
build-tools 34):
```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleDebug          # or ./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Run
Install the APK on two or more Android phones, grant the Bluetooth / Nearby-WiFi / Location
permissions, and they will find each other and chat. Put a third phone between two that are
out of range to see messages relay through it.

## Roadmap
- [ ] Media (images / files) over the WiFi-hotspot leg, chunked
- [ ] End-to-end encryption with the per-install keypair
- [ ] Foreground service so discovery survives the app being backgrounded
- [ ] Offline outbox + delivery receipts when a peer reappears
- [ ] 1:1 (private) chats in addition to the group mesh

## License
Private project.
