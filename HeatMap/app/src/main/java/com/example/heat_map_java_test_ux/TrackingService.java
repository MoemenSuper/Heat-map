package com.example.heat_map_java_test_ux;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Build;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public class TrackingService extends Service implements SensorEventListener {
    private static final String TAG = "TrackingService";
    private static final String CHANNEL_ID = "TrackingServiceChannel";
    private static final int NOTIFICATION_ID = 12345;

    // fixed by Moemen: this pipeline is tuned to be strict on false positives
    // fixed by Moemen: we only stop a session after sustained car-like movement
    private static final float MAX_ACCEPTED_ACCURACY_METERS = 25f;
    private static final long MIN_TIME_DELTA_MS = 800L;
    private static final long MAX_TIME_DELTA_MS = 10_000L;

    private static final float MAX_TRACK_SPEED_MPS = 8.5f; // fast running allowed
    private static final float VEHICLE_SPEED_SOFT_MPS = 8.8f; // ~31.7 km/h
    private static final float VEHICLE_SPEED_HARD_MPS = 11.0f; // ~39.6 km/h
    private static final float VEHICLE_SCORE_TRIGGER = 10.0f;
    private static final int VEHICLE_STREAK_LIMIT = 5;
    private static final int SPEED_WINDOW_SIZE = 12;

    private static final long MIN_SESSION_AGE_FOR_BLOCK_MS = 25_000L;
    private static final float MIN_SESSION_DISTANCE_FOR_BLOCK_M = 180f;
    
    private FusedLocationProviderClient fusedLocationClient;
    private SensorManager sensorManager;
    private Sensor stepSensor;
    private int startSteps = -1;
    private int currentSessionSteps = 0;

    private LocationCallback locationCallback;
    private HandlerThread locationThread;
    private final IBinder binder = new LocalBinder();
    
    private Location lastRawLocation = null;
    private long lastRawTimeMs = -1L;
    private long sessionStartMs = -1L;
    private float sessionDistanceMeters = 0f;

    private float vehicleScore = 0f;
    private int highSpeedStreak = 0;
    private long lastVehicleEvidenceMs = -1L;
    private boolean cheatingAlreadyReported = false;
    private final Deque<Float> recentSpeedWindow = new ArrayDeque<>();

    public interface LocationUpdateListener {
        void onLocationUpdate(Location location);
        void onStepsUpdate(int steps);
        void onCheatingDetected(String reason);
    }
    
    private LocationUpdateListener listener;

    public class LocalBinder extends Binder {
        TrackingService getService() {
            return TrackingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);

        createNotificationChannel();
        resetDetectionState();

        locationThread = new HandlerThread("heatmap-location-thread");
        locationThread.start();
        
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    // fixed by Moemen: if cheating is already reported in this batch, stop processing
                    if (cheatingAlreadyReported) break;

                    if (isLocationValid(location)) {
                        if (listener != null) {
                            listener.onLocationUpdate(location);
                        }
                    }
                }
            }
        };
    }

    private void resetDetectionState() {
        lastRawLocation = null;
        lastRawTimeMs = -1L;
        sessionStartMs = -1L;
        sessionDistanceMeters = 0f;
        startSteps = -1;
        currentSessionSteps = 0;

        vehicleScore = 0f;
        highSpeedStreak = 0;
        lastVehicleEvidenceMs = -1L;
        cheatingAlreadyReported = false;
        recentSpeedWindow.clear();
    }

    private long locationTimeMs(Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            long elapsedMs = location.getElapsedRealtimeNanos() / 1_000_000L;
            if (elapsedMs > 0) {
                return elapsedMs;
            }
        }
        return location.getTime();
    }

    private void addSpeedSample(float speedMps) {
        recentSpeedWindow.addLast(speedMps);
        while (recentSpeedWindow.size() > SPEED_WINDOW_SIZE) {
            recentSpeedWindow.removeFirst();
        }
    }

    private float percentileSpeed(float percentile) {
        if (recentSpeedWindow.isEmpty()) return 0f;
        List<Float> values = new ArrayList<>(recentSpeedWindow);
        Collections.sort(values);
        int index = Math.min(values.size() - 1, Math.max(0, (int) Math.floor(percentile * (values.size() - 1))));
        return values.get(index);
    }

    private float recentMedianSpeed() {
        return percentileSpeed(0.5f);
    }

    private boolean isMockLocation(Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }

    private float blendedSpeed(Location location, float computedSpeed) {
        if (!location.hasSpeed()) {
            return computedSpeed;
        }

        float gpsSpeed = location.getSpeed();
        if (gpsSpeed < 0f || gpsSpeed > 25f) {
            return computedSpeed;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
            if (location.getSpeedAccuracyMetersPerSecond() > 3.0f) {
                return computedSpeed;
            }
        }

        // fixed by Moemen: blend reported speed with geometric speed so one bad source does not dominate
        return (computedSpeed * 0.65f) + (gpsSpeed * 0.35f);
    }

    private void updateVehicleEvidence(float speedMps, float accelerationMps2, float distanceMeters, float deltaSeconds, long nowMs) {
        vehicleScore = Math.max(0f, vehicleScore - 0.6f);

        if (speedMps >= VEHICLE_SPEED_SOFT_MPS) {
            highSpeedStreak++;
            vehicleScore += 1.8f;
            lastVehicleEvidenceMs = nowMs;
        } else {
            highSpeedStreak = Math.max(0, highSpeedStreak - 1);
        }

        if (speedMps >= VEHICLE_SPEED_HARD_MPS) {
            vehicleScore += 2.8f;
            lastVehicleEvidenceMs = nowMs;
        }

        if (speedMps > 8.0f && accelerationMps2 > 5.5f) {
            vehicleScore += 1.2f;
        }

        if (distanceMeters > 120f && deltaSeconds < 8f) {
            vehicleScore += 1.2f;
        }

        if (speedMps < 6.0f) {
            vehicleScore = Math.max(0f, vehicleScore - 1.4f);
        }

        if (lastVehicleEvidenceMs > 0 && nowMs - lastVehicleEvidenceMs > 10_000L) {
            highSpeedStreak = Math.max(0, highSpeedStreak - 2);
        }
    }

    private boolean shouldTriggerVehicleDetection(long nowMs) {
        if (sessionStartMs <= 0) return false;

        long sessionAgeMs = nowMs - sessionStartMs;
        if (sessionAgeMs < MIN_SESSION_AGE_FOR_BLOCK_MS) return false;
        if (sessionDistanceMeters < MIN_SESSION_DISTANCE_FOR_BLOCK_M) return false;
        if (recentSpeedWindow.size() < 6) return false;

        float medianSpeed = recentMedianSpeed();
        float p80Speed = percentileSpeed(0.8f);

        boolean sustainedFastPattern = highSpeedStreak >= VEHICLE_STREAK_LIMIT
                && vehicleScore >= VEHICLE_SCORE_TRIGGER;

        boolean strongWindowPattern = medianSpeed >= VEHICLE_SPEED_SOFT_MPS
                && p80Speed >= 9.5f;

        return sustainedFastPattern || strongWindowPattern;
    }

    private boolean isLikelyGpsJump(float distanceMeters, float deltaSeconds, float accuracyMeters) {
        float impliedSpeed = distanceMeters / Math.max(deltaSeconds, 0.001f);
        return distanceMeters > 140f && impliedSpeed > 15f && accuracyMeters > 10f;
    }

    private boolean isLocationValid(Location newLocation) {
        if (newLocation == null) return false;

        if (cheatingAlreadyReported) {
            return false;
        }

        long nowMs = locationTimeMs(newLocation);
        if (nowMs <= 0) return false;

        if (sessionStartMs <= 0) {
            sessionStartMs = nowMs;
        }

        if (isMockLocation(newLocation)) {
            Log.w(TAG, "Cheating detected: mock location provider used");
            cheatingAlreadyReported = true;
            if (listener != null) listener.onCheatingDetected("fake gps detected");
            return false;
        }

        // fixed by Moemen: ignore fuzzy points instead of punishing the player
        if (!newLocation.hasAccuracy() || newLocation.getAccuracy() > MAX_ACCEPTED_ACCURACY_METERS) {
            return false;
        }

        if (lastRawLocation == null || lastRawTimeMs <= 0) {
            lastRawLocation = newLocation;
            lastRawTimeMs = nowMs;
            addSpeedSample(0f);
            return true;
        }

        long timeDeltaMs = nowMs - lastRawTimeMs;
        if (timeDeltaMs < MIN_TIME_DELTA_MS) {
            return false;
        }

        if (timeDeltaMs > MAX_TIME_DELTA_MS) {
            // after long gaps we reset streak-sensitive signals
            lastRawLocation = newLocation;
            lastRawTimeMs = nowMs;
            highSpeedStreak = Math.max(0, highSpeedStreak - 2);
            vehicleScore = Math.max(0f, vehicleScore - 1.5f);
            addSpeedSample(0f);
            return true;
        }

        float deltaSeconds = timeDeltaMs / 1000f;
        float distanceMeters = lastRawLocation.distanceTo(newLocation);
        float computedSpeed = distanceMeters / Math.max(deltaSeconds, 0.001f);
        float observedSpeed = blendedSpeed(newLocation, computedSpeed);
        float lastMedian = recentMedianSpeed();
        float acceleration = Math.abs(observedSpeed - lastMedian) / Math.max(deltaSeconds, 0.001f);

        addSpeedSample(observedSpeed);
        sessionDistanceMeters += Math.min(distanceMeters, 80f);
        updateVehicleEvidence(observedSpeed, acceleration, distanceMeters, deltaSeconds, nowMs);

        lastRawLocation = newLocation;
        lastRawTimeMs = nowMs;

        if (shouldTriggerVehicleDetection(nowMs)) {
            Log.w(TAG, "Cheating detected: sustained vehicle-like movement");
            cheatingAlreadyReported = true;
            if (listener != null) listener.onCheatingDetected("vehicle movement detected");
            return false;
        }

        // fixed by Moemen: huge jumps are dropped quietly so they cannot kill a legit run
        if (isLikelyGpsJump(distanceMeters, deltaSeconds, newLocation.getAccuracy())) {
            Log.w(TAG, "Dropped GPS jump: dist=" + distanceMeters + "m speed=" + (observedSpeed * 3.6f) + "km/h");
            return false;
        }

        if (observedSpeed > MAX_TRACK_SPEED_MPS) {
            return false;
        }

        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        resetDetectionState();
        startForeground(NOTIFICATION_ID, createNotification());
        startLocationUpdates();
        return START_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI);
        }

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateDistanceMeters(2f)
                .build();

        Looper callbackLooper = locationThread != null ? locationThread.getLooper() : Looper.getMainLooper();
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, callbackLooper);
    }

    private void stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
        sensorManager.unregisterListener(this);
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MapActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Heatmap Tracking")
                .setContentText("Recording your territory walk...")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Tracking Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            int totalSteps = (int) event.values[0];
            if (startSteps < 0) {
                startSteps = totalSteps;
            }
            currentSessionSteps = totalSteps - startSteps;
            if (listener != null) {
                listener.onStepsUpdate(currentSessionSteps);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setLocationUpdateListener(LocationUpdateListener listener) {
        this.listener = listener;
    }

    @Override
    public void onDestroy() {
        stopLocationUpdates();
        if (locationThread != null) {
            // added by Moemen: free background callback thread when service is destroyed
            locationThread.quitSafely();
            locationThread = null;
        }
        super.onDestroy();
    }
}
