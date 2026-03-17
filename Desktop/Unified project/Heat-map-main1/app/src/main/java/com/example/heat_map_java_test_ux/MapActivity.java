package com.example.heat_map_java_test_ux;

import android.Manifest;
import android.content.pm.PackageManager;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

public class MapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "MapActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final float DEFAULT_ZOOM = 16f;
    private static final float MAX_LOCATION_ACCURACY_METERS = 35f;

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private boolean hasCenteredOnUserLocation = false;
    private boolean sessionRunning = false;
    private boolean pendingSessionStart = false;

    private TextView statusText;
    private TextView liveBadge;
    private TextView chipMode;
    private TextView chipDistance;
    private TextView chipArea;
    private MaterialButton sessionButton;

    private final List<LatLng> trackedPath = new ArrayList<>();
    private Polyline activePathPolyline;
    private Location lastAcceptedLocation;
    private float totalDistanceMeters = 0f;

    private final LocationCallback trackingLocationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(@NonNull LocationResult locationResult) {
            for (Location location : locationResult.getLocations()) {
                consumeTrackingLocation(location);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupPreviewControls();

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        applyMapStyle();
        mMap.getUiSettings().setMyLocationButtonEnabled(true);
        mMap.getUiSettings().setMapToolbarEnabled(false);
        mMap.setPadding(0, 170, 0, 260);

        enableMyLocation();
        setupTrackingPathOverlay();
    }

    private void setupPreviewControls() {
        statusText = findViewById(R.id.status_text);
        liveBadge = findViewById(R.id.live_badge);
        chipMode = findViewById(R.id.chip_mode);
        chipDistance = findViewById(R.id.chip_distance);
        chipArea = findViewById(R.id.chip_area);
        sessionButton = findViewById(R.id.session_button);

        sessionButton.setOnClickListener(v -> {
            if (sessionRunning) {
                stopTrackingSession();
            } else {
                startTrackingSession();
            }
        });
    }

    private void setupTrackingPathOverlay() {
        if (mMap == null) {
            return;
        }

        activePathPolyline = mMap.addPolyline(new PolylineOptions()
                .color(Color.parseColor("#FF5A1F"))
                .width(14f)
                .geodesic(true));
    }

    private void applyMapStyle() {
        try {
            boolean loaded = mMap.setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_heatmap)
            );
            if (!loaded) {
                Log.w(TAG, "Map style parsing failed.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not apply map style.", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            statusText.setText("GPS permission granted. Locking position...");
            liveBadge.setText("GPS ON");
            centerMapOnCurrentLocation();
        } else {
            statusText.setText("Waiting for location permission...");
            liveBadge.setText("GPS OFF");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void startTrackingSession() {
        if (mMap == null) {
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSessionStart = true;
            statusText.setText("Grant location permission to start tracking.");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        sessionRunning = true;
        pendingSessionStart = false;
        trackedPath.clear();
        totalDistanceMeters = 0f;
        lastAcceptedLocation = null;
        updateTrackingStats();
        activePathPolyline.setPoints(trackedPath);

        sessionButton.setText("STOP SESSION");
        chipMode.setText("Tracking");
        statusText.setText("Tracking movement... walk to paint your path.");
        liveBadge.setText("LIVE");

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2500L)
                .setMinUpdateDistanceMeters(3f)
                .setWaitForAccurateLocation(true)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, trackingLocationCallback, Looper.getMainLooper());
        Toast.makeText(this, "Session started", Toast.LENGTH_SHORT).show();
    }

    private void stopTrackingSession() {
        sessionRunning = false;
        fusedLocationClient.removeLocationUpdates(trackingLocationCallback);
        sessionButton.setText("START SESSION");
        chipMode.setText("Preview");
        liveBadge.setText("GPS ON");
        statusText.setText(String.format(Locale.US, "Session complete: %.2f km painted", totalDistanceMeters / 1000f));
        Toast.makeText(this, "Session stopped", Toast.LENGTH_SHORT).show();
    }

    private void consumeTrackingLocation(Location location) {
        if (!sessionRunning || location == null) {
            return;
        }

        if (location.hasAccuracy() && location.getAccuracy() > MAX_LOCATION_ACCURACY_METERS) {
            statusText.setText("Waiting for better GPS accuracy...");
            return;
        }

        if (lastAcceptedLocation != null) {
            totalDistanceMeters += lastAcceptedLocation.distanceTo(location);
        }

        lastAcceptedLocation = location;

        LatLng point = new LatLng(location.getLatitude(), location.getLongitude());
        trackedPath.add(point);
        activePathPolyline.setPoints(trackedPath);
        updateTrackingStats();

        if (!hasCenteredOnUserLocation) {
            moveCameraToLocation(location);
        }
    }

    private void updateTrackingStats() {
        chipDistance.setText(String.format(Locale.US, "%.2f km", totalDistanceMeters / 1000f));
        chipArea.setText(String.format(Locale.US, "%d pts", trackedPath.size()));
    }

    @SuppressLint("MissingPermission")
    private void centerMapOnCurrentLocation() {
        if (hasCenteredOnUserLocation) {
            return;
        }

        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.getToken())
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        moveCameraToLocation(location);
                        return;
                    }

                    fusedLocationClient.getLastLocation().addOnSuccessListener(this, lastLocation -> {
                        if (lastLocation != null) {
                            moveCameraToLocation(lastLocation);
                        } else {
                            statusText.setText("Location unavailable. Set emulator location or move outdoors.");
                            Toast.makeText(this, "Could not get current location. If using emulator, set a location.", Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .addOnFailureListener(this, e -> {
                    Log.w(TAG, "Failed to get current location", e);
                    statusText.setText("Location failed. Check location services.");
                    Toast.makeText(this, "Could not get current location. Check location services.", Toast.LENGTH_SHORT).show();
                });
    }

    private void moveCameraToLocation(Location location) {
        LatLng userLatLng = new LatLng(location.getLatitude(), location.getLongitude());
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, DEFAULT_ZOOM));
        statusText.setText("Locked: " + String.format("%.5f, %.5f", location.getLatitude(), location.getLongitude()));
        hasCenteredOnUserLocation = true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation();
                if (pendingSessionStart) {
                    startTrackingSession();
                }
            } else {
                pendingSessionStart = false;
                statusText.setText("Location permission denied.");
                liveBadge.setText("GPS OFF");
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (sessionRunning) {
            stopTrackingSession();
        }
    }
}
