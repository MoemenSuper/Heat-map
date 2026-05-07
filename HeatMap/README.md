# HeatMap

HeatMap is an Android app built in Java for turning real-world movement into territory on a map. Users can sign up, track walking or running sessions, claim areas on the map, compare progress on a leaderboard, and manage their profile with custom territory colors and personal stats.

This project was created by three second-year computer science students as a course project.

## What the app does

- Sign in and sign up with Firebase Authentication.
- Track sessions using live GPS and step counter data.
- Claim map territory based on the path you walk or run.
- Search for places and jump to a location on the map.
- View a leaderboard with territory, distance, and personal stats tabs.
- Edit your username and choose a custom territory color.
- See awards and progress summaries on the profile screen.
- Receive notifications when significant territory is lost.
- Detect suspicious movement patterns and reject fake GPS locations.

## Main Screens

- Login screen: entry point for existing users.
- Sign up screen: creates a new Firebase account and user profile.
- Map screen: live territory capture, route tracking, search, and session controls.
- Leaderboard screen: rankings, podium, and personal statistics.
- Profile screen: username editing, color selection, awards, and stats.

## Tech Stack

- Java
- Android SDK
- Material Design components
- Firebase Authentication
- Firebase Realtime Database
- Firebase Analytics
- Firebase Storage
- Google Maps SDK
- Google Play Services Location
- Google Maps Android Utils
- JTS for polygon and territory geometry
- Glide for image loading
- Skydoves ColorPickerView

## Project Structure

```text
HeatMap/
  app/
    src/main/java/com/example/heat_map_java_test_ux/
      MainActivity.java
      SignUpActivity.java
      MapActivity.java
      LeaderboardActivity.java
      ProfileActivity.java
      TrackingService.java
      User.java
      Territory.java
      Custom views and UI helpers
    src/main/res/
      layout/
        activity_main.xml
        activity_sign_up.xml
        activity_map.xml
        activity_leaderboard.xml
        activity_profile.xml
      drawable/
        gradients, cards, buttons, and map UI assets
      values/
        strings.xml, colors.xml, themes.xml
    src/main/AndroidManifest.xml
  TERRITORY_OVERLAP_FIX_ANALYSIS.md
  build.gradle
  settings.gradle
```

## How It Works

1. A user creates an account or signs in.
2. The app opens the map screen.
3. The tracking service collects location and step updates while a session is active.
4. When the user closes a loop or covers enough area, the territory is stored in Firebase.
5. The map and leaderboard refresh from the shared backend data.
6. Profile stats are computed from saved territories and session history.

## Setup Requirements

Before running the project, make sure you have:

- Android Studio installed.
- A recent Android SDK and Java 11 configured.
- A Firebase project with Authentication and Realtime Database enabled.
- The `google-services.json` file placed in `app/`.
- A valid Google Maps API key configured for the app.

## Secrets Setup (Do Not Commit)

1. Keep `local.properties` and `app/google-services.json` out of Git (already ignored).
2. Add your Maps key to `local.properties` in the project root:

```properties
MAPS_API_KEY=your_real_google_maps_key
```

3. Optional: instead of `local.properties`, set an environment variable named `MAPS_API_KEY`.
4. Download your own `google-services.json` from Firebase and place it in `app/` locally.

## Running the App

1. Open the project in Android Studio.
2. Sync Gradle.
3. Check that Firebase and Google Maps credentials are configured.
4. Run the `app` module on an emulator or Android device.
5. Grant the location, activity recognition, and notification permissions when prompted.

## Permissions Used

The app requests permissions for:

- Internet access
- Fine and coarse location
- Background location
- Foreground location service
- Activity recognition
- Notifications

These are required for live tracking, territory capture, and session alerts.

## Data Model

The app stores data in Firebase Realtime Database using two main models:

- `User`: profile info, total distance, total area, steps, and territory color.
- `Territory`: the claimed polygon points, area, owner, and timestamp.

## Notes

- The project contains a dedicated analysis document for a territory overlap bug fix: `TERRITORY_OVERLAP_FIX_ANALYSIS.md`.
- The app uses a foreground tracking service, so location tracking can continue reliably during a session.
- If you fork or reuse the project, replace backend credentials and API keys with your own.

## License

No license was provided with the project.
