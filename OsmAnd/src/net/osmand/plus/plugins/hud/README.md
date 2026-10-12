# HUD ESP32 Plugin for OsmAnd

## Overview

This plugin enables OsmAnd to communicate with an ESP32-based motorcycle HUD (Head-Up Display) via Bluetooth Low Energy (BLE). It sends real-time navigation data, route geometry, junction warnings, and position information to a DIY HUD device.

## Features

- **Navigation Mode**: Sends route geometry in sliding windows (100m back, up to 1500m ahead), turn-by-turn maneuvers, distance/ETA information
- **Free Ride Mode**: When not navigating, follows the current road with geometry extending up to the next junction or 500m
- **Junction Detection**: Warns of upcoming intersections and roundabouts
- **Background Operation**: Works with screen off using NavigationService
- **Auto-reconnect**: Automatically reconnects to the HUD device with exponential backoff

## Protocol

The plugin implements **BLE Protocol v2** as specified in the firmware:

### GATT Service
- Service UUID: `8d1f0001-6a4c-4b3e-9a0e-7c2d5f1e4b00`
- RX Characteristic: `8d1f0002-...` (WRITE, WRITE_NO_RESPONSE)
- STATUS Characteristic: `8d1f0003-...` (READ, NOTIFY)

### Packet Types
- **0x01-0x05**: v1 packets (BEGIN, POINTS, END, POS, CLEAR)
- **0x10**: HELLO - Protocol handshake
- **0x11**: ROUTE_META - Route metadata
- **0x13**: JUNCTION - Junction/roundabout warning
- **0x14**: MANEUVER - Turn instruction
- **0x15**: POS2 - Extended position with metadata
- **0x16**: HEARTBEAT - Periodic state indicator
- **0x17**: TIME - Clock synchronization
- **0x18**: TRIP - Distance/time remaining, ETA

All integers are little-endian. Coordinates are int32 (degrees × 10^7).

## Installation

### Building the APK

1. Ensure Android SDK is installed and `ANDROID_HOME` is set
2. Build the debug APK:
   ```bash
   ./gradlew :OsmAnd:assembleHudFullOpenglArm64Debug
   ```
3. The APK will be at: `OsmAnd/build/outputs/apk/hudFull/opengl/arm64/debug/OsmAnd-hudFull-opengl-arm64-debug.apk`

### Installation
The `hudFull` flavor uses `applicationId = "net.osmand.plus.hud"`, allowing side-by-side installation with the official OsmAnd from Google Play.

```bash
adb install OsmAnd-hudFull-opengl-arm64-debug.apk
```

## Configuration

1. Open OsmAnd
2. Go to **Plugins** → Enable **HUD ESP32**
3. Open plugin settings
4. Configure:
   - **HUD Device Name**: Default "HUD-Moto"
   - **Position Update Rate**: 1-5 Hz (default 2 Hz)
   - **Route Window Ahead**: Distance in meters (default 1500m)
   - **Enable Free Ride Mode**: Toggle for non-navigation mode
   - **Free Ride Max Distance**: Max geometry distance in free ride (default 500m)

## Usage

### First Connection
1. Power on the HUD device (it will advertise as "HUD-Moto")
2. Open OsmAnd with the plugin enabled
3. The plugin will automatically scan and connect
4. Once connected, the HUD will display the OsmAnd logo or idle screen

### Navigation
1. Plan a route in OsmAnd
2. Start navigation
3. The HUD will display:
   - **ICONA mode**: Turn symbol, distance, street name, ETA
   - **MAPPA mode**: Mini-map with heading-up view
   - **CURVA mode**: Upcoming curves with severity indicators

### Free Ride
When not navigating and traveling > 10 km/h:
- The plugin sends geometry of the road ahead
- Junction warnings appear in CURVA mode
- No turn instructions (ICONA/MAPPA show clock)

## Architecture

### Key Classes
- `HudEsp32Plugin`: Plugin entry point, lifecycle management
- `HudController`: State machine (IDLE/NAV/FREE)
- `HudBleClient`: BLE communication, auto-reconnect
- `HudProtocolEncoder`: Byte-level protocol encoding
- `HudRouteProvider`: Navigation mode logic, route windows
- `FreeRideRoadFollower`: Free ride mode, road following
- `TurnTypeMapper`: Maps OsmAnd TurnType to HUD codes
- `PolylineUtils`: Geometry utilities (simplification, projection)

### State Machine
```
IDLE ←→ NAV (when route calculated)
IDLE ←→ FREE (when free ride enabled and moving)
NAV → FREE (when route finished/cancelled, if free ride enabled)
```

### Threading
- Location and routing callbacks post to a dedicated `HandlerThread` ("HudWorker")
- All BLE operations and geometry calculations run on the worker thread
- BLE write queue ensures only one characteristic write in flight at a time

## Testing

### Unit Tests
Run protocol encoder tests:
```bash
./gradlew :OsmAnd:testHudFullOpenglArm64DebugUnitTest --tests "net.osmand.plus.plugins.hud.HudProtocolEncoderTest"
```

### Manual Testing
1. Install the firmware on an ESP32 with OLED display
2. Use the test button in plugin settings to trigger reconnection
3. Simulate navigation with GPX tracks
4. Verify:
   - Route geometry appears in CURVA mode
   - Turn instructions update in ICONA mode
   - Free ride shows geometry when not navigating
   - Reconnection works after power cycling the HUD

## Limitations

- Requires Android 6.0+ (API 24, minSdk)
- BLE requires location permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT on Android 12+)
- Junction detection is simplified (does not use full routing graph)
- Free ride mode currently projects straight ahead (road following to be enhanced)

## Firmware Repository

The HUD firmware is at: `https://cursor.com/codebase/enrico-menegatti/moto-hub`

Files of interest:
- `src/route_rx.*`: Protocol parser (reference implementation)
- `tools/hudproto.py`: Python encoder
- `test_vectors.txt`: Hex test vectors for validation

## License

This plugin is part of OsmAnd and is licensed under GPLv3.
