package net.osmand.plus.plugins.hud;

import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.NextDirectionInfo;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RouteSegmentResult;
import net.osmand.router.TurnType;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.List;

public class HudRouteProvider {
	private static final Log LOG = PlatformUtil.getLog(HudRouteProvider.class);
	
	private final OsmandApplication app;
	private final HudEsp32Plugin plugin;
	private final JunctionDetector junctionDetector;
	
	private int routeId = 1;
	private List<LatLon> currentWindow;
	private double[] windowCumDist;
	private int lastManeuverDist = -1;
	private TurnType lastManeuverType;
	private long lastTripUpdate = 0;
	private long lastJunctionCheckTime = 0;
	private float lastValidHeading = 0;
	private int lastWindowStartIndex = -1;
	private long lastWindowSentTime = 0;
	
	private static final long TRIP_UPDATE_INTERVAL = 5000;
	private static final long JUNCTION_CHECK_INTERVAL = 3000;
	private static final long MIN_WINDOW_UPDATE_INTERVAL = 2000;
	
	public HudRouteProvider(@NonNull OsmandApplication app, @NonNull HudEsp32Plugin plugin) {
		this.app = app;
		this.plugin = plugin;
		this.junctionDetector = new JunctionDetector(app, plugin);
	}
	
	public void onStateEnter(@Nullable Location location, @NonNull HudBleClient bleClient) {
		if (location != null) {
			sendRouteWindow(location, bleClient, false);
		}
	}
	
	public void onStateExit() {
		currentWindow = null;
		windowCumDist = null;
		lastWindowStartIndex = -1;
		lastWindowSentTime = 0;
	}
	
	public void onNewRoute(boolean newRoute) {
		routeId = (routeId + 1) & 0xFFFF;
		currentWindow = null;
		windowCumDist = null;
	}
	
