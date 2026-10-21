# Subsharic

Subsharic is a Navidrome/Subsonic API app interface that allows for users to share their streamed music library with others by joining parties, allowing for multiple users to experience one person's music at the same time through their each of their own headphones. 

## Project Structure 

```
sync-broker/
├── package.json
├── tsconfig.json
├── .env.example
└── src/
    ├── server.ts                   # Express HTTP upgrade & WebSocket server setup
    ├── config/
    │   └── environment.ts          # Navidrome host credentials & port settings
    ├── handlers/
    │   ├── connectionHandler.ts    # Socket lifecycle & auth handshakes
    │   ├── roomHandler.ts          # Join, leave, and host transfer events
    │   └── syncHandler.ts          # Playback commands (play, seek, skip)
    ├── managers/
    │   ├── RoomManager.ts          # In-memory store of active room states
    │   └── ClockSyncManager.ts     # NTP timestamp calculator & ping responder
    ├── services/
    │   └── NavidromeService.ts     # Server-side proxy for Navidrome API calls
    ├── models/
    │   ├── Room.ts                 # Data models for Room, Host, and Peer
    │   ├── Track.ts                # Audio track metadata schema
    │   └── Messages.ts             # Strongly typed WS incoming/outgoing payloads
    └── utils/
        ├── logger.ts               # Winston/Pino logger setup
        └── crypto.ts               # MD5 + Salt hashing for Subsonic auth
soundparty-android/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── res/
│           └── java/com/zevan/soundparty/
│               ├── SoundPartyApplication.kt
│               │
│               ├── data/                       # Data Layer (Network & Storage)
│               │   ├── remote/
│               │   │   ├── NavidromeApi.kt     # Retrofit interface for Subsonic endpoints
│               │   │   ├── WebSocketClient.kt  # OkHttp WS manager with auto-reconnect
│               │   │   └── dto/                # API Response schemas
│               │   └── repository/
│               │       ├── MediaRepository.kt  # Fetches tracks & streams from Navidrome
│               │       └── RoomRepository.kt   # Manages WS room connection & state flow
│               │
│               ├── domain/                     # Business Logic & Math Models
│               │   ├── model/
│               │   │   ├── SyncState.kt        # Room playback state schema
│               │   │   └── Track.kt            # Core track domain model
│               │   └── sync/
│               │       ├── ClockSynchronizer.kt # Cristian's Algo using SystemClock.elapsedRealtime()
│               │       └── DriftCalculator.kt  # Calculates playhead variance delta
│               │
│               ├── service/                    # Background Media Services
│               │   ├── MediaPlaybackService.kt # MediaSessionService hosting ExoPlayer
│               │   ├── AudioFocusHandler.kt    # Manages calls & headphone disconnects
│               │   └── SyncEngine.kt           # Adjusts ExoPlayer speed (0.99x-1.01x)
│               │
│               ├── ui/                         # Presentation Layer (Jetpack Compose)
│               │   ├── theme/                  # Colors, Typography, Shapes
│               │   ├── components/             # Reusable UI (Buttons, Track List Item)
│               │   ├── debug/
│               │   │   └── SyncDebugOverlay.kt # Floating HUD for RTT, Drift, and Clock Offset
│               │   ├── lobby/
│               │   │   ├── LobbyScreen.kt      # Create / Join Party dialogs
│               │   │   └── LobbyViewModel.kt
│               │   └── player/
│               │       ├── PlayerScreen.kt     # Main playback controls & queue
│               │       └── PlayerViewModel.kt  # Binds SyncEngine to Compose UI
│               │
│               └── utils/
│                   └── Extensions.kt           # Time formatting & byte converters
```

## Class Project 

This project has been created for Com S 4540 (Distributed Systems) at Iowa State University for my final project.
