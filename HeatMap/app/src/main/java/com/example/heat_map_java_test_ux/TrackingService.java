package com.example.heat_map_java_test_ux;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
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

public class TrackingService extends Service {
    private static final String TAG = "TrackingService";
    private static final String CHANNEL_ID = "TrackingServiceChannel";
    private static final int NOTIFICATION_ID = 12345;

    // --- Anti-cheat parameters ---
    private static final float MAX_WALK_SPEED_MPS = 6.0f; // ~21.6 km/h (World-class sprint speed)
    private static final float MAX_ACCELERATION_MPSS = 4.0f; // Max realistic acceleration for a human
    private static final float MIN_ACCURACY_METERS = 30f; // Reject locations with poor signal
    private static final int CONSECUTIVE_VIOLATIONS_LIMIT = 3; // Allow for 2 GPS glitches before blocking
    
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private final IBinder binder = new LocalBinder();
    
    private Location lastValidLocation = null;
    private long lastLocationTime = 0;
    private float lastSpeed = 0;
    private int consecutiveSpeedViolations = 0;

    public interface LocationUpdateListener {
        void onLocationUpdate(Location location);
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
        createNotificationChannel();
        
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    if (isLocationValid(location)) {
                        if (listener != null) {
                            listener.onLocationUpdate(location);
                        }
                    }
                }
            }
        };
    }

    private boolean isLocationValid(Location newLocation) {
        // 1. GPS Accuracy check (reject fuzzy data)
        if (newLocation.getAccuracy() > MIN_ACCURACY_METERS) {
            return false; 
        }

        // 2. Mock location check (detects Fake GPS apps)
        boolean isMock;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock = newLocation.isMock();
        } else {
            isMock = newLocation.isFromMockProvider();
        }

        if (isMock) {
            Log.w(TAG, "Cheating detected: Mock location provider used");
            if (listener != null) listener.onCheatingDetected("Fake GPS detected!");
            return false;
        }

        if (lastValidLocation != null) {
            long timeDeltaMillis = newLocation.getTime() - lastLocationTime;
            if (timeDeltaMillis <= 0) return false;
            float timeDeltaSec = timeDeltaMillis / 1000f;

            float distance = lastValidLocation.distanceTo(newLocation);
            float speed = distance / timeDeltaSec;

            // 3. Realistic Speed Check
            if (speed > MAX_WALK_SPEED_MPS) {
                consecutiveSpeedViolations++;
                Log.w(TAG, "Speed violation: " + (speed * 3.6) + " km/h (Violation #" + consecutiveSpeedViolations + ")");
                
                if (consecutiveSpeedViolations >= CONSECUTIVE_VIOLATIONS_LIMIT) {
                    if (listener != null) listener.onCheatingDetected("Moving too fast (Car/Bicycle detected)");
                    return false;
                }
                return false; // Skip this point, but don't ban yet
            } else {
                consecutiveSpeedViolations = Math.max(0, consecutiveSpeedViolations - 1);
            }

            // 4. Teleportation / Acceleration Check
            float speedChange = Math.abs(speed - lastSpeed);
            float acceleration = speedChange / timeDeltaSec;
            
            // If the jump is physically impossible for a human (e.g. teleporting 1km)
            if (distance > 150 && acceleration > MAX_ACCELERATION_MPSS) {
                Log.w(TAG, "Impossible acceleration: " + acceleration + " m/s² over " + distance + "m");
                if (listener != null) listener.onCheatingDetected("Teleportation detected!");
                return false;
            }
            
            lastSpeed = speed;
        }

        lastValidLocation = newLocation;
        lastLocationTime = newLocation.getTime();
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        startLocationUpdates();
        return START_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateDistanceMeters(2f)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback);
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
        super.onDestroy();
    }
}
