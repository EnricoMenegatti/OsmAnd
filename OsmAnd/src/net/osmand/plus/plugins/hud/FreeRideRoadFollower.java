package net.osmand.plus.plugins.hud;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.helpers.CurrentPositionHelper;
import net.osmand.plus.settings.backend.ApplicationMode;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.List;

public class FreeRideRoadFollower {
	private static final Log LOG = PlatformUtil.getLog(FreeRideRoadFollower.class);
	
	private final OsmandApplication app;
	private final HudEsp32Plugin plugin;
	private final JunctionDetector junctionDetector;
	
	private int routeId = 30000;
	private Location lastLocation;
	private long lastUpdateTime = 0;
	private RouteDataObject currentRoad;
	private long lastRoadSearchTime = 0;
	private long lastJunctionCheckTime = 0;
	private float lastValidHeading = 0;
	private static final long ROAD_SEARCH_INTERVAL = 2000;
	private static final long JUNCTION_CHECK_INTERVAL = 3000;
	
	public FreeRideRoadFollower(@NonNull OsmandApplication app, @NonNull HudEsp32Plugin plugin) {
		this.app = app;
		this.plugin = plugin;
		this.junctionDetector = new JunctionDetector(app, plugin);
	}
	
	public void onStateEnter(@Nullable Location location, @NonNull HudBleClient bleClient) {
		if (location != null) {
			sendFreeRideRoute(location, bleClient);
		}
	}
	
	public void onStateExit() {
		lastLocation = null;
	}
	
	public void onLocationUpdate(@NonNull Location location, @NonNull HudBleClient bleClient) {
		lastLocation = location;
		
		sendPosition(location, bleClient);
		
		long now = System.currentTimeMillis();
		if (now - lastUpdateTime > 2000) {
			sendFreeRideRoute(location, bleClient);
			lastUpdateTime = now;
		}
	}
	
	private void sendPosition(@NonNull Location location, @NonNull HudBleClient bleClient) {
		int speedKmh10 = (int) (location.getSpeed() * 36);
		
		float heading = 0;
		if (location.hasBearing() && location.hasSpeed() && location.getSpeed() > 1.0f) {
			heading = location.getBearing();
			lastValidHeading = heading;
		} else if (location.hasBearing()) {
			heading = location.getBearing();
			lastValidHeading = heading;
		} else {
			heading = lastValidHeading;
		}
		
		int heading10 = (int) (heading * 10);
		int accuracyM = location.hasAccuracy() ? (int) location.getAccuracy() : 255;
		
		byte[] packet = HudProtocolEncoder.encodePos2(
			location.getLatitude(),
			location.getLongitude(),
			speedKmh10,
			heading10,
			accuracyM,
			false,
			false,
			false,
			routeId,
			0xFFFF
		);
		
		bleClient.write(packet);
	}
	
	private void sendFreeRideRoute(@NonNull Location location, @NonNull HudBleClient bleClient) {
		if (!location.hasSpeed() || location.getSpeed() < 2.78f) {
			return;
		}
		
		long now = System.currentTimeMillis();
		if (now - lastRoadSearchTime > ROAD_SEARCH_INTERVAL) {
			lastRoadSearchTime = now;
			searchCurrentRoad(location);
		}
		
		routeId = (routeId + 1) & 0xFFFF;
		
		int maxDistM = plugin.HUD_FREE_RIDE_MAX_M.get();
		List<LatLon> points = new ArrayList<>();
		
		if (currentRoad != null && location.hasBearing()) {
			points = extractRoadGeometry(currentRoad, location, maxDistM);
		}
		
		if (points.isEmpty()) {
			points = projectStraightAhead(location, maxDistM);
		}
		
		byte[] metaPacket = HudProtocolEncoder.encodeRouteMeta(
			routeId, 1, false, false, 0, maxDistM
		);
		bleClient.write(metaPacket);
		
		LatLon origin = points.get(0);
		int totalPoints = points.size();
		int deltaPoints = totalPoints - 1;
		
		byte[] beginPacket = HudProtocolEncoder.encodeBegin(
			totalPoints, origin.getLatitude(), origin.getLongitude()
		);
		bleClient.write(beginPacket);
		
		List<HudProtocolEncoder.LatLon> encoderPoints = new ArrayList<>();
		for (LatLon pt : points) {
			encoderPoints.add(new HudProtocolEncoder.LatLon(pt.getLatitude(), pt.getLongitude()));
		}
		
		List<byte[]> pointsPackets = HudProtocolEncoder.encodePointsDelta(
			encoderPoints, origin.getLatitude(), origin.getLongitude(), 0, bleClient.getMtu()
		);
		
		for (byte[] packet : pointsPackets) {
			bleClient.write(packet);
		}
		
		byte[] endPacket = HudProtocolEncoder.encodeEnd(deltaPoints);
		bleClient.write(endPacket);
		
		checkAndSendJunction(bleClient, location);
		sendFreeRideManeuver(bleClient, location);
	}
	
	private void checkAndSendJunction(@NonNull HudBleClient bleClient, @NonNull Location location) {
		long now = System.currentTimeMillis();
		if (now - lastJunctionCheckTime < JUNCTION_CHECK_INTERVAL) {
			return;
		}
		lastJunctionCheckTime = now;
		
		try {
			JunctionDetector.JunctionInfo junction = junctionDetector.detectInFreeRide(location, currentRoad);
			if (junction != null) {
				byte[] packet = HudProtocolEncoder.encodeJunction(
					junction.type, junction.distanceM, junction.exitNumber
				);
				bleClient.write(packet);
				
				if (plugin.HUD_DEBUG_LOG.get()) {
					LOG.info("Sent junction (free ride): " + junction);
				}
			}
		} catch (Exception e) {
			if (plugin.HUD_DEBUG_LOG.get()) {
				LOG.warn("Error checking junction in free ride", e);
			}
		}
	}
	
