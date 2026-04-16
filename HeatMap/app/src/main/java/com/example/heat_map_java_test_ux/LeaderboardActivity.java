package com.example.heat_map_java_test_ux;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.animation.DecelerateInterpolator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    private static class BestSession {
        double surface;
        long timestamp;
    }

    private static class GhostSnapshot {
        double recordSurface;
        long recordWeekStartMs;
        double currentWeekSurface;
    }

    private static class TerritoryProgressItem {
        String zoneName;
        int zoneColor;
        double surfaceBefore;
        double surfaceAfter;
        double maxEver;
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
    private boolean sortByTerritory = true;
    private boolean isMesStatsTab = false;

    private View mesStatsScroll;
    private RecyclerView leaderboardRecycler;
    private MaterialButtonToggleGroup periodToggleGroup;
    private MaterialButton periodThisMonthButton;
    private MaterialButton periodLastMonthButton;
    private MaterialButton periodThreeMonthsButton;
    private MaterialButton periodAllTimeButton;

    private MaterialCardView kpiSurfaceCard;
    private TextView kpiSurfaceValue;
    private TextView kpiSurfaceDelta;
    private ProgressBar kpiSurfaceProgress;

    private TextView kpiDistanceValue;
    private TextView kpiDistanceDelta;
    private ProgressBar kpiDistanceProgress;

    private TextView kpiSessionsValue;
    private TextView kpiSessionsDelta;
    private ProgressBar kpiSessionsProgress;

    private TextView kpiBestValue;
    private TextView kpiBestBadge;

    private MaterialCardView chartCard;
    private WeeklyAreaChartView weeklyChartView;

    private MaterialCardView ghostCard;
    private TextView ghostTitle;
    private TextView ghostBody;
    private TextView ghostSub;
    private ProgressBar ghostProgress;

    private TextView territorySectionTitle;
    private LinearLayout territoryProgressContainer;
    private View emptyStateView;
    private MaterialButton emptyStateActionButton;

    private final List<Territory> myTerritories = new ArrayList<>();
    private StatsPeriod selectedStatsPeriod = StatsPeriod.THIS_MONTH;

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
        setupMesStatsViews();

        TabLayout tabLayout = findViewById(R.id.tab_layout);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position == 2) {
                    isMesStatsTab = true;
                    leaderboardRecycler.setVisibility(View.GONE);
                    mesStatsScroll.setVisibility(View.VISIBLE);
                    refreshMesStatsUi();
                    return;
                }

                isMesStatsTab = false;
                mesStatsScroll.setVisibility(View.GONE);
                leaderboardRecycler.setVisibility(View.VISIBLE);

                sortByTerritory = position == 0;
                sortAndDisplay();
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
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

        if (usersRef != null && usersListener != null) {
            usersRef.removeEventListener(usersListener);
        }

        if (currentUserRef != null && userColorListener != null) {
            currentUserRef.child("territoryColor").removeEventListener(userColorListener);
        }

        if (myTerritoriesQuery != null && territoriesListener != null) {
            myTerritoriesQuery.removeEventListener(territoriesListener);
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

        kpiSurfaceCard = findViewById(R.id.kpi_surface_card);
        kpiSurfaceValue = findViewById(R.id.kpi_surface_value);
        kpiSurfaceDelta = findViewById(R.id.kpi_surface_delta);
        kpiSurfaceProgress = findViewById(R.id.kpi_surface_progress);

        kpiDistanceValue = findViewById(R.id.kpi_distance_value);
        kpiDistanceDelta = findViewById(R.id.kpi_distance_delta);
        kpiDistanceProgress = findViewById(R.id.kpi_distance_progress);

        kpiSessionsValue = findViewById(R.id.kpi_sessions_value);
        kpiSessionsDelta = findViewById(R.id.kpi_sessions_delta);
        kpiSessionsProgress = findViewById(R.id.kpi_sessions_progress);

        kpiBestValue = findViewById(R.id.kpi_best_value);
        kpiBestBadge = findViewById(R.id.kpi_best_badge);

        chartCard = findViewById(R.id.mes_chart_card);
        weeklyChartView = findViewById(R.id.mes_chart);

        ghostCard = findViewById(R.id.ghost_card);
        ghostTitle = findViewById(R.id.ghost_title);
        ghostBody = findViewById(R.id.ghost_body);
        ghostSub = findViewById(R.id.ghost_sub);
        ghostProgress = findViewById(R.id.ghost_progress);

        territorySectionTitle = findViewById(R.id.territory_section_title);
        territoryProgressContainer = findViewById(R.id.territory_progress_container);

        emptyStateView = findViewById(R.id.mes_empty_state);
        emptyStateActionButton = findViewById(R.id.mes_empty_action_btn);
        emptyStateActionButton.setOnClickListener(v -> {
            Intent intent = new Intent(LeaderboardActivity.this, MapActivity.class);
            startActivity(intent);
            finish();
        });

        periodToggleGroup.check(R.id.period_this_month);
        periodToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            selectedStatsPeriod = periodFromButton(checkedId);
            updatePeriodButtonsStyle();
            refreshMesStatsUi();
        });
        updatePeriodButtonsStyle();
    }

    private void attachMesStatsListeners() {
        if (currentUserRef == null || myTerritoriesQuery == null) return;

        if (userColorListener != null) {
            currentUserRef.child("territoryColor").removeEventListener(userColorListener);
        }
        userColorListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String color = snapshot.getValue(String.class);
                    if (color != null && !color.trim().isEmpty()) {
                        currentUserColorHex = color;
                    }
                }
                updatePeriodButtonsStyle();
                if (isMesStatsTab) refreshMesStatsUi();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        currentUserRef.child("territoryColor").addValueEventListener(userColorListener);

        if (territoriesListener != null) {
            myTerritoriesQuery.removeEventListener(territoriesListener);
        }
        territoriesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                myTerritories.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Territory territory = child.getValue(Territory.class);
                    if (territory != null) {
                        myTerritories.add(territory);
                    }
                }
                if (isMesStatsTab) refreshMesStatsUi();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        };
        myTerritoriesQuery.addValueEventListener(territoriesListener);
    }

    private void fetchUsers() {
        progressBar.setVisibility(View.VISIBLE);

        if (usersListener != null) {
            usersRef.removeEventListener(usersListener);
        }

        usersListener = new ValueEventListener() {
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
        };

        usersRef.addValueEventListener(usersListener);
    }

    private void refreshMesStatsUi() {
        if (!isMesStatsTab) return;

        if (currentUserId == null) {
            showMesStatsEmptyState(true);
            return;
        }

        PeriodWindow currentWindow = buildCurrentWindow(selectedStatsPeriod);
        PeriodWindow previousWindow = selectedStatsPeriod == StatsPeriod.ALL_TIME ? null : buildPreviousWindow(currentWindow);

        List<Territory> currentTerritories = filterTerritories(myTerritories, currentWindow.startMs, currentWindow.endMs);
        List<Territory> previousTerritories = previousWindow == null
                ? new ArrayList<>()
                : filterTerritories(myTerritories, previousWindow.startMs, previousWindow.endMs);

        double totalSurface = sumSurface(currentTerritories);
        double previousSurface = sumSurface(previousTerritories);

        double totalDistanceKm = sumDistanceKm(currentTerritories);
        double previousDistanceKm = sumDistanceKm(previousTerritories);

        int totalSessions = currentTerritories.size();
        int previousSessions = previousTerritories.size();

        BestSession bestSession = findBestSession(currentTerritories);
        BestSession bestAllTimeSession = findBestSession(myTerritories);
        boolean isAllTimeRecord = bestSession.surface > 0 && bestSession.surface >= bestAllTimeSession.surface;

        boolean showDeltas = previousWindow != null && previousSessions > 0;

        bindKpis(totalSurface, previousSurface,
                totalDistanceKm, previousDistanceKm,
                totalSessions, previousSessions,
                bestSession.surface, isAllTimeRecord,
                showDeltas);

        List<Float> currentWeekly = buildWeeklyBreakdown(currentTerritories, currentWindow);
        List<Float> previousWeekly = previousWindow == null
                ? new ArrayList<>()
                : buildWeeklyBreakdown(previousTerritories, previousWindow);
        List<String> labels = buildWeekLabels(currentWindow.bucketCount);

        weeklyChartView.setChartData(currentWeekly, previousWeekly, labels, safeColor(currentUserColorHex, "#FF5A1F"));

        GhostSnapshot ghost = buildGhostSnapshot(myTerritories);
        bindGhostBanner(ghost);

        List<TerritoryProgressItem> zoneItems = buildTerritoryProgressItems(currentTerritories, previousTerritories, myTerritories);
        bindTerritoryProgress(zoneItems);

        showMesStatsEmptyState(totalSessions == 0);
    }

    private void bindKpis(double totalSurface,
                          double previousSurface,
                          double totalDistanceKm,
                          double previousDistanceKm,
                          int totalSessions,
                          int previousSessions,
                          double bestSessionSurface,
                          boolean isAllTimeRecord,
                          boolean showDeltas) {

        animateSurfaceValue(kpiSurfaceValue, totalSurface);
        animateDistanceValue(kpiDistanceValue, totalDistanceKm);
        animateIntegerValue(kpiSessionsValue, totalSessions);
        animateSurfaceValue(kpiBestValue, bestSessionSurface);

        styleHeroCard();

        int userColor = safeColor(currentUserColorHex, "#FF5A1F");
        kpiSurfaceProgress.setProgressTintList(ContextCompat.getColorStateList(this, android.R.color.transparent));
        kpiSurfaceProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(userColor));
        kpiDistanceProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(userColor));
        kpiSessionsProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(userColor));

        kpiSurfaceProgress.setProgress(progressPercent(totalSurface, previousSurface));
        kpiDistanceProgress.setProgress(progressPercent(totalDistanceKm, previousDistanceKm));
        kpiSessionsProgress.setProgress(progressPercent(totalSessions, previousSessions));

        if (isAllTimeRecord) {
            kpiBestBadge.setVisibility(View.VISIBLE);
            kpiBestBadge.setText(R.string.label_record_personnel);
        } else {
            kpiBestBadge.setVisibility(View.GONE);
        }

        if (!showDeltas) {
            kpiSurfaceDelta.setVisibility(View.GONE);
            kpiDistanceDelta.setVisibility(View.GONE);
            kpiSessionsDelta.setVisibility(View.GONE);
            return;
        }

        bindPercentDelta(kpiSurfaceDelta, totalSurface, previousSurface);
        bindPercentDelta(kpiDistanceDelta, totalDistanceKm, previousDistanceKm);
        bindSessionDelta(kpiSessionsDelta, totalSessions - previousSessions);
    }

    private void bindGhostBanner(GhostSnapshot ghostSnapshot) {
        if (ghostSnapshot.recordSurface <= 0) {
            ghostTitle.setText(getString(R.string.ghost_title));
            ghostBody.setText(getString(R.string.mes_stats_no_data));
            ghostSub.setText("");
            ghostProgress.setProgress(0);
            return;
        }

        ghostTitle.setText(R.string.ghost_title_win);
        ghostBody.setText(String.format(Locale.US,
            "Your best week: %s on %s",
                formatSurface(ghostSnapshot.recordSurface),
                formatDate(ghostSnapshot.recordWeekStartMs)));

        if (ghostSnapshot.currentWeekSurface >= ghostSnapshot.recordSurface) {
            ghostSub.setText(R.string.ghost_record_broken);
            ghostSub.setTextColor(safeColor("#FF6B2B", "#FF5A1F"));
            ghostProgress.setProgress(100);
        } else {
            double missing = Math.max(0, ghostSnapshot.recordSurface - ghostSnapshot.currentWeekSurface);
            ghostSub.setText(String.format(Locale.US,
                    "This week: %s - %s left to beat it",
                    formatSurface(ghostSnapshot.currentWeekSurface),
                    formatSurface(missing)));
            ghostSub.setTextColor(safeColor("#888888", "#888888"));

            int progress = (int) Math.round((ghostSnapshot.currentWeekSurface / Math.max(1d, ghostSnapshot.recordSurface)) * 100d);
            ghostProgress.setProgress(Math.max(0, Math.min(100, progress)));
        }

        ghostProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(safeColor(currentUserColorHex, "#FF5A1F")));
    }

    private void bindTerritoryProgress(List<TerritoryProgressItem> items) {
        territoryProgressContainer.removeAllViews();

        LayoutInflater inflater = LayoutInflater.from(this);
        for (TerritoryProgressItem item : items) {
            View card = inflater.inflate(R.layout.item_territory_progress, territoryProgressContainer, false);

            View dot = card.findViewById(R.id.territory_dot);
            TextView name = card.findViewById(R.id.territory_name);
            TextView delta = card.findViewById(R.id.territory_surface_delta);
            ProgressBar progress = card.findViewById(R.id.territory_progress);
            MaterialCardView cardView = (MaterialCardView) card;

            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(item.zoneColor);
            dot.setBackground(dotShape);
            name.setText(item.zoneName);
            delta.setText(String.format(Locale.US, "%s -> %s", formatSurface(item.surfaceBefore), formatSurface(item.surfaceAfter)));
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(item.zoneColor));

            int percent = (int) Math.round((item.surfaceAfter / Math.max(1d, item.maxEver)) * 100d);
            progress.setProgress(Math.max(0, Math.min(100, percent)));

            int borderColor = Color.argb(180, Color.red(item.zoneColor), Color.green(item.zoneColor), Color.blue(item.zoneColor));
            cardView.setStrokeColor(borderColor);
            cardView.setOnClickListener(v -> cardView.setCardElevation(cardView.getCardElevation() > 0 ? 0f : 8f));

            territoryProgressContainer.addView(card);
        }
    }

    private void showMesStatsEmptyState(boolean show) {
        emptyStateView.setVisibility(show ? View.VISIBLE : View.GONE);
        chartCard.setVisibility(show ? View.GONE : View.VISIBLE);
        ghostCard.setVisibility(show ? View.GONE : View.VISIBLE);
        territorySectionTitle.setVisibility(show ? View.GONE : View.VISIBLE);
        territoryProgressContainer.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void updatePeriodButtonsStyle() {
        stylePeriodButton(periodThisMonthButton, periodToggleGroup.getCheckedButtonId() == R.id.period_this_month);
        stylePeriodButton(periodLastMonthButton, periodToggleGroup.getCheckedButtonId() == R.id.period_last_month);
        stylePeriodButton(periodThreeMonthsButton, periodToggleGroup.getCheckedButtonId() == R.id.period_three_months);
        stylePeriodButton(periodAllTimeButton, periodToggleGroup.getCheckedButtonId() == R.id.period_all_time);
    }

    private void stylePeriodButton(MaterialButton button, boolean selected) {
        int selectedStroke = safeColor(currentUserColorHex, "#FF5A1F");
        button.setTextColor(selected ? Color.WHITE : safeColor("#888888", "#888888"));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(selected ? selectedStroke : safeColor("#2A2A2A", "#2A2A2A")));
    }

    private void styleHeroCard() {
        int accent = safeColor(currentUserColorHex, "#FF5A1F");
        kpiSurfaceCard.setStrokeColor(accent);
    }

    private StatsPeriod periodFromButton(int checkedId) {
        if (checkedId == R.id.period_last_month) return StatsPeriod.LAST_MONTH;
        if (checkedId == R.id.period_three_months) return StatsPeriod.THREE_MONTHS;
        if (checkedId == R.id.period_all_time) return StatsPeriod.ALL_TIME;
        return StatsPeriod.THIS_MONTH;
    }

    private PeriodWindow buildCurrentWindow(StatsPeriod period) {
        long now = System.currentTimeMillis();
        if (period == StatsPeriod.THIS_MONTH) {
            return new PeriodWindow(startOfMonth(0), now, 4);
        }
        if (period == StatsPeriod.LAST_MONTH) {
            return new PeriodWindow(startOfMonth(-1), endOfMonth(-1), 4);
        }
        if (period == StatsPeriod.THREE_MONTHS) {
            return new PeriodWindow(startOfMonth(-2), now, 12);
        }

        long start = now;
        for (Territory territory : myTerritories) {
            start = Math.min(start, territory.timestamp);
        }
        return new PeriodWindow(start, now, 12);
    }

    private PeriodWindow buildPreviousWindow(PeriodWindow currentWindow) {
        long duration = Math.max(1, currentWindow.endMs - currentWindow.startMs + 1);
        long end = currentWindow.startMs - 1;
        long start = end - duration + 1;
        return new PeriodWindow(start, end, currentWindow.bucketCount);
    }

    private long startOfMonth(int monthOffset) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MONTH, monthOffset);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private long endOfMonth(int monthOffset) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MONTH, monthOffset);
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTimeInMillis();
    }

    private List<Territory> filterTerritories(List<Territory> source, long start, long end) {
        List<Territory> result = new ArrayList<>();
        for (Territory territory : source) {
            if (territory.timestamp >= start && territory.timestamp <= end) {
                result.add(territory);
            }
        }
        return result;
    }

    private double sumSurface(List<Territory> territories) {
        double total = 0d;
        for (Territory territory : territories) {
            total += territory.area;
        }
        return total;
    }

    private double sumDistanceKm(List<Territory> territories) {
        double totalKm = 0d;
        for (Territory territory : territories) {
            if (territory.points == null || territory.points.size() < 2) continue;

            List<LatLng> path = new ArrayList<>();
            for (Territory.MyLatLng point : territory.points) {
                path.add(new LatLng(point.latitude, point.longitude));
            }
            totalKm += SphericalUtil.computeLength(path) / 1000d;
        }
        return totalKm;
    }

    private BestSession findBestSession(List<Territory> territories) {
        BestSession best = new BestSession();
        best.surface = 0d;
        best.timestamp = 0L;

        for (Territory territory : territories) {
            if (territory.area > best.surface) {
                best.surface = territory.area;
                best.timestamp = territory.timestamp;
            }
        }
        return best;
    }

    private List<Float> buildWeeklyBreakdown(List<Territory> territories, PeriodWindow window) {
        List<Float> values = new ArrayList<>();
        for (int i = 0; i < window.bucketCount; i++) {
            values.add(0f);
        }

        long duration = Math.max(1, window.endMs - window.startMs + 1);
        for (Territory territory : territories) {
            double ratio = (double) (territory.timestamp - window.startMs) / (double) duration;
            int index = (int) Math.floor(ratio * window.bucketCount);
            index = Math.max(0, Math.min(window.bucketCount - 1, index));
            values.set(index, (float) (values.get(index) + territory.area));
        }

        return values;
    }

    private List<String> buildWeekLabels(int count) {
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            labels.add("W" + i);
        }
        return labels;
    }

    private GhostSnapshot buildGhostSnapshot(List<Territory> allTerritories) {
        GhostSnapshot ghost = new GhostSnapshot();

        Map<String, Double> surfaceByWeek = new HashMap<>();
        Map<String, Long> weekStartByKey = new HashMap<>();

        for (Territory territory : allTerritories) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(territory.timestamp);
            int year = calendar.get(Calendar.YEAR);
            int week = calendar.get(Calendar.WEEK_OF_YEAR);
            String key = year + "-" + week;

            surfaceByWeek.put(key, surfaceByWeek.getOrDefault(key, 0d) + territory.area);

            calendar.set(Calendar.DAY_OF_WEEK, calendar.getFirstDayOfWeek());
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            weekStartByKey.put(key, calendar.getTimeInMillis());
        }

        String bestKey = null;
        double bestValue = 0d;
        for (Map.Entry<String, Double> entry : surfaceByWeek.entrySet()) {
            if (entry.getValue() > bestValue) {
                bestValue = entry.getValue();
                bestKey = entry.getKey();
            }
        }

        ghost.recordSurface = bestValue;
        ghost.recordWeekStartMs = bestKey == null ? 0L : weekStartByKey.getOrDefault(bestKey, 0L);

        Calendar now = Calendar.getInstance();
        String currentWeekKey = now.get(Calendar.YEAR) + "-" + now.get(Calendar.WEEK_OF_YEAR);
        ghost.currentWeekSurface = surfaceByWeek.getOrDefault(currentWeekKey, 0d);
        return ghost;
    }

    private List<TerritoryProgressItem> buildTerritoryProgressItems(List<Territory> current,
                                                                    List<Territory> previous,
                                                                    List<Territory> all) {
        Map<String, Double> currentMap = aggregateSurfaceByZone(current);
        Map<String, Double> previousMap = aggregateSurfaceByZone(previous);
        Map<String, Double> allMap = aggregateSurfaceByZone(all);

        List<TerritoryProgressItem> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : currentMap.entrySet()) {
            String key = entry.getKey();
            TerritoryProgressItem item = new TerritoryProgressItem();
            item.zoneName = "Zone " + (Math.abs(key.hashCode()) % 900 + 100);
            item.zoneColor = zoneColorForKey(key);
            item.surfaceAfter = entry.getValue();
            item.surfaceBefore = previousMap.getOrDefault(key, 0d);
            item.maxEver = Math.max(item.surfaceAfter, allMap.getOrDefault(key, item.surfaceAfter));
            result.add(item);
        }

        result.sort((a, b) -> Double.compare(b.surfaceAfter, a.surfaceAfter));
        return result;
    }

    private Map<String, Double> aggregateSurfaceByZone(List<Territory> territories) {
        Map<String, Double> result = new HashMap<>();
        for (Territory territory : territories) {
            String key = zoneKey(territory);
            result.put(key, result.getOrDefault(key, 0d) + territory.area);
        }
        return result;
    }

    private String zoneKey(Territory territory) {
        if (territory.points == null || territory.points.isEmpty()) {
            return "zone-unknown-" + territory.timestamp;
        }

        double lat = 0d;
        double lng = 0d;
        for (Territory.MyLatLng point : territory.points) {
            lat += point.latitude;
            lng += point.longitude;
        }

        lat /= territory.points.size();
        lng /= territory.points.size();

        int latBucket = (int) Math.round(lat * 20d);
        int lngBucket = (int) Math.round(lng * 20d);
        return latBucket + ":" + lngBucket;
    }

    private int zoneColorForKey(String key) {
        int[] palette = new int[]{
                safeColor("#FF6B2B", "#FF5A1F"),
                safeColor("#4A9EFF", "#007AFF"),
                safeColor("#4CAF50", "#4CAF50"),
                safeColor("#9C27B0", "#9C27B0"),
                safeColor("#E91E63", "#E91E63"),
                safeColor("#00BCD4", "#00BCD4")
        };
        int index = Math.abs(key.hashCode()) % palette.length;
        return palette[index];
    }

    private void bindPercentDelta(TextView textView, double current, double previous) {
        if (previous <= 0d) {
            textView.setVisibility(View.GONE);
            return;
        }

        double delta = ((current - previous) / previous) * 100d;
        if (Math.abs(delta) < 0.01d) {
            textView.setVisibility(View.GONE);
            return;
        }

        String prefix = delta > 0 ? "+" : "";
        textView.setText(String.format(Locale.US, "%s%.0f%% vs previous period", prefix, delta));
        textView.setTextColor(delta > 0 ? safeColor("#FF6B2B", "#FF5A1F") : safeColor("#FF4444", "#FF4444"));
        fadeIn(textView);
    }

    private void bindSessionDelta(TextView textView, int delta) {
        if (delta == 0) {
            textView.setVisibility(View.GONE);
            return;
        }

        String prefix = delta > 0 ? "+" : "";
        textView.setText(String.format(Locale.US, "%s%d vs previous period", prefix, delta));
        textView.setTextColor(delta > 0 ? safeColor("#FF6B2B", "#FF5A1F") : safeColor("#FF4444", "#FF4444"));
        fadeIn(textView);
    }

    private int progressPercent(double current, double previous) {
        double max = Math.max(1d, Math.max(current, previous));
        return (int) Math.max(0d, Math.min(100d, (current / max) * 100d));
    }

    private void fadeIn(TextView view) {
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(420).start();
    }

    private void animateSurfaceValue(TextView view, double target) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(620);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            view.setText(formatSurface(target * progress));
        });
        animator.start();
    }

    private void animateDistanceValue(TextView view, double targetKm) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(620);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            view.setText(String.format(Locale.US, "%.1f km", targetKm * progress));
        });
        animator.start();
    }

    private void animateIntegerValue(TextView view, int target) {
        ValueAnimator animator = ValueAnimator.ofInt(0, Math.max(0, target));
        animator.setDuration(620);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> view.setText(String.valueOf((int) animation.getAnimatedValue())));
        animator.start();
    }

    private int safeColor(String colorHex, String fallbackHex) {
        try {
            return Color.parseColor(colorHex);
        } catch (Exception e) {
            return Color.parseColor(fallbackHex);
        }
    }

    private String formatSurface(double areaM2) {
        if (areaM2 >= 1_000_000d) {
            return String.format(Locale.US, "%.1f km2", areaM2 / 1_000_000d);
        }
        return String.format(Locale.US, "%.0f m2", areaM2);
    }

    private String formatDate(long timestamp) {
        if (timestamp <= 0) return "-";
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int month = calendar.get(Calendar.MONTH) + 1;
        return String.format(Locale.US, "%02d/%02d", day, month);
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
