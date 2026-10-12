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
	 * Encode JUNCTION packet (type 0x13).
	 * @param type 0 = normal junction, 1 = roundabout
	 * @param distanceM Distance to junction in meters (uint16 LE)
	 * @param exitNumber For roundabouts: exit number (1-based), 0 for normal junctions
	 */
	public static byte[] encodeJunction(int type, int distanceM, int exitNumber) {
		byte[] packet = new byte[6];
		packet[0] = (byte) 0x13;
		packet[1] = 4;
		packet[2] = (byte) type;
		packet[3] = (byte) exitNumber;
		packet[4] = (byte) (distanceM & 0xFF);
		packet[5] = (byte) ((distanceM >> 8) & 0xFF);
		return packet;
	}
}
