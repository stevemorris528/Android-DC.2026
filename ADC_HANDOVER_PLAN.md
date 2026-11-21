# Handover plan: full ADC / ADCS support for Android DC++

> **For Claude Code (Android Studio).** Put this file in the repo root next to `settings.gradle`.
> Work through the phases **in order**, one phase per branch/PR. At the end of each phase the app must
> build, and NMDC (`dchub://`) behaviour must be unchanged. Don't start a phase until the previous
> phase's acceptance checks pass.

---

## 0. Goal and scope

Android DC++ currently speaks only plain NMDC. Add:

1. **ADC** (`adc://host:port`): the hub protocol (BASE + TIGR) and client-to-client (C-C) transfers.
2. **ADCS** (`adcs://host:port[/?kp=SHA256/<base32>]`): ADC over TLS, for the hub link *and* C-C
   transfers, with keyprint (KP) verification.
3. **NMDCS** (`nmdcs://`): a cheap extra once the TLS layer exists. It's the same NMDC code over a TLS
   socket.

Everything the app already does on NMDC must also work on ADC hubs: login (including registered
nicks), chat, PMs, user list, file lists, downloads (with resume), uploads, search (sending and
answering), active/passive/UPnP/PIA, hub user commands, and reconnect/keep-alive.

**Out of scope:** ADC hub hosting, DHT, segmented multi-source downloads, ASCH/SUDP extras (unless
time allows; see Phase 9).

**Reference specs** (read before coding):
- ADC base spec 1.0.x: https://adc.sourceforge.io/ADC.html
- ADC extensions: https://adc.sourceforge.io/ADC-EXT.html (BLOM, KEYP, ADCS, TCP4/UDP4, ZLIF, UCMD,
  SEGA, PING)
- Test hub: ADCH++ 3.1.0 (the owner has the Windows build locally; see §9).

---

## 1. Current architecture (what you'll be changing)

Package `com.phinvader.libjdcpp` (the protocol library, pure Java plus `android.util.Log`):

| File | Role today | NMDC-specific? |
|---|---|---|
| `DCClient.java` | Hub connect + `$Lock/$Key/$ValidateNick/$Hello/$GetPass` handshake, `bootstrap()`, routing setup, download start helpers, `sendRaw()` | Yes, fully |
| `MessageHandler.java` | Socket reader thread: splits on `\|`, ISO-8859-1, raw "dump" mode for file data, `send_*` helpers | Yes (delimiter, charset, commands) |
| `DCMessage.java` | Parses NMDC text into one flat object with many fields | Yes |
| `MessageRouter.java` | Dispatches `DCMessage.command` to `DCCommand` subscribers | Mostly generic |
| `DCHandlers.java` | `$ConnectToMe` consumer, download handshake, upload hand-off callback | Yes |
| `DCDownloader.java` | Pending-download register keyed by **nick** | Keyed by nick (ADC needs SID/CID + token) |
| `UsersHandler.java` / `DCUser.java` | User list from `$MyINFO`/`$Quit`; `DCUser` has nick/ip/share/tag/active/op | Needs SID, CID, ADC fields |
| `DCPreferences.java` | Nick, password, share, port, active, self_ip | Needs TLS/KP prefs |

App package `com.phinmadvader.andcpp`:

| File | Notes for ADC |
|---|---|
| `DCPPService.java` (~4,700 lines) | `HubSession` per hub (keyed by `hubIp` / `hubKey`); `connectToHub()` builds a `DCClient` directly. Uses `session.client.sendRaw(...)` with hand-built NMDC strings (`$SR`, `$Search`, `$To`, `$GetINFO` probe, `$ConnectToMe`, `$RevConnectToMe`). Answers searches in `MyIncomingSearchHandler`. Keep-alive and reconnect logic live here. |
| `UploadManager.java` | ServerSocket listener, slots, queue, `$ADCGET/$ADCSND` (file/tthl/list), file list `files.xml.bz2`, TTH index, `search()` over the share. **The CID is a random UUID. That's invalid for ADC and must be fixed (Phase 2).** |
| `PeerConnectionHandler.java` | NMDC C-C handshake for uploads (`$MyNick/$Lock/$Direction/$Key`) |
| `ConnectivityDetector.java`, `pia/PIAPortForwardingManager.java` | Decide active/passive and the listening port. Reuse as-is. |
| `PublicHubsDialog.java` | **Strips** `adc://`, `adcs://`, `dchub://` and treats every address as NMDC. Must keep the scheme. |
| `HubListFragment`, `LoginFragment`, `SettingsBackup` | Hub addresses saved as `host[:port]`. Must keep the scheme. |
| `util/TigerDigest`, `util/TTH`, `util/Base32` | Already correct. Reuse for CID/PID, GPA/PAS and KP. |

