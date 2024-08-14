package com.example.rssimodelapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;
    private static final long SCAN_PERIOD = 10000;
    private BluetoothAdapter bluetoothAdapter;
    private boolean scanning = false;
    private Handler handler = new Handler();
    private Map<String, Integer> beaconRSSIs = new HashMap<>();
    private List<BluetoothDevice> scannedDevices = new ArrayList<>();
    private ArrayAdapter<String> deviceListAdapter;

    private ListView listViewBeacons;
    private Button buttonSelectBeacons;

    private List<String> selectedBeaconAddresses = new ArrayList<>();
    private List<String> knownBeaconAddresses = Arrays.asList(
            "C3:00:00:21:8F:0B",
            "C3:00:00:21:8D:DB",
            "C3:00:00:21:8D:DC",
            "C3:00:00:21:8D:E0"
    );
    private Module module;
    private float minRSSI = -100f;
    private float maxRSSI = 0f;

    private TextView textViewResult;
    private PredictionOverlayView predictionOverlayView;
    private TextView textViewCoordinates;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Bluetooth
        enableBluetoothAndLocation();

        // Request necessary permissions
        requestPermissions();

        copyAssetToFile("best_rssi_model_scripted_cpu.pt");

        try {
            loadModel();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load model", e);
            return;
        }

        listViewBeacons = findViewById(R.id.listViewBeacons);
        buttonSelectBeacons = findViewById(R.id.buttonSelectBeacons);
        textViewResult = findViewById(R.id.textViewResult);
        predictionOverlayView = findViewById(R.id.predictionOverlayView);
        textViewCoordinates = findViewById(R.id.textViewCoordinates);

        deviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, new ArrayList<>());
        listViewBeacons.setAdapter(deviceListAdapter);
        listViewBeacons.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        Button buttonPredict = findViewById(R.id.buttonPredict);
        buttonPredict.setOnClickListener(v -> startRealTimeTracking());

        buttonSelectBeacons.setOnClickListener(v -> {
            selectedBeaconAddresses.clear();
            for (int i = 0; i < listViewBeacons.getCount(); i++) {
                if (listViewBeacons.isItemChecked(i)) {
                    selectedBeaconAddresses.add(scannedDevices.get(i).getAddress());
                }
            }
            updateSelectedBeaconsUI();
        });

        // Start scanning for beacons
        scanLeDevice(true);
    }

    private void enableBluetoothAndLocation() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            Log.e(TAG, "Bluetooth Manager is null");
            return;
        }

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Device doesn't support Bluetooth");
            return;
        } else if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Intent enableLocationIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(enableLocationIntent);
        }
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_PERMISSIONS);
        }
    }

    private void copyAssetToFile(String assetFileName) {
        File file = new File(getFilesDir(), assetFileName);
        if (!file.exists()) {
            try (InputStream inputStream = getAssets().open(assetFileName);
                 FileOutputStream outputStream = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                outputStream.flush();
            } catch (Exception e) {
                Log.e(TAG, "Failed to copy asset file", e);
            }
        }
    }

    private void loadModel() throws Exception {
        File file = new File(getFilesDir(), "best_rssi_model_scripted_cpu.pt");
        module = Module.load(file.getAbsolutePath());
    }

    private float[] predictCoordinates(float[] preprocessedData) throws Exception {
        Tensor inputTensor = Tensor.fromBlob(preprocessedData, new long[]{1, preprocessedData.length});
        Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();
        return outputTensor.getDataAsFloatArray();
    }

    private float[] preprocessData(float[] data) {
        float[] preprocessedData = new float[data.length];
        for (int i = 0; i < data.length; i++) {
            preprocessedData[i] = (data[i] - minRSSI) / (maxRSSI - minRSSI);
        }
        return preprocessedData;
    }

    private float[] getBeaconRSSIs() {
        float[] rssiValues = new float[4];
        for (int i = 0; i < selectedBeaconAddresses.size(); i++) {
            String macAddress = selectedBeaconAddresses.get(i);
            rssiValues[i] = beaconRSSIs.getOrDefault(macAddress, (int)minRSSI);
            Log.d(TAG, "Beacon " + (i+1) + " (" + macAddress + "): RSSI = " + rssiValues[i]);
        }
        return rssiValues;
    }

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            handler.postDelayed(() -> {
                scanning = false;
                bluetoothAdapter.stopLeScan(leScanCallback);
                Log.d(TAG, "Scan period ended. Processing results...");
                // Trigger prediction after scanning period ends
                try {
                    float[] inputData = getBeaconRSSIs();
                    float[] preprocessedData = preprocessData(inputData);
                    float[] outputData = predictCoordinates(preprocessedData);
                    updateUI(outputData);
                } catch (Exception e) {
                    Log.e(TAG, "Prediction failed", e);
                    textViewResult.setText("Prediction failed. Please try again.");
                }
            }, SCAN_PERIOD);

            scanning = true;
            bluetoothAdapter.startLeScan(leScanCallback);
            Log.d(TAG, "Starting scan...");
        } else {
            scanning = false;
            bluetoothAdapter.stopLeScan(leScanCallback);
        }
    }

    private final BluetoothAdapter.LeScanCallback leScanCallback =
            (device, rssi, scanRecord) -> {
                beaconRSSIs.put(device.getAddress(), rssi);
                if (!scannedDevices.contains(device)) {
                    scannedDevices.add(device);
                    deviceListAdapter.add(device.getName() + " (" + device.getAddress() + ")");
                }
                runOnUiThread(() -> {
                    deviceListAdapter.notifyDataSetChanged();
                    Log.d(TAG, "Found device: " + device.getName() + " (" + device.getAddress() + ")");
                });
            };

    private void updateSelectedBeaconsUI() {
        TextView textViewRSSI1 = findViewById(R.id.textViewRSSI1);
        TextView textViewRSSI2 = findViewById(R.id.textViewRSSI2);
        TextView textViewRSSI3 = findViewById(R.id.textViewRSSI3);
        TextView textViewRSSI4 = findViewById(R.id.textViewRSSI4);

        textViewRSSI1.setText("RSSI for Beacon 1: " + beaconRSSIs.getOrDefault(selectedBeaconAddresses.get(0), (int)minRSSI));
        textViewRSSI2.setText("RSSI for Beacon 2: " + beaconRSSIs.getOrDefault(selectedBeaconAddresses.get(1), (int)minRSSI));
        textViewRSSI3.setText("RSSI for Beacon 3: " + beaconRSSIs.getOrDefault(selectedBeaconAddresses.get(2), (int)minRSSI));
        textViewRSSI4.setText("RSSI for Beacon 4: " + beaconRSSIs.getOrDefault(selectedBeaconAddresses.get(3), (int)minRSSI));

        Log.d(TAG, "Updated selected beacons on UI");
    }

    private void startRealTimeTracking() {
        final long TRACKING_INTERVAL = 1000; // 1 second
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                scanLeDevice(true);
                handler.postDelayed(this, TRACKING_INTERVAL);
            }
        }, TRACKING_INTERVAL);
    }

    private void updateUI(float[] outputData) {
        textViewResult.setText("Predicted Coordinates:\nX: " + outputData[0] + "\nY: " + outputData[1]);
        Log.d(TAG, "Prediction successful: X = " + outputData[0] + ", Y = " + outputData[1]);

        // Update PredictionOverlayView with the new coordinates
        predictionOverlayView.setPredictedCoordinates(outputData[0], outputData[1]);

        // Update Coordinates TextView
        textViewCoordinates.setText("Predicted Coordinates: X: " + outputData[0] + ", Y: " + outputData[1]);
    }
}
