package com.example.heat_map_java_test_ux;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
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
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.maps.android.SphericalUtil;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;

public class MapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "MapActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final String CHANNEL_ID_LAND = "land_takeover_channel";
    private static final float DEFAULT_ZOOM = 16f;
    private static final float MAX_LOCATION_ACCURACY_METERS = 35f;
    private static final double MIN_SIGNIFICANT_LAND_LOSS_M2 = 100.0; 

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private DatabaseReference mDatabase;
    private DatabaseReference mUserRef;
    private DatabaseReference mAllUsersRef;
    private FirebaseAuth mAuth;
    
    private boolean hasCenteredOnUserLocation = false;
    private boolean sessionRunning = false;

    private TextView chipMode;
    private TextView chipDistance;
    private TextView chipArea;
    private MaterialButton sessionButton;
    private FrameLayout loadingOverlay;

    private final List<LatLng> trackedPath = new ArrayList<>();
    private Polyline activePathPolyline;
    private Location lastAcceptedLocation;
    private float totalDistanceMeters = 0f;

    private TrackingService trackingService;
    private boolean isBound = false;

    // Firebase Listeners (for Issue 7 - Memory Leak Fix)
    private ValueEventListener usersListener;
    private ValueEventListener territoriesListener;
    private ValueEventListener userColorListener;
    private ChildEventListener territoryWatchdogListener;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TrackingService.LocalBinder binder = (TrackingService.LocalBinder) service;
            trackingService = binder.getService();
            isBound = true;
            trackingService.setLocationUpdateListener(new TrackingService.LocationUpdateListener() {
                @Override
                public void onLocationUpdate(Location location) {
                    consumeTrackingLocation(location);
                }

                @Override
                public void onCheatingDetected(String reason) {
                    runOnUiThread(() -> {
                        Toast.makeText(MapActivity.this, "Anti-Cheat: " + reason, Toast.LENGTH_LONG).show();
                        stopTrackingSession();
                    });
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    private Polygon claimedAreaPolygon = null;
    private final List<Polygon> remotePolygons = new ArrayList<>();
    private static final float CLOSE_PATH_RADIUS_METERS = 50f;

    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final Map<String, String> userColors = new HashMap<>();
    private final Map<String, String> userNames = new HashMap<>();
    private final Map<String, Double> myTerritoryAreas = new HashMap<>();
    private DataSnapshot lastTerritoriesSnapshot;
    private String currentUserColor = "#FF5A1F"; // Added to track user color for redraws

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        
        createLandTakeoverNotificationChannel();
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingOverlay.setVisibility(View.VISIBLE);

        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            finish();
            return;
        }

        String dbUrl = "https://heatmap-48e81-default-rtdb.europe-west1.firebasedatabase.app/";
        mDatabase = FirebaseDatabase.getInstance(dbUrl).getReference("territories");
        mUserRef = FirebaseDatabase.getInstance(dbUrl).getReference("users").child(currentUser.getUid());
        mAllUsersRef = FirebaseDatabase.getInstance(dbUrl).getReference("users");
        
        checkIfUsernameSet();

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupPreviewControls();

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    private void createLandTakeoverNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID_LAND, 
                    "Land Takeovers", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Get notified when significant parts of your land are taken.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void setupTerritoryWatchdog() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        if (territoryWatchdogListener != null) mDatabase.removeEventListener(territoryWatchdogListener);

        territoryWatchdogListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String prev) {
                Territory territory = snapshot.getValue(Territory.class);
                if (territory != null && territory.userId.equals(currentUser.getUid())) {
                    myTerritoryAreas.put(snapshot.getKey(), territory.area);
                }
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snapshot, String prev) {
                Territory updated = snapshot.getValue(Territory.class);
                if (updated != null && updated.userId.equals(currentUser.getUid())) {
                    String key = snapshot.getKey();
                    Double oldArea = myTerritoryAreas.get(key);
                    
                    if (oldArea != null) {
                        double loss = oldArea - updated.area;
                        if (loss > MIN_SIGNIFICANT_LAND_LOSS_M2) {
                            sendLandLossNotification(String.format(Locale.US, 
                                "Alert! Another player just took %.0f m² of your territory!", loss));
                        }
                    }
                    myTerritoryAreas.put(key, updated.area);
                }
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                Territory removed = snapshot.getValue(Territory.class);
                if (removed != null && removed.userId.equals(currentUser.getUid())) {
                    if (removed.area > MIN_SIGNIFICANT_LAND_LOSS_M2) {
                        sendLandLossNotification("Major Defeat! A large piece of your land was completely conquered.");
                    }
                    myTerritoryAreas.remove(snapshot.getKey());
                }
            }

            @Override
            public void onChildMoved(@NonNull DataSnapshot snapshot, String prev) {}
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        mDatabase.addChildEventListener(territoryWatchdogListener);
    }

    @SuppressLint("MissingPermission")
    private void sendLandLossNotification(String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_LAND)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Territory Lost!")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, TrackingService.class);
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        
        // Re-attach listeners when returning to the activity
        setupTerritoryWatchdog();
        if (mMap != null) {
            loadMapData();
            setupTrackingPathOverlay();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }

        // Fix for Issue 7: Remove Firebase listeners to prevent memory leaks
        if (mAllUsersRef != null && usersListener != null) {
            mAllUsersRef.removeEventListener(usersListener);
        }
        if (mDatabase != null && territoriesListener != null) {
            mDatabase.removeEventListener(territoriesListener);
        }
        if (mUserRef != null && userColorListener != null) {
            mUserRef.child("territoryColor").removeEventListener(userColorListener);
        }
        if (mDatabase != null && territoryWatchdogListener != null) {
            mDatabase.removeEventListener(territoryWatchdogListener);
        }
    }

    private void checkIfUsernameSet() {
        mUserRef.child("username").get().addOnSuccessListener(snapshot -> {
            if (!snapshot.exists()) {
                Toast.makeText(this, "Please set a username in your profile", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        applyMapStyle();
        mMap.getUiSettings().setMyLocationButtonEnabled(true);
        mMap.getUiSettings().setMapToolbarEnabled(false);
        mMap.setPadding(0, 170, 0, 260);

        mMap.setOnPolygonClickListener(polygon -> {
            String userId = (String) polygon.getTag();
            if (userId != null) {
                showOwnerDialog(userId);
            }
        });

        enableMyLocation();
        setupTrackingPathOverlay();
        loadMapData();
    }

    private void showOwnerDialog(String userId) {
        String name = userNames.get(userId);
        if (name == null) name = "Unknown Player";

        new MaterialAlertDialogBuilder(this)
                .setTitle("Territory Owner")
                .setMessage("This land belongs to: " + name)
                .setPositiveButton("View Profile", (dialog, which) -> {
                    Intent intent = new Intent(MapActivity.this, ProfileActivity.class);
                    intent.putExtra("USER_ID", userId);
                    startActivity(intent);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void loadMapData() {
        if (usersListener != null) mAllUsersRef.removeEventListener(usersListener);
        usersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                userColors.clear();
                userNames.clear();
                for (DataSnapshot userSnap : snapshot.getChildren()) {
                    User user = userSnap.getValue(User.class);
                    if (user != null) {
                        userNames.put(userSnap.getKey(), user.username);
                        if (user.territoryColor != null) {
                            userColors.put(userSnap.getKey(), user.territoryColor);
                        }
                    }
                }
                if (lastTerritoriesSnapshot != null) {
                    processAndDrawTerritories(lastTerritoriesSnapshot);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        mAllUsersRef.addValueEventListener(usersListener);

        if (territoriesListener != null) mDatabase.removeEventListener(territoriesListener);
        territoriesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                lastTerritoriesSnapshot = snapshot;
                processAndDrawTerritories(snapshot);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                loadingOverlay.setVisibility(View.GONE);
            }
        };
        mDatabase.addValueEventListener(territoriesListener);
    }

    private void processAndDrawTerritories(DataSnapshot dataSnapshot) {
        if (mMap == null) return;
        
        // Fix for Darkening Colors: Surgical Map Clearing
        // Instead of removing individual polygons, we wipe the map clean
        // This ensures no ghost polygons from previous sessions remain.
        mMap.clear();
        remotePolygons.clear();
        claimedAreaPolygon = null;

        // Immediately redraw active elements like the current run path
        refreshActivePathPolyline();

        List<Territory> territories = new ArrayList<>();
        for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
            Territory territory = snapshot.getValue(Territory.class);
            if (territory != null && territory.points != null) {
                territories.add(territory);
            }
        }

        Collections.sort(territories, (t1, t2) -> Long.compare(t2.timestamp, t1.timestamp));

        List<Geometry> processedGeometries = new ArrayList<>();

        for (Territory t : territories) {
            org.locationtech.jts.geom.Polygon polyJts = createJtsPolygonFromMyLatLng(t.points);
            if (polyJts == null) continue;

            Geometry currentGeom = polyJts.buffer(0);
            
            for (Geometry newer : processedGeometries) {
                if (currentGeom.intersects(newer)) {
                    try {
                        currentGeom = currentGeom.difference(newer);
                    } catch (Exception e) {
                        Log.e(TAG, "Local diff failed", e);
                    }
                }
            }

            if (!currentGeom.isEmpty()) {
                drawGeometry(t, currentGeom);
                processedGeometries.add(polyJts.buffer(0));
            }
        }
        
        loadingOverlay.setVisibility(View.GONE);
    }

    private void drawGeometry(Territory territory, Geometry geom) {
        if (geom instanceof org.locationtech.jts.geom.Polygon) {
            drawSingleJtsPolygon(territory, (org.locationtech.jts.geom.Polygon) geom);
        } else if (geom instanceof MultiPolygon || geom instanceof GeometryCollection) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                Geometry g = geom.getGeometryN(i);
                if (g instanceof org.locationtech.jts.geom.Polygon) {
                    drawSingleJtsPolygon(territory, (org.locationtech.jts.geom.Polygon) g);
                }
            }
        }
    }

    private void drawSingleJtsPolygon(Territory territory, org.locationtech.jts.geom.Polygon jtsPoly) {
        List<LatLng> path = new ArrayList<>();
        Coordinate[] coords = jtsPoly.getExteriorRing().getCoordinates();
        for (int i = 0; i < coords.length - 1; i++) {
            path.add(new LatLng(coords[i].y, coords[i].x));
        }

        String userColorHex = userColors.get(territory.userId);
        if (userColorHex == null) userColorHex = "#FF5A1F";

        int strokeColor = Color.parseColor(userColorHex);
        int fillColor = Color.argb(100, Color.red(strokeColor), Color.green(strokeColor), Color.blue(strokeColor));

        Polygon poly = mMap.addPolygon(new PolygonOptions()
                .addAll(path)
                .strokeColor(strokeColor) 
                .strokeWidth(5f)
                .fillColor(fillColor)
                .clickable(true)
                .zIndex((float) territory.timestamp / 1000000000f) 
        );
        poly.setTag(territory.userId);
        remotePolygons.add(poly);
    }

    private void setupPreviewControls() {
        chipMode = findViewById(R.id.chip_mode);
        chipDistance = findViewById(R.id.chip_distance);
        chipArea = findViewById(R.id.chip_area);
        sessionButton = findViewById(R.id.session_button);
        MaterialCardView profileButton = findViewById(R.id.profile_button);
        MaterialCardView leaderboardButton = findViewById(R.id.leaderboard_button);

        profileButton.setOnClickListener(v -> startActivity(new Intent(MapActivity.this, ProfileActivity.class)));

        leaderboardButton.setOnClickListener(v -> startActivity(new Intent(MapActivity.this, LeaderboardActivity.class)));

        sessionButton.setOnClickListener(v -> {
            if (sessionRunning) {
                showEndSessionConfirmation();
            } else {
                startTrackingSession();
            }
        });
    }

    private void showEndSessionConfirmation() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("End Session")
                .setMessage("Are you sure you want to end your run? If you have closed a path, your territory will be claimed.")
                .setPositiveButton("End Run", (dialog, which) -> stopTrackingSession())
                .setNegativeButton("Keep Going", null)
                .show();
    }

    private void setupTrackingPathOverlay() {
        if (mMap == null) return;
        
        if (userColorListener != null) mUserRef.child("territoryColor").removeEventListener(userColorListener);

        userColorListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    currentUserColor = snapshot.getValue(String.class);
                } else {
                    currentUserColor = "#FF5A1F";
                }
                refreshActivePathPolyline();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        mUserRef.child("territoryColor").addValueEventListener(userColorListener);
    }

    private void refreshActivePathPolyline() {
        if (mMap == null) return;
        if (activePathPolyline != null) activePathPolyline.remove();
        
        activePathPolyline = mMap.addPolyline(new PolylineOptions()
                .color(Color.parseColor(currentUserColor))
                .width(14f)
                .geodesic(true)
                .zIndex(10f));
        
        if (sessionRunning) {
            activePathPolyline.setPoints(trackedPath);
        }
    }

    private void applyMapStyle() {
        try {
            mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_heatmap));
        } catch (Exception e) {
            Log.w(TAG, "Map style failed", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            centerMapOnCurrentLocation();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void startTrackingSession() {
        if (mMap == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        sessionRunning = true;
        trackedPath.clear();
        totalDistanceMeters = 0f;
        lastAcceptedLocation = null;
        updateTrackingStats();
        activePathPolyline.setPoints(trackedPath);

        sessionButton.setText("STOP SESSION");
        chipMode.setText("Tracking");

        Intent serviceIntent = new Intent(this, TrackingService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void stopTrackingSession() {
        sessionRunning = false;
        
        Intent serviceIntent = new Intent(this, TrackingService.class);
        stopService(serviceIntent);

        sessionButton.setText("START SESSION");
        chipMode.setText("Preview");

        if (activePathPolyline != null) activePathPolyline.setPoints(new ArrayList<>());

        if (isPathClosed()) {
            double area = drawClaimedAreaPolygon();
            if (area < 1000000.0) {
                chipArea.setText(String.format(Locale.US, "%.0f m²", area));
            } else {
                chipArea.setText(String.format(Locale.US, "%.1f km²", area / 1000000.0));
            }
            handleTerritoryClaim(area);
            updateUserDistanceStat();
        } else {
            Toast.makeText(this, "Path not closed. Move closer to start.", Toast.LENGTH_LONG).show();
        }
    }

    private void updateUserDistanceStat() {
        mUserRef.child("totalDistanceWalked").get().addOnSuccessListener(snapshot -> {
            double current = 0;
            if (snapshot.exists()) {
                Double val = snapshot.getValue(Double.class);
                if (val != null) current = val;
            }
            mUserRef.child("totalDistanceWalked").setValue(current + totalDistanceMeters);
        });
    }

    private void handleTerritoryClaim(double area) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        org.locationtech.jts.geom.Polygon newPolyJts = createJtsPolygon(trackedPath);
        if (newPolyJts == null) {
            saveTerritoryToFirebase(trackedPath, area);
            return;
        }

        mDatabase.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                    Territory other = snapshot.getValue(Territory.class);
                    if (other == null || other.points == null || other.userId.equals(currentUser.getUid())) continue;
                    subtractAndUpdateTerritory(snapshot.getRef(), other, newPolyJts);
                }
                saveTerritoryToFirebase(trackedPath, area);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void subtractAndUpdateTerritory(DatabaseReference ref, Territory other, org.locationtech.jts.geom.Polygon newPolyJts) {
        List<LatLng> otherPoints = new ArrayList<>();
        for (Territory.MyLatLng p : other.points) otherPoints.add(new LatLng(p.latitude, p.longitude));
        
        org.locationtech.jts.geom.Polygon otherPolyJts = createJtsPolygon(otherPoints);
        if (otherPolyJts == null) return;

        Geometry cleanOther = otherPolyJts.buffer(0);
        Geometry cleanNew = newPolyJts.buffer(0);

        if (cleanOther.intersects(cleanNew)) {
            try {
                Geometry difference = cleanOther.difference(cleanNew);
                
                // Fix for Issue 2: Calculate land loss and update victim stats
                double oldArea = other.area;
                double newArea = 0;
                
                if (difference.isEmpty() || difference.getArea() < 1e-10) {
                    ref.removeValue();
                } else {
                    newArea = SphericalUtil.computeArea(geometryToLatLngList(difference));
                    applyGeometryToFirebase(ref, other, difference);
                }
                
                double loss = oldArea - newArea;
                if (loss > 0) {
                    FirebaseDatabase.getInstance().getReference("users")
                            .child(other.userId).child("totalAreaClaimed")
                            .setValue(ServerValue.increment(-loss));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Diff failed", e);
            }
        }
    }

    private List<LatLng> geometryToLatLngList(Geometry geometry) {
        List<LatLng> path = new ArrayList<>();
        if (geometry instanceof org.locationtech.jts.geom.Polygon) {
            Coordinate[] coords = ((org.locationtech.jts.geom.Polygon) geometry).getExteriorRing().getCoordinates();
            for (int i = 0; i < coords.length - 1; i++) path.add(new LatLng(coords[i].y, coords[i].x));
        } else if (geometry instanceof MultiPolygon || geometry instanceof GeometryCollection) {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                path.addAll(geometryToLatLngList(geometry.getGeometryN(i)));
            }
        }
        return path;
    }

    private void applyGeometryToFirebase(DatabaseReference ref, Territory original, Geometry diff) {
        if (diff instanceof org.locationtech.jts.geom.Polygon) {
            updateTerritoryWithJtsPoly(ref, (org.locationtech.jts.geom.Polygon) diff);
        } else if (diff instanceof MultiPolygon || diff instanceof GeometryCollection) {
            ref.removeValue();
            for (int i = 0; i < diff.getNumGeometries(); i++) {
                Geometry g = diff.getGeometryN(i);
                if (g instanceof org.locationtech.jts.geom.Polygon && !g.isEmpty()) {
                    pushNewTerritoryFromJtsPoly(original.userId, original.userEmail, (org.locationtech.jts.geom.Polygon) g);
                }
            }
        }
    }

    private void updateTerritoryWithJtsPoly(DatabaseReference ref, org.locationtech.jts.geom.Polygon poly) {
        List<Territory.MyLatLng> points = jtsPolygonToMyLatLng(poly);
        if (points.isEmpty()) {
            ref.removeValue();
        } else {
            ref.child("points").setValue(points);
            ref.child("area").setValue(SphericalUtil.computeArea(myLatLngToLatLng(points)));
        }
    }

    private void pushNewTerritoryFromJtsPoly(String userId, String email, org.locationtech.jts.geom.Polygon poly) {
        List<Territory.MyLatLng> points = jtsPolygonToMyLatLng(poly);
        if (points.size() < 3) return;
        double area = SphericalUtil.computeArea(myLatLngToLatLng(points));
        mDatabase.push().setValue(new Territory(userId, email, points, area, System.currentTimeMillis()));
    }

    private org.locationtech.jts.geom.Polygon createJtsPolygonFromMyLatLng(List<Territory.MyLatLng> points) {
        List<LatLng> latLngs = new ArrayList<>();
        for (Territory.MyLatLng p : points) latLngs.add(new LatLng(p.latitude, p.longitude));
        return createJtsPolygon(latLngs);
    }

    private org.locationtech.jts.geom.Polygon createJtsPolygon(List<LatLng> path) {
        if (path.size() < 3) return null;
        List<Coordinate> coordsList = new ArrayList<>();
        for (LatLng l : path) {
            Coordinate c = new Coordinate(l.longitude, l.latitude);
            if (coordsList.isEmpty() || coordsList.get(coordsList.size()-1).distance(c) > 1e-9) coordsList.add(c);
        }
        if (coordsList.size() < 3) return null;
        Coordinate[] coords = new Coordinate[coordsList.size() + 1];
        for (int i = 0; i < coordsList.size(); i++) coords[i] = coordsList.get(i);
        coords[coordsList.size()] = coords[0];
        try {
            org.locationtech.jts.geom.Polygon p = geometryFactory.createPolygon(geometryFactory.createLinearRing(coords), null);
            return (org.locationtech.jts.geom.Polygon) p.buffer(0);
        } catch (Exception e) { return null; }
    }

    private List<Territory.MyLatLng> jtsPolygonToMyLatLng(org.locationtech.jts.geom.Polygon poly) {
        List<Territory.MyLatLng> res = new ArrayList<>();
        Coordinate[] coords = poly.getExteriorRing().getCoordinates();
        for (int i = 0; i < coords.length - 1; i++) res.add(new Territory.MyLatLng(coords[i].y, coords[i].x));
        return res;
    }

    private List<LatLng> myLatLngToLatLng(List<Territory.MyLatLng> pts) {
        List<LatLng> res = new ArrayList<>();
        for (Territory.MyLatLng p : pts) res.add(new LatLng(p.latitude, p.longitude));
        return res;
    }

    private void saveTerritoryToFirebase(List<LatLng> path, double area) {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) return;
        
        List<Territory.MyLatLng> pts = new ArrayList<>();
        for (LatLng l : path) pts.add(new Territory.MyLatLng(l.latitude, l.longitude));
        
        mDatabase.push().setValue(new Territory(user.getUid(), user.getEmail(), pts, area, System.currentTimeMillis()));
        
        // Fix for Issue 1: Update the user's totalAreaClaimed stat for the leaderboard
        mUserRef.child("totalAreaClaimed").setValue(ServerValue.increment(area));
    }

    private void consumeTrackingLocation(Location location) {
        if (!sessionRunning || location == null) return;
        if (location.getAccuracy() > MAX_LOCATION_ACCURACY_METERS) return;
        if (lastAcceptedLocation != null) totalDistanceMeters += lastAcceptedLocation.distanceTo(location);
        lastAcceptedLocation = location;
        LatLng point = new LatLng(location.getLatitude(), location.getLongitude());
        trackedPath.add(point);
        
        runOnUiThread(() -> {
            if (activePathPolyline != null) {
                activePathPolyline.setPoints(trackedPath);
            }
            updateTrackingStats();
            
            // Fix: Camera follows the user during a session
            if (sessionRunning || !hasCenteredOnUserLocation) {
                moveCameraToLocation(location);
            }
        });
    }

    private void updateTrackingStats() {
        chipDistance.setText(String.format(Locale.US, "%.2f km", totalDistanceMeters / 1000f));
        chipArea.setText(String.format(Locale.US, "%d pts", trackedPath.size()));
    }

    @SuppressLint("MissingPermission")
    private void centerMapOnCurrentLocation() {
        if (hasCenteredOnUserLocation) return;
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, new CancellationTokenSource().getToken())
                .addOnSuccessListener(l -> { if (l != null) moveCameraToLocation(l); });
    }

    private void moveCameraToLocation(Location l) {
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(l.getLatitude(), l.getLongitude()), DEFAULT_ZOOM));
        hasCenteredOnUserLocation = true;
    }

    private boolean isPathClosed() {
        if (trackedPath.size() < 3) return false;
        return SphericalUtil.computeDistanceBetween(trackedPath.get(0), trackedPath.get(trackedPath.size()-1)) <= CLOSE_PATH_RADIUS_METERS;
    }

    private double drawClaimedAreaPolygon() {
        if (claimedAreaPolygon != null) claimedAreaPolygon.remove();
        
        mUserRef.child("territoryColor").get().addOnSuccessListener(snapshot -> {
            String color = "#FF5A1F";
            if (snapshot.exists()) color = snapshot.getValue(String.class);
            int stroke = Color.parseColor(color);
            int fill = Color.argb(120, Color.red(stroke), Color.green(stroke), Color.blue(stroke));
            
            claimedAreaPolygon = mMap.addPolygon(new PolygonOptions().addAll(trackedPath)
                    .strokeColor(stroke).strokeWidth(8f)
                    .fillColor(fill).zIndex(100f));
        });

        return SphericalUtil.computeArea(trackedPath);
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == LOCATION_PERMISSION_REQUEST_CODE && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) enableMyLocation();
    }
}