Build: AGP 8.13.2, compileSdk 35, **minSdk 14**, targetSdk 28, Java 8. Deps: support-v4 28,
commons-compress 1.26.2.

---

## 2. Target design

### 2.1 Protocol abstraction (the key refactor)

Introduce a protocol-neutral interface that the service talks to, so `DCPPService` stops building
NMDC strings itself:

```java
package com.phinvader.libjdcpp.hub;

public interface HubConnection {
    enum Protocol { NMDC, NMDCS, ADC, ADCS }
    Protocol protocol();
    void connect(HubAddress addr, DCPreferences prefs) throws IOException; // full login, blocking, bounded timeouts
    void close();
    boolean isConnected();
    long millisSinceLastReceived();
    void sendKeepAlive();                               // NMDC: "|" or $GetINFO probe; ADC: empty line "\n"

    // identity / info
    void updateMyInfo(DCUser me, DCPreferences prefs);  // $MyINFO  | BINF (only changed fields)
    List<DCUser> users();

    // chat
    void sendMainChat(String text);                     // <nick> text | BMSG
    void sendPrivate(DCUser to, String text);           // $To:     | EMSG ... PM<mySID>

    // search
    void search(SearchQuery q);                         // $Search  | BSCH (AN/NO/EX/TR/TY/SI/GE/LE/TO)
    void sendSearchResult(SearchQuery from, SearchResult r); // $SR  | DRES (passive) / UDP URES (active)

    // C-C connection setup
    void requestConnection(DCUser peer, ConnectRequest req); // $ConnectToMe/$RevConnectToMe | DCTM/DRCM with token

    // hub user commands
    List<UserCommand> userCommands(int context);
    void runUserCommand(UserCommand c, DCUser target, Map<String,String> params);

    void setListener(HubEventListener l);               // user joined/left/changed, chat, PM, search, search result,
                                                        // connect-request, hub name/topic, redirect, status/error, disconnected
}
```

- `NmdcHubConnection`: a thin adapter around the existing `DCClient` (no behaviour change). The
  NMDC strings that `DCPPService` builds today move into this class.
- `AdcHubConnection`: new.
- `HubAddress`: parses `dchub://`, `nmdc://`, `nmdcs://`, `adc://`, `adcs://`, plus a bare `host[:port]`
  (bare means NMDC, for backward compatibility). It keeps `scheme`, `host`, `port` and the optional `kp`.
  Default ports: NMDC 411. ADC/ADCS have no standard default, so **require a port** (or try 411 and show
  a clear error).
- `HubConnectionFactory.create(HubAddress)` returns the right implementation.

`DCUser` gains: `String sid` (4-char base32), `String cid` (39-char base32), `String kp`,
`Set<String> features` (SU field: `TCP4`, `UDP4`, `ADC0`, `ADCS`, `TCP6`, …), `int slots`,
`int freeSlots`, `long sharedFiles`, `int hubsNormal/hubsReg/hubsOp`, `String client` (VE), `int ct`
(client type: bot/reg/op/su/owner/hub), `String ip4`, `int udp4`, `boolean away`. Keep the NMDC fields.
Map ADC fields onto them where they overlap (NI→nick, DE→description, SS→share_size, I4→ip,
CT op bit→op, has TCP4→active).

### 2.2 ADC wire layer

New package `com.phinvader.libjdcpp.adc`:

- `AdcCommand`: type char (`B C D E F H I U`), 3-letter command, positional params, named params
  (2-letter code + value), `fromSID`, `toSID`, and feature list for `F` messages. `parse(String line)`
  and `toString()`, with ADC escaping: `\s` space, `\n` newline, `\\` backslash. **This differs from
  the NMDC/`$ADCGET` escaping that already exists in `DCMessage.escapeAdc`.** Don't mix them up.
