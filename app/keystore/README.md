# Release signing key

| Field | Value |
| --- | --- |
| Keystore | `app/keystore/main.jks` (PKCS12) |
| Alias | `logfox` |
| Owner (DN) | `CN=hUh63, OU=hUh63, O=hUh63, L=Internet, ST=Internet, C=CN` |
| Algorithm | RSA 4096, SHA384withRSA |
| Validity | 2026-11-17 → 2126-10-24 (36,500 days / 100 years) |
| Certificate SHA-256 | `F6:C9:EE:E5:EB:98:CD:76:C3:33:F8:E1:83:95:0C:26:48:19:F8:0E:48:F3:26:5A:23:4A:8F:DA:14:93:3C:D6` |
| Certificate SHA-1 | `E8:5D:C5:4A:B3:E8:B2:86:C5:6D:3D:67:2F:E0:E3:D1:F7:96:9E:1E` |

Credentials live in `keystore.properties` next to the keystore. Release builds read
them automatically; `KEY_STORE_PASSWORD` / `ALIAS` / `KEY_PASSWORD` environment
variables (used by the upstream release workflows) take precedence when set.

> The key is committed to the repository so that builds stay signed with the same
> certificate. Anyone who can read the repository can therefore sign APKs with it —
> that is acceptable for a self-built fork, but do not publish F-Droid style
> reproducible claims with it.
