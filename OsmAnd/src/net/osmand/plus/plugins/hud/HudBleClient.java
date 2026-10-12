package net.osmand.plus.plugins.hud;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

public class HudBleClient {
	private static final Log LOG = PlatformUtil.getLog(HudBleClient.class);
	
	private static final UUID SERVICE_UUID = UUID.fromString("8d1f0001-6a4c-4b3e-9a0e-7c2d5f1e4b00");
	private static final UUID RX_UUID = UUID.fromString("8d1f0002-6a4c-4b3e-9a0e-7c2d5f1e4b00");
	private static final UUID STATUS_UUID = UUID.fromString("8d1f0003-6a4c-4b3e-9a0e-7c2d5f1e4b00");
	private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
	
	private static final int MTU_PREFERRED = 247;
	private static final int MTU_MIN = 23;
	private static final int SCAN_TIMEOUT_MS = 30000;
	
	private final Context context;
	private final Handler handler;
	private final ConnectionListener listener;
	private final boolean debugLog;
	
	private BluetoothAdapter bluetoothAdapter;
	private BluetoothGatt bluetoothGatt;
	private BluetoothGattCharacteristic rxCharacteristic;
	private BluetoothGattCharacteristic statusCharacteristic;
	
	private String targetAddress;
	private String targetName;
	private int currentMtu = MTU_MIN;
	private boolean connected = false;
	private boolean notifyEnabled = false;
	
	private final Queue<byte[]> writeQueue = new LinkedList<>();
	private boolean writeInProgress = false;
	
	private int reconnectAttempt = 0;
	private static final int[] RECONNECT_DELAYS = {1000, 2000, 5000, 10000, 15000};
	
	public interface ConnectionListener {
		void onConnected();
		void onDisconnected();
		void onStatusReceived(byte[] status);
		void onConnectionError(String error);
	}
	
	public HudBleClient(@NonNull Context context, @NonNull Handler handler, 
			@NonNull ConnectionListener listener, boolean debugLog) {
		this.context = context;
		this.handler = handler;
		this.listener = listener;
		this.debugLog = debugLog;
		
		BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
		if (bluetoothManager != null) {
			bluetoothAdapter = bluetoothManager.getAdapter();
		}
	}
	
	public void connect(@Nullable String address, @NonNull String name) {
		this.targetAddress = address;
		this.targetName = name;
		
		if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
			listener.onConnectionError("Bluetooth not available or disabled");
			return;
		}
		