- `AdcReader`: reads **UTF-8** lines terminated by `\n`, on its own thread, onto a `BlockingQueue`.
  Like `MessageHandler`, it needs a "raw bytes" mode for C-C file data after `CSND` (copy the
  volatile-flag approach and the `|`-first-byte fix: here the danger is the first byte being `\n`).
  Track `lastReceivedAt`.
- `AdcWriter`: synchronized `send(AdcCommand)` that writes UTF-8 and flushes.
- `AdcIdentity`: persistent **PID** (24 random bytes, kept in SharedPreferences and in
  `SettingsBackup`) and **CID = Base32(Tiger(PID))**. Base32 without padding gives 39 chars.
  Use `util/TigerDigest` + `util/Base32`.
- `AdcHubConnection`: the state machine below.
- `AdcClientConnection`: C-C protocol (both directions, plain or TLS).

### 2.3 TLS layer

New package `com.phinvader.libjdcpp.tls`:

- `TlsSocketFactory`: creates `SSLSocket`s:
  - **minSdk 14 problem:** TLS 1.2 exists from API 16 but is only *enabled by default* from API 20.
    Call `setEnabledProtocols` with every supported protocol from `{"TLSv1.3","TLSv1.2"}`. ADCH++
    rejects anything below 1.2 by default. On API < 16 show "Secure hubs need Android 4.1+".
    Consider raising minSdk to 16 or 21 and ask the owner first; this decision is theirs.
  - Hubs and peers use **self-signed certs**, so use a custom `X509TrustManager` that accepts any
    chain **but** records the leaf cert. Verification is done by keyprint, not by CA.
  - `keyprint(cert)`: `"SHA256/" + Base32(SHA-256(cert.getEncoded()))`.
  - No hostname verification (meaningless here), so don't use `HttpsURLConnection` helpers.
- `ClientCertificate`: the client needs its **own** cert for C-C TLS and to publish its KP in `INF`.
  Generate an RSA-2048 (or EC P-256) key pair once and a self-signed X.509 cert (CN = CID, long
  validity). Store it in the app's private storage as PKCS#12 or BKS, and include it in the
  `SettingsBackup` restore so the KP survives reinstall.
  - Easiest: add `org.bouncycastle:bcpkix-jdk18on` + `bcprov-jdk18on` (≈ 6 MB before R8). Check the
    APK size, and enable minify/R8 rules if needed.
  - Lighter alternative: a ~150-line DER encoder for a v3 self-signed cert signed with
    `Signature.getInstance("SHA256withRSA")`. Pick one and note the choice in `THIRD_PARTY_NOTICES.md`.
- `KeyprintStore`: remembers the KP per hub (trust on first use) when the address has no `?kp=`. If it
  changes later, warn the user and don't auto-connect.

---

## 3. ADC hub login state machine (`AdcHubConnection`)

```
TCP / TLS connect (15 s timeout; for adcs, verify kp if given)
→ PROTOCOL: send  HSUP ADBASE ADTIGR [ADBLO0]   (add ADZLIF only once implemented; never PING, which is for hub-list pingers)
  recv ISUP (hub features), ISID <mySID>
  recv IINF (hub info: NI hub name, DE topic, VE software)  [may come later]
→ IDENTIFY: send BINF <mySID> ID<CID> PD<PID> NI<nick> SL<slots> FS<free> SS<share> SF<files>
          HN/HR/HO<hub counts> VE<"AndroidDC++ 1.0.5"> AP? SU<TCP4,UDP4,ADC0[,ADCS]>
          I4<0.0.0.0 or our public IP> U4<udp port> [KP<our keyprint> for ADCS]
→ VERIFY (only if the hub sends IGPA <random base32 data>):
          send HPAS Base32(Tiger(password_utf8 || base32decode(data)))
          wrong password → ISTA 223 (or similar) → error "Invalid password"; none saved → ask the user
→ NORMAL: recv BINF for every user (first full, then deltas); IQUI <sid> when users leave;
          ISTA status / errors; IMSG / BMSG chat; EMSG PMs; DCTM / DRCM; DSCH / BSCH searches;
          ICMD user commands (UCMD); IQUI <mySID> with RD<url> = redirect, TL = tempban
```

