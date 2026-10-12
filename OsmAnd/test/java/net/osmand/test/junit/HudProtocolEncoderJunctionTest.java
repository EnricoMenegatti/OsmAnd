package net.osmand.test.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.osmand.plus.plugins.hud.HudProtocolEncoder;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Instrumentation test for HudProtocolEncoder JUNCTION packet (type 0x13).
 * Verifies the 10-byte packet layout matches firmware spec:
 * u8 type=0x13, u8 kind, u16 LE distM, u8 side, u8 exit, u16 LE routeId, u16 LE sJunction
 */
@RunWith(AndroidJUnit4.class)
public class HudProtocolEncoderJunctionTest {
	
	@Test
	public void testJunctionPacketLayout() {
		// Test case: kind=2 (roundabout), distM=150, side=0, exit=3, routeId=1, sJunction=0xFFFF
		byte[] packet = HudProtocolEncoder.encodeJunction(2, 150, 0, 3, 1, 0xFFFF);
		
		// Verify packet length (10 bytes)
		assertEquals("JUNCTION packet must be 10 bytes", 10, packet.length);
		
		// Verify each field using little-endian byte buffer
		ByteBuffer buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
		
		assertEquals("Byte 0: packet type must be 0x13", (byte) 0x13, buf.get());
		assertEquals("Byte 1: kind must be 2", (byte) 2, buf.get());
		assertEquals("Bytes 2-3: distM must be 150 (LE)", (short) 150, buf.getShort());
		assertEquals("Byte 4: side must be 0", (byte) 0, buf.get());
		assertEquals("Byte 5: exit must be 3", (byte) 3, buf.get());
		assertEquals("Bytes 6-7: routeId must be 1 (LE)", (short) 1, buf.getShort());
		assertEquals("Bytes 8-9: sJunction must be 0xFFFF (LE)", (short) 0xFFFF, buf.getShort());
	}
	
	@Test
	public void testJunctionKindValues() {
		// Test kind=0 (none/ignored)
		byte[] packet0 = HudProtocolEncoder.encodeJunction(0, 100, 0, 0, 1, 0xFFFF);
		assertEquals("Kind 0 (none)", (byte) 0, packet0[1]);
		
		// Test kind=1 (junction, shows T)
		byte[] packet1 = HudProtocolEncoder.encodeJunction(1, 100, 1, 0, 1, 0xFFFF);
		assertEquals("Kind 1 (junction)", (byte) 1, packet1[1]);
		assertEquals("Side 1 (left)", (byte) 1, packet1[4]);
		
		// Test kind=2 (roundabout, shows circle)
		byte[] packet2 = HudProtocolEncoder.encodeJunction(2, 100, 0, 5, 1, 0xFFFF);
		assertEquals("Kind 2 (roundabout)", (byte) 2, packet2[1]);
		assertEquals("Exit 5", (byte) 5, packet2[5]);
		
		// Test kind=3 (road end)
		byte[] packet3 = HudProtocolEncoder.encodeJunction(3, 100, 0, 0, 1, 0xFFFF);
		assertEquals("Kind 3 (road end)", (byte) 3, packet3[1]);
	}
	
	@Test
	public void testJunctionSideValues() {
		// side=0 (straight)
		byte[] packet0 = HudProtocolEncoder.encodeJunction(1, 100, 0, 0, 1, 0xFFFF);
		assertEquals("Side 0 (straight)", (byte) 0, packet0[4]);
		
		// side=1 (left)
		byte[] packet1 = HudProtocolEncoder.encodeJunction(1, 100, 1, 0, 1, 0xFFFF);
		assertEquals("Side 1 (left)", (byte) 1, packet1[4]);
		
		// side=2 (right)
		byte[] packet2 = HudProtocolEncoder.encodeJunction(1, 100, 2, 0, 1, 0xFFFF);
		assertEquals("Side 2 (right)", (byte) 2, packet2[4]);
	}
	
	@Test
	public void testJunctionDistanceLittleEndian() {
		// Test distance encoding in little-endian u16
		byte[] packet = HudProtocolEncoder.encodeJunction(1, 0x1234, 0, 0, 1, 0xFFFF);
		
		// In little-endian, 0x1234 is stored as [0x34, 0x12]
		assertEquals("Distance low byte", (byte) 0x34, packet[2]);
		assertEquals("Distance high byte", (byte) 0x12, packet[3]);
	}
	
	@Test
	public void testJunctionRouteIdAndSJunction() {
		// Test routeId and sJunction encoding
		byte[] packet = HudProtocolEncoder.encodeJunction(1, 100, 0, 0, 0xABCD, 0x5678);
		
		ByteBuffer buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
		buf.position(6); // Skip to routeId field
		
		assertEquals("RouteId must be 0xABCD", (short) 0xABCD, buf.getShort());
		assertEquals("sJunction must be 0x5678", (short) 0x5678, buf.getShort());
	}
	
	@Test
	public void testJunctionMinimumLength() {
		// Firmware requires len >= 10
		byte[] packet = HudProtocolEncoder.encodeJunction(1, 100, 0, 0, 1, 0xFFFF);
		assertTrue("Packet length must be >= 10", packet.length >= 10);
	}
}
