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
	private static final String TAG = "HudBle";
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
		
		LOG.info(TAG + ": Creating HudBleClient, debugLog=" + debugLog);
		
		BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
		if (bluetoothManager != null) {
			bluetoothAdapter = bluetoothManager.getAdapter();
			LOG.info(TAG + ": BluetoothAdapter obtained, enabled=" + (bluetoothAdapter != null && bluetoothAdapter.isEnabled()));
		} else {
			LOG.error(TAG + ": BluetoothManager is null");
		}
	}
	
	public void connect(@Nullable String address, @NonNull String name) {
		this.targetAddress = address;
		this.targetName = name;
		
		LOG.info(TAG + ": connect() called with address=" + address + " name=" + name);
		
		if (bluetoothAdapter == null) {
			LOG.error(TAG + ": BluetoothAdapter is null");
			listener.onConnectionError("Bluetooth not available");
			return;
		}
		
		if (!bluetoothAdapter.isEnabled()) {
			LOG.error(TAG + ": Bluetooth is disabled");
			listener.onConnectionError("Bluetooth is disabled");
			return;
		}
		
		if (address != null && BluetoothAdapter.checkBluetoothAddress(address)) {
			LOG.info(TAG + ": Valid address provided, connecting directly");
			connectToDevice(address);
		} else {
			LOG.info(TAG + ": No valid address, starting scan for name=" + name);
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
		
		org.apache.commons.logging.Log txLog = PlatformUtil.getLog("HudTx");
		txLog.info(HudProtocolEncoder.decodeForLog(data));
		
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
		LOG.info(TAG + ": startScan() called");
		
		if (bluetoothAdapter == null) {
			LOG.error(TAG + ": Cannot scan - bluetoothAdapter is null");
			return;
		}
		
		if (!hasBluetoothPermissions()) {
			LOG.error(TAG + ": Missing BLUETOOTH_SCAN/CONNECT permissions");
			listener.onConnectionError("Missing BLUETOOTH_SCAN permission (Android 12+)");
			return;
		}
		
		LOG.info(TAG + ": Bluetooth permissions OK");
		
		BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
		if (scanner == null) {
			LOG.error(TAG + ": getBluetoothLeScanner() returned null");
			listener.onConnectionError("BLE scanner not available");
			return;
		}
		
		LOG.info(TAG + ": Got BluetoothLeScanner, setting up filters");
		
		List<ScanFilter> filters = new ArrayList<>();
		
		ScanFilter nameFilter = new ScanFilter.Builder()
			.setDeviceName(targetName)
			.build();
		filters.add(nameFilter);
		
		ScanFilter uuidFilter = new ScanFilter.Builder()
			.setServiceUuid(new ParcelUuid(SERVICE_UUID))
			.build();
		filters.add(uuidFilter);
		
		ScanSettings settings = new ScanSettings.Builder()
			.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
			.build();
		
		LOG.info(TAG + ": Starting BLE scan for name=" + targetName + " or serviceUUID=" + SERVICE_UUID);
		
		try {
			scanner.startScan(filters, settings, scanCallback);
			LOG.info(TAG + ": Scan started successfully, timeout in " + SCAN_TIMEOUT_MS + "ms");
			handler.postDelayed(this::stopScan, SCAN_TIMEOUT_MS);
		} catch (SecurityException e) {
			LOG.error(TAG + ": SecurityException during scan start", e);
			listener.onConnectionError("Missing Bluetooth permissions: " + e.getMessage());
		} catch (Exception e) {
			LOG.error(TAG + ": Exception during scan start", e);
			listener.onConnectionError("Scan failed: " + e.getMessage());
		}
	}
	
	private void stopScan() {
		LOG.info(TAG + ": stopScan() called");
		if (bluetoothAdapter == null) {
			LOG.warn(TAG + ": stopScan - bluetoothAdapter is null");
			return;
		}
		
		BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
		if (scanner != null) {
			try {
				scanner.stopScan(scanCallback);
				LOG.info(TAG + ": Scan stopped");
			} catch (SecurityException e) {
				LOG.warn(TAG + ": SecurityException stopping scan", e);
			} catch (Exception e) {
				LOG.warn(TAG + ": Exception stopping scan", e);
			}
		} else {
			LOG.warn(TAG + ": stopScan - scanner is null");
		}
	}
	
	private final ScanCallback scanCallback = new ScanCallback() {
		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			BluetoothDevice device = result.getDevice();
			LOG.info(TAG + ": onScanResult - device found");
			try {
				String address = device.getAddress();
				String deviceName = device.getName();
				int rssi = result.getRssi();
				
				LOG.info(TAG + ": Device: name=" + deviceName + " address=" + address + " rssi=" + rssi);
				
				boolean matches = false;
				String matchReason = "";
				
				if (result.getScanRecord() != null) {
					String scanName = result.getScanRecord().getDeviceName();
					List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
					
					LOG.info(TAG + ": ScanRecord: deviceName=" + scanName + " serviceUuids=" + 
						(uuids != null ? uuids.size() + " found" : "null"));
					
					if (scanName != null && targetName.equals(scanName)) {
						matches = true;
						matchReason = "name from ScanRecord";
						LOG.info(TAG + ": Name from ScanRecord matches: " + scanName);
					}
					
					if (uuids != null) {
						for (ParcelUuid uuid : uuids) {
							LOG.info(TAG + ": Service UUID: " + uuid.getUuid());
							if (SERVICE_UUID.equals(uuid.getUuid())) {
								matches = true;
								matchReason = "service UUID";
								LOG.info(TAG + ": Service UUID matches HUD service!");
								break;
							}
						}
					}
				}
				
				if (deviceName != null && targetName.equals(deviceName)) {
					matches = true;
					matchReason = "name from device";
					LOG.info(TAG + ": Device name matches: " + deviceName);
				}
				
				if (matches) {
					LOG.info(TAG + ": Found matching HUD device by " + matchReason + ": " + address);
					stopScan();
					targetAddress = address;
					connectToDevice(address);
				} else {
					LOG.info(TAG + ": Device doesn't match (name: " + deviceName + 
						", scanName: " + (result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : "null") + 
						", target: " + targetName + ")");
				}
			} catch (SecurityException e) {
				LOG.error(TAG + ": SecurityException reading device info", e);
			} catch (Exception e) {
				LOG.error(TAG + ": Exception in onScanResult", e);
			}
		}
		
		@Override
		public void onScanFailed(int errorCode) {
			LOG.error(TAG + ": BLE scan failed with error code: " + errorCode);
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
		LOG.info(TAG + ": connectToDevice() address=" + address);
		
		if (!hasBluetoothPermissions()) {
			LOG.error(TAG + ": Missing BLUETOOTH_CONNECT permission");
			listener.onConnectionError("Missing BLUETOOTH_CONNECT permission (Android 12+)");
			return;
		}
		
		LOG.info(TAG + ": Bluetooth permissions OK for connect");
		
		try {
			BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
			LOG.info(TAG + ": Got BluetoothDevice, calling connectGatt");
			
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
			LOG.info(TAG + ": onConnectionStateChange status=" + status + " newState=" + newState);
			
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				LOG.info(TAG + ": GATT connected successfully!");
				
				if (targetAddress != null) {
					LOG.info(TAG + ": Saving address for future direct reconnects: " + targetAddress);
				}
				
				LOG.info(TAG + ": Requesting MTU=" + MTU_PREFERRED);
				try {
					boolean ok = gatt.requestMtu(MTU_PREFERRED);
					LOG.info(TAG + ": MTU request returned: " + ok);
				} catch (SecurityException e) {
					LOG.error(TAG + ": SecurityException requesting MTU", e);
				} catch (Exception e) {
					LOG.error(TAG + ": Exception requesting MTU", e);
				}
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				LOG.info(TAG + ": GATT disconnected, status=" + status);
				
				connected = false;
				notifyEnabled = false;
				listener.onDisconnected();
				
				if (targetAddress != null) {
					LOG.info(TAG + ": Scheduling reconnect to " + targetAddress);
					scheduleReconnect();
				}
			}
		}
		
		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
			LOG.info(TAG + ": onMtuChanged mtu=" + mtu + " status=" + status);
			
			if (status == BluetoothGatt.GATT_SUCCESS) {
				currentMtu = mtu;
				LOG.info(TAG + ": MTU successfully changed to " + mtu);
			} else {
				LOG.warn(TAG + ": MTU change failed with status=" + status);
			}
			
			try {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					LOG.info(TAG + ": Requesting PHY LE_2M");
					gatt.setPreferredPhy(BluetoothDevice.PHY_LE_2M, BluetoothDevice.PHY_LE_2M, 
							BluetoothDevice.PHY_OPTION_NO_PREFERRED);
				}
			} catch (SecurityException e) {
				LOG.warn(TAG + ": SecurityException requesting PHY", e);
			} catch (Exception e) {
				LOG.warn(TAG + ": Exception requesting PHY", e);
			}
			
			LOG.info(TAG + ": Starting service discovery");
			try {
				boolean ok = gatt.discoverServices();
				LOG.info(TAG + ": discoverServices returned: " + ok);
			} catch (SecurityException e) {
				LOG.error(TAG + ": SecurityException discovering services", e);
			} catch (Exception e) {
				LOG.error(TAG + ": Exception discovering services", e);
			}
		}
		
		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			LOG.info(TAG + ": onServicesDiscovered status=" + status + 
				(status == BluetoothGatt.GATT_SUCCESS ? " (SUCCESS)" : " (FAILURE)"));
			
			if (status == BluetoothGatt.GATT_SUCCESS) {
				LOG.info(TAG + ": Service discovery successful, looking for service " + SERVICE_UUID);
				
				List<BluetoothGattService> services = gatt.getServices();
				LOG.info(TAG + ": Found " + services.size() + " services total");
				for (BluetoothGattService svc : services) {
					LOG.info(TAG + ": Available service: " + svc.getUuid());
				}
				
				BluetoothGattService service = gatt.getService(SERVICE_UUID);
				
				if (service != null) {
					LOG.info(TAG + ": Found HUD service, getting characteristics");
					rxCharacteristic = service.getCharacteristic(RX_UUID);
					statusCharacteristic = service.getCharacteristic(STATUS_UUID);
					
					LOG.info(TAG + ": RX characteristic (" + RX_UUID + "): " + (rxCharacteristic != null ? "FOUND" : "NOT FOUND"));
					LOG.info(TAG + ": STATUS characteristic (" + STATUS_UUID + "): " + (statusCharacteristic != null ? "FOUND" : "NOT FOUND"));
					
					if (rxCharacteristic != null && statusCharacteristic != null) {
						LOG.info(TAG + ": All characteristics found, enabling notifications");
						enableStatusNotifications();
					} else {
						LOG.error(TAG + ": Missing required characteristics");
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
