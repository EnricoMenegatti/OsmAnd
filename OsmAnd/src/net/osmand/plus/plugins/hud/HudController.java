package net.osmand.plus.plugins.hud;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.RoutingHelper;

import org.apache.commons.logging.Log;

import java.util.Calendar;
import java.util.TimeZone;

public class HudController {
	private static final Log LOG = PlatformUtil.getLog(HudController.class);
	
	public enum State {
		IDLE, NAV, FREE
	}
	
	private final OsmandApplication app;
	private final HudEsp32Plugin plugin;
	private final Handler handler;
	
	private HudBleClient bleClient;
	private State currentState = State.IDLE;
	
	private HudRouteProvider routeProvider;
	private FreeRideRoadFollower freeRideFollower;
	
	private Location lastLocation;
	private long lastPosTime = 0;
	private long lastHeartbeatTime = 0;
	private long lastTimeUpdateTime = 0;
	
	private static final long HEARTBEAT_INTERVAL_MS = 2000;
	private static final long TIME_UPDATE_INTERVAL_MS = 60000;
	
	public HudController(@NonNull OsmandApplication app, @NonNull HudEsp32Plugin plugin,
			@NonNull Handler handler) {
		this.app = app;
		this.plugin = plugin;
		this.handler = handler;
	}
	
	public void init() {
		LOG.info("HudController init starting");
		
		routeProvider = new HudRouteProvider(app, plugin);
		freeRideFollower = new FreeRideRoadFollower(app, plugin);
		
		bleClient = new HudBleClient(
			app,
			handler,
			new HudBleClient.ConnectionListener() {
				@Override
				public void onConnected() {
					LOG.info("HUD connected");
					onBleConnected();
				}
				
				@Override
				public void onDisconnected() {
					LOG.info("HUD disconnected");
				}
				
				@Override
				public void onStatusReceived(byte[] status) {
					if (status == null || status.length < 8) {
						LOG.warn("Invalid STATUS received: length=" + (status != null ? status.length : 0));
						return;
					}
					
					byte lastResult = status[1];
					if (lastResult != 0x00) {
						String errorMsg = decodeStatusError(lastResult);
						LOG.warn("HUD STATUS error: lastResult=0x" + String.format("%02X", lastResult & 0xFF) + 
							" (" + errorMsg + ") - " + HudProtocolEncoder.toHex(status));
					} else if (plugin.HUD_DEBUG_LOG.get()) {
						LOG.debug("STATUS received OK: " + HudProtocolEncoder.toHex(status));
					}
				}
				
				@Override
				public void onConnectionError(String error) {
					LOG.error("HUD connection error: " + error);
				}
			},
			true
		);
		
		LOG.info("HudController starting connection attempt");
		startConnection();
		
		handler.post(this::periodicTasks);
	}
	
	private void startConnection() {
		handler.postDelayed(() -> {
			String address = plugin.HUD_DEVICE_ADDRESS.get();
			String name = plugin.HUD_DEVICE_NAME.get();
			
			if (name == null || name.isEmpty()) {
				name = "HUD-Moto";
			}
			
			LOG.info("HudController connecting: address=" + address + " name=" + name);
			bleClient.connect(address, name);
		}, 3000);
	}
	
	public void reconnect() {
		LOG.info("HudController reconnect requested");
		if (bleClient != null) {
			bleClient.disconnect();
			startConnection();
		}
	}
	
	public void destroy() {
		if (bleClient != null) {
			bleClient.disconnect();
			bleClient = null;
		}
		
		handler.removeCallbacksAndMessages(null);
	}
	
	private void onBleConnected() {
		bleClient.write(HudProtocolEncoder.encodeHello());
		sendTimeUpdate();
		
		RoutingHelper routingHelper = app.getRoutingHelper();
		boolean hasRoute = routingHelper.isRouteCalculated();
		boolean isFollowing = routingHelper.isFollowingMode();
		boolean isPlanning = routingHelper.isRoutePlanningMode();
		
		LOG.info("BLE connected: hasRoute=" + hasRoute + " isFollowing=" + isFollowing + 
			" isPlanning=" + isPlanning);
		
		if (hasRoute && (isFollowing || isPlanning)) {
			transitionTo(State.NAV);
		} else if (plugin.HUD_FREE_RIDE.get() && lastLocation != null) {
			transitionTo(State.FREE);
		} else {
			transitionTo(State.IDLE);
		}
	}
	
	public void onLocationUpdate(@NonNull Location location) {
		lastLocation = location;
		
		if (!bleClient.isConnected()) {
			return;
		}
		
		long now = System.currentTimeMillis();
		int rateHz = plugin.HUD_POS_RATE_HZ.get();
		long minInterval = 1000 / Math.max(1, rateHz);
		
		if (now - lastPosTime < minInterval) {
			return;
		}
		lastPosTime = now;
		
		RoutingHelper routingHelper = app.getRoutingHelper();
		boolean hasRoute = routingHelper.isRouteCalculated();
		boolean isFollowing = routingHelper.isFollowingMode();
		boolean isPlanning = routingHelper.isRoutePlanningMode();
		boolean isNavigating = hasRoute && (isFollowing || isPlanning);
		
		if (isNavigating && currentState != State.NAV) {
			LOG.info("Detected navigation route during location update, transitioning to NAV");
			transitionTo(State.NAV);
		} else if (!isNavigating && currentState == State.NAV) {
			LOG.info("Route ended during location update");
			if (plugin.HUD_FREE_RIDE.get()) {
				transitionTo(State.FREE);
			} else {
				transitionTo(State.IDLE);
			}
		}
		
		switch (currentState) {
			case NAV:
				routeProvider.onLocationUpdate(location, bleClient);
				break;
			case FREE:
				freeRideFollower.onLocationUpdate(location, bleClient);
				break;
			case IDLE:
				if (plugin.HUD_FREE_RIDE.get() && location.hasSpeed() && location.getSpeed() > 2.78f) {
					transitionTo(State.FREE);
				}
				break;
		}
	}
	
