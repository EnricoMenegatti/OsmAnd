package net.osmand.plus.plugins.hud;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class HudProtocolEncoder {
	
	public static final int PROTO_VERSION = 2;
	
	public static final byte PKT_BEGIN = 0x01;
	public static final byte PKT_POINTS = 0x02;
	public static final byte PKT_END = 0x03;
	public static final byte PKT_POS = 0x04;
	public static final byte PKT_CLEAR = 0x05;
	
	public static final byte PKT_HELLO = 0x10;
	public static final byte PKT_ROUTE_META = 0x11;
	public static final byte PKT_POINTS_DELTA = 0x12;
	public static final byte PKT_JUNCTION = 0x13;
	public static final byte PKT_MANEUVER = 0x14;
	public static final byte PKT_POS2 = 0x15;
	public static final byte PKT_HEARTBEAT = 0x16;
	public static final byte PKT_TIME = 0x17;
	public static final byte PKT_TRIP = 0x18;
	
	public static final int CAP_MANEUVER = 0x01;
	public static final int CAP_JUNCTION = 0x02;
	public static final int CAP_POS2 = 0x04;
	public static final int CAP_TRIP = 0x08;
	public static final int CAP_TIME = 0x10;
	
	public static byte[] encodeHello() {
		ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_HELLO);
		buf.put((byte) PROTO_VERSION);
		buf.put((byte) (CAP_MANEUVER | CAP_JUNCTION | CAP_POS2 | CAP_TRIP | CAP_TIME));
		buf.put((byte) 0);
		return buf.array();
	}
	
	public static byte[] encodeRouteMeta(int routeId, int source, boolean recalculated, 
			boolean nextWindow, int backM, int totalM) {
		ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_ROUTE_META);
		buf.putShort((short) routeId);
		buf.put((byte) source);
		int flags = 0;
		if (recalculated) flags |= 0x01;
		if (nextWindow) flags |= 0x02;
		buf.put((byte) flags);
		buf.putShort((short) Math.min(backM, 65535));
		buf.putShort((short) Math.min(totalM, 65535));
		return buf.array();
	}
	
	public static byte[] encodeBegin(int nPoints, double lat0, double lon0) {
		ByteBuffer buf = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_BEGIN);
		buf.putShort((short) nPoints);
		buf.putInt(latLonToInt(lat0));
		buf.putInt(latLonToInt(lon0));
		return buf.array();
	}
	
	public static List<byte[]> encodePoints(List<LatLon> points, int startIndex, int mtu) {
		List<byte[]> packets = new ArrayList<>();
		int maxPointsPerPacket = (mtu - 3 - 4) / 8;
		
		for (int i = 0; i < points.size(); ) {
			int n = Math.min(maxPointsPerPacket, points.size() - i);
			ByteBuffer buf = ByteBuffer.allocate(4 + 8 * n).order(ByteOrder.LITTLE_ENDIAN);
			buf.put(PKT_POINTS);
			buf.putShort((short) (startIndex + i));
			buf.put((byte) n);
			
			for (int j = 0; j < n; j++) {
				LatLon pt = points.get(i + j);
				buf.putInt(latLonToInt(pt.lat));
				buf.putInt(latLonToInt(pt.lon));
			}
			
			packets.add(buf.array());
			i += n;
		}
		
		return packets;
	}
	
	/**
	 * Encode route points as delta offsets.
	 * CRITICAL: Each point's delta is relative to the PREVIOUS point, not the origin.
	 * First point (index 0) is relative to origin (typically dx=0, dy=0).
	 * Coordinate system: x = East (positive to east), y = North (positive to north)
	 * Units: DECIMETERS (firmware expects dm, applied as lastLocalPt += d/10 meters)
	 * @param points Route points (lat/lon in degrees), starting from origin (point 0)
	 * @param originLat Origin latitude in degrees
	 * @param originLon Origin longitude in degrees
	 * @param startIndex Starting point index
	 * @param mtu Maximum ATT MTU size
	 * @return List of PKT_POINTS_DELTA packets
	 */
	public static List<byte[]> encodePointsDelta(List<LatLon> points, double originLat, double originLon, 
			int startIndex, int mtu) {
		List<byte[]> packets = new ArrayList<>();
		
		int attPayload = mtu - 3;
		int packetOverhead = 4;
		int maxPointsPerPacket = (attPayload - packetOverhead) / 4;
		maxPointsPerPacket = Math.min(maxPointsPerPacket, 56);
		
		double prevLat = originLat;
		double prevLon = originLon;
		
		int pointsToSkip = 1;
		
		for (int i = pointsToSkip; i < points.size(); ) {
			int n = Math.min(maxPointsPerPacket, points.size() - i);
			ByteBuffer buf = ByteBuffer.allocate(4 + 4 * n).order(ByteOrder.LITTLE_ENDIAN);
			buf.put(PKT_POINTS_DELTA);
			buf.putShort((short) (startIndex + i - pointsToSkip));
			buf.put((byte) n);
			
			for (int j = 0; j < n; j++) {
				LatLon pt = points.get(i + j);
				
				double dLat = pt.lat - prevLat;
				double dLon = pt.lon - prevLon;
				
				double dNorthM = dLat * 111320.0;
				double cosLat = Math.cos(Math.toRadians(prevLat));
				double dEastM = dLon * 111320.0 * cosLat;
				
				short dx = (short) Math.round(dEastM * 10.0);
				short dy = (short) Math.round(dNorthM * 10.0);
				
				buf.putShort(dx);
				buf.putShort(dy);
				
				prevLat = pt.lat;
				prevLon = pt.lon;
			}
			
			packets.add(buf.array());
			i += n;
		}
		
		return packets;
	}
	
	public static byte[] encodeEnd(int nPoints) {
		ByteBuffer buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_END);
		buf.putShort((short) nPoints);
		return buf.array();
	}
	
	public static byte[] encodePos(double lat, double lon, int speedKmh10, int heading10) {
		ByteBuffer buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_POS);
		buf.putInt(latLonToInt(lat));
		buf.putInt(latLonToInt(lon));
		buf.putShort((short) Math.max(0, speedKmh10));
		buf.putShort((short) ((heading10 % 3600 + 3600) % 3600));
		return buf.array();
	}
	
	public static byte[] encodeClear() {
		return new byte[] { PKT_CLEAR };
	}
	
	public static byte[] encodeJunction(int kind, int distM, int side, int exit, 
			int routeId, int sJunction) {
		ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_JUNCTION);
		buf.put((byte) kind);
		buf.putShort((short) Math.min(distM, 65535));
		buf.put((byte) side);
		buf.put((byte) exit);
		buf.putShort((short) routeId);
		buf.putShort((short) Math.min(sJunction, 65535));
		return buf.array();
	}
	
	public static byte[] encodeManeuver(int turn, int distM, int exit, boolean arrivalImminent, 
			boolean offRoute, @NonNull String street) {
		byte[] streetBytes = street.getBytes(StandardCharsets.UTF_8);
		int len = Math.min(streetBytes.length, 64);
		
		ByteBuffer buf = ByteBuffer.allocate(7 + len).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_MANEUVER);
		buf.put((byte) turn);
		buf.putShort((short) Math.min(distM, 65535));
		buf.put((byte) exit);
		int flags = 0;
		if (arrivalImminent) flags |= 0x01;
		if (offRoute) flags |= 0x02;
		buf.put((byte) flags);
		buf.put((byte) len);
		buf.put(streetBytes, 0, len);
		return buf.array();
	}
	
	public static byte[] encodePos2(double lat, double lon, int speedKmh10, int heading10,
			int accuracyM, boolean snapped, boolean simulated, boolean tunnel,
			int routeId, int sOnRoute) {
		ByteBuffer buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_POS2);
		buf.putInt(latLonToInt(lat));
		buf.putInt(latLonToInt(lon));
		buf.putShort((short) Math.max(0, speedKmh10));
		buf.putShort((short) ((heading10 % 3600 + 3600) % 3600));
		buf.put((byte) Math.min(accuracyM, 255));
		int flags = 0;
		if (snapped) flags |= 0x01;
		if (simulated) flags |= 0x02;
		if (tunnel) flags |= 0x04;
		buf.put((byte) flags);
		buf.putShort((short) routeId);
		buf.putShort((short) Math.min(sOnRoute, 65535));
		return buf.array();
	}
	
	public static byte[] encodeHeartbeat(int state) {
		return new byte[] { PKT_HEARTBEAT, (byte) state };
	}
	
	public static byte[] encodeTime(long unixSeconds, int offsetMinutes) {
		ByteBuffer buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_TIME);
		buf.putInt((int) unixSeconds);
		buf.putShort((short) offsetMinutes);
		return buf.array();
	}
	
	public static byte[] encodeTrip(int remainingM, int remainingS, int etaMinOfDay, boolean arrived) {
		ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		buf.put(PKT_TRIP);
		buf.putInt(remainingM);
		buf.putInt(remainingS);
		buf.putShort((short) Math.min(etaMinOfDay, 65535));
		buf.put((byte) (arrived ? 0x01 : 0x00));
		return buf.array();
	}
	
	private static int latLonToInt(double degrees) {
		return (int) Math.round(degrees * 1e7);
	}
	
	public static class LatLon {
		public final double lat;
		public final double lon;
		
		public LatLon(double lat, double lon) {
			this.lat = lat;
			this.lon = lon;
		}
	}
	
	public static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02X ", b & 0xFF));
		}
		return sb.toString().trim();
	}
	
	/**
	 * Decode packet for logging purposes.
	 * Returns human-readable string with packet type and key fields.
	 */
	public static String decodeForLog(byte[] packet) {
		if (packet == null || packet.length == 0) {
			return "empty";
		}
		
		ByteBuffer buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
		byte type = buf.get();
		
		StringBuilder sb = new StringBuilder();
		
		switch (type) {
			case PKT_HELLO:
				sb.append("HELLO len=").append(packet.length);
				if (packet.length >= 3) {
					sb.append(" ver=").append(packet[1] & 0xFF);
					sb.append(" caps=0x").append(String.format("%02X", packet[2] & 0xFF));
				}
				break;
				
			case PKT_BEGIN:
				sb.append("BEGIN len=").append(packet.length);
				if (packet.length >= 11) {
					short nPoints = buf.getShort();
					int lat0 = buf.getInt();
					int lon0 = buf.getInt();
					sb.append(" nPoints=").append(nPoints & 0xFFFF);
					sb.append(" origin=").append(lat0 / 1e7).append(",").append(lon0 / 1e7);
				}
				break;
				
			case PKT_POINTS:
				sb.append("POINTS len=").append(packet.length);
				if (packet.length >= 4) {
					short idx = buf.getShort();
					byte n = buf.get();
					sb.append(" startIdx=").append(idx & 0xFFFF).append(" n=").append(n & 0xFF);
				}
				break;
				
			case PKT_POINTS_DELTA:
				sb.append("POINTS_DELTA len=").append(packet.length);
				if (packet.length >= 4) {
					short idx = buf.getShort();
					byte n = buf.get();
					sb.append(" firstIdx=").append(idx & 0xFFFF).append(" n=").append(n & 0xFF);
					int expectedLen = 4 + (n & 0xFF) * 4;
					if (packet.length != expectedLen) {
						sb.append(" INVALID_LEN(expected=").append(expectedLen).append(")");
					}
					if (packet.length >= 8) {
						short dx0 = buf.getShort();
						short dy0 = buf.getShort();
						sb.append(" first_dx=").append(dx0).append("dm (").append(dx0/10.0).append("m)");
						sb.append(" dy=").append(dy0).append("dm (").append(dy0/10.0).append("m)");
					}
				}
				break;
				
			case PKT_END:
				sb.append("END len=").append(packet.length);
				if (packet.length >= 3) {
					short nPoints = buf.getShort();
					sb.append(" nPoints=").append(nPoints & 0xFFFF);
				}
				break;
				
			case PKT_POS:
				sb.append("POS len=").append(packet.length);
				if (packet.length >= 13) {
					int lat = buf.getInt();
					int lon = buf.getInt();
					short speed = buf.getShort();
					short heading = buf.getShort();
					sb.append(" lat=").append(lat / 1e7).append(" lon=").append(lon / 1e7);
					sb.append(" spd=").append((speed & 0xFFFF) / 36.0).append("m/s");
					sb.append(" hdg=").append((heading & 0xFFFF) / 10.0).append("°");
				}
				break;
				
			case PKT_POS2:
				sb.append("POS2 len=").append(packet.length);
				if (packet.length >= 19) {
					int lat = buf.getInt();
					int lon = buf.getInt();
					short speed = buf.getShort();
					short heading = buf.getShort();
					byte accuracy = buf.get();
					byte flags = buf.get();
					short routeId = buf.getShort();
					short sOnRoute = buf.getShort();
					sb.append(" lat=").append(lat / 1e7).append(" lon=").append(lon / 1e7);
					sb.append(" spd=").append((speed & 0xFFFF) / 36.0).append("m/s");
					sb.append(" hdg=").append((heading & 0xFFFF) / 10.0).append("°");
					sb.append(" acc=").append(accuracy & 0xFF).append("m");
					sb.append(" flags=0x").append(String.format("%02X", flags & 0xFF));
					sb.append(" routeId=").append(routeId & 0xFFFF);
				}
				break;
				
			case PKT_MANEUVER:
				sb.append("MANEUVER len=").append(packet.length);
				if (packet.length >= 6) {
					byte turn = buf.get();
					short distM = buf.getShort();
					byte exit = buf.get();
					byte flags = buf.get();
					byte strLen = buf.get();
					sb.append(" turn=").append(turn & 0xFF);
					sb.append(" dist=").append(distM & 0xFFFF).append("m");
					sb.append(" exit=").append(exit & 0xFF);
					if (strLen > 0 && packet.length >= 7 + (strLen & 0xFF)) {
						byte[] strBytes = new byte[strLen & 0xFF];
						buf.get(strBytes);
						String street = new String(strBytes, StandardCharsets.UTF_8);
						sb.append(" street=\"").append(street).append("\"");
					}
				}
				break;
				
			case PKT_TRIP:
				sb.append("TRIP len=").append(packet.length);
				if (packet.length >= 12) {
					int remainingM = buf.getInt();
					int remainingS = buf.getInt();
					short etaMinOfDay = buf.getShort();
					byte arrived = buf.get();
					sb.append(" remainM=").append(remainingM);
					sb.append(" remainS=").append(remainingS);
					sb.append(" eta=").append(etaMinOfDay & 0xFFFF).append("min");
				}
				break;
				
			case PKT_JUNCTION:
				sb.append("JUNCTION");
				if (packet.length >= 10) {
					buf.get();
					byte kind = buf.get();
					short distM = buf.getShort();
					byte side = buf.get();
					byte exit = buf.get();
					short rId = buf.getShort();
					short sJct = buf.getShort();
					
					String kindStr = switch (kind & 0xFF) {
						case 0 -> "none";
						case 1 -> "junction";
						case 2 -> "roundabout";
						case 3 -> "road-end";
						default -> "?";
					};
					
					sb.append(" kind=").append(kindStr);
					sb.append(" dist=").append(distM & 0xFFFF).append("m");
					sb.append(" side=").append(side & 0xFF);
					sb.append(" exit=").append(exit & 0xFF);
					sb.append(" routeId=").append(rId & 0xFFFF);
					if ((sJct & 0xFFFF) != 0xFFFF) {
						sb.append(" sJunction=").append(sJct & 0xFFFF);
					}
				}
				sb.append(" len=").append(packet.length);
				break;
				
			case PKT_ROUTE_META:
				sb.append("ROUTE_META len=").append(packet.length);
				if (packet.length >= 9) {
					short routeId = buf.getShort();
					byte source = buf.get();
					byte flags = buf.get();
					short backM = buf.getShort();
					short totalM = buf.getShort();
					sb.append(" routeId=").append(routeId & 0xFFFF);
					sb.append(" src=").append(source & 0xFF);
					sb.append(" flags=0x").append(String.format("%02X", flags & 0xFF));
					sb.append(" back=").append(backM & 0xFFFF).append("m");
					sb.append(" total=").append(totalM & 0xFFFF).append("m");
				}
				break;
				
			case PKT_TIME:
				sb.append("TIME len=").append(packet.length);
				if (packet.length >= 7) {
					int unixSeconds = buf.getInt();
					short offsetMinutes = buf.getShort();
					sb.append(" unix=").append(unixSeconds);
					sb.append(" offset=").append(offsetMinutes).append("min");
				}
				break;
				
			case PKT_HEARTBEAT:
				sb.append("HEARTBEAT len=").append(packet.length);
				if (packet.length >= 2) {
					sb.append(" state=").append(packet[1] & 0xFF);
				}
				break;
				
			case PKT_CLEAR:
				sb.append("CLEAR len=").append(packet.length);
				break;
				
			default:
				sb.append("UNKNOWN type=0x").append(String.format("%02X", type & 0xFF));
				sb.append(" len=").append(packet.length);
				break;
		}
		
		return sb.toString();
	}
}