Must-haves:
- **PID is sent only in our own BINF to the hub, never to other clients.**
- `I4` handling: send `I40.0.0.0` to let the hub fill in our real IP, or send the address
  `resolveAdvertiseIp()` already computes. Passive mode means no `TCP4` in `SU`, and `I4`/`U4`
  are omitted.
- When share, slots or free slots change, send a **delta BINF** with only the changed fields.
  This replaces the NMDC `$MyINFO` re-send done after file list rebuilds.
- **BLOM / BLO0**: ADCH++ ships a Bloom plugin, and the bundled `access.guard.lua` can *force*
  BLOM support ("This hub requires that your client supports the BLOM extension"). Implement it.
  When the hub sends `HGET blom / 0 <bytes> BK<k> BH<h>`, build a Bloom filter of our TTHs (the
  `UploadManager` TTH index) and answer `HSND blom / 0 <bytes>` followed by the raw filter bytes.
  Follow ADC-EXT exactly: k hash functions taken from successive `h`-bit slices of the TTH.
- Keep-alive: send an empty line every ~60 s of silence. Use the existing 30 s tick and AlarmManager
  path, and reuse the liveness check (`millisSinceLastReceived` > 120 s means dead, so reconnect).
- Map `ISTA` severities and codes to user-visible messages. At minimum: 2xx = fatal (disconnect),
  221 nick taken, 222 nick invalid, 223 bad password, 224 CID taken, 226 hub full, 231 banned
  (show `TL`/`MS`), 240 protocol error (show `PR` feature), 245/246 TLS required.

---

## 4. ADC client-to-client (transfers)

### 4.1 Connection setup
- **Active (we can accept):** send `DCTM <mySID> <peerSID> <proto> <ourPort> <token>`. `proto` is
  `ADC/1.0`, or `ADCS/0.10` when both sides advertise `ADCS`. The peer connects to us.