	public void onNewRouteCalculated(boolean newRoute) {
		if (bleClient.isConnected()) {
			transitionTo(State.NAV);
			
			if (routeProvider != null) {
				routeProvider.onNewRoute(newRoute);
			}
		}
	}
	
	public void onRouteCancelled() {
		if (plugin.HUD_FREE_RIDE.get()) {
			transitionTo(State.FREE);
		} else {
			transitionTo(State.IDLE);
			if (bleClient.isConnected()) {
				bleClient.write(HudProtocolEncoder.encodeClear());
			}
		}
	}
	
	public void onRouteFinished() {
		if (plugin.HUD_FREE_RIDE.get()) {
			transitionTo(State.FREE);
		} else {
			transitionTo(State.IDLE);
			if (bleClient.isConnected()) {
				bleClient.write(HudProtocolEncoder.encodeClear());
			}
		}
	}
	
	private void transitionTo(State newState) {
		if (newState == currentState) {
			return;
		}
		
		LOG.info("State transition: " + currentState + " -> " + newState);
		
		State oldState = currentState;
		currentState = newState;
		
		if (oldState == State.NAV && routeProvider != null) {
			routeProvider.onStateExit();
		} else if (oldState == State.FREE && freeRideFollower != null) {
			freeRideFollower.onStateExit();
		}
		
		if (newState == State.NAV && routeProvider != null) {
			routeProvider.onStateEnter(lastLocation, bleClient);
		} else if (newState == State.FREE && freeRideFollower != null) {
			freeRideFollower.onStateEnter(lastLocation, bleClient);
		}
	}
	
	private void periodicTasks() {
		long now = System.currentTimeMillis();
		
		if (bleClient.isConnected()) {
			checkAndUpdateState();
			
			if (now - lastHeartbeatTime >= HEARTBEAT_INTERVAL_MS) {
				lastHeartbeatTime = now;
				int state = currentState == State.NAV ? 1 : (currentState == State.FREE ? 2 : 0);
				bleClient.write(HudProtocolEncoder.encodeHeartbeat(state));
			}
			
			if (now - lastTimeUpdateTime >= TIME_UPDATE_INTERVAL_MS) {
				sendTimeUpdate();
			}
		}
		
		handler.postDelayed(this::periodicTasks, 500);
	}
	
	private void checkAndUpdateState() {
		RoutingHelper routingHelper = app.getRoutingHelper();
		boolean hasRoute = routingHelper.isRouteCalculated();
		boolean isFollowing = routingHelper.isFollowingMode();
		boolean isPlanning = routingHelper.isRoutePlanningMode();
		boolean isNavigating = hasRoute && (isFollowing || isPlanning);
		
		if (plugin.HUD_DEBUG_LOG.get()) {
			LOG.debug("checkState: hasRoute=" + hasRoute + " isFollowing=" + isFollowing + 
				" isPlanning=" + isPlanning + " currentState=" + currentState);
		}
		
		if (isNavigating && currentState != State.NAV) {
			LOG.info("Periodic check detected navigation, transitioning to NAV");
			transitionTo(State.NAV);
		} else if (!isNavigating && currentState == State.NAV) {
			LOG.info("Periodic check detected route ended");
			if (plugin.HUD_FREE_RIDE.get()) {
				transitionTo(State.FREE);
			} else {
				transitionTo(State.IDLE);
			}
		} else if (currentState == State.IDLE && plugin.HUD_FREE_RIDE.get() && lastLocation != null) {
			if (lastLocation.hasSpeed() && lastLocation.getSpeed() > 2.78f) {
				transitionTo(State.FREE);
			}
		}
	}
	
	private void sendTimeUpdate() {
		if (!bleClient.isConnected()) return;
		
		Calendar cal = Calendar.getInstance();
		long unixSeconds = cal.getTimeInMillis() / 1000;
		int offsetMinutes = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60000;
		
		bleClient.write(HudProtocolEncoder.encodeTime(unixSeconds, offsetMinutes));
		lastTimeUpdateTime = System.currentTimeMillis();
	}
	
	private String decodeStatusError(byte errorCode) {
		switch (errorCode & 0xFF) {
			case 0x00: return "OK";
			case 0x01: return "ErrGeneric";
			case 0x02: return "ErrType";
			case 0x03: return "ErrIndex";
			case 0x04: return "ErrState";
			case 0x05: return "ErrBounds";
			case 0x06: return "ErrCrc";
			case 0x07: return "ErrTimeout";
			case 0x08: return "ErrOverflow";
			case 0x09: return "ErrInvalid";
			case 0x0A: return "ErrNoData";
			case 0x0B: return "ErrShort (packet truncated/too short)";
			case 0x0C: return "ErrLong";
			case 0x0D: return "ErrSequence";
			case 0x0E: return "ErrCount (point count mismatch)";
			case 0x0F: return "ErrFormat";
			default: return "Unknown error 0x" + String.format("%02X", errorCode & 0xFF);
		}
	}
	
	public State getCurrentState() {
		return currentState;
	}
	
	public boolean isConnected() {
		return bleClient != null && bleClient.isConnected();
	}
	
	public HudBleClient getBleClient() {
		return bleClient;
	}
}
