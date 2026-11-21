# Changelog

## 2.1.0 (2026-11-22)

### New: ADC, ADCS and NMDCS hubs

- **Hub addresses keep their protocol** (`dchub://`, `nmdcs://`, `adc://`, `adcs://`). Existing NMDC hubs, nicks
  and passwords are kept as they were.
- **ADC / ADCS hubs:** login (with password for registered nicks), user list, main chat, private messages,
  search (sending and answering), hub menus, BLOM, readable hub errors.
- **ADC transfers:** file lists, downloads by TTH with resume, uploads; active and passive; plain (ADC/1.0) and
  encrypted (ADCS/0.10, TLS 1.3/1.2) with users who support or require it. NMDC and ADC peers share one port.
- **NMDCS hubs:** the NMDC protocol over TLS.
- **Keyprints:** your own TLS certificate and keyprint (shown in About), hub keyprints remembered on first use,
  a warning with Trust new / Cancel when one changes, refusal on a wrong `?kp=`, keyprints in Hub details.
- **ADC identity:** a permanent client ID (CID); your file list now carries it (it used to carry a random ID).

### Changed

- Minimum Android version is now **4.1** (TLS 1.2 for secure hubs). Encrypted transfers need Android 5.
- **Upload slots: at least 3** (the default was 2; many hubs require 3).
- Incoming private messages add their tab quietly (or notify in the background) instead of taking over the
  screen.
- Connecting a hub over the other protocol (plain vs secure) replaces the old connection instead of the two
  kicking each other.

### Fixed

- Hub-reported IP addresses ending in `$$`; nicks with non-UTF-8 characters (e.g. Cyrillic hubs) breaking file
  lists, PMs and downloads.
- Chat and Users tabs jumping between hubs, and showing or sending to the wrong hub while one reconnects.
- File lists from the Saved tab opening the PM tab; a crash when a file list finished loading while a download
  dialog was open.
- A file list request failing at once ("no valid small icon").
- An NMDC peer's upload request being taken for our own pending download.
- Queued downloads moving onto a hub that couldn't serve them.
- A crash when deleting or reconnecting a secure hub.
- Endless reconnects to a hub that asked us not to reconnect (e.g. too few slots).
- Delete Hub sometimes reconnecting a different hub.

## 1.0.5

- Previous release (NMDC only).
