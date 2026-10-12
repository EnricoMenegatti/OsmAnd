package net.osmand.plus.plugins.hud;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.router.RouteSegmentResult;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.util.List;

/**
 * Detects junctions and roundabouts ahead on the route or current road.
 * Generates JUNCTION packets per HUD ESP32 protocol v2 spec.
 */
public class JunctionDetector {
	private static final Log LOG = PlatformUtil.getLog(JunctionDetector.class);
	
	private final OsmandApplication app;
	private final HudEsp32Plugin plugin;
	
	// Junction types per protocol spec
	public static final int JUNCTION_TYPE_NORMAL = 0;
	public static final int JUNCTION_TYPE_ROUNDABOUT = 1;
	
	// Detection thresholds
	private static final double JUNCTION_AHEAD_DISTANCE_M = 200.0;
	private static final int MIN_CONNECTED_ROADS_FOR_JUNCTION = 3;
	
	public JunctionDetector(@NonNull OsmandApplication app, @NonNull HudEsp32Plugin plugin) {
		this.app = app;
		this.plugin = plugin;
	}
	
	/**
	 * Detect junction ahead in navigation mode from route segments.
	 * @param currentLocation Current GPS location
	 * @param routeSegments Remaining route segments
	 * @return Junction info or null if none detected
	 */
	@Nullable
	public JunctionInfo detectInNavigation(@NonNull Location currentLocation, 
	                                       @NonNull List<RouteSegmentResult> routeSegments) {
		if (routeSegments.isEmpty()) {
			return null;
		}
		
		try {
			double currentLat = currentLocation.getLatitude();
			double currentLon = currentLocation.getLongitude();
			double coveredDist = 0;
			
			for (RouteSegmentResult segment : routeSegments) {
				RouteDataObject road = segment.getObject();
				if (road == null) {
					continue;
				}
				
				// Check if this segment is a roundabout
				if (road.roundabout()) {
					// Calculate distance to roundabout entry
					int startIdx = segment.getStartPointIndex();
					double lat = road.getPoint31YTile(startIdx) / (double) (1 << 31) * 180.0;
					double lon = road.getPoint31XTile(startIdx) / (double) (1 << 31) * 360.0 - 180.0;
					
					double distToEntry = MapUtils.getDistance(currentLat, currentLon, lat, lon);
					
					if (distToEntry <= JUNCTION_AHEAD_DISTANCE_M && distToEntry >= 10) {
						return new JunctionInfo(
							JUNCTION_TYPE_ROUNDABOUT,
							(int) distToEntry,
							calculateExitNumber(segment, routeSegments)
						);
					}
				}
				
				// Calculate segment distance
				for (int i = segment.getStartPointIndex(); i < segment.getEndPointIndex(); i++) {
					double lat1 = road.getPoint31YTile(i) / (double) (1 << 31) * 180.0;
					double lon1 = road.getPoint31XTile(i) / (double) (1 << 31) * 360.0 - 180.0;
					double lat2 = road.getPoint31YTile(i + 1) / (double) (1 << 31) * 180.0;
					double lon2 = road.getPoint31XTile(i + 1) / (double) (1 << 31) * 360.0 - 180.0;
					
					coveredDist += MapUtils.getDistance(lat1, lon1, lat2, lon2);
				}
				
				if (coveredDist > JUNCTION_AHEAD_DISTANCE_M) {
					break;
				}
			}
			
		} catch (Exception e) {
			if (plugin.HUD_DEBUG_LOG.get()) {
				LOG.warn("Error detecting junction in navigation", e);
			}
		}
		
		return null;
	}
	
	/**
	 * Detect junction ahead in free ride mode from current road.
	 * @param currentLocation Current GPS location
	 * @param currentRoad Current road object
	 * @return Junction info or null if none detected
	 */
	@Nullable
	public JunctionInfo detectInFreeRide(@NonNull Location currentLocation,
	                                     @Nullable RouteDataObject currentRoad) {
		if (currentRoad == null) {
			return null;
		}
		
		try {
			// Check if current road is roundabout
			if (currentRoad.roundabout()) {
				return new JunctionInfo(JUNCTION_TYPE_ROUNDABOUT, 50, 0);
			}
			
			// In free ride, we detect junctions by road endpoints
			// This is simplified - full implementation would need road network graph
			int pointCount = currentRoad.getPointsLength();
			if (pointCount < 2) {
				return null;
			}
			
			double currentLat = currentLocation.getLatitude();
			double currentLon = currentLocation.getLongitude();
			
			// Check distance to road endpoint (potential junction)
			double lat = currentRoad.getPoint31YTile(pointCount - 1) / (double) (1 << 31) * 180.0;
			double lon = currentRoad.getPoint31XTile(pointCount - 1) / (double) (1 << 31) * 360.0 - 180.0;
			
			double distToEnd = MapUtils.getDistance(currentLat, currentLon, lat, lon);
			
			if (distToEnd <= JUNCTION_AHEAD_DISTANCE_M && distToEnd >= 10) {
				// Assume normal junction at road end
				return new JunctionInfo(JUNCTION_TYPE_NORMAL, (int) distToEnd, 0);
			}
			
		} catch (Exception e) {
			if (plugin.HUD_DEBUG_LOG.get()) {
				LOG.warn("Error detecting junction in free ride", e);
			}
		}
		
		return null;
	}
	
	/**
	 * Calculate roundabout exit number from route segments.
	 */
	private int calculateExitNumber(RouteSegmentResult roundaboutSegment, 
	                                 List<RouteSegmentResult> allSegments) {
		// Find where we exit the roundabout
		int segmentIndex = allSegments.indexOf(roundaboutSegment);
		int exitCount = 0;
		
		for (int i = segmentIndex; i < allSegments.size(); i++) {
			RouteSegmentResult seg = allSegments.get(i);
			if (seg.getObject() != null && seg.getObject().roundabout()) {
				// Count exits while in roundabout
				// Simplified: assume each segment change is potential exit
				exitCount++;
			} else {
				// Exited roundabout
				break;
			}
		}
		
		return Math.max(1, exitCount);
	}
	
	/**
	 * Junction information for JUNCTION packet generation.
	 */
	public static class JunctionInfo {
		public final int type; // 0 = normal, 1 = roundabout
		public final int distanceM;
		public final int exitNumber; // For roundabouts, 0 for normal junctions
		
		public JunctionInfo(int type, int distanceM, int exitNumber) {
			this.type = type;
			this.distanceM = distanceM;
			this.exitNumber = exitNumber;
		}
		
		@Override
		public String toString() {
			return "Junction{type=" + (type == JUNCTION_TYPE_ROUNDABOUT ? "roundabout" : "normal") +
			       ", dist=" + distanceM + "m, exit=" + exitNumber + "}";
		}
	}
}
