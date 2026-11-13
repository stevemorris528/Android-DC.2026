# Android DC++ Plus Plus — Development Progress

Android DC++ Plus Plus began as a simple hub-connectivity testing application based on the original open-source AnDCpp project. Development has since expanded it into a substantially more functional Android DC++ client.

### Major Improvements

- Converted the original AnDCpp project into a modern Android Studio Gradle project.
- Preserved and extended the original DC++ networking library.
- Implemented DC++ hub connectivity, login and chat functionality.
- Added connected-user lists and user management.
- Implemented user file-list downloading and browsing.
- Added persistent file-list storage across application restarts.
- Improved file-list navigation and alphabetical arrangement.
- Implemented file downloads, download management and progress handling.
- Improved download destination handling and Downloads folder integration.
- Added saved file lists and favourite hub functionality.
- Introduced a dedicated background DC++ service.
- Implemented foreground-service operation, wake locks and connection keep-alive functionality.
- Added automatic hub reconnection and network recovery handling.
- Improved application lifecycle and background stability.
- Added private messaging components and application settings.
- Implemented dark and light theme resources.
- Refined the graphical interface, dialogs, buttons, tabs and application branding.
- Added DC++-style active/passive user identification graphics.
- Improved password persistence and resolved a password-saving crash.
- Developed PIA port-forwarding integration components.
- Expanded peer-to-peer connection and RevConnect handling.
- Continued refinement of hub management and connection stability.

### Public Hub Browser and Hub Management

- Added a Public Hubs browser for discovering public DC++ hubs.
- Implemented public hub-list downloading and parsing.
- Added support for BZip2-compressed hub lists and XML parsing.
- Displayed hub names, addresses, descriptions, user counts and shared file sizes.
- Added sorting of public hubs by user count.
- Implemented public hub-list caching to reduce unnecessary downloads.
- Added configurable public hub-list sources.
- Added support for custom hub-list URLs.
- Implemented hub-list source selection, addition, editing and removal.
- Added persistence of hub-list sources and selected source across application sessions.
- Added the ability to connect directly to a public hub.
- Added the ability to add public hubs to Favourite Hubs.
- Added the ability to copy hub addresses to the clipboard.
- Improved navigation after initiating a hub connection.
- Added automatic navigation to the Login tab when a nickname has not been configured.
- Improved hub connection status displays and connecting/reconnecting state handling.
- Added improved hub chat, disconnect and delete hub actions.
- Improved offline Favourite Hubs display.
- Added persistence of hub names for displaying favourite hubs while offline.
- Improved favourite-hub indicators and formatting.

### Search and User Navigation Improvements

- Restored and improved the application search menu action.
- Added automatic expansion and focusing of the search interface.
- Added a dedicated Search Results tab.
- Implemented search dispatch across connected hubs.
- Improved handling of disconnected or invalid hub sessions during searches.
- Improved search-result filename and nickname handling.
- Added safer UI-thread handling for search-result updates and clearing.
- Improved navigation to connected-user lists.
- Added direct navigation to the Users tab when opening a hub's user list.
- Improved user-list handling when switching between hub sessions.

### Peer-to-Peer Connection Improvements

- Improved local IP address detection for peer connections.
- Added consideration of local socket IP addresses when identifying the application's own IP.
- Improved use of hub-reported IP addresses with fallback handling.
- Improved ConnectToMe command handling.
- Added checks for pending download entities before handling incoming connection requests.
- Improved peer connection request handling.
- Updated supported peer connection methods to include UserIP2.
- Removed previously advertised unsupported connection methods.
- Improved hub information persistence for offline favourite-hub displays.

### Project and Configuration Improvements

- Added Apache Commons Compress support for BZip2 hub-list processing.
- Added Android network security configuration for cleartext HTTP traffic.
- Added new Public Hubs interface layouts and menu entries.
- Improved application configuration and resource management.
- Continued updating project documentation and GitHub development notes.

### Current Development Focus

Current development is concentrated on GUI refinement, application stability, background operation, hub management and improving the overall user experience.

Further development is continuing on:

- Active/passive peer connection reliability.
- Multi-hub functionality.
- Upload handling.
- Queue management.
- Peer-to-peer connection improvements.
- Additional application controls.
- Continued GUI polish and stability refinements.

### Original Project Credits

Android DC++ Plus Plus is based on the original open-source AnDCpp project.

Original developers:

- Anish Shankar
- Lakshminarayanan Srinivasan
- Madhavan Malolan

This project builds upon their original open-source contribution.
