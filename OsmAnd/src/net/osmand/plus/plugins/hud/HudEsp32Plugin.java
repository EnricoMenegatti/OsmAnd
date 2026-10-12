package net.osmand.plus.plugins.hud;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.routing.IRouteInformationListener;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.fragments.SettingsScreenType;
import net.osmand.data.ValueHolder;

import org.apache.commons.logging.Log;

public class HudEsp32Plugin extends OsmandPlugin {
	private static final Log LOG = PlatformUtil.getLog(HudEsp32Plugin.class);
	private static final String PLUGIN_ID = "osmand.hud.esp32";
	
	private HandlerThread workerThread;
	private Handler workerHandler;
	private HudController controller;
	
	public final CommonPreference<String> HUD_DEVICE_ADDRESS;
	public final CommonPreference<String> HUD_DEVICE_NAME;
	public final CommonPreference<Integer> HUD_POS_RATE_HZ;
	public final CommonPreference<Integer> HUD_WINDOW_AHEAD_M;
	public final CommonPreference<Boolean> HUD_FREE_RIDE;
	public final CommonPreference<Integer> HUD_FREE_RIDE_MAX_M;
	public final CommonPreference<String> HUD_JUNCTION_MIN_CLASS;
	public final CommonPreference<Boolean> HUD_JUNCTION_IGNORE_LINKS;
	public final CommonPreference<Integer> HUD_JUNCTION_SEARCH_M;
	public final CommonPreference<Boolean> HUD_DEBUG_LOG;
	
	private final IRouteInformationListener routeListener = new IRouteInformationListener() {
		@Override
		public void newRouteIsCalculated(boolean newRoute, @NonNull ValueHolder<Boolean> showToast) {
			if (workerHandler != null && controller != null) {
				workerHandler.post(() -> controller.onNewRouteCalculated(newRoute));
			}
		}
		
		@Override
		public void routeWasCancelled() {
			if (workerHandler != null && controller != null) {
				workerHandler.post(() -> controller.onRouteCancelled());
			}
		}
		
		@Override
		public void routeWasFinished() {
			if (workerHandler != null && controller != null) {
				workerHandler.post(() -> controller.onRouteFinished());
			}
		}
	};
	
	public HudEsp32Plugin(@NonNull OsmandApplication app) {
		super(app);
		
		HUD_DEVICE_ADDRESS = registerStringPreference("hud_esp32_device_address", null).makeGlobal();
		HUD_DEVICE_NAME = registerStringPreference("hud_esp32_device_name", "HUD-Moto").makeGlobal();
		HUD_POS_RATE_HZ = registerIntPreference("hud_esp32_pos_rate_hz", 2).makeGlobal();
		HUD_WINDOW_AHEAD_M = registerIntPreference("hud_esp32_window_ahead_m", 1500).makeGlobal();
		HUD_FREE_RIDE = registerBooleanPreference("hud_esp32_free_ride", true).makeGlobal();
		HUD_FREE_RIDE_MAX_M = registerIntPreference("hud_esp32_free_ride_max_m", 500).makeGlobal();
		HUD_JUNCTION_MIN_CLASS = registerStringPreference("hud_esp32_junction_min_class", "same_or_higher").makeGlobal();
		HUD_JUNCTION_IGNORE_LINKS = registerBooleanPreference("hud_esp32_junction_ignore_links", true).makeGlobal();
		HUD_JUNCTION_SEARCH_M = registerIntPreference("hud_esp32_junction_search_m", 500).makeGlobal();
		HUD_DEBUG_LOG = registerBooleanPreference("hud_esp32_debug_log", false).makeGlobal();
	}
	
	@Override
	@NonNull
	public String getId() {
		return PLUGIN_ID;
	}
	
	@Override
	@NonNull
	public String getName() {
		return app.getString(R.string.hud_esp32_plugin_name);
	}
	
	@Override
	@NonNull
	public CharSequence getDescription(boolean linksEnabled) {
		return app.getString(R.string.hud_esp32_plugin_description);
	}
	
	@Override
	public int getLogoResourceId() {
		return R.drawable.ic_action_motorcycle_dark;
	}
	
	@Override
	public Drawable getAssetResourceImage() {
		return app.getUIUtilities().getIcon(R.drawable.ic_action_motorcycle_dark);
	}
	
	@Nullable
	@Override
	public SettingsScreenType getSettingsScreenType() {
		return SettingsScreenType.HUD_ESP32_SETTINGS;
	}
	
	@Override
	public boolean init(@NonNull OsmandApplication app, @Nullable Activity activity) {
		try {
			RoutingHelper routingHelper = app.getRoutingHelper();
			routingHelper.addListener(routeListener);
			
			workerThread = new HandlerThread("HudWorker");
			workerThread.start();
			workerHandler = new Handler(workerThread.getLooper());
			
			controller = new HudController(app, this, workerHandler);
			controller.init();
			
			return true;
		} catch (Exception e) {
			LOG.error("Failed to initialize HUD ESP32 plugin", e);
			return false;
		}
	}
	
	@Override
	public void disable(@NonNull OsmandApplication app) {
		try {
			RoutingHelper routingHelper = app.getRoutingHelper();
			routingHelper.removeListener(routeListener);
			
			if (controller != null) {
				controller.destroy();
				controller = null;
			}
			
			if (workerThread != null) {
				workerThread.quitSafely();
				workerThread = null;
				workerHandler = null;
			}
		} catch (Exception e) {
			LOG.error("Error disabling HUD ESP32 plugin", e);
		}
	}
	
	@Override
	public void updateLocation(@NonNull Location location) {
		if (workerHandler != null && controller != null) {
			workerHandler.post(() -> controller.onLocationUpdate(location));
		}
	}
	
	public Handler getWorkerHandler() {
		return workerHandler;
	}
	
	public HudController getController() {
		return controller;
	}
}
