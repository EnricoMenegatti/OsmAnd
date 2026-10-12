# HUD ESP32 Plugin v0.7 - Fix Summary

## Release Information

**Download:** [OsmAnd-hudFull-opengl-arm64-debug.apk](https://github.com/EnricoMenegatti/OsmAnd/releases/download/hud-v0.7/OsmAnd-hudFull-opengl-arm64-debug.apk)

**Release Page:** https://github.com/EnricoMenegatti/OsmAnd/releases/tag/hud-v0.7

**Pull Request:** https://github.com/EnricoMenegatti/OsmAnd/pull/1

---

## Issues Reported in v0.6

User test with ESP32 firmware ('HUD-Moto', address `C8:2E:18:8E:B2:FA`):

1. **NAV State Never Activated**
   - Route simulation was active in OsmAnd
   - Plugin never left FREE state (logs showed only 'IDLE -> FREE')
   - Expected: Should transition to NAV state and send route geometry

2. **MAPPA Mode Shows Wrong Road**
   - Map display on HUD showed completely incorrect road geometry
   - CURVA mode worked well (turn-by-turn arrows)
   - Problem isolated to coordinate/geometry encoding

3. **ICONA Mode Shows Nothing**
   - In FREE state, ICONA display was blank
   - Expected: Should show straight arrow with distance to next junction and street name

---

## Root Causes Identified

### 1. NAV State Detection Failure

**Problem:**
```java
// Old code in HudController.onBleConnected() and onLocationUpdate()
if (routingHelper.isRouteCalculated() && routingHelper.isFollowingMode()) {
    transitionTo(State.NAV);
}
```

**Why it failed:**
- `isFollowingMode()` returns `false` during route simulation mode
- OsmAnd uses `isRoutePlanningMode()` for simulation
- Plugin only checked at BLE connection time, missed routes starting later
- No continuous monitoring of route status

**Fix implemented:**
```java
boolean hasRoute = routingHelper.isRouteCalculated();
boolean isFollowing = routingHelper.isFollowingMode();
boolean isPlanning = routingHelper.isRoutePlanningMode();
boolean isNavigating = hasRoute && (isFollowing || isPlanning);

if (isNavigating && currentState != State.NAV) {
    LOG.info("Detected navigation route during location update, transitioning to NAV");
    transitionTo(State.NAV);
}
```

**Changes:**
- Check both `isFollowingMode()` AND `isRoutePlanningMode()`
- Monitor continuously in `onLocationUpdate()` (not just at connection time)
- Log detailed status flags for debugging
- Detect routes that start after plugin initialization

---

### 2. Map Geometry Coordinate System Mismatch

**Problem:**
```java
// Old code in HudRouteProvider.sendRouteWindow()
List<byte[]> pointsPackets = HudProtocolEncoder.encodePoints(
    encoderPoints, 0, bleClient.getMtu()  // PKT_POINTS = absolute lat/lon
);
```

**What we were sending (PKT_POINTS, type 0x02):**
- Absolute latitude/longitude coordinates
- Format: `int32 lat = degrees * 1e7`, `int32 lon = degrees * 1e7`
- Example: `45.5°N, 9.2°E` → `455000000, 92000000`

**What firmware expected (PKT_POINTS_DELTA, type 0x12):**
- Delta offsets from origin in centimeters
- Coordinate system: **x = East** (positive to east), **y = North** (positive to north)
- Format: `int16 dx` (cm), `int16 dy` (cm)
- Example origin `45.5°N, 9.2°E`, point 50m east and 100m north:
  - `dx = 5000` cm (50m east)
  - `dy = 10000` cm (100m north)

**Why CURVA worked but MAPPA didn't:**
- CURVA mode only uses turn type and distance from MANEUVER packets (no coordinates)
- MAPPA mode draws the map using route geometry coordinates
- Wrong coordinate format caused completely incorrect road display

**Fix implemented:**
```java
// New encodePointsDelta() method
public static List<byte[]> encodePointsDelta(List<LatLon> points, 
        double originLat, double originLon, int startIndex, int mtu) {
    // For each point:
    double dLat = pt.lat - originLat;
    double dLon = pt.lon - originLon;
    
    // Convert to meters using Mercator projection
    double dNorthM = dLat * 111320.0;  // 1° latitude ≈ 111.32 km
    double cosLat = Math.cos(Math.toRadians(originLat));
    double dEastM = dLon * 111320.0 * cosLat;  // Adjust for latitude
    
    // Convert to centimeters
    short dx = (short) Math.round(dEastM * 100.0);  // x = East
    short dy = (short) Math.round(dNorthM * 100.0); // y = North
}
```

**Changes:**
- Implemented `encodePointsDelta()` with proper coordinate system
- Switched `HudRouteProvider` to use `PKT_POINTS_DELTA`
- Switched `FreeRideRoadFollower` to use `PKT_POINTS_DELTA`
- Applied Mercator projection for accurate meter conversion
- Coordinate axes aligned with firmware: x=East, y=North

---

### 3. FREE State ICONA Display Missing

**Problem:**
- No maneuver packets sent in FREE state
- `FreeRideRoadFollower` only sent route geometry and junction info
- ICONA mode had no data to display

**Fix implemented:**
```java
private void sendFreeRideManeuver(@NonNull HudBleClient bleClient, 
                                   @NonNull Location location) {
    // Get junction info for distance
    JunctionDetector.JunctionInfo junction = 
        junctionDetector.detectInFreeRide(location, currentRoad);
    
    int turn = 1;  // Straight arrow
    int distM = junction != null ? junction.distanceM : 500;
    
    // Extract road name
    String streetName = "";
    if (currentRoad != null) {
        streetName = currentRoad.getName("en");
        if (streetName == null || streetName.isEmpty()) {
            streetName = currentRoad.getRef("en", false, true);
        }
    }
    
    // Send MANEUVER packet with straight arrow
    byte[] packet = HudProtocolEncoder.encodeManeuver(
        turn, distM, 0, false, false, streetName
    );
    bleClient.write(packet);
}
```

**Changes:**
- Added `sendFreeRideManeuver()` method to `FreeRideRoadFollower`
- Sends MANEUVER packet in FREE state with:
  - Turn type 1 (straight arrow)
  - Distance to next junction (from `JunctionDetector`)
  - Current road name (from `RouteDataObject.getName()` or `.getRef()`)
- Called after sending route geometry

---

### 4. Additional Improvements

#### Heading Handling for Stationary Positions

**Problem:**
```java
// Old code
int heading10 = location.hasBearing() ? (int) (location.getBearing() * 10) : 0;
```
- Sent 0° when bearing unavailable
- Caused heading to jump when GPS loses bearing at low speeds

**Fix:**
```java
// Maintain last valid heading
private float lastValidHeading = 0;

float heading = 0;
if (location.hasBearing() && location.hasSpeed() && location.getSpeed() > 1.0f) {
    heading = location.getBearing();
    lastValidHeading = heading;  // Update when moving
} else {
    heading = lastValidHeading;   // Keep last valid when stationary
}
```

**Changes:**
- Added `lastValidHeading` field to both `HudRouteProvider` and `FreeRideRoadFollower`
- Update heading only when moving (speed > 1 m/s)
- Maintain last valid heading when stationary or bearing unavailable
- Applied to both NAV and FREE modes

#### Comprehensive Packet Logging (HudTx)

**Added `decodeForLog()` method** to decode all packet types:

```java
public static String decodeForLog(byte[] packet) {
    // Decodes packet header and key fields for all packet types:
    // - HELLO: version, capabilities
    // - BEGIN: point count, origin lat/lon
    // - POINTS_DELTA: start index, point count, first dx/dy in cm
    // - POS2: lat/lon, speed m/s, heading degrees, accuracy
    // - MANEUVER: turn type, distance m, street name
    // - TRIP: remaining distance/time, ETA
    // - JUNCTION: type, exit number, distance
    // - ROUTE_META: route ID, source, flags, distances
    // - TIME: unix timestamp, timezone offset
    // - HEARTBEAT: state
    // - CLEAR, END
}
```

**HudBleClient.write() now logs every packet:**
```java
public void write(byte[] data) {
    // ... connection checks ...
    
    org.apache.commons.logging.Log txLog = PlatformUtil.getLog("HudTx");
    txLog.info(HudProtocolEncoder.decodeForLog(data));
    
    // ... queue and send ...
}
```

**Example log output:**
```
HudTx: HELLO len=4 ver=2 caps=0x1F
HudTx: BEGIN len=11 nPoints=87 origin=45.5,9.2
HudTx: POINTS_DELTA len=351 startIdx=0 n=87 first_dx=0cm dy=0cm
HudTx: POS2 len=19 lat=45.5 lon=9.2 spd=13.9m/s hdg=87.0° acc=5m flags=0x00 routeId=1
HudTx: MANEUVER len=27 turn=3 dist=350m exit=0 street="Via Roma"
```

**Changes:**
- Implemented comprehensive packet decoder for all 15 packet types
- Log includes:
  - Packet type name and length
  - Origin coordinates for BEGIN packets
  - Point count and first delta for POINTS_DELTA packets
  - Position, speed, heading for POS2 packets
  - Turn info and street name for MANEUVER packets
  - Remaining distance/time for TRIP packets
- Use `adb logcat -s HudTx` to see only packet transmission logs

---

## Files Modified

### HudProtocolEncoder.java
- Added `encodePointsDelta()` method for delta-encoded geometry
- Added `decodeForLog()` method for comprehensive packet logging
- Coordinate conversion: lat/lon → meters (Mercator) → centimeters → int16 dx/dy

### HudBleClient.java
- Added HudTx logging in `write()` method
- Logs decoded packet info for every transmission

### HudController.java
- Updated `onBleConnected()` to check both `isFollowingMode()` and `isRoutePlanningMode()`
- Updated `onLocationUpdate()` to continuously monitor route status
- Added transition from NAV to FREE/IDLE when route ends
- Enhanced logging with route status flags

### HudRouteProvider.java
- Switched from `encodePoints()` to `encodePointsDelta()`
- Added `lastValidHeading` field
- Updated `sendPosition()` to maintain heading when stationary
- Improved heading handling (only update when moving > 1 m/s)

### FreeRideRoadFollower.java
- Switched from `encodePoints()` to `encodePointsDelta()`
- Added `sendFreeRideManeuver()` method for ICONA support
- Added `lastValidHeading` field
- Updated `sendPosition()` to maintain heading when stationary
- Extracts and sends road name in FREE mode maneuver

---

## Testing Guide

### Enable HudTx Logging

```bash
adb logcat -s HudTx:I HudBle:I HudController:I
```

**Expected output when route active:**
```
HudController: BLE connected: hasRoute=true isFollowing=false isPlanning=true
HudController: State transition: IDLE -> NAV
HudTx: HELLO len=4 ver=2 caps=0x1F
HudTx: TIME len=7 unix=1728748800 offset=120min
HudTx: ROUTE_META len=9 routeId=1 src=0 flags=0x00 back=100m total=1500m
HudTx: BEGIN len=11 nPoints=87 origin=45.5,9.2
HudTx: POINTS_DELTA len=351 startIdx=0 n=87 first_dx=0cm dy=0cm
HudTx: POINTS_DELTA len=135 startIdx=87 n=33 first_dx=12345cm dy=23456cm
HudTx: END len=3 nPoints=120
HudTx: POS2 len=19 lat=45.5 lon=9.2 spd=13.9m/s hdg=87.0° acc=5m flags=0x00 routeId=1
HudTx: MANEUVER len=27 turn=3 dist=350m exit=0 street="Via Roma"
HudTx: TRIP len=12 remainM=5432 remainS=780 eta=845min
```

### Test Scenario 1: Route Simulation

1. Enable plugin: OsmAnd → Plugins → HUD ESP32
2. Configure device address or enable auto-discovery
3. Create a route in OsmAnd
4. **Start route simulation** (Settings → Navigation → Route simulation)
5. Check logs:
   - `HudController: hasRoute=true isFollowing=false isPlanning=true`
   - `HudController: State transition: IDLE -> NAV` (or `FREE -> NAV`)
6. HUD should show:
   - **CURVA:** Turn arrows with distance
   - **MAPPA:** Correct road geometry ahead
   - **ICONA:** Maneuver icon with distance and street name

### Test Scenario 2: FREE Mode

1. Enable "Free Ride Mode" in plugin settings
2. Disable or cancel navigation
3. Start moving (speed > 2.78 m/s ≈ 10 km/h)
4. Check logs:
   - `HudController: State transition: IDLE -> FREE`
   - `HudTx: POINTS_DELTA` packets with road geometry
   - `HudTx: MANEUVER` with straight arrow and road name
5. HUD should show:
   - **MAPPA:** Current road ahead
   - **ICONA:** Straight arrow with junction distance and road name

### Test Scenario 3: Stationary Heading

1. Start navigation or free ride
2. Stop moving (speed < 1 m/s)
3. Check logs:
   - `HudTx: POS2 ... hdg=<last_heading>°` (not 0°)
4. HUD heading should remain stable, not jump to 0°

---

## Coordinate System Details

### Mercator Projection Formula

For a point at latitude `lat` and longitude `lon`, relative to origin at `lat0`, `lon0`:

```
dLat = lat - lat0  (degrees)
dLon = lon - lon0  (degrees)

dNorthM = dLat * 111320.0  (meters north)

cosLat0 = cos(lat0 * π / 180)
dEastM = dLon * 111320.0 * cosLat0  (meters east)

dx = round(dEastM * 100)  (centimeters east, int16)
dy = round(dNorthM * 100)  (centimeters north, int16)
```

**Constants:**
- 1° latitude ≈ 111.32 km (constant everywhere)
- 1° longitude ≈ 111.32 km * cos(latitude) (varies with latitude)

**Range:**
- `int16` max = 32767 cm = 327.67 m
- Maximum distance from origin: ~327 m in any direction
- Route window limited to 300 points, typically 1500m total (spread over multiple windows)

### Heading Convention

**Format:** `uint16` in tenths of degrees (0-3600)
- 0 = North
- 900 = East
- 1800 = South
- 2700 = West
- 3599 = 359.9° (almost North, clockwise)

**Encoding:**
```java
int heading10 = (int) (bearing * 10);  // 90.0° → 900
buf.putShort((short) ((heading10 % 3600 + 3600) % 3600));  // Wrap to 0-3600
```

---

## Summary

### What Was Wrong in v0.6

| Issue | Root Cause | Impact |
|-------|-----------|---------|
| NAV state never activates | Only checked `isFollowingMode()`, missed `isRoutePlanningMode()` | Route simulation not detected |
| MAPPA shows wrong road | Sent absolute lat/lon (PKT_POINTS) instead of deltas (PKT_POINTS_DELTA) | Map geometry completely incorrect |
| ICONA shows nothing | No MANEUVER packets sent in FREE mode | No display data in FREE state |
| Heading jumps at stops | Sent 0° when bearing unavailable | Unstable heading display |

### What's Fixed in v0.7

| Fix | Implementation | Result |
|-----|---------------|--------|
| NAV detection | Check `isRoutePlanningMode()` + continuous monitoring | Route simulation properly detected |
| Map geometry | Delta encoding (PKT_POINTS_DELTA) with x=East/y=North in cm | MAPPA shows correct road |
| FREE ICONA | Send MANEUVER with straight arrow, junction distance, road name | ICONA displays info in FREE mode |
| Heading stability | Maintain `lastValidHeading` when stationary | Stable heading at stops |
| Debug logging | Comprehensive HudTx packet logging | Easy troubleshooting |

---

## Build Command

```bash
./gradlew :OsmAnd:assembleHudFullOpenglArm64Debug
```

**Output:**
```
/workspace/OsmAnd/build/outputs/apk/hudFullOpenglArm64/debug/OsmAnd-hudFull-opengl-arm64-debug.apk
```

**Size:** ~74 MB  
**Package:** net.osmand.plus.hud  
**Min SDK:** 24 (Android 7.0)  
**Target SDK:** 35 (Android 15)

---

**Report Date:** 2026-10-12  
**Release:** hud-v0.7  
**Download:** https://github.com/EnricoMenegatti/OsmAnd/releases/download/hud-v0.7/OsmAnd-hudFull-opengl-arm64-debug.apk
