package com.example.heat_map_java_test_ux;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class LeaderboardActivity extends AppCompatActivity {

    private LeaderboardAdapter adapter;
    private final List<User> userList = new ArrayList<>();
    private DatabaseReference usersRef;
    private ProgressBar progressBar;
    private boolean sortByTerritory = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_leaderboard);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        progressBar = findViewById(R.id.loading_progress);
        RecyclerView recyclerView = findViewById(R.id.leaderboard_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LeaderboardAdapter(userList);
        recyclerView.setAdapter(adapter);

        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                sortByTerritory = tab.getPosition() == 0;
                sortAndDisplay();
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        String dbUrl = "https://heatmap-48e81-default-rtdb.europe-west1.firebasedatabase.app/";
        usersRef = FirebaseDatabase.getInstance(dbUrl).getReference("users");

        fetchUsers();
    }

    private void fetchUsers() {
        progressBar.setVisibility(View.VISIBLE);
        usersRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                userList.clear();
                for (DataSnapshot data : snapshot.getChildren()) {
                    User user = data.getValue(User.class);
                    if (user != null) {
                        // added by Moemen: fallback for older records that may miss userId in the object
                        if (user.userId == null || user.userId.trim().isEmpty()) {
                            user.userId = data.getKey();
                        }
                        userList.add(user);
                    }
                }
                sortAndDisplay();
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                progressBar.setVisibility(View.GONE);
            }
        });
    }

    private void sortAndDisplay() {
        if (sortByTerritory) {
            Collections.sort(userList, (u1, u2) -> Double.compare(u2.totalAreaClaimed, u1.totalAreaClaimed));
        } else {
            Collections.sort(userList, (u1, u2) -> Double.compare(u2.totalDistanceWalked, u1.totalDistanceWalked));
        }
        adapter.notifyDataSetChanged();
    }

    class LeaderboardAdapter extends RecyclerView.Adapter<LeaderboardAdapter.ViewHolder> {
        private final List<User> users;

        LeaderboardAdapter(List<User> users) {
            this.users = users;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_leaderboard, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            User user = users.get(position);
            holder.rank.setText(String.valueOf(position + 1));
            holder.name.setText(user.username != null ? user.username : "Anonymous");
            
            if (sortByTerritory) {
                double areaM2 = user.totalAreaClaimed;
                if (areaM2 < 1_000_000.0) {
                    holder.value.setText(String.format(Locale.US, "%.0f m²", areaM2));
                } else {
                    holder.value.setText(String.format(Locale.US, "%.2f km²", areaM2 / 1_000_000.0));
                }
            } else {
                holder.value.setText(String.format(Locale.US, "%.1f km", user.totalDistanceWalked / 1000.0));
            }

            holder.itemView.setOnClickListener(v -> {
                if (user.userId == null || user.userId.trim().isEmpty()) {
                    return;
                }

                // added by Moemen: tap a leaderboard row to jump straight to that player profile
                Intent intent = new Intent(LeaderboardActivity.this, ProfileActivity.class);
                intent.putExtra("USER_ID", user.userId);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return users.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView rank, name, value;

            ViewHolder(View itemView) {
                super(itemView);
                rank = itemView.findViewById(R.id.rank_text);
                name = itemView.findViewById(R.id.username_text);
                value = itemView.findViewById(R.id.value_text);
            }
        }
    }
}
