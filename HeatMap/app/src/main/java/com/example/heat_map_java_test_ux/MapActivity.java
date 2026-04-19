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
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final int ACTIVITY_RECOGNITION_PERMISSION_REQUEST_CODE = 2;
    private static final String CHANNEL_ID_LAND = "land_takeover_channel";
    private static final float DEFAULT_ZOOM = 16f;
    private static final float PLACE_SEARCH_ZOOM = 12.6f;
    private static final float MAX_LOCATION_ACCURACY_METERS = 35f;
    private static final double MIN_SIGNIFICANT_LAND_LOSS_M2 = 100.0; 
    private static final long TRACKING_POLYLINE_UPDATE_THROTTLE_MS = 550L;
    private static final long TRACKING_CAMERA_UPDATE_THROTTLE_MS = 1800L;
    private static final int PLACE_SEARCH_MIN_QUERY_LENGTH = 2;
    private static final int PLACE_SEARCH_MAX_RESULTS = 6;
    private static final long PLACE_SEARCH_DEBOUNCE_MS = 350L;

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private DatabaseReference mDatabase;
    private DatabaseReference mUserRef;
    private DatabaseReference mAllUsersRef;
    private FirebaseAuth mAuth;
    
    private boolean hasCenteredOnUserLocation = false;
    private boolean sessionRunning = false;
    private boolean followUserCamera = true;

    private TextView chipSteps;
    private TextView chipDistance;
    private TextView chipArea;
    private MaterialButton sessionButton;
    private FrameLayout loadingOverlay;
    private AutoCompleteTextView placeSearchInput;
    private ArrayAdapter<String> placeSearchAdapter;
    private MaterialCardView placeSearchCard;
    private android.widget.ImageView searchIconTrigger;

    private RunEdgeGlowView edgeGlowOverlay;

    private final List<LatLng> trackedPath = new ArrayList<>();
    private final List<PlaceSuggestion> placeSuggestions = new ArrayList<>();
    private Polyline activePathPolyline;
    private Location lastAcceptedLocation;
    private float totalDistanceMeters = 0f;
    private final Handler placeSearchHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService geocodeExecutor = Executors.newSingleThreadExecutor();
    private Runnable pendingSearchRunnable;
    private int latestPlaceQueryToken = 0;
    private boolean suppressPlaceSearchWatcher = false;
    private long lastPolylineUiUpdateMs = 0L;
    private long lastCameraUiUpdateMs = 0L;

    private int currentSessionSteps = 0;
    private TrackingService trackingService;
    private boolean isBound = false;
    private boolean sessionInvalidatedByAntiCheat = false;
    private String antiCheatReason = null;

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
                public void onStepsUpdate(int steps) {
                    currentSessionSteps = steps;
                    runOnUiThread(() -> {
                        if (chipSteps != null) {
                            chipSteps.setText(String.valueOf(steps));
                        }
                    });
                }

                @Override
                public void onCheatingDetected(String reason) {
                    runOnUiThread(() -> {
                        sessionInvalidatedByAntiCheat = true;
                        antiCheatReason = reason;
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
    private String currentUserColor = "#FF5A1F"; 
    private String lastRenderedTerritoryFingerprint = "";

    private static class PlaceSuggestion {
        final String label;
        final LatLng latLng;

        PlaceSuggestion(String label, LatLng latLng) {
            this.label = label;
            this.latLng = latLng;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_map);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.map_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        
        createLandTakeoverNotificationChannel();
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingOverlay.setVisibility(View.VISIBLE);
        edgeGlowOverlay = findViewById(R.id.edge_glow_overlay);
        if (edgeGlowOverlay != null) edgeGlowOverlay.setVisibility(View.GONE);

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
        setupPlaceSearchControl();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkActivityRecognitionPermission();
        }

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
            public void onChildChanged(@NonNull DataSnapshot snapshot, String prevChildKey) {
                Territory territory = snapshot.getValue(Territory.class);
                if (territory != null && territory.userId.equals(currentUser.getUid())) {
                    Double prevArea = myTerritoryAreas.get(snapshot.getKey());
                    if (prevArea != null) {
                        double loss = prevArea - territory.area;
                        if (loss >= MIN_SIGNIFICANT_LAND_LOSS_M2) {
                            sendLandLossNotification(loss);
                        }
                    }
                    myTerritoryAreas.put(snapshot.getKey(), territory.area);
                }
            }

            @Override public void onChildAdded(@NonNull DataSnapshot s, String p) {
                Territory t = s.getValue(Territory.class);
                if (t != null && t.userId.equals(currentUser.getUid())) myTerritoryAreas.put(s.getKey(), t.area);
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot s) { myTerritoryAreas.remove(s.getKey()); }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        mDatabase.addChildEventListener(territoryWatchdogListener);
    }

    private void sendLandLossNotification(double lossM2) {
        String msg = String.format(Locale.US, "Alert! You just lost %.0f m² of territory!", lossM2);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_LAND)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Territory Lost")
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify((int)System.currentTimeMillis(), builder.build());
        }
    }

    private void checkIfUsernameSet() {
        mUserRef.child("username").get().addOnSuccessListener(snapshot -> {
            if (!snapshot.exists() || snapshot.getValue(String.class) == null || snapshot.getValue(String.class).isEmpty()) {
                showUsernameDialog();
            }
        });
    }

    private void showUsernameDialog() {
        final AutoCompleteTextView input = new AutoCompleteTextView(this);
        input.setHint("Enter Username");
        input.setPadding(48, 32, 48, 32);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Set Username")
                .setMessage("Choose a name for the leaderboard.")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) mUserRef.child("username").setValue(name);
                    else showUsernameDialog();
                })
                .show();
    }

    private void setupPlaceSearchControl() {
        placeSearchCard = findViewById(R.id.place_search_card);
        placeSearchInput = findViewById(R.id.place_search_input);
        searchIconTrigger = findViewById(R.id.search_icon_trigger);
        
        if (placeSearchInput == null || placeSearchCard == null) return;

        placeSearchAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        placeSearchInput.setAdapter(placeSearchAdapter);
        placeSearchInput.setThreshold(PLACE_SEARCH_MIN_QUERY_LENGTH);

        searchIconTrigger.setOnClickListener(v -> expandSearchBar());
        placeSearchCard.setOnClickListener(v -> expandSearchBar());

        placeSearchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                collapseSearchBar();
            }
        });

        placeSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable editable) {
                if (suppressPlaceSearchWatcher) return;
                requestPlaceSuggestions(editable == null ? "" : editable.toString());
            }
        });

        placeSearchInput.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= placeSuggestions.size()) return;
            focusMapOnSuggestion(placeSuggestions.get(position));
            collapseSearchBar();
        });

        placeSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);

            if (!isSearchAction) return false;

            String query = v.getText() == null ? "" : v.getText().toString().trim();
            if (!query.isEmpty()) {
                performDirectPlaceSearch(query);
            }
            collapseSearchBar();
            return true;
        });
    }

    private void expandSearchBar() {
        if (placeSearchInput.getVisibility() == View.VISIBLE) return;
        
        TransitionManager.beginDelayedTransition((ViewGroup) placeSearchCard.getParent(), 
                new AutoTransition().setDuration(250));
        
        placeSearchInput.setVisibility(View.VISIBLE);
        placeSearchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(placeSearchInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void collapseSearchBar() {
        if (placeSearchInput.getVisibility() == View.GONE) return;
        
        TransitionManager.beginDelayedTransition((ViewGroup) placeSearchCard.getParent(), 
                new AutoTransition().setDuration(250));
        
        placeSearchInput.setVisibility(View.GONE);
        placeSearchInput.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(placeSearchInput.getWindowToken(), 0);
    }

    private void requestPlaceSuggestions(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < PLACE_SEARCH_MIN_QUERY_LENGTH) {
            clearPlaceSuggestions();
            return;
        }

        if (!Geocoder.isPresent()) {
            clearPlaceSuggestions();
            return;
        }

        if (pendingSearchRunnable != null) {
            placeSearchHandler.removeCallbacks(pendingSearchRunnable);
        }

        int token = ++latestPlaceQueryToken;
        pendingSearchRunnable = () -> geocodeExecutor.execute(() -> {
            List<PlaceSuggestion> suggestions = geocodeToSuggestions(query, PLACE_SEARCH_MAX_RESULTS);
            runOnUiThread(() -> {
                if (isFinishing() || token != latestPlaceQueryToken) return;
                updatePlaceSuggestionDropdown(suggestions);
            });
        });
        placeSearchHandler.postDelayed(pendingSearchRunnable, PLACE_SEARCH_DEBOUNCE_MS);
    }

    private void performDirectPlaceSearch(String query) {
        if (!Geocoder.isPresent()) return;

        int token = ++latestPlaceQueryToken;
        geocodeExecutor.execute(() -> {
            List<PlaceSuggestion> suggestions = geocodeToSuggestions(query, 1);
            runOnUiThread(() -> {
                if (isFinishing() || token != latestPlaceQueryToken) return;
                if (suggestions.isEmpty()) {
                    Toast.makeText(this, "No results found", Toast.LENGTH_SHORT).show();
                    return;
                }
                focusMapOnSuggestion(suggestions.get(0));
            });
        });
    }

    private List<PlaceSuggestion> geocodeToSuggestions(String query, int limit) {
        List<PlaceSuggestion> results = new ArrayList<>();
        Set<String> seenLabels = new HashSet<>();

        try {
            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocationName(query, limit);
            if (addresses == null) return results;

            for (Address address : addresses) {
                if (address == null || !address.hasLatitude() || !address.hasLongitude()) continue;
                String label = buildAddressLabel(address);
                if (label.isEmpty() || seenLabels.contains(label)) continue;
                seenLabels.add(label);
                results.add(new PlaceSuggestion(label, new LatLng(address.getLatitude(), address.getLongitude())));
            }
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "Place search failed", e);
        }
        return results;
    }

    private String buildAddressLabel(Address address) {
        List<String> parts = new ArrayList<>();
        addAddressPart(parts, address.getLocality());
        addAddressPart(parts, address.getSubAdminArea());
        addAddressPart(parts, address.getAdminArea());
        addAddressPart(parts, address.getCountryName());
        return String.join(", ", parts);
    }

    private void addAddressPart(List<String> list, String part) {
        if (part != null && !part.isEmpty()) list.add(part);
    }

    private void clearPlaceSuggestions() {
        placeSuggestions.clear();
        placeSearchAdapter.clear();
        placeSearchAdapter.notifyDataSetChanged();
    }

    private void updatePlaceSuggestionDropdown(List<PlaceSuggestion> suggestions) {
        placeSuggestions.clear();
        placeSuggestions.addAll(suggestions);
        
        List<String> labels = new ArrayList<>();
        for (PlaceSuggestion s : suggestions) labels.add(s.label);
        
        suppressPlaceSearchWatcher = true;
        placeSearchAdapter.clear();
        placeSearchAdapter.addAll(labels);
        placeSearchAdapter.notifyDataSetChanged();
        suppressPlaceSearchWatcher = false;
        
        if (!labels.isEmpty() && placeSearchInput.hasFocus()) {
            placeSearchInput.showDropDown();
        }
    }

    private void focusMapOnSuggestion(PlaceSuggestion suggestion) {
        if (mMap == null) return;
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(suggestion.latLng, PLACE_SEARCH_ZOOM));
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        applyMapStyle();
        enableMyLocation();
        
        mMap.getUiSettings().setCompassEnabled(false);

        setupTrackingPathOverlay();
        loadInitialData();
        setupTerritoryWatchdog();
        
        mMap.setOnMapClickListener(latLng -> {
            if (placeSearchInput.getVisibility() == View.VISIBLE) {
                collapseSearchBar();
            }
        });
    }

    private void loadInitialData() {
        usersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                userColors.clear();
                userNames.clear();
                for (DataSnapshot ds : snapshot.getChildren()) {
                    String uid = ds.getKey();
                    String color = ds.child("territoryColor").getValue(String.class);
                    String name = ds.child("username").getValue(String.class);
                    if (uid != null) {
                        if (color != null) userColors.put(uid, color);
                        if (name != null) userNames.put(uid, name);
                    }
                }
                if (lastTerritoriesSnapshot != null) {
                    lastRenderedTerritoryFingerprint = "";
                    renderTerritories(lastTerritoriesSnapshot);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        mAllUsersRef.addValueEventListener(usersListener);

        territoriesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                lastTerritoriesSnapshot = snapshot;
                renderTerritories(snapshot);
                if (loadingOverlay.getVisibility() == View.VISIBLE) {
                    loadingOverlay.setVisibility(View.GONE);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        mDatabase.addValueEventListener(territoriesListener);
    }

    private void renderTerritories(DataSnapshot snapshot) {
        if (mMap == null) return;

        String currentFingerprint = buildTerritoryFingerprint(snapshot);
        if (currentFingerprint.equals(lastRenderedTerritoryFingerprint)) return;
        lastRenderedTerritoryFingerprint = currentFingerprint;

        if (claimedAreaPolygon != null) {
            claimedAreaPolygon.remove();
            claimedAreaPolygon = null;
        }

        for (Polygon p : remotePolygons) p.remove();
        remotePolygons.clear();

        for (DataSnapshot ds : snapshot.getChildren()) {
            Territory t = ds.getValue(Territory.class);
            if (t == null || t.points == null || t.points.size() < 3) continue;

            List<LatLng> pts = new ArrayList<>();
            for (Territory.MyLatLng p : t.points) pts.add(new LatLng(p.latitude, p.longitude));

            String colorStr = userColors.getOrDefault(t.userId, "#808080");
            int color = Color.parseColor(colorStr);
            int fill = Color.argb(100, Color.red(color), Color.green(color), Color.blue(color));

            Polygon p = mMap.addPolygon(new PolygonOptions().addAll(pts)
                    .strokeColor(Color.TRANSPARENT)
                    .strokeWidth(0f)
                    .fillColor(fill).zIndex(1f));
            remotePolygons.add(p);
        }
    }

    private String buildTerritoryFingerprint(DataSnapshot snapshot) {
        StringBuilder fingerprint = new StringBuilder();

        TreeMap<String, String> sortedColors = new TreeMap<>(userColors);
        for (Map.Entry<String, String> entry : sortedColors.entrySet()) {
            fingerprint.append("c:")
                    .append(entry.getKey())
                    .append('=')
                    .append(entry.getValue())
                    .append(';');
        }

        for (DataSnapshot ds : snapshot.getChildren()) {
            String key = ds.getKey() == null ? "no-key" : ds.getKey();
            Territory territory = ds.getValue(Territory.class);

            fingerprint.append("t:")
                    .append(key)
                    .append('|');

            if (territory == null) {
                fingerprint.append("null;");
                continue;
            }

            fingerprint.append(territory.userId == null ? "no-user" : territory.userId)
                    .append('|')
                    .append(territory.timestamp)
                    .append('|')
                    .append(Math.round(territory.area * 10d))
                    .append('|');

            if (territory.points == null) {
                fingerprint.append("no-points;");
                continue;
            }

            fingerprint.append(territory.points.size()).append('|');
            for (Territory.MyLatLng point : territory.points) {
                long lat = Math.round(point.latitude * 1_000_000d);
                long lng = Math.round(point.longitude * 1_000_000d);
                fingerprint.append(lat).append(',').append(lng).append(';');
            }
            fingerprint.append('|');
        }

        return fingerprint.toString();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, TrackingService.class);
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (usersListener != null) mAllUsersRef.removeEventListener(usersListener);
        if (territoriesListener != null) mDatabase.removeEventListener(territoriesListener);
        if (userColorListener != null) mUserRef.child("territoryColor").removeEventListener(userColorListener);
        if (territoryWatchdogListener != null) mDatabase.removeEventListener(territoryWatchdogListener);
        geocodeExecutor.shutdown();
    }

    private void setupPreviewControls() {
        chipSteps = findViewById(R.id.chip_steps);
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
        
        MaterialCardView centerButton = findViewById(R.id.custom_center_button);
        if (centerButton != null) {
            centerButton.setOnClickListener(v -> {
                hasCenteredOnUserLocation = false;
                centerMapOnCurrentLocation();
            });
        }
    }

    private void showEndSessionConfirmation() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("End Run")
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
            @Override public void onCancelled(@NonNull DatabaseError error) {}
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
                .zIndex(2f));
        
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
            mMap.getUiSettings().setMyLocationButtonEnabled(false);
            centerMapOnCurrentLocation();
            checkActivityRecognitionPermission();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);
        }
    }

    private void checkActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                        ACTIVITY_RECOGNITION_PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void startTrackingSession() {
        if (mMap == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, ACTIVITY_RECOGNITION_PERMISSION_REQUEST_CODE);
                return;
            }
        }
        if (claimedAreaPolygon != null) {
            claimedAreaPolygon.remove();
            claimedAreaPolygon = null;
        }
        sessionRunning = true;
        followUserCamera = true;
        sessionInvalidatedByAntiCheat = false;
        antiCheatReason = null;
        trackedPath.clear();
        totalDistanceMeters = 0f;
        currentSessionSteps = 0;
        lastAcceptedLocation = null;
        updateTrackingStats();
        activePathPolyline.setPoints(trackedPath);
        sessionButton.setText(getString(R.string.stop_run));
        sessionButton.setBackgroundResource(R.drawable.map_primary_button);
        if (edgeGlowOverlay != null) edgeGlowOverlay.setVisibility(View.VISIBLE);
        Intent serviceIntent = new Intent(this, TrackingService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void stopTrackingSession() {
        sessionRunning = false;
        followUserCamera = true;
        if (edgeGlowOverlay != null) edgeGlowOverlay.setVisibility(View.GONE);
        Intent serviceIntent = new Intent(this, TrackingService.class);
        stopService(serviceIntent);
        sessionButton.setText(getString(R.string.start_run));
        sessionButton.setBackgroundResource(R.drawable.map_primary_button);
        if (activePathPolyline != null) activePathPolyline.setPoints(new ArrayList<>());
        if (sessionInvalidatedByAntiCheat) {
            trackedPath.clear();
            totalDistanceMeters = 0f;
            currentSessionSteps = 0;
            lastAcceptedLocation = null;
            updateTrackingStats();
            String reason = antiCheatReason == null ? "vehicle movement detected" : antiCheatReason;
            Toast.makeText(this, "Anti-Cheat: " + reason + " run canceled", Toast.LENGTH_LONG).show();
            sessionInvalidatedByAntiCheat = false;
            antiCheatReason = null;
            return;
        }
        float sessionDistKm = totalDistanceMeters / 1000f;
        int sessionSteps = currentSessionSteps;
        double sessionAreaKm2 = 0;
        if (isPathClosed()) {
            double finalAreaM2 = SphericalUtil.computeArea(trackedPath);
            sessionAreaKm2 = finalAreaM2 / 1000000.0;
            handleTerritoryClaim(finalAreaM2, new ArrayList<>(trackedPath));
        } else {
            Toast.makeText(this, "Path not closed. Move closer to start.", Toast.LENGTH_LONG).show();
        }
        updateUserStats();
        showSessionSummary(sessionDistKm, sessionSteps, sessionAreaKm2);
        trackedPath.clear();
        totalDistanceMeters = 0f;
        currentSessionSteps = 0;
        lastAcceptedLocation = null;
        updateTrackingStats();
    }

    private void showSessionSummary(float distanceKm, int steps, double areaKm2) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_session_summary, null);
        
        TextView tvDistance = dialogView.findViewById(R.id.summary_distance);
        TextView tvSteps = dialogView.findViewById(R.id.summary_steps);
        TextView tvArea = dialogView.findViewById(R.id.summary_area);
        MaterialButton closeBtn = dialogView.findViewById(R.id.summary_close_button);

        tvDistance.setText(String.format(Locale.US, "%.2f km", distanceKm));
        tvSteps.setText(String.valueOf(steps));
        tvArea.setText(String.format(Locale.US, "%.3f km²", areaKm2));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        closeBtn.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void updateUserStats() {
        if (totalDistanceMeters <= 0 && currentSessionSteps <= 0) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("totalDistanceWalked", ServerValue.increment(totalDistanceMeters));
        updates.put("totalSteps", ServerValue.increment(currentSessionSteps));
        mUserRef.updateChildren(updates);
    }

    private void handleTerritoryClaim(double area, List<LatLng> sessionPath) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) return;

        org.locationtech.jts.geom.Polygon newPolyJts = createJtsPolygon(sessionPath);
        if (newPolyJts == null) {
            saveTerritoryToFirebase(sessionPath, area);
            return;
        }

        mDatabase.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                    Territory other = snapshot.getValue(Territory.class);
                    if (other == null || other.points == null) continue;
                    captureTerritory(snapshot.getRef(), other, newPolyJts);
                }
                saveTerritoryToFirebase(sessionPath, area);
                if (lastTerritoriesSnapshot != null) {
                    lastRenderedTerritoryFingerprint = ""; 
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void captureTerritory(DatabaseReference ref, Territory victim, org.locationtech.jts.geom.Polygon capturerPolyJts) {
        List<LatLng> victimPoints = new ArrayList<>();
        for (Territory.MyLatLng p : victim.points) victimPoints.add(new LatLng(p.latitude, p.longitude));
        
        org.locationtech.jts.geom.Polygon victimPolyJts = createJtsPolygon(victimPoints);
        if (victimPolyJts == null) return;

        Geometry cleanVictim = victimPolyJts.buffer(0);
        Geometry cleanCapturer = capturerPolyJts.buffer(0);

        if (cleanVictim.intersects(cleanCapturer)) {
            try {
                Geometry remaining = cleanVictim.difference(cleanCapturer);
                double oldArea = victim.area;
                double remainingArea = 0;
                
                if (remaining.isEmpty() || remaining.getArea() < 1e-10) {
                    ref.removeValue();
                } else {
                    remainingArea = computeGeometryAreaM2(remaining);
                    applyGeometryToFirebase(ref, victim, remaining);
                }
                
                double loss = oldArea - remainingArea;
                if (loss > 0) {
                    FirebaseDatabase.getInstance().getReference("users")
                            .child(victim.userId).child("totalAreaClaimed")
                            .setValue(ServerValue.increment(-loss));
                }
            } catch (Exception e) {
                Log.e(TAG, "Territory capture failed", e);
            }
        }
    }

    private double computeGeometryAreaM2(Geometry geometry) {
        if (geometry == null || geometry.isEmpty()) return 0;

        if (geometry instanceof org.locationtech.jts.geom.Polygon) {
            List<LatLng> path = geometryToLatLngList(geometry);
            return path.size() >= 3 ? SphericalUtil.computeArea(path) : 0;
        }

        if (geometry instanceof MultiPolygon || geometry instanceof GeometryCollection) {
            double total = 0;
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                total += computeGeometryAreaM2(geometry.getGeometryN(i));
            }
            return total;
        }

        return 0;
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
        mUserRef.child("totalAreaClaimed").setValue(ServerValue.increment(area));
    }

    private void consumeTrackingLocation(Location location) {
        if (!sessionRunning || location == null) return;
        if (location.getAccuracy() > MAX_LOCATION_ACCURACY_METERS) return;
        if (lastAcceptedLocation != null) totalDistanceMeters += lastAcceptedLocation.distanceTo(location);
        lastAcceptedLocation = location;
        LatLng point = new LatLng(location.getLatitude(), location.getLongitude());
        trackedPath.add(point);

        if (edgeGlowOverlay != null && location.hasSpeed()) {
            edgeGlowOverlay.setSpeedMetersPerSecond(location.getSpeed());
        }
        
        runOnUiThread(() -> {
            long nowMs = SystemClock.uptimeMillis();
            boolean shouldUpdatePolyline = trackedPath.size() <= 3
                    || (nowMs - lastPolylineUiUpdateMs) >= TRACKING_POLYLINE_UPDATE_THROTTLE_MS;
            if (activePathPolyline != null && shouldUpdatePolyline) {
                activePathPolyline.setPoints(trackedPath);
                lastPolylineUiUpdateMs = nowMs;
            }
            updateTrackingStats();
            
            boolean shouldUpdateCamera = !hasCenteredOnUserLocation
                    || (nowMs - lastCameraUiUpdateMs) >= TRACKING_CAMERA_UPDATE_THROTTLE_MS;
            if (((sessionRunning && followUserCamera) || !hasCenteredOnUserLocation) && shouldUpdateCamera) {
                moveCameraToLocation(location);
                lastCameraUiUpdateMs = nowMs;
            }
        });
    }

    private void updateTrackingStats() {
        chipDistance.setText(String.format(Locale.US, "%.2f km", totalDistanceMeters / 1000f));
        chipArea.setText(String.format(Locale.US, "%.3f km²", SphericalUtil.computeArea(trackedPath) / 1000000.0));
        if (!sessionRunning) {
            chipSteps.setText("0");
        }
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

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == LOCATION_PERMISSION_REQUEST_CODE) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation();
            }
        } else if (rc == ACTIVITY_RECOGNITION_PERMISSION_REQUEST_CODE) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Activity recognition permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Steps counting requires activity recognition permission", Toast.LENGTH_LONG).show();
            }
        }
    }
}
