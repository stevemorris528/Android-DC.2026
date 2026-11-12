# Minimal DC++ hub connection test base

This is a functional connection test integrated into Android DC++.

## What the test does

1. Opens a TCP connection to the supplied hub hostname/IP and port (default 411).
2. Waits for the hub's NMDC `$Lock` handshake.
3. Uses the original libjdcpp `DCFunctions.convert_lock_to_key()` implementation to calculate and send the key.
4. Sends `$ValidateNick` and waits for the hub's response.
5. If the hub replies `$Hello <nickname>`, sends the initial `$Version`, `$GetNickList`, and `$MyINFO` messages and reports successful hub entry.
6. Stops and closes the socket. It does not start the old full client service, parse the user list, search, or download.

The screen separately reports TCP connection, hub response, and successful nickname acceptance. A refusal or kick is still useful evidence: it demonstrates that the remote endpoint was reached, and the last hub response is shown where available.

## Files added or changed

- `app/src/main/java/com/phinmadvader/andcpp/HubTestFragment.java` — new fragment hosting the test UI.
- `app/src/main/java/com/phinmadvader/andcpp/MainActivity.java` — hosts the Hub Test as a persistent tab.
- `app/src/main/AndroidManifest.xml` — `MainActivity` is the launcher activity.
- `src/com/phinvader/libjdcpp/` — includes the library Java source so the legacy Ant Android build can compile it.
- `libjdcpp/` — contains the complete supplied library repository snapshot, including docs and tests.

## Build note

This is a Gradle-based Android project. The application targets a legacy API level (17) for compatibility with its original codebase.

## Important protocol boundary

A successful TCP connection proves the address/port is reachable. Receiving `$Lock` proves the remote endpoint is speaking the expected NMDC handshake. Receiving `$Hello <nickname>` proves the hub accepted the nickname. The test reports these stages separately so a TCP-only connection is not mistaken for a successful hub login.
