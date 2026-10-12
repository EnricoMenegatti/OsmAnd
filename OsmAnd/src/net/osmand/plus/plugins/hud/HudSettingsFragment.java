package net.osmand.plus.plugins.hud;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.Preference;

import net.osmand.plus.R;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;

public class HudSettingsFragment extends BaseSettingsFragment {
	
	private HudEsp32Plugin plugin;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		plugin = PluginsHelper.getPlugin(HudEsp32Plugin.class);
	}
	
	@Override
	protected void setupPreferences() {
		Preference statusPref = findPreference("hud_connection_status");
		if (statusPref != null) {
			updateConnectionStatus(statusPref);
		}
		
		Preference testPref = findPreference("hud_test");
		if (testPref != null) {
			testPref.setOnPreferenceClickListener(preference -> {
				if (plugin != null && plugin.getController() != null) {
					plugin.getController().reconnect();
				}
				return true;
			});
		}
	}
	
	@Override
	public void onResume() {
		super.onResume();
		
		Preference statusPref = findPreference("hud_connection_status");
		if (statusPref != null) {
			updateConnectionStatus(statusPref);
		}
	}
	
	private void updateConnectionStatus(@NonNull Preference statusPref) {
		if (plugin != null && plugin.getController() != null) {
			boolean connected = plugin.getController().isConnected();
			HudController.State state = plugin.getController().getCurrentState();
			
			String status;
			if (connected) {
				status = getString(R.string.hud_status_connected) + " (" + state.name() + ")";
			} else {
				status = getString(R.string.hud_status_disconnected);
			}
			
			statusPref.setSummary(status);
		}
	}
}
