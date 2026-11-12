Android DC++ - minimal hub connection test base

Access the test from the "Hub Test" tab in the application.

Enter a hub hostname or IP address. Add :port when the hub does not use the
standard NMDC port 411. Enter a nickname and tap Test hub connection.

The test reports these separate stages:
- TCP connection established: the remote address and port accepted a socket.
- NMDC $Lock received: the remote endpoint is speaking the expected hub protocol.
- $Hello for the chosen nickname: the hub accepted the nickname.
- Initial client-entry messages sent: the probe sent Version, GetNickList and
  MyINFO, then closed its test connection.

A hub refusal or kick is still useful evidence and its response is displayed.
The test does not load the hub's user list, search, or download anything.

This is a functional connection test integrated into Android DC++.
The hub connection test is integrated as a tab within the application.

Build environment note: the library Java sources compile, but an APK build
requires the legacy Android SDK/platform configured for this project (target
API 17). No APK was built in this environment because sdk.dir was unavailable.
