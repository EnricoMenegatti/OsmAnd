package net.osmand.plus.plugins.hud;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.List;

public class FreeRideRoadFollower {
	private static final Log LOG = PlatformUtil.getLog(FreeRideRoadFollower.class);
	
	private final OsmandApplication app;
	private final HudEsp32Plugin plugin;
	
	private int routeId = 30000;
	private Location lastLocation;
	private long lastUpdateTime = 0;
	
	public FreeRideRoadFollower(@NonNull OsmandApplication app, @NonNull HudEsp32Plugin plugin) {
		this.app = app;
		this.plugin = plugin;
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
		int heading10 = location.hasBearing() ? (int) (location.getBearing() * 10) : 0;
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
		
		if (!location.hasBearing()) {
			return;
		}
		
		routeId = (routeId + 1) & 0xFFFF;
		
		int maxDistM = plugin.HUD_FREE_RIDE_MAX_M.get();
		double bearing = location.getBearing();
		
		List<LatLon> points = new ArrayList<>();
		points.add(new LatLon(location.getLatitude(), location.getLongitude()));
		
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
		
		byte[] metaPacket = HudProtocolEncoder.encodeRouteMeta(
			routeId, 1, false, false, 0, maxDistM
		);
		bleClient.write(metaPacket);
		
		LatLon origin = points.get(0);
		byte[] beginPacket = HudProtocolEncoder.encodeBegin(
			points.size(), origin.getLatitude(), origin.getLongitude()
		);
		bleClient.write(beginPacket);
		
		List<HudProtocolEncoder.LatLon> encoderPoints = new ArrayList<>();
		for (LatLon pt : points) {
			encoderPoints.add(new HudProtocolEncoder.LatLon(pt.getLatitude(), pt.getLongitude()));
		}
		
		List<byte[]> pointsPackets = HudProtocolEncoder.encodePoints(
			encoderPoints, 0, bleClient.getMtu()
		);
		
		for (byte[] packet : pointsPackets) {
			bleClient.write(packet);
		}
		
		byte[] endPacket = HudProtocolEncoder.encodeEnd(points.size());
		bleClient.write(endPacket);
	}
}
