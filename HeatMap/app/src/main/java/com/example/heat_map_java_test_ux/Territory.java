package com.example.heat_map_java_test_ux;

import java.util.List;

public class Territory {
    public String userId;
    public String userEmail;
    public List<MyLatLng> points;
    public double area;
    public long timestamp;

    public Territory() {
        // Default constructor required for calls to DataSnapshot.getValue(Territory.class)
    }

    public Territory(String userId, String userEmail, List<MyLatLng> points, double area, long timestamp) {
        this.userId = userId;
        this.userEmail = userEmail;
        this.points = points;
        this.area = area;
        this.timestamp = timestamp;
    }

    public static class MyLatLng {
        public double latitude;
        public double longitude;

        public MyLatLng() {}

        public MyLatLng(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