	private void sendFreeRideManeuver(@NonNull HudBleClient bleClient, @NonNull Location location) {
		try {
			JunctionDetector.JunctionInfo junction = junctionDetector.detectInFreeRide(location, currentRoad);
			
			int turn = 1;
			int distM = 500;
			String streetName = "";
			
			if (junction != null) {
				distM = junction.distanceM;
			}
			
			if (currentRoad != null) {
				try {
					streetName = currentRoad.getName("en");
					if (streetName == null || streetName.isEmpty()) {
						streetName = currentRoad.getRef("en", false, true);
					}
					if (streetName == null) {
						streetName = "";
					}
				} catch (Exception e) {
					streetName = "";
				}
			}
			
			byte[] packet = HudProtocolEncoder.encodeManeuver(
				turn, distM, 0, false, false, streetName
			);
			bleClient.write(packet);
			
			if (plugin.HUD_DEBUG_LOG.get()) {
				LOG.info("Sent free ride maneuver: straight arrow, dist=" + distM + "m, street=" + streetName);
			}
		} catch (Exception e) {
			if (plugin.HUD_DEBUG_LOG.get()) {
				LOG.warn("Error sending free ride maneuver", e);
			}
		}
	}
	
	private void searchCurrentRoad(@NonNull Location location) {
		try {
			CurrentPositionHelper helper = new CurrentPositionHelper(app);
			if (helper != null) {
				helper.getRouteSegment(location, ApplicationMode.DEFAULT, false, new ResultMatcher<RouteDataObject>() {
					@Override
					public boolean publish(RouteDataObject road) {
						currentRoad = road;
						return true;
					}
					
					@Override
					public boolean isCancelled() {
						return false;
					}
				});
			}
		} catch (Exception e) {
			if (plugin.HUD_DEBUG_LOG.get()) {
				LOG.warn("Failed to search current road", e);
			}
		}
	}
	
	private List<LatLon> extractRoadGeometry(@NonNull RouteDataObject road, @NonNull Location location, int maxDistM) {
		List<LatLon> points = new ArrayList<>();
		
		try {
			int pointCount = road.getPointsLength();
			if (pointCount < 2) {
				return points;
			}
			
			int closestIdx = 0;
			double minDist = Double.MAX_VALUE;
			
			for (int i = 0; i < pointCount; i++) {
				double lat = road.getPoint31YTile(i) / (double) (1 << 31) * 180.0;
				double lon = road.getPoint31XTile(i) / (double) (1 << 31) * 360.0 - 180.0;
				double dist = Math.sqrt(
					Math.pow(lat - location.getLatitude(), 2) + 
					Math.pow(lon - location.getLongitude(), 2)
				);
				
				if (dist < minDist) {
					minDist = dist;
					closestIdx = i;
				}
			}
			
			points.add(new LatLon(location.getLatitude(), location.getLongitude()));
			
			double bearing = location.getBearing();
			boolean forward = true;
			
			if (closestIdx < pointCount - 1) {
				double lat1 = road.getPoint31YTile(closestIdx) / (double) (1 << 31) * 180.0;
				double lon1 = road.getPoint31XTile(closestIdx) / (double) (1 << 31) * 360.0 - 180.0;
				double lat2 = road.getPoint31YTile(closestIdx + 1) / (double) (1 << 31) * 180.0;
				double lon2 = road.getPoint31XTile(closestIdx + 1) / (double) (1 << 31) * 360.0 - 180.0;
				
				double roadBearing = Math.toDegrees(Math.atan2(lon2 - lon1, lat2 - lat1));
				double diff = Math.abs(roadBearing - bearing);
				if (diff > 180) diff = 360 - diff;
				
				forward = diff < 90;
			}
			
			double totalDist = 0;
			int step = forward ? 1 : -1;
			int idx = closestIdx + step;
			
			while (idx >= 0 && idx < pointCount && totalDist < maxDistM) {
				double lat = road.getPoint31YTile(idx) / (double) (1 << 31) * 180.0;
				double lon = road.getPoint31XTile(idx) / (double) (1 << 31) * 360.0 - 180.0;
				
				LatLon lastPt = points.get(points.size() - 1);
				double segDist = net.osmand.util.MapUtils.getDistance(
					lastPt.getLatitude(), lastPt.getLongitude(), lat, lon
				);
				
				totalDist += segDist;
				if (totalDist <= maxDistM) {
					points.add(new LatLon(lat, lon));
				}
				
				idx += step;
			}
			
		} catch (Exception e) {
			if (plugin.HUD_DEBUG_LOG.get()) {
				LOG.warn("Failed to extract road geometry", e);
			}
		}
		
		return points;
	}
	
	private List<LatLon> projectStraightAhead(@NonNull Location location, int maxDistM) {
		List<LatLon> points = new ArrayList<>();
		points.add(new LatLon(location.getLatitude(), location.getLongitude()));
		
		if (!location.hasBearing()) {
			return points;
		}
		
		double bearing = location.getBearing();
		double lat = location.getLatitude();
		double lon = location.getLongitude();
		int numPoints = Math.min(50, maxDistM / 10);
		
		for (int i = 1; i <= numPoints; i++) {
			double distM = (maxDistM / (double) numPoints) * i;
			
			double bearingRad = Math.toRadians(bearing);
			double latRad = Math.toRadians(lat);
			double dLat = distM * Math.cos(bearingRad) / 111320.0;
			double dLon = distM * Math.sin(bearingRad) / 
				(111320.0 * Math.cos(latRad));
			
			points.add(new LatLon(lat + dLat, lon + dLon));
		}
		
		return points;
	}
}