	public void onLocationUpdate(@NonNull Location location, @NonNull HudBleClient bleClient) {
		RoutingHelper routingHelper = app.getRoutingHelper();
		
		if (!routingHelper.isRouteCalculated()) {
			return;
		}
		
		sendPosition(location, bleClient);
		
		if (shouldUpdateWindow(location)) {
			sendRouteWindow(location, bleClient, true);
		}
		
		sendManeuver(bleClient);
		
		long now = System.currentTimeMillis();
		if (now - lastTripUpdate >= TRIP_UPDATE_INTERVAL) {
			sendTrip(bleClient);
			lastTripUpdate = now;
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
		
		boolean snapped = false;
		boolean simulated = false;
		boolean tunnel = false;
		int sOnRoute = 0xFFFF;
		
		Location fixedLocation = app.getRoutingHelper().getLastFixedLocation();
		if (fixedLocation != null) {
			snapped = true;
		}
		
		byte[] packet = HudProtocolEncoder.encodePos2(
			location.getLatitude(),
			location.getLongitude(),
			speedKmh10,
			heading10,
			accuracyM,
			snapped,
			simulated,
			tunnel,
			routeId,
			sOnRoute
		);
		
		bleClient.write(packet);
	}
	
	private boolean shouldUpdateWindow(@NonNull Location location) {
		if (currentWindow == null || windowCumDist == null) {
			return true;
		}
		
		long now = System.currentTimeMillis();
		if (now - lastWindowSentTime < MIN_WINDOW_UPDATE_INTERVAL) {
			return false;
		}
		
		RoutingHelper routingHelper = app.getRoutingHelper();
		RouteCalculationResult route = routingHelper.getRoute();
		if (route == null || !route.isCalculated()) {
			return false;
		}
		
		int currentIndex = route.getCurrentRoute();
		if (currentIndex < 0) {
			return false;
		}
		
		int indexDiff = Math.abs(currentIndex - lastWindowStartIndex);
		if (indexDiff < 10) {
			return false;
		}
		
		int remainingDist = routingHelper.getLeftDistance();
		int windowAhead = plugin.HUD_WINDOW_AHEAD_M.get();
		
		return remainingDist < 600 || remainingDist > windowAhead + 500;
	}
	
	private void sendRouteWindow(@NonNull Location location, @NonNull HudBleClient bleClient, boolean isUpdate) {
		RoutingHelper routingHelper = app.getRoutingHelper();
		RouteCalculationResult route = routingHelper.getRoute();
		
		if (route == null || !route.isCalculated()) {
			return;
		}
		
		List<Location> allPoints = route.getImmutableAllLocations();
		if (allPoints == null || allPoints.isEmpty()) {
			return;
		}
		
		int currentIndex = route.getCurrentRoute();
		if (currentIndex < 0 || currentIndex >= allPoints.size()) {
			currentIndex = 0;
		}
		
		int windowAhead = plugin.HUD_WINDOW_AHEAD_M.get();
		int backM = 100;
		
		List<LatLon> windowPoints = new ArrayList<>();
		double windowDist = 0;
		int startIndex = Math.max(0, currentIndex - 5);
		
		for (int i = startIndex; i < allPoints.size() && windowDist < windowAhead; i++) {
			Location loc = allPoints.get(i);
			windowPoints.add(new LatLon(loc.getLatitude(), loc.getLongitude()));
			
			if (i > startIndex) {
				Location prev = allPoints.get(i - 1);
				windowDist += net.osmand.util.MapUtils.getDistance(
					prev.getLatitude(), prev.getLongitude(),
					loc.getLatitude(), loc.getLongitude()
				);
			}
		}
		
		if (windowPoints.isEmpty()) {
			return;
		}
		
		windowPoints = PolylineUtils.simplify(windowPoints, 1.5);
		windowPoints = PolylineUtils.densify(windowPoints, 25);
		
		if (windowPoints.size() > 300) {
			windowPoints = windowPoints.subList(0, 300);
		}
		
		currentWindow = windowPoints;
		windowCumDist = PolylineUtils.computeCumulativeDistances(windowPoints);
		lastWindowStartIndex = currentIndex;
		lastWindowSentTime = System.currentTimeMillis();
		
		byte[] metaPacket = HudProtocolEncoder.encodeRouteMeta(
			routeId, 0, isUpdate, false, backM, (int) windowDist
		);
		
		if (plugin.HUD_DEBUG_LOG.get()) {
			LOG.info("Sending route window: routeId=" + routeId + " points=" + windowPoints.size() + 
				" currentIdx=" + currentIndex + " isUpdate=" + isUpdate);
		}
		
		bleClient.write(metaPacket);
		
		if (windowPoints.isEmpty()) {
			return;
		}
		
		LatLon origin = windowPoints.get(0);
		byte[] beginPacket = HudProtocolEncoder.encodeBegin(
			windowPoints.size(), origin.getLatitude(), origin.getLongitude()
		);
		bleClient.write(beginPacket);
		
		List<HudProtocolEncoder.LatLon> encoderPoints = new ArrayList<>();
		for (LatLon pt : windowPoints) {
			encoderPoints.add(new HudProtocolEncoder.LatLon(pt.getLatitude(), pt.getLongitude()));
		}
		
		List<byte[]> pointsPackets = HudProtocolEncoder.encodePointsDelta(
			encoderPoints, origin.getLatitude(), origin.getLongitude(), 0, bleClient.getMtu()
		);
		
		for (byte[] packet : pointsPackets) {
			bleClient.write(packet);
		}
		
		byte[] endPacket = HudProtocolEncoder.encodeEnd(windowPoints.size());
		bleClient.write(endPacket);
		
		// Check for junctions ahead
		List<RouteSegmentResult> segments = route.getOriginalRoute();
		if (segments != null && !segments.isEmpty()) {
			checkAndSendJunction(bleClient, location, segments);
		}
	}
	
	private void sendManeuver(@NonNull HudBleClient bleClient) {
		RoutingHelper routingHelper = app.getRoutingHelper();
		RouteCalculationResult route = routingHelper.getRoute();
		
		if (route == null || !route.isCalculated()) {
			return;
		}
		
		TurnType turnType = null;
		int distanceToTurn = 0;
		String streetName = "";
		
		try {
			NextDirectionInfo nextInfo = new NextDirectionInfo();
			routingHelper.getNextRouteDirectionInfo(nextInfo, false);
			
			if (nextInfo.directionInfo != null) {
				turnType = nextInfo.directionInfo.getTurnType();
				distanceToTurn = nextInfo.distanceTo;
				
				if (!Algorithms.isEmpty(nextInfo.directionInfo.getStreetName())) {
					streetName = nextInfo.directionInfo.getStreetName();
				} else if (!Algorithms.isEmpty(nextInfo.directionInfo.getRef())) {
					streetName = nextInfo.directionInfo.getRef();
				} else if (!Algorithms.isEmpty(nextInfo.directionInfo.getDestinationName())) {
					streetName = nextInfo.directionInfo.getDestinationName();
				}
			}
		} catch (Exception e) {
			LOG.warn("Error getting maneuver info", e);
		}
		
		if (turnType == null) {
			return;
		}
		
		boolean distanceChanged = Math.abs(distanceToTurn - lastManeuverDist) > 10;
		boolean typeChanged = !turnType.equals(lastManeuverType);
		
		if (!distanceChanged && !typeChanged) {
			return;
		}
		
		lastManeuverDist = distanceToTurn;
		lastManeuverType = turnType;
		
		int turn = TurnTypeMapper.mapTurnType(turnType);
		int exit = TurnTypeMapper.getExitNumber(turnType);
		
		if (plugin.HUD_DEBUG_LOG.get()) {
			LOG.info("Sending maneuver: turn=" + turn + " dist=" + distanceToTurn + 
				"m street=" + streetName + " (type=" + turnType + ")");
		}
		
		byte[] packet = HudProtocolEncoder.encodeManeuver(
			turn, distanceToTurn, exit, false, false, streetName
		);
		bleClient.write(packet);
	}
	
	private void checkAndSendJunction(@NonNull HudBleClient bleClient, 
	                                   @NonNull Location location,
	                                   @NonNull List<RouteSegmentResult> segments) {
		long now = System.currentTimeMillis();
		if (now - lastJunctionCheckTime < JUNCTION_CHECK_INTERVAL) {
			return;
		}
		lastJunctionCheckTime = now;
		
		try {
			JunctionDetector.JunctionInfo junction = junctionDetector.detectInNavigation(location, segments);
			if (junction != null) {
				byte[] packet = HudProtocolEncoder.encodeJunction(
					junction.type, junction.distanceM, junction.exitNumber
				);
				bleClient.write(packet);
				
				if (plugin.HUD_DEBUG_LOG.get()) {
					LOG.info("Sent junction: " + junction);
				}
			}
		} catch (Exception e) {
			if (plugin.HUD_DEBUG_LOG.get()) {
				LOG.warn("Error checking junction", e);
			}
		}
	}
	
	private void sendTrip(@NonNull HudBleClient bleClient) {
		RoutingHelper routingHelper = app.getRoutingHelper();
		
		int remainingM = routingHelper.getLeftDistance();
		int remainingS = routingHelper.getLeftTime();
		
		long nowMs = System.currentTimeMillis();
		long etaMs = nowMs + remainingS * 1000L;
		
		java.util.Calendar cal = java.util.Calendar.getInstance();
		cal.setTimeInMillis(etaMs);
		int etaMinOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + 
			cal.get(java.util.Calendar.MINUTE);
		
		byte[] packet = HudProtocolEncoder.encodeTrip(remainingM, remainingS, etaMinOfDay, false);
		bleClient.write(packet);
	}
}
