# SoftEther VPN Client for Android

A multi-protocol VPN client for Android with a native **SoftEther VPN** implementation — no third-party VPN app required. Also supports **OpenVPN**, **MS-SSTP**, and **L2TP/IPsec** across both free community servers and private paid servers.

> Built and maintained by **Morteza Taheri**

> **Documentation map:** this README is the public overview (features, build, license). [PROJECT_SPECIFICATION.md](PROJECT_SPECIFICATION.md) is the canonical requirements and project-status document. [SERVER_SOURCE_ARCHITECTURE.md](SERVER_SOURCE_ARCHITECTURE.md) is the design-only document for server sources/paths (retrieval paths, emergency CSV schema, manual import, retention).

## Features

Implemented and shipping today:

- 🔒 Native SoftEther VPN protocol over SSL/TLS (TCP + UDP)
- 🌐 OpenVPN support via integrated library
- 🔗 MS-SSTP over HTTPS/TLS
- 📱 Clean Material Design UI (Jetpack Compose) with server list, filtering, sorting & search
- 🚫 Per-app VPN exclusion (split tunneling)
- ⚡ Real-time connection speed & traffic statistics
- 🔐 Multiple authentication methods
- 🆘 **Emergency server sources** — user-configured fallback CSV URLs (Settings → Emergency Server Sources), fetched only when the live sources fail
- ✍️ **Manual server entry** — add your own server records (Settings → Manual Servers); they survive list refreshes
- 🔀 OPEN-first / VPN-fallback server-list retrieval — updates try the direct network first and can fall back to an active VPN tunnel (an internal "always prefer VPN" preference can invert the order; there is no settings toggle for it yet)

### Planned (not implemented)

The items below are approved design directions, **not** available features. They are listed here so the roadmap is visible; do not rely on them.

- A server-list entry point for adding a manual server (row or floating action button); manual entry currently lives in Settings (roadmap item E)
- Paste / file / share-sheet import of server configurations (roadmap item F)
- Cumulative server retention — the local database growing across updates instead of being replaced (design: [SERVER_SOURCE_ARCHITECTURE.md](SERVER_SOURCE_ARCHITECTURE.md) §9, approved as a requirement, not implemented)

## Protocol Support

| Protocol | Transport | Free Server | Paid Server |
|----------|-----------|:-----------:|:-----------:|
| SoftEther VPN | TCP | ✅ | ✅ |
| SoftEther VPN | UDP | ✅ | ✅ |
| OpenVPN | TCP | ✅ | ✅ |
| OpenVPN | UDP | ✅ | ✅ |
| MS-SSTP | TCP | ✅ | ✅ |
| L2TP/IPsec | — | ✅ ⚠️ | ✅ ⚠️ |

### SoftEther VPN

Native SoftEther VPN protocol implementation built into the app as a native module. Supports TCP and UDP (RUDP V1 + V2) transports. V2 uses ChaCha20-Poly1305 AEAD encryption with automatic fallback to V1 on servers that don't support it.

**Authentication methods:**

| Method | Free Server | Paid Server |
|--------|:-----------:|:-----------:|
| Anonymous | ✅ | — |
| Hashed Password | ✅ | — |
| Plain Password (RADIUS) | — | ✅ |

### OpenVPN

Integrated OpenVPN client library supporting TCP and UDP transports with automatic or user-selected protocol selection.

### MS-SSTP

Connects over HTTPS/TLS using the standard Microsoft SSTP protocol with username/password authentication.

### L2TP/IPsec

Uses the Android OS built-in L2TP/IPsec client.

> ⚠️ **Deprecated by Android**: Google deprecated the built-in L2TP/IPsec VPN in **Android 12** (API 31) and fully removed it in **Android 13** (API 33). This protocol only works on devices running **Android 12 or below**. For Android 13+, please use SoftEther VPN, OpenVPN, or MS-SSTP instead.

## Project Structure

```
├── app/                  # Main application module
├── SoftEtherClient/      # Native SoftEther VPN protocol implementation (C/C++)
├── sstpClient/           # MS-SSTP protocol client (Kotlin)
├── vpnLib/               # OpenVPN integration library
├── emergency-sources/    # Emergency server-list CSV (servers.csv) — point-in-time snapshot
└── server-setup/         # Server configuration scripts
```

## Building

The app has a single flavor, `pro`. Debug variants are disabled unless the `CI` environment variable is set, so local debug builds need `CI=true`:

```bash
CI=true ./gradlew assembleProDebug     # Debug APK
./gradlew bundleProRelease             # Release AAB (requires release signing)
```

Release builds are **fail-closed**: if the release keystore (`keyStore/release.jks`) or its password (`SIGN_KEY_PASSWORD` env var, or `signKeyPassword` in `local.properties`) is missing, the release task stops with an explicit error instead of silently producing a debug-signed build.

Requires:
- JDK 17+
- Android SDK (compileSdk 37)
- Android NDK (for SoftEther native module)

## License

This project is licensed under **GPLv3**. If you use this project or any part of it in your own project, it must also be open-sourced under the same license.

This project uses the following open-source libraries:

* [**OpenVPN for Android**](https://github.com/schwabe/ics-openvpn) — GPLv2
* [**Open SSTP Client for Android**](https://github.com/kittoku/Open-SSTP-Client) — MIT License
* [**Glide**](https://github.com/bumptech/glide) — BSD, part MIT and Apache License 2.0
* [**Bouncy Castle**](https://www.bouncycastle.org/) — MIT License
* [**Jsoup**](https://jsoup.org/) — MIT License
* [**OkHttp**](https://square.github.io/okhttp/) — Apache License 2.0
* [**Jetpack Room**](https://developer.android.com/jetpack/androidx/releases/room) — Apache License 2.0
* [**Jetpack Compose**](https://developer.android.com/jetpack/compose) — Apache License 2.0
