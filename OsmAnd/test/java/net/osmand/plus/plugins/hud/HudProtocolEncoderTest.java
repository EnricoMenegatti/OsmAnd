package net.osmand.plus.plugins.hud;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

public class HudProtocolEncoderTest {
	
	@Test
	public void testHelloPacket() {
		byte[] packet = HudProtocolEncoder.encodeHello();
		
		assertEquals(4, packet.length);
		assertEquals(0x10, packet[0] & 0xFF);
		assertEquals(2, packet[1] & 0xFF);
		assertEquals(0x1F, packet[2] & 0xFF);
		assertEquals(0, packet[3] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertEquals("10 02 1F 00", hex);
	}
	
	@Test
	public void testRouteMetaPacket() {
		byte[] packet = HudProtocolEncoder.encodeRouteMeta(42, 0, false, false, 100, 1500);
		
		assertEquals(9, packet.length);
		assertEquals(0x11, packet[0] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertEquals("11 2A 00 00 00 64 00 DC 05", hex);
	}
	
	@Test
	public void testBeginPacket() {
		byte[] packet = HudProtocolEncoder.encodeBegin(2, 45.4642035, 9.1899820);
		
		assertEquals(11, packet.length);
		assertEquals(0x01, packet[0] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertEquals("01 02 00 73 49 19 1B AC 47 7A 05", hex);
	}
	
	@Test
	public void testEndPacket() {
		byte[] packet = HudProtocolEncoder.encodeEnd(2);
		
		assertEquals(3, packet.length);
		assertEquals(0x03, packet[0] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertEquals("03 02 00", hex);
	}
	
	@Test
	public void testPosPacket() {
		byte[] packet = HudProtocolEncoder.encodePos(45.4642035, 9.1899820, 300, 900);
		
		assertEquals(13, packet.length);
		assertEquals(0x04, packet[0] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertEquals("04 73 49 19 1B AC 47 7A 05 2C 01 84 03", hex);
	}
	
	@Test
	public void testClearPacket() {
		byte[] packet = HudProtocolEncoder.encodeClear();
		
		assertEquals(1, packet.length);
		assertEquals(0x05, packet[0] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertEquals("05", hex);
	}
	
	@Test
	public void testJunctionPacket() {
		byte[] packet = HudProtocolEncoder.encodeJunction(2, 80, 0, 2, 42, 576);
		
		assertEquals(10, packet.length);
		assertEquals(0x13, packet[0] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertEquals("13 02 50 00 00 02 2A 00 40 02", hex);
	}
	
	@Test
	public void testManeuverPacket() {
		byte[] packet = HudProtocolEncoder.encodeManeuver(3, 300, 0, false, false, "Via Roma");
		
		assertEquals(15, packet.length);
		assertEquals(0x14, packet[0] & 0xFF);
		assertEquals(3, packet[1] & 0xFF);
		assertEquals(8, packet[5] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertEquals("14 03 2C 01 00 00 08 56 69 61 20 52 6F 6D 61", hex);
	}
	
	@Test
	public void testHeartbeatPacket() {
		byte[] packet = HudProtocolEncoder.encodeHeartbeat(1);
		
		assertEquals(2, packet.length);
		assertEquals(0x16, packet[0] & 0xFF);
		assertEquals(1, packet[1] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertEquals("16 01", hex);
	}
	
	@Test
	public void testTimePacket() {
		byte[] packet = HudProtocolEncoder.encodeTime(1704067200L, 120);
		
		assertEquals(7, packet.length);
		assertEquals(0x17, packet[0] & 0xFF);
		
		String hex = HudProtocolEncoder.toHex(packet);
		assertTrue(hex.startsWith("17"));
		assertTrue(hex.endsWith("78 00"));
	}
	
	@Test
	public void testPointsEncoding() {
		List<HudProtocolEncoder.LatLon> points = new ArrayList<>();
		points.add(new HudProtocolEncoder.LatLon(45.464, 9.190));
		points.add(new HudProtocolEncoder.LatLon(45.465, 9.191));
		
		List<byte[]> packets = HudProtocolEncoder.encodePoints(points, 0, 247);
		
		assertEquals(1, packets.size());
		byte[] packet = packets.get(0);
		
		assertEquals(0x02, packet[0] & 0xFF);
		assertEquals(0, packet[1] & 0xFF);
		assertEquals(2, packet[3] & 0xFF);
	}
	
	@Test
	public void testLatLonConversion() {
		double lat = 45.4642035;
		double lon = 9.1899820;
		
		List<HudProtocolEncoder.LatLon> points = new ArrayList<>();
		points.add(new HudProtocolEncoder.LatLon(lat, lon));
		
		byte[] begin = HudProtocolEncoder.encodeBegin(1, lat, lon);
		byte[] pos = HudProtocolEncoder.encodePos(lat, lon, 0, 0);
		
		int latInt1 = ((begin[3] & 0xFF)) | ((begin[4] & 0xFF) << 8) | 
			((begin[5] & 0xFF) << 16) | (begin[6] << 24);
		int latInt2 = ((pos[1] & 0xFF)) | ((pos[2] & 0xFF) << 8) | 
			((pos[3] & 0xFF) << 16) | (pos[4] << 24);
		
		assertEquals(latInt1, latInt2);
	}
	
	@Test
	public void testManeuverStringTruncation() {
		StringBuilder longStreet = new StringBuilder();
		for (int i = 0; i < 100; i++) {
			longStreet.append("A");
		}
		
		byte[] packet = HudProtocolEncoder.encodeManeuver(3, 100, 0, false, false, longStreet.toString());
		
		int len = packet[5] & 0xFF;
		assertEquals(64, len);
		assertEquals(71, packet.length);
	}
	
	@Test
	public void testDistanceSaturation() {
		byte[] packet = HudProtocolEncoder.encodeManeuver(1, 100000, 0, false, false, "Test");
		
		int dist = ((packet[2] & 0xFF)) | ((packet[3] & 0xFF) << 8);
		assertEquals(65535, dist);
	}
	
	@Test
	public void testNegativeCoordinates() {
		double lat = -33.8688;
		double lon = 151.2093;
		
		byte[] packet = HudProtocolEncoder.encodePos(lat, lon, 100, 900);
		
		assertEquals(13, packet.length);
		assertEquals(0x04, packet[0] & 0xFF);
	}
}
