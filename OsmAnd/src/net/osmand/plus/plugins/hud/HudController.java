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
					if (plugin.HUD_DEBUG_LOG.get()) {
						LOG.debug("STATUS received: " + HudProtocolEncoder.toHex(status));
					}
				}
				
				@Override
				public void onConnectionError(String error) {
					LOG.error("HUD connection error: " + error);
				}
			},
			plugin.HUD_DEBUG_LOG.get()
		);
		
		routeProvider = new HudRouteProvider(app, plugin);
		freeRideFollower = new FreeRideRoadFollower(app, plugin);
		
		String address = plugin.HUD_DEVICE_ADDRESS.get();
		String name = plugin.HUD_DEVICE_NAME.get();
		
		if (address != null || name != null) {
			bleClient.connect(address, name != null ? name : "HUD-Moto");
		}
		
		handler.post(this::periodicTasks);
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
		if (routingHelper.isRouteCalculated() && routingHelper.isFollowingMode()) {
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
		boolean isNavigating = routingHelper.isRouteCalculated() && routingHelper.isFollowingMode();
		
		if (isNavigating && currentState != State.NAV) {
			transitionTo(State.NAV);
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
	
	private void sendTimeUpdate() {
		if (!bleClient.isConnected()) return;
		
		Calendar cal = Calendar.getInstance();
		long unixSeconds = cal.getTimeInMillis() / 1000;
		int offsetMinutes = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60000;
		
		bleClient.write(HudProtocolEncoder.encodeTime(unixSeconds, offsetMinutes));
		lastTimeUpdateTime = System.currentTimeMillis();
	}
	
	public State getCurrentState() {
		return currentState;
	}
	
	public boolean isConnected() {
		return bleClient != null && bleClient.isConnected();
	}
	
	public void reconnect() {
		if (bleClient != null) {
			String address = plugin.HUD_DEVICE_ADDRESS.get();
			String name = plugin.HUD_DEVICE_NAME.get();
			bleClient.disconnect();
			handler.postDelayed(() -> 
				bleClient.connect(address, name != null ? name : "HUD-Moto"), 1000);
		}
	}
}