		if (address != null && BluetoothAdapter.checkBluetoothAddress(address)) {
			connectToDevice(address);
		} else {
			startScan();
		}
	}
	
	public void disconnect() {
		stopScan();
		
		if (bluetoothGatt != null) {
			bluetoothGatt.disconnect();
			bluetoothGatt.close();
			bluetoothGatt = null;
		}
		
		connected = false;
		notifyEnabled = false;
		reconnectAttempt = 0;
		writeQueue.clear();
		writeInProgress = false;
	}
	
	public boolean isConnected() {
		return connected && notifyEnabled;
	}
	
	public int getMtu() {
		return currentMtu;
	}
	
	public void write(byte[] data) {
		if (!isConnected() || rxCharacteristic == null) {
			if (debugLog) {
				LOG.warn("Attempted write while not connected");
			}
			return;
		}
		
		synchronized (writeQueue) {
			writeQueue.offer(data);
			processWriteQueue();
		}
	}
	
	private void processWriteQueue() {
		synchronized (writeQueue) {
			if (writeInProgress || writeQueue.isEmpty() || rxCharacteristic == null) {
				return;
			}
			
			byte[] data = writeQueue.poll();
			if (data != null) {
				writeInProgress = true;
				rxCharacteristic.setValue(data);
				rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
				
				boolean success = bluetoothGatt.writeCharacteristic(rxCharacteristic);
				if (!success) {
					LOG.warn("Write characteristic failed");
					writeInProgress = false;
					scheduleReconnect();
				}
			}
		}
	}
	
	private void startScan() {
		if (bluetoothAdapter == null) return;
		
		if (!hasBluetoothPermissions()) {
			listener.onConnectionError("Missing BLUETOOTH_SCAN permission (Android 12+)");
			return;
		}
		
		BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
		if (scanner == null) {
			listener.onConnectionError("BLE scanner not available");
			return;
		}
		
		ScanFilter filter = new ScanFilter.Builder()
				.setServiceUuid(new ParcelUuid(SERVICE_UUID))
				.build();
		
		ScanSettings settings = new ScanSettings.Builder()
				.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
				.build();
		
		List<ScanFilter> filters = new ArrayList<>();
		filters.add(filter);
		
		if (debugLog) {
			LOG.info("Starting BLE scan for " + targetName);
		}
		
		try {
			scanner.startScan(filters, settings, scanCallback);
			handler.postDelayed(this::stopScan, SCAN_TIMEOUT_MS);
		} catch (SecurityException e) {
			listener.onConnectionError("Missing Bluetooth permissions");
		}
	}
	
	private void stopScan() {
		if (bluetoothAdapter == null) return;
		
		BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
		if (scanner != null) {
			try {
				scanner.stopScan(scanCallback);
			} catch (SecurityException e) {
				LOG.warn("Error stopping scan", e);
			}
		}
	}
	
	private final ScanCallback scanCallback = new ScanCallback() {
		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			BluetoothDevice device = result.getDevice();
			try {
				String name = device.getName();
				if (targetName.equals(name)) {
					if (debugLog) {
						LOG.info("Found HUD device: " + device.getAddress());
					}
					stopScan();
					targetAddress = device.getAddress();
					connectToDevice(device.getAddress());
				}
			} catch (SecurityException e) {
				LOG.warn("Security exception reading device name", e);
			}
		}
		
		@Override
		public void onScanFailed(int errorCode) {
			LOG.error("BLE scan failed: " + errorCode);
			listener.onConnectionError("Scan failed: " + errorCode);
		}
	};
	
	private boolean hasBluetoothPermissions() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			int scanPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN);
			int connectPerm = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT);
			return scanPerm == android.content.pm.PackageManager.PERMISSION_GRANTED
				&& connectPerm == android.content.pm.PackageManager.PERMISSION_GRANTED;
		}
		return true;
	}
	
	private void connectToDevice(@NonNull String address) {
		if (!hasBluetoothPermissions()) {
			listener.onConnectionError("Missing BLUETOOTH_CONNECT permission (Android 12+)");
			return;
		}
		
		try {
			BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
			
			if (debugLog) {
				LOG.info("Connecting to " + address);
			}
			
			bluetoothGatt = device.connectGatt(context, false, gattCallback, 
					BluetoothDevice.TRANSPORT_LE);
		} catch (SecurityException e) {
			listener.onConnectionError("Missing Bluetooth permissions");
		} catch (Exception e) {
			LOG.error("Connection error", e);
			listener.onConnectionError("Connection error: " + e.getMessage());
			scheduleReconnect();
		}
	}
	
	private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				if (debugLog) {
					LOG.info("GATT connected");
				}
				
				try {
					gatt.requestMtu(MTU_PREFERRED);
				} catch (SecurityException e) {
					LOG.warn("MTU request failed", e);
				}
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				if (debugLog) {
					LOG.info("GATT disconnected");
				}
				
				connected = false;
				notifyEnabled = false;
				listener.onDisconnected();
				
				if (targetAddress != null) {
					scheduleReconnect();
				}
			}
		}
		
		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				currentMtu = mtu;
				if (debugLog) {
					LOG.info("MTU changed to " + mtu);
				}
			}
			
			try {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					gatt.setPreferredPhy(BluetoothDevice.PHY_LE_2M, BluetoothDevice.PHY_LE_2M, 
							BluetoothDevice.PHY_OPTION_NO_PREFERRED);
				}
			} catch (SecurityException e) {
				LOG.warn("PHY request failed", e);
			}
			
			try {
				gatt.discoverServices();
			} catch (SecurityException e) {
				LOG.warn("Service discovery failed", e);
			}
		}
		
		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				BluetoothGattService service = gatt.getService(SERVICE_UUID);
				if (service != null) {
					rxCharacteristic = service.getCharacteristic(RX_UUID);
					statusCharacteristic = service.getCharacteristic(STATUS_UUID);
					
					if (rxCharacteristic != null && statusCharacteristic != null) {
						enableStatusNotifications();
					} else {
						LOG.error("Required characteristics not found");
						gatt.disconnect();
					}
				} else {
					LOG.error("HUD service not found");
					gatt.disconnect();
				}
			}
		}
		
		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			if (status == BluetoothGatt.GATT_SUCCESS && descriptor.getUuid().equals(CCCD_UUID)) {
				notifyEnabled = true;
				connected = true;
				reconnectAttempt = 0;
				
				if (debugLog) {
					LOG.info("HUD connected and ready");
				}
				
				listener.onConnected();
			}
		}
		
		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			synchronized (writeQueue) {
				writeInProgress = false;
				processWriteQueue();
			}
		}
		
		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			if (STATUS_UUID.equals(characteristic.getUuid())) {
				byte[] value = characteristic.getValue();
				if (value != null && value.length >= 8) {
					listener.onStatusReceived(value);
				}
			}
		}
	};
	
	private void enableStatusNotifications() {
		if (bluetoothGatt == null || statusCharacteristic == null) return;
		
		try {
			bluetoothGatt.setCharacteristicNotification(statusCharacteristic, true);
			
			BluetoothGattDescriptor descriptor = statusCharacteristic.getDescriptor(CCCD_UUID);
			if (descriptor != null) {
				descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
				bluetoothGatt.writeDescriptor(descriptor);
			}
		} catch (SecurityException e) {
			LOG.warn("Failed to enable notifications", e);
		}
	}
	
	private void scheduleReconnect() {
		if (targetAddress == null) return;
		
		int delay = RECONNECT_DELAYS[Math.min(reconnectAttempt, RECONNECT_DELAYS.length - 1)];
		reconnectAttempt++;
		
		if (debugLog) {
			LOG.info("Scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempt + ")");
		}
		
		handler.postDelayed(() -> connectToDevice(targetAddress), delay);
	}
}
