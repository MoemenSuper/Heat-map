package com.example.heat_map_java_test_ux;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.card.MaterialCardView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.maps.android.SphericalUtil;
import com.skydoves.colorpickerview.ColorEnvelope;
import com.skydoves.colorpickerview.ColorPickerDialog;
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProfileActivity extends AppCompatActivity {

    private static final String TAG = "ProfileActivity";
    private TextView usernameText, areaStat, distanceStat;
    private FirebaseAuth mAuth;
    private DatabaseReference mUserRef;
    private DatabaseReference mTerritoryRef;
    private ValueEventListener userProfileListener;
    private ValueEventListener territoryStatsListener;

    private MaterialCardView cardNovice, cardExplorer, cardMaster;
    private android.widget.ImageView iconNovice, iconExplorer, iconMaster;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        mAuth = FirebaseAuth.getInstance();
        
        String userId = getIntent().getStringExtra("USER_ID");
        boolean isOtherProfile = userId != null;
        
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            finish();
            return;
        }

        if (!isOtherProfile) {
            userId = currentUser.getUid();
        }

        String dbUrl = "https://heatmap-48e81-default-rtdb.europe-west1.firebasedatabase.app/";
        mUserRef = FirebaseDatabase.getInstance(dbUrl).getReference("users").child(userId);
        mTerritoryRef = FirebaseDatabase.getInstance(dbUrl).getReference("territories");

        usernameText = findViewById(R.id.username_text);
        areaStat = findViewById(R.id.stat_area_value);
        distanceStat = findViewById(R.id.stat_distance_value);
        
        cardNovice = findViewById(R.id.award_novice_card);
        cardExplorer = findViewById(R.id.award_explorer_card);
        cardMaster = findViewById(R.id.award_master_card);
        
        iconNovice = findViewById(R.id.award_novice_icon);
        iconExplorer = findViewById(R.id.award_explorer_icon);
        iconMaster = findViewById(R.id.award_master_icon);

        Toolbar toolbar = findViewById(R.id.toolbar);
        TextView toolbarTitle = findViewById(R.id.toolbar_title);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
            if (isOtherProfile && toolbarTitle != null) {
                toolbarTitle.setText("Member Profile");
            }
        }
        
        View usernameContainer = findViewById(R.id.username_container);
        if (isOtherProfile) {
            usernameContainer.setOnClickListener(null);
            findViewById(R.id.edit_username_hint).setVisibility(View.GONE);
            findViewById(R.id.color_label).setVisibility(View.GONE);
            findViewById(R.id.color_picker_scroll).setVisibility(View.GONE);
            findViewById(R.id.logout_button).setVisibility(View.GONE);
        } else {
            usernameContainer.setOnClickListener(v -> showEditUsernameDialog());
            findViewById(R.id.logout_button).setOnClickListener(v -> {
                mAuth.signOut();
                Intent intent = new Intent(ProfileActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            });
            setupColorPickers();
        }

        loadUserProfile();
        calculateStatsFromExistingData(userId);
    }

    private void setupColorPickers() {
        findViewById(R.id.color_orange).setOnClickListener(v -> updateTerritoryColor("#FF5A1F"));
        findViewById(R.id.color_blue).setOnClickListener(v -> updateTerritoryColor("#007AFF"));
        findViewById(R.id.color_green).setOnClickListener(v -> updateTerritoryColor("#4CAF50"));
        findViewById(R.id.color_purple).setOnClickListener(v -> updateTerritoryColor("#9C27B0"));
        findViewById(R.id.color_pink).setOnClickListener(v -> updateTerritoryColor("#E91E63"));
        findViewById(R.id.color_cyan).setOnClickListener(v -> updateTerritoryColor("#00BCD4"));
        
        findViewById(R.id.color_custom).setOnClickListener(v -> {
            new ColorPickerDialog.Builder(this)
                    .setTitle("Pick Color")
                    .setPreferenceName("MyColorPicker")
                    .setPositiveButton("Choose", new ColorEnvelopeListener() {
                        @Override
                        public void onColorSelected(ColorEnvelope envelope, boolean fromUser) {
                            String hexColor = "#" + envelope.getHexCode();
                            updateTerritoryColor(hexColor);
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                    .attachAlphaSlideBar(false)
                    .attachBrightnessSlideBar(true)
                    .show();
        });
    }

    private void updateTerritoryColor(String hexColor) {
        mUserRef.child("territoryColor").setValue(hexColor)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Territory color updated!", Toast.LENGTH_SHORT).show();
                    updateColorSelectionUI(hexColor);
                });
    }

    private void updateColorSelectionUI(String selectedColor) {
        // Only show selection borders if it's the current user's profile
        if (getIntent().getStringExtra("USER_ID") != null) return;

        int[] ids = {R.id.color_orange, R.id.color_blue, R.id.color_green, 
                     R.id.color_purple, R.id.color_pink, R.id.color_cyan, R.id.color_custom};
        
        for (int id : ids) {
            View view = findViewById(id);
            if (view instanceof MaterialCardView) {
                ((MaterialCardView) view).setStrokeWidth(0);
                // Reset the custom card's background to default gray if we are iterating
                if (id == R.id.color_custom) {
                    ((MaterialCardView) view).setCardBackgroundColor(Color.parseColor("#3A3A3A")); // Using a surface-like color
                }
            }
        }

        View selectedView = null;
        if (selectedColor.equalsIgnoreCase("#FF5A1F")) selectedView = findViewById(R.id.color_orange);
        else if (selectedColor.equalsIgnoreCase("#007AFF")) selectedView = findViewById(R.id.color_blue);
        else if (selectedColor.equalsIgnoreCase("#4CAF50")) selectedView = findViewById(R.id.color_green);
        else if (selectedColor.equalsIgnoreCase("#9C27B0")) selectedView = findViewById(R.id.color_purple);
        else if (selectedColor.equalsIgnoreCase("#E91E63")) selectedView = findViewById(R.id.color_pink);
        else if (selectedColor.equalsIgnoreCase("#00BCD4")) selectedView = findViewById(R.id.color_cyan);
        else {
            selectedView = findViewById(R.id.color_custom);
            // Dynamic Fix: Update the background of the custom card to the picked color
            if (selectedView instanceof MaterialCardView) {
                ((MaterialCardView) selectedView).setCardBackgroundColor(Color.parseColor(selectedColor));
            }
        }

        if (selectedView instanceof MaterialCardView) {
            ((MaterialCardView) selectedView).setStrokeWidth(4);
            // Ensure stroke is visible (white for contrast)
            ((MaterialCardView) selectedView).setStrokeColor(Color.WHITE);
        }
    }

    private void showEditUsernameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Username");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(usernameText.getText().toString());
        input.setTextColor(ContextCompat.getColor(this, R.color.white));
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newUsername = input.getText().toString().trim();
            if (!newUsername.isEmpty()) {
                mUserRef.child("username").setValue(newUsername);
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void loadUserProfile() {
        if (userProfileListener != null) {
            mUserRef.removeEventListener(userProfileListener);
        }

        userProfileListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                User user = snapshot.getValue(User.class);
                if (user != null) {
                    usernameText.setText(user.username);
                    if (user.territoryColor != null) {
                        updateColorSelectionUI(user.territoryColor);
                    }
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };

        mUserRef.addValueEventListener(userProfileListener);
    }

    private void calculateStatsFromExistingData(String userId) {
        if (territoryStatsListener != null) {
            mTerritoryRef.removeEventListener(territoryStatsListener);
        }

        territoryStatsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                double totalAreaM2 = 0;
                double totalDistanceMeters = 0;

                for (DataSnapshot territorySnapshot : snapshot.getChildren()) {
                    Territory t = territorySnapshot.getValue(Territory.class);
                    if (t != null && userId.equals(t.userId)) {
                        totalAreaM2 += t.area;
                        if (t.points != null && t.points.size() > 1) {
                            List<LatLng> path = new ArrayList<>();
                            for (Territory.MyLatLng p : t.points) path.add(new LatLng(p.latitude, p.longitude));
                            totalDistanceMeters += SphericalUtil.computeLength(path);
                        }
                    }
                }
                
                double areaKm2 = totalAreaM2 / 1_000_000.0;
                double distKm = totalDistanceMeters / 1000.0;
                
                if (totalAreaM2 < 1_000_000.0) {
                    areaStat.setText(String.format(Locale.US, "%.0f m²", totalAreaM2));
                } else {
                    areaStat.setText(String.format(Locale.US, "%.1f km²", areaKm2));
                }
                
                distanceStat.setText(String.format(Locale.US, "%.1f km", distKm));
                updateAwards(areaKm2, distKm);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };

        mTerritoryRef.addValueEventListener(territoryStatsListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mUserRef != null && userProfileListener != null) {
            // added by Moemen: avoid stacking profile listeners on repeated screen opens
            mUserRef.removeEventListener(userProfileListener);
        }
        if (mTerritoryRef != null && territoryStatsListener != null) {
            mTerritoryRef.removeEventListener(territoryStatsListener);
        }
    }

    private void updateAwards(double areaKm2, double distKm) {
        if (distKm >= 0.5) unlockAward(cardNovice, iconNovice, "#FFD700");
        if (areaKm2 >= 0.005) unlockAward(cardExplorer, iconExplorer, "#00BFFF");
        if (distKm >= 5.0) unlockAward(cardMaster, iconMaster, "#FF4500");
    }

    private void unlockAward(MaterialCardView card, android.widget.ImageView icon, String colorHex) {
        card.setStrokeColor(android.graphics.Color.parseColor(colorHex));
        icon.setColorFilter(android.graphics.Color.parseColor(colorHex));
        card.setCardBackgroundColor(android.graphics.Color.parseColor("#3A3A3A"));
    }
}
