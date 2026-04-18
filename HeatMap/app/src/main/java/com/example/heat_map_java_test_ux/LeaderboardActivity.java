package com.example.heat_map_java_test_ux;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.maps.android.SphericalUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class LeaderboardActivity extends AppCompatActivity {

    private enum StatsPeriod {
        THIS_MONTH,
        LAST_MONTH,
        THREE_MONTHS,
        ALL_TIME
    }

    private static class PeriodWindow {
        final long startMs;
        final long endMs;
        final int bucketCount;

        PeriodWindow(long startMs, long endMs, int bucketCount) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.bucketCount = bucketCount;
        }
    }

    private LeaderboardAdapter adapter;
    private final List<User> userList = new ArrayList<>();
    private DatabaseReference usersRef;
    private DatabaseReference territoriesRef;
    private DatabaseReference currentUserRef;
    private Query myTerritoriesQuery;
    private ValueEventListener usersListener;
    private ValueEventListener userColorListener;
    private ValueEventListener territoriesListener;

    private FirebaseAuth auth;
    private String currentUserId;
    private String currentUserColorHex = "#FF5A1F";

    private ProgressBar progressBar;
    private int selectedTabIndex = 0;
    private boolean isMesStatsTab = false;
    private boolean pendingScrollToCurrentUser = true;

    private View mesStatsScroll;
    private RecyclerView leaderboardRecycler;
    private MaterialButtonToggleGroup periodToggleGroup;
    private MaterialButton periodThisMonthButton, periodLastMonthButton, periodThreeMonthsButton, periodAllTimeButton;

    private MaterialCardView kpiSurfaceCard;
    private TextView kpiSurfaceValue;
    private ProgressBar kpiSurfaceProgress;

    private TextView kpiDistanceValue;
    private ProgressBar kpiDistanceProgress;

    private WeeklyAreaChartView weeklyChartView;

    private final List<Territory> myTerritories = new ArrayList<>();
    private StatsPeriod selectedStatsPeriod = StatsPeriod.THIS_MONTH;

    // Podium Views
    private View podiumContainer;
    private TextView rank1Name, rank2Name, rank3Name;
    private CelebrationView celebrationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_leaderboard);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        progressBar = findViewById(R.id.loading_progress);
        leaderboardRecycler = findViewById(R.id.leaderboard_recycler);
        leaderboardRecycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LeaderboardAdapter(userList);
        leaderboardRecycler.setAdapter(adapter);

        mesStatsScroll = findViewById(R.id.mes_stats_scroll);
        podiumContainer = findViewById(R.id.podium_container);
        rank1Name = findViewById(R.id.rank1_name);
        rank2Name = findViewById(R.id.rank2_name);
        rank3Name = findViewById(R.id.rank3_name);
        celebrationView = findViewById(R.id.celebration_view);
        
        setupMesStatsViews();

        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                selectedTabIndex = position;
                if (position == 3) {
                    isMesStatsTab = true;
                    leaderboardRecycler.setVisibility(View.GONE);
                    podiumContainer.setVisibility(View.GONE);
                    mesStatsScroll.setVisibility(View.VISIBLE);
                    if (celebrationView != null) celebrationView.stop();
                    refreshMesStatsUi();
                    return;
                }

                isMesStatsTab = false;
                mesStatsScroll.setVisibility(View.GONE);
                leaderboardRecycler.setVisibility(View.VISIBLE);
                podiumContainer.setVisibility(View.VISIBLE);
                if (celebrationView != null) celebrationView.start();
                pendingScrollToCurrentUser = true;
                sortAndDisplay();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        String dbUrl = "https://heatmap-48e81-default-rtdb.europe-west1.firebasedatabase.app/";
        usersRef = FirebaseDatabase.getInstance(dbUrl).getReference("users");
        territoriesRef = FirebaseDatabase.getInstance(dbUrl).getReference("territories");

        auth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            currentUserId = currentUser.getUid();
            currentUserRef = usersRef.child(currentUserId);
            myTerritoriesQuery = territoriesRef.orderByChild("userId").equalTo(currentUserId);
            attachMesStatsListeners();
        }

        fetchUsers();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (usersRef != null && usersListener != null) usersRef.removeEventListener(usersListener);
        if (currentUserRef != null && userColorListener != null) currentUserRef.child("territoryColor").removeEventListener(userColorListener);
        if (myTerritoriesQuery != null && territoriesListener != null) myTerritoriesQuery.removeEventListener(territoriesListener);
        if (celebrationView != null) celebrationView.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (celebrationView != null && !isMesStatsTab) {
            celebrationView.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (celebrationView != null) {
            celebrationView.stop();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        fetchUsers();
        attachMesStatsListeners();
    }

    private void setupMesStatsViews() {
        periodToggleGroup = findViewById(R.id.mes_period_group);
        periodThisMonthButton = findViewById(R.id.period_this_month);
        periodLastMonthButton = findViewById(R.id.period_last_month);
        periodThreeMonthsButton = findViewById(R.id.period_three_months);
        periodAllTimeButton = findViewById(R.id.period_all_time);

        periodThisMonthButton.setCheckable(true);
        periodLastMonthButton.setCheckable(true);
        periodThreeMonthsButton.setCheckable(true);
        periodAllTimeButton.setCheckable(true);

        kpiSurfaceCard = findViewById(R.id.kpi_surface_card);
        kpiSurfaceValue = findViewById(R.id.kpi_surface_value);
        kpiSurfaceProgress = findViewById(R.id.kpi_surface_progress);

        kpiDistanceValue = findViewById(R.id.kpi_distance_value);
        kpiDistanceProgress = findViewById(R.id.kpi_distance_progress);

        weeklyChartView = findViewById(R.id.mes_chart);

        periodToggleGroup.check(R.id.period_this_month);
        updatePeriodToggleStyles(R.id.period_this_month);
        periodToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            selectedStatsPeriod = periodFromButton(checkedId);
            updatePeriodToggleStyles(checkedId);
            refreshMesStatsUi();
        });
    }

    private void updatePeriodToggleStyles(int checkedId) {
        stylePeriodButton(periodThisMonthButton, checkedId == R.id.period_this_month);
        stylePeriodButton(periodLastMonthButton, checkedId == R.id.period_last_month);
        stylePeriodButton(periodThreeMonthsButton, checkedId == R.id.period_three_months);
        stylePeriodButton(periodAllTimeButton, checkedId == R.id.period_all_time);
    }

    private void stylePeriodButton(MaterialButton button, boolean selected) {
        int selectedStroke = ContextCompat.getColor(this, R.color.heat_start);
        int unselectedStroke = ContextCompat.getColor(this, R.color.divider_color);
        int selectedText = ContextCompat.getColor(this, R.color.white);
        int unselectedText = ContextCompat.getColor(this, R.color.text_secondary);
        int selectedBackground = Color.parseColor("#33FF5A1F");

        button.setStrokeColor(ColorStateList.valueOf(selected ? selectedStroke : unselectedStroke));
        button.setStrokeWidth(selected ? 3 : 2);
        button.setTextColor(selected ? selectedText : unselectedText);
        button.setBackgroundTintList(ColorStateList.valueOf(selected ? selectedBackground : Color.TRANSPARENT));
    }

    private void attachMesStatsListeners() {
        if (currentUserRef == null || myTerritoriesQuery == null) return;

        userColorListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String color = snapshot.getValue(String.class);
                    if (color != null && !color.trim().isEmpty()) currentUserColorHex = color;
                }
                if (isMesStatsTab) refreshMesStatsUi();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        currentUserRef.child("territoryColor").addValueEventListener(userColorListener);

        territoriesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                myTerritories.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Territory territory = child.getValue(Territory.class);
                    if (territory != null) myTerritories.add(territory);
                }
                if (isMesStatsTab) refreshMesStatsUi();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        myTerritoriesQuery.addValueEventListener(territoriesListener);
    }

    private void fetchUsers() {
        progressBar.setVisibility(View.VISIBLE);
        usersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                userList.clear();
                for (DataSnapshot data : snapshot.getChildren()) {
                    User user = data.getValue(User.class);
                    if (user != null) {
                        if (user.userId == null || user.userId.trim().isEmpty()) user.userId = data.getKey();
                        userList.add(user);
                    }
                }
                sortAndDisplay();
                progressBar.setVisibility(View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { progressBar.setVisibility(View.GONE); }
        };
        usersRef.addValueEventListener(usersListener);
    }

    private void refreshMesStatsUi() {
        if (!isMesStatsTab || currentUserId == null) return;

        PeriodWindow currentWindow = buildCurrentWindow(selectedStatsPeriod);
        List<Territory> currentTerritories = filterTerritories(myTerritories, currentWindow.startMs, currentWindow.endMs);

        double totalSurface = sumSurface(currentTerritories);
        double totalDistanceKm = sumDistanceKm(currentTerritories);

        animateSurfaceValue(kpiSurfaceValue, totalSurface);
        animateDistanceValue(kpiDistanceValue, totalDistanceKm);

        List<Float> currentWeekly = buildWeeklyBreakdown(currentTerritories, currentWindow);
        List<String> labels = buildWeekLabels(currentWindow.bucketCount);
        weeklyChartView.setChartData(currentWeekly, new ArrayList<>(), labels, Color.parseColor(currentUserColorHex));
    }

    private StatsPeriod periodFromButton(int checkedId) {
        if (checkedId == R.id.period_last_month) return StatsPeriod.LAST_MONTH;
        if (checkedId == R.id.period_three_months) return StatsPeriod.THREE_MONTHS;
        if (checkedId == R.id.period_all_time) return StatsPeriod.ALL_TIME;
        return StatsPeriod.THIS_MONTH;
    }

    private PeriodWindow buildCurrentWindow(StatsPeriod period) {
        long now = System.currentTimeMillis();
        if (period == StatsPeriod.THIS_MONTH) return new PeriodWindow(startOfMonth(0), now, 4);
        if (period == StatsPeriod.LAST_MONTH) return new PeriodWindow(startOfMonth(-1), endOfMonth(-1), 4);
        if (period == StatsPeriod.THREE_MONTHS) return new PeriodWindow(startOfMonth(-2), now, 12);
        long start = now;
        for (Territory territory : myTerritories) start = Math.min(start, territory.timestamp);
        return new PeriodWindow(start, now, 12);
    }

    private long startOfMonth(int monthOffset) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MONTH, monthOffset);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private long endOfMonth(int monthOffset) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MONTH, monthOffset);
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        calendar.set(Calendar.HOUR_OF_DAY, 23); calendar.set(Calendar.MINUTE, 59); calendar.set(Calendar.SECOND, 59); calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTimeInMillis();
    }

    private List<Territory> filterTerritories(List<Territory> source, long start, long end) {
        List<Territory> result = new ArrayList<>();
        for (Territory territory : source) if (territory.timestamp >= start && territory.timestamp <= end) result.add(territory);
        return result;
    }

    private double sumSurface(List<Territory> territories) {
        double total = 0d;
        for (Territory territory : territories) total += territory.area;
        return total;
    }

    private double sumDistanceKm(List<Territory> territories) {
        double totalKm = 0d;
        for (Territory territory : territories) {
            if (territory.points == null || territory.points.size() < 2) continue;
            List<LatLng> path = new ArrayList<>();
            for (Territory.MyLatLng point : territory.points) path.add(new LatLng(point.latitude, point.longitude));
            totalKm += SphericalUtil.computeLength(path) / 1000d;
        }
        return totalKm;
    }

    private List<Float> buildWeeklyBreakdown(List<Territory> territories, PeriodWindow window) {
        List<Float> values = new ArrayList<>();
        for (int i = 0; i < window.bucketCount; i++) values.add(0f);
        long duration = Math.max(1, window.endMs - window.startMs + 1);
        for (Territory territory : territories) {
            int index = (int) Math.floor(((double) (territory.timestamp - window.startMs) / (double) duration) * window.bucketCount);
            index = Math.max(0, Math.min(window.bucketCount - 1, index));
            values.set(index, (float) (values.get(index) + territory.area));
        }
        return values;
    }

    private List<String> buildWeekLabels(int count) {
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= count; i++) labels.add("W" + i);
        return labels;
    }

    private void animateSurfaceValue(TextView view, double target) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(600);
        animator.addUpdateListener(animation -> view.setText(formatSurface(target * (float) animation.getAnimatedValue())));
        animator.start();
    }

    private void animateDistanceValue(TextView view, double targetKm) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(600);
        animator.addUpdateListener(animation -> view.setText(String.format(Locale.US, "%.1f km", targetKm * (float) animation.getAnimatedValue())));
        animator.start();
    }

    private String formatSurface(double areaM2) {
        return String.format(Locale.US, "%.3f km²", areaM2 / 1_000_000.0);
    }

    private void sortAndDisplay() {
        if (selectedTabIndex == 0) {
            Collections.sort(userList, (u1, u2) -> Double.compare(u2.totalAreaClaimed, u1.totalAreaClaimed));
        } else if (selectedTabIndex == 1) {
            Collections.sort(userList, (u1, u2) -> Double.compare(u2.totalDistanceWalked, u1.totalDistanceWalked));
        } else if (selectedTabIndex == 2) {
            Collections.sort(userList, (u1, u2) -> Long.compare(u2.totalSteps, u1.totalSteps));
        }

        if (userList.size() >= 1) rank1Name.setText(userList.get(0).username); else rank1Name.setText("---");
        if (userList.size() >= 2) rank2Name.setText(userList.get(1).username); else rank2Name.setText("---");
        if (userList.size() >= 3) rank3Name.setText(userList.get(2).username); else rank3Name.setText("---");

        adapter.notifyDataSetChanged();
        scrollToCurrentUserRankIfNeeded();
    }

    private void scrollToCurrentUserRankIfNeeded() {
        if (!pendingScrollToCurrentUser || currentUserId == null || isMesStatsTab) return;

        int currentUserPosition = -1;
        for (int i = 0; i < userList.size(); i++) {
            User user = userList.get(i);
            if (user.userId != null && user.userId.equals(currentUserId)) {
                currentUserPosition = i;
                break;
            }
        }

        if (currentUserPosition < 0) return;

        pendingScrollToCurrentUser = false;
        int targetPosition = currentUserPosition;
        leaderboardRecycler.post(() -> {
            RecyclerView.LayoutManager layoutManager = leaderboardRecycler.getLayoutManager();
            if (layoutManager instanceof LinearLayoutManager) {
                ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(targetPosition, 24);
            } else {
                leaderboardRecycler.scrollToPosition(targetPosition);
            }
        });
    }

    class LeaderboardAdapter extends RecyclerView.Adapter<LeaderboardAdapter.ViewHolder> {
        private final List<User> users;
        LeaderboardAdapter(List<User> users) { this.users = users; }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_leaderboard, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            User user = users.get(position);
            int rankNum = position + 1;
            holder.rank.setText(String.valueOf(rankNum));
            String displayName = user.username != null ? user.username : "Anonymous";
            boolean isCurrentUser = user.userId != null && user.userId.equals(currentUserId);
            holder.name.setText(isCurrentUser ? "You • " + displayName : displayName);

            if (rankNum == 1) {
                holder.rankContainer.setCardBackgroundColor(Color.parseColor("#FFD700"));
                holder.rank.setTextColor(Color.BLACK);
            } else if (rankNum == 2) {
                holder.rankContainer.setCardBackgroundColor(Color.parseColor("#C0C0C0"));
                holder.rank.setTextColor(Color.BLACK);
            } else if (rankNum == 3) {
                holder.rankContainer.setCardBackgroundColor(Color.parseColor("#CD7F32"));
                holder.rank.setTextColor(Color.BLACK);
            } else {
                holder.rankContainer.setCardBackgroundColor(Color.parseColor("#2A2A2A"));
                holder.rank.setTextColor(Color.WHITE);
            }

            MaterialCardView rowCard = (MaterialCardView) holder.itemView;
            if (isCurrentUser) {
                rowCard.setStrokeColor(Color.parseColor("#FF5A1F"));
                rowCard.setStrokeWidth(4);
                rowCard.setCardBackgroundColor(Color.parseColor("#2BFF5A1F"));
                holder.name.setTextColor(Color.parseColor("#FFF3E8"));
            } else {
                rowCard.setStrokeColor(Color.parseColor("#1AFFFFFF"));
                rowCard.setStrokeWidth(2);
                rowCard.setCardBackgroundColor(ContextCompat.getColor(LeaderboardActivity.this, R.color.surface_variant));
                holder.name.setTextColor(ContextCompat.getColor(LeaderboardActivity.this, R.color.white));
            }

            if (selectedTabIndex == 0) {
                holder.value.setText(formatSurface(user.totalAreaClaimed));
            } else if (selectedTabIndex == 1) {
                holder.value.setText(String.format(Locale.US, "%.1f km", user.totalDistanceWalked / 1000.0));
            } else if (selectedTabIndex == 2) {
                holder.value.setText(String.format(Locale.US, "%,d steps", user.totalSteps));
            }

            holder.itemView.setOnClickListener(v -> {
                if (user.userId == null || user.userId.trim().isEmpty()) return;
                Intent intent = new Intent(LeaderboardActivity.this, ProfileActivity.class);
                intent.putExtra("USER_ID", user.userId);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() { return users.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView rank, name, value;
            MaterialCardView rankContainer;
            ViewHolder(View itemView) {
                super(itemView);
                rank = itemView.findViewById(R.id.rank_text);
                name = itemView.findViewById(R.id.username_text);
                value = itemView.findViewById(R.id.value_text);
                rankContainer = itemView.findViewById(R.id.rank_container);
            }
        }
    }
}