- **Passive (we can't accept), peer active:** send `DRCM <mySID> <peerSID> <proto> <token>`. The peer
  replies with a CTM and we connect out.
- **We receive DCTM:** connect out to the peer's `I4` + port, TLS if `ADCS/0.10`.
- **We receive DRCM:** if we're active, reply with DCTM using the same token. If both sides are
  passive, reply `DSTA <mySID> <peerSID> 1xx "Both passive"` (or ignore) and mark the download
  WAITING_FOR_PEER with that reason.
- The **token** ties a C-C connection to the request. Change `DCDownloader` so it can key pending
  entries by `(hubKey, peerCID or SID, token)` as well as by nick for NMDC. Keep `hasPendingFor`,
  `isEntityPending` and the case-insensitive lookups working for NMDC.

### 4.2 Handshake on the C-C socket
```
connector → CSUP ADBASE ADTIGR [ADZLIG]
acceptor  → CSUP ADBASE ADTIGR [ADZLIG]
acceptor  → CINF ID<theirCID>           (spec: the side that received the connection sends first)
connector → CINF ID<ourCID> TO<token>
```
Then:
- **For ADCS**, after the TLS handshake, compare the peer cert's keyprint with the `KP` from that
  user's hub BINF (if they published one). If it doesn't match, close the connection and log it.
- Look up the token. If there's a pending download for that CID/token, this is a **download** and we
  send `CGET`. Otherwise it's a peer downloading from us, so hand the socket to `UploadManager`.
  Same idea as `DCHandlers.UploadHandlerCallback`, but with an ADC entry point.

### 4.3 Requests (most logic already exists in `UploadManager` for `$ADCGET`)
- `CGET file TTH/<tth> <start> <bytes>` → `CSND file TTH/<tth> <start> <bytes>` + raw data.
- `CGET file /path <start> <bytes>`: paths now use `/` (not NMDC `\`) and ADC escaping.
- `CGET tthl TTH/<tth> 0 -1` → TTH leaves.
- `CGET list /dir/ 0 -1 [RE1]` → partial list (already implemented as `$ADCGET list`).
- `CGET file files.xml.bz2 0 -1` → the full list.
- Errors: `CSTA 151 File not available` (maps to the existing "permanent error" flag), `CSTA 153 No slots`
  (maps to the existing `$MaxedOut` back-off handling, with an `ADC slot queue` reason).
- Optional ZLIG (compressed transfer) later. Do not advertise it until implemented.

**Refactor tip:** pull the request handling out of `UploadManager`/`PeerConnectionHandler` into a
protocol-neutral `TransferRequestHandler` with
`serve(type, identifier, start, bytes, recursive) → (headerParams, InputStream)`. NMDC wraps it in
`$ADCSND`, ADC wraps it in `CSND`. Downloads similarly: a `TransferRequester` that the NMDC and ADC
connections both use, so resume, progress, `totalDownloadedBytes`, the stale-claim watchdog and
`saveDownloadsState()` keep working unchanged.

### 4.4 Download identity
Today downloads are stored by nick. ADC nicks can change and are not unique across hubs. Store
**CID** (plus last-known nick for display) in `DownloadObject` and in `.downloads_state`, and
migrate old entries (no CID means an NMDC item, matched by nick as before). When a user rejoins,
match by CID first.

---

## 5. Searching on ADC

- Outgoing: `BSCH <mySID> AN<term> AN<term2> [NO<exclude>] [EX<ext>] [TY1|TY2] [GE/LE<size>] TO<token>`,
  or `TR<tth>` for TTH search. Passive users can only get results from active peers through the hub
  (`DRES`). Keep the token to match results to the current search tab.
- Results: `URES` over UDP (active, so the UDP listener must parse ADC as well as NMDC `$SR`) and
  `DRES <peerSID> <mySID> FN<path> SI<size> SL<freeSlots> TR<tth> TO<token>` via the hub. Map these to
  the existing `SearchResultsFragment` model (file vs directory: a directory `FN` ends with `/`).
- Incoming `BSCH`/`DSCH`: reuse `UploadManager.search()` (it already supports words + TTH; translate
  `AN/NO/EX/TY/GE/LE/EQ/TR`). Reply `URES` to the peer's `I4:U4` if they're active and we know them,
  else `DRES` via the hub. Keep the existing bounded queue / off-reader-thread design and the
  "two passive users can't connect" rule. Answer with at most 10 results, and don't answer our own
  searches. Use the Bloom filter only on the hub side, not here.

---

## 6. Chat, PMs and user commands

- Main chat: `BMSG <mySID> <text>` out; `BMSG`/`IMSG` in (`ME1` means /me). Unescape text.
- PM: `EMSG <mySID> <peerSID> <text> PM<mySID>` out; incoming `EMSG`/`DMSG` with `PM<groupSID>` goes to
  the existing `PrivateChatManager`, keyed by CID (fall back to nick).
- Hub user commands (UCMD): `ICMD <name> TT<command> CT<context bits> [RM1 remove] [SP separator]`.
  Map them onto the existing `UserCommand` model (`CONTEXT_HUB/USER/SEARCH/FILELIST`). Substitute
  `%[myNI]`, `%[userNI]`, `%[userSID]`, `%[userCID]`, `%[line:Prompt]`, etc. Send the result raw
  (it's already ADC).

---

## 7. UI, storage and settings changes

- **Keep the scheme everywhere.** `PublicHubsDialog.normalizeAddress()` must stop stripping
  `adc://`/`adcs://`/`nmdcs://`. Hub keys and SharedPreferences keys (`favorite_nicks_*`,
  `favorite_passwords_*`, `hub_name_*`) should use the full address including the scheme. Write a
  one-time migration: existing keys without a scheme are NMDC, so read the old key if the new one is
  missing. Update `SettingsBackup` the same way.
- Hub list item: show a protocol badge (NMDC / NMDCS / ADC / ADCS 🔒) and the hub software (VE).
- Add-hub dialog: accept a full URL. If the user types a bare `host:port`, offer a protocol picker
  (NMDC default, ADC, ADCS). Validate that the port is present for ADC/ADCS.
- Keyprint UI: show the hub KP in hub details, and a "Keyprint changed" warning dialog
  (Trust new / Cancel).
- Settings: a "Use TLS for transfers when possible" switch (default on). Show our own CID and KP
  (copyable) in About or Settings.
- Public hub lists: the pwiam list already includes ADC/ADCS hubs. Show them, and drop any
  "unsupported" filtering.
- User list: show the op/bot/hub icon from `CT`, and slots/free slots and client (`VE`) if space allows.

---

## 8. Phases (one branch / PR each)

| # | Phase | Main files | Done when |
|---|---|---|---|
| 1 | **Address parsing and scheme preservation** | `HubAddress` (new), `PublicHubsDialog`, `HubListFragment`, `LoginFragment`, `DCPPService` keys, `SettingsBackup` + migration | Old saved hubs still connect. `adc://` hubs are saved with their scheme and show "ADC (not yet supported)". Unit tests for `HubAddress`. |
| 2 | **Identity**: PID/CID, fix the random-UUID CID in `UploadManager` (files.xml `CID` attribute must be the real CID); include them in the settings backup | `AdcIdentity` (new), `UploadManager`, `SettingsBackup` | CID is 39-char base32 = Tiger(PID) (unit test against a known vector). Survives reinstall. |
| 3 | **HubConnection abstraction + `NmdcHubConnection`** (pure refactor). Move every NMDC string out of `DCPPService` (`$SR`, `$Search`, `$To`, `$GetINFO` probe, CTM/RCM) into the adapter. | `hub/*` (new), `DCPPService`, `DCClient` (minimal) | **No behaviour change on NMDC.** Full regression checklist (§10) passes. |
| 4 | **TLS layer + NMDCS** | `tls/*` (new), `NmdcHubConnection`, `ClientCertificate`, Gradle deps | `nmdcs://` hub login works (e.g. `nmdcs://international.dchub.net:411`). Own KP is shown. |
| 5 | **ADC hub: login, users, chat, PM, keep-alive, reconnect, password (GPA/PAS), STA/QUI/redirect** | `adc/AdcCommand`, `AdcReader/Writer`, `AdcHubConnection`, `DCUser` fields | Logs in to the local ADCH++ (plain) as an unregistered user and as a registered user, user list correct, chat + PM both ways, survives 30 min of screen-off. |
| 6 | **ADCS hub + KP verification + TOFU store** | `AdcHubConnection`, `tls/*`, `KeyprintStore`, UI | `adcs://` with the correct `?kp=` connects. A wrong `kp` is refused with a clear message. Without `kp`, the KP is stored and a later change warns. |
| 7 | **BLOM** | `adc/BloomFilter` (new), `AdcHubConnection`, `UploadManager` TTH index | ADCH++ with the Bloom plugin + `checkemptyinbloom.lua`/guard "force BLOM" accepts us. Unit test: filter bits for known TTHs match the ADC-EXT algorithm. |
| 8 | **C-C transfers: downloads + uploads, plain and TLS, active/passive, tokens, resume, file lists, tthl, partial lists** | `adc/AdcClientConnection`, `TransferRequestHandler`/`TransferRequester` refactor, `DCDownloader` keys, `DCPPService` download objects (CID), `UploadManager` listener (accept ADC and NMDC on the same port by peeking the first bytes: `$` = NMDC, `C` = ADC, `0x16` = TLS) | Phone ⇄ desktop DC++/AirDC++ on the ADCH++ hub: file list both ways, download and upload both ways, resume after killing the app, TLS on when both support ADCS. Existing NMDC transfers still work. |
| 9 | **Search (BSCH/DSCH, URES/DRES, UDP)**, **user commands (ICMD)**, polish (badges, settings, About CID/KP) | `DCPPService`, `UploadManager.search`, UDP listener, `SearchResultsFragment`, `UserListFragment` | Search from the phone finds desktop files and vice versa (active and passive). Hub menu commands from ADCH++ scripts appear and work. |
| 10 | **Docs + release** | `README.md` (remove the "ADC not supported" note), `THIRD_PARTY_NOTICES.md`, changelog | README updated, version bumped, release APK tested on a real phone over mobile data (passive) and Wi-Fi (active/UPnP). |

Commit often with clear messages. Keep `[DLTRACE]`-style logging for new code (`[ADC]`, `[ADCS]`,
`[CC]` tags) so the existing `dltrace.txt` logger captures it.

---

## 9. Test environment

**Local ADCH++ 3.1.0 (owner's Windows PC):**
1. In `config/adchpp.xml`, under `<Servers>`, set up one plain and one TLS server, e.g.
   `<Server Port="2780"/>` and
   `<Server Port="2781" TLS="1" Certificate="certs/cacert.pem" PrivateKey="certs/privkey.pem" TrustedPath="certs/trusted/" DHParams="certs/dhparam.pem"/>`
2. Run `Generate_certs.cmd` (needs Win64 OpenSSL) to create the certs. Run `FirstReg.cmd` to make an
   admin account (to test GPA/PAS with a password).
3. Allow ports 2780/2781 in Windows Firewall. Phone on the same Wi-Fi:
   `adc://<pc-lan-ip>:2780` and `adcs://<pc-lan-ip>:2781`.
4. Get the hub keyprint (shown by ADCH++ at start-up, or from a desktop client's hub info) and test
   `adcs://<ip>:2781/?kp=SHA256/<kp>`.
5. Enable `Bloom` + the guard's "force BLOM" option for Phase 7.
6. Put a desktop client (DC++ or AirDC++) on the same hub as the transfer partner.

**Public hubs (after local tests pass):** the ADCH++ hubs on public lists, e.g. DCDev
(`adcs://hub.dcbase.org:16591` with its published `kp`, the developers' hub, so be polite: test
logins only), and a bridged ADC+NMDC hub such as ValhallaHub for mixed-protocol transfers.
NMDCS: `nmdcs://international.dchub.net:411`.

**Unit tests** (JVM, `app/src/test`): `HubAddress`, `AdcCommand` parse/escape round-trips,
CID-from-PID, GPA/PAS hash, Base32, Bloom filter, keyprint, BINF delta builder, `CGET` path
translation. Add `testImplementation 'junit:junit:4.13.2'`. Keep the `libjdcpp` classes
Android-free where possible (wrap `android.util.Log` behind `DCLogger`) so they run on the JVM.

---

## 10. NMDC regression checklist (run after every phase)

- [ ] Saved hubs auto-reconnect on start. Password hub login works.
- [ ] Chat, PMs, user list join/leave, hub user commands.
- [ ] File list download (another user's) and browse. Saved file lists still open.
- [ ] Download a file + folder. Pause/resume. Kill the app mid-download, and it resumes.
- [ ] Upload to a desktop client, active and passive. Slots / `$MaxedOut`.
- [ ] Search out (active + passive), and incoming searches answered.
- [ ] UPnP and PIA port forwarding still pick the port and the listener follows it.
- [ ] Overnight screen-off: hub stays connected or reconnects. Downloads continue (builds g–n behaviour).
- [ ] Settings backup/restore after a clean reinstall.

---

## 11. Pitfalls to watch

- **Two different escapings:** ADC (`\s`, `\n`, `\\`) vs the NMDC `$ADCGET` escaping already in `DCMessage`.
- **Charset:** ADC is UTF-8 everywhere. NMDC stays ISO-8859-1 (or the hub's encoding). Never route ADC through `MessageHandler`.
- **SIDs are per-hub and per-session.** CIDs are global. Never persist SIDs.
- **Same user on two hubs:** dedupe transfers by CID, not nick.
- **TLS on old Android:** enable TLS 1.2 explicitly on API 16–19. Some old devices lack modern
  ciphers. Fail with a clear message, never silently fall back to plain.
- **Don't block the reader thread:** BLOM generation, search answering and C-C connects must run off
  it (the same rule the NMDC code already follows).
- **The first raw byte after `CSND` can be `\n`.** Apply the same fix as build "G" for `|`.
- **targetSdk stays 28** (per the existing BUILD-INFO), so no new storage or foreground-service rules
  are triggered. Don't raise it in this work.
- `DCPPService` is huge. Make small, surgical edits plus new classes, rather than rewriting it.

---

## 12. Questions for the owner before Phase 4

1. Is raising **minSdk to 16 (or 21)** OK? It simplifies TLS a lot.
2. BouncyCastle dependency (simple, +APK size) or a hand-rolled self-signed cert generator (smaller)?
3. Client name/version string to advertise in `VE` (proposed `AndroidDC++ 1.0.5`) and the `AP` field.
