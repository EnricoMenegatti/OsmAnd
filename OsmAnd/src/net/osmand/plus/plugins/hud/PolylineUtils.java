package net.osmand.plus.plugins.hud;

import net.osmand.data.LatLon;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.List;

public class PolylineUtils {
	
	public static List<LatLon> simplify(List<LatLon> points, double toleranceMeters) {
		if (points.size() <= 2) {
			return new ArrayList<>(points);
		}
		
		return douglasPeucker(points, 0, points.size() - 1, toleranceMeters);
	}
	
	private static List<LatLon> douglasPeucker(List<LatLon> points, int start, int end, double tolerance) {
		double maxDist = 0;
		int maxIndex = start;
		
		LatLon startPt = points.get(start);
		LatLon endPt = points.get(end);
		
		for (int i = start + 1; i < end; i++) {
			double dist = perpendicularDistance(points.get(i), startPt, endPt);
			if (dist > maxDist) {
				maxDist = dist;
				maxIndex = i;
			}
		}
		
		List<LatLon> result = new ArrayList<>();
		
		if (maxDist > tolerance) {
			List<LatLon> left = douglasPeucker(points, start, maxIndex, tolerance);
			List<LatLon> right = douglasPeucker(points, maxIndex, end, tolerance);
			
			result.addAll(left.subList(0, left.size() - 1));
			result.addAll(right);
		} else {
			result.add(startPt);
			result.add(endPt);
		}
		
		return result;
	}
	
	private static double perpendicularDistance(LatLon point, LatLon lineStart, LatLon lineEnd) {
		double lat1 = lineStart.getLatitude();
		double lon1 = lineStart.getLongitude();
		double lat2 = lineEnd.getLatitude();
		double lon2 = lineEnd.getLongitude();
		double latP = point.getLatitude();
		double lonP = point.getLongitude();
		
		double A = lonP - lon1;
		double B = latP - lat1;
		double C = lon2 - lon1;
		double D = lat2 - lat1;
		
		double dot = A * C + B * D;
		double lenSq = C * C + D * D;
		
		double param = (lenSq != 0) ? (dot / lenSq) : -1;
		
		double xx, yy;
		
		if (param < 0) {
			xx = lon1;
			yy = lat1;
		} else if (param > 1) {
			xx = lon2;
			yy = lat2;
		} else {
			xx = lon1 + param * C;
			yy = lat1 + param * D;
		}
		
		return MapUtils.getDistance(latP, lonP, yy, xx);
	}
	
	public static List<LatLon> densify(List<LatLon> points, double maxSegmentMeters) {
		List<LatLon> result = new ArrayList<>();
		
		for (int i = 0; i < points.size(); i++) {
			result.add(points.get(i));
			
			if (i < points.size() - 1) {
				LatLon p1 = points.get(i);
				LatLon p2 = points.get(i + 1);
				double dist = MapUtils.getDistance(p1, p2);
				
				int segments = (int) Math.ceil(dist / maxSegmentMeters);
				if (segments > 1) {
					for (int j = 1; j < segments; j++) {
						double t = (double) j / segments;
						double lat = p1.getLatitude() + t * (p2.getLatitude() - p1.getLatitude());
						double lon = p1.getLongitude() + t * (p2.getLongitude() - p1.getLongitude());
						result.add(new LatLon(lat, lon));
					}
				}
			}
		}
		
		return result;
	}
	
	public static double[] computeCumulativeDistances(List<LatLon> points) {
		double[] cumDist = new double[points.size()];
		cumDist[0] = 0;
		
		for (int i = 1; i < points.size(); i++) {
			cumDist[i] = cumDist[i - 1] + MapUtils.getDistance(points.get(i - 1), points.get(i));
		}
		
		return cumDist;
	}
	
	public static int findClosestPoint(List<LatLon> points, LatLon target) {
		if (points.isEmpty()) {
			return -1;
		}
		
		double minDist = Double.MAX_VALUE;
		int minIndex = 0;
		
		for (int i = 0; i < points.size(); i++) {
			double dist = MapUtils.getDistance(points.get(i), target);
			if (dist < minDist) {
				minDist = dist;
				minIndex = i;
			}
		}
		
		return minIndex;
	}
	
	public static ProjectionResult projectOnPolyline(List<LatLon> points, LatLon target) {
		if (points.isEmpty()) {
			return null;
		}
		
		if (points.size() == 1) {
			return new ProjectionResult(0, 0, MapUtils.getDistance(points.get(0), target));
		}
		
		double minDist = Double.MAX_VALUE;
		int bestSegment = 0;
		double bestT = 0;
		
		for (int i = 0; i < points.size() - 1; i++) {
			LatLon p1 = points.get(i);
			LatLon p2 = points.get(i + 1);
			
			double segLen = MapUtils.getDistance(p1, p2);
			if (segLen < 0.1) continue;
			
			double t = 0;
			double lat1 = p1.getLatitude();
			double lon1 = p1.getLongitude();
			double lat2 = p2.getLatitude();
			double lon2 = p2.getLongitude();
			double latT = target.getLatitude();
			double lonT = target.getLongitude();
			
			double dx = lon2 - lon1;
			double dy = lat2 - lat1;
			double dot = (lonT - lon1) * dx + (latT - lat1) * dy;
			double lenSq = dx * dx + dy * dy;
			
			if (lenSq > 0) {
				t = Math.max(0, Math.min(1, dot / lenSq));
			}
			
			double projLat = lat1 + t * (lat2 - lat1);
			double projLon = lon1 + t * (lon2 - lon1);
			double dist = MapUtils.getDistance(latT, lonT, projLat, projLon);
			
			if (dist < minDist) {
				minDist = dist;
				bestSegment = i;
				bestT = t;
			}
		}
		
		return new ProjectionResult(bestSegment, bestT, minDist);
	}
	
	public static class ProjectionResult {
		public final int segmentIndex;
		public final double t;
		public final double distance;
		
		public ProjectionResult(int segmentIndex, double t, double distance) {
			this.segmentIndex = segmentIndex;
			this.t = t;
			this.distance = distance;
		}
	}
}
