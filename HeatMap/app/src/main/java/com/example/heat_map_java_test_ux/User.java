package com.example.heat_map_java_test_ux;

public class User {
    public String userId;
    public String username;
    public String email;
    public double totalDistanceWalked;
    public double totalAreaClaimed;
    public long totalSteps;
    public String territoryColor; // Hex string, e.g., "#FF5A1F"

    public User() {
        // Default constructor required for calls to DataSnapshot.getValue(User.class)
    }

    public User(String userId, String username, String email) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.totalDistanceWalked = 0;
        this.totalAreaClaimed = 0;
        this.territoryColor = "#FF5A1F"; // Default orange
    }
}
