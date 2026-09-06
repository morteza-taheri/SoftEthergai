# SoftEther VPN Client for Android

A multi-protocol VPN client for Android with a native **SoftEther VPN** implementation — no third-party VPN app required. Also supports **OpenVPN**, **MS-SSTP**, and **L2TP/IPsec** across both free community servers and private paid servers.

> Built and maintained by **Morteza Taheri**

## Features

- 🔒 Native SoftEther VPN protocol over SSL/TLS (TCP + UDP)
- 🌐 OpenVPN support via integrated library
- 🔗 MS-SSTP over HTTPS/TLS
- 📱 Clean Material Design UI with server list, filtering, sorting & search
- 🚫 Per-app VPN exclusion (split tunneling)
- ⚡ Real-time connection speed & traffic statistics
- 🔐 Multiple authentication methods

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
└── server-setup/         # Server configuration scripts
```

## Building

```bash
./gradlew assembleFreeDebug     # Free flavor (debug)
./gradlew assembleProDebug      # Pro flavor (debug)
./gradlew bundleFreeRelease     # Free flavor (release AAB)
```

Requires:
- JDK 17+
- Android SDK (compileSdk 37)
- Android NDK (for SoftEther native module)

## License

This project is licensed under **GPLv3**. If you use this project or any part of it in your own project, it must also be open-sourced under the same license.

This project uses the following open-source libraries:

* [**OpenVPN for Android**](https://github.com/schwabe/ics-openvpn) — GPLv2
* [**Glide**](https://github.com/bumptech/glide) — Apache License 2.0
* [**Open SSTP Client for Android**](https://github.com/kittoku/Open-SSTP-Client) — MIT License
* [**Bouncy Castle**](https://www.bouncycastle.org/) — MIT License
