# Cyclocomp

An Android-native, local-first cycling computer for a handlebar-mounted phone.

## Current V1 slice

- GPS ride recording in a user-started foreground service (1 second updates)
- Speed, distance, moving time, GPS quality, pause/resume/end controls
- Auto-pause and GPS-accuracy filtering in the ride domain
- Private, atomic GPX saving after a completed ride
- GPX course import, course-line distance, and off-course indication
- Bluetooth LE discovery for standard heart-rate, speed/cadence, and power sensors
- High-contrast Material 3 dashboard, routes, history, and devices screenshttps://maps.app.goo.gl/1yNRe3qwthp2WRC67

## Run on the Galaxy A70 (Android 11)

1. Open this folder in Android Studio with JDK 17 and Android SDK Platform 35 installed.
2. Let Android Studio sync the Gradle project, then run the `app` configuration on the phone.
3. Grant precise location when starting a ride. On Android 11, turn on the phone's Location setting before scanning BLE sensors; Android 11 uses Location permission for BLE discovery, not Nearby devices.

## Test focus

Use a real outdoor ride to verify GPS lock, background recording with screen off, auto-pause at a traffic stop, GPX output, and BLE discovery. Unit tests cover ride distance/auto-pause and GPX parsing/off-course geometry.

## Deliberately next

Offline map packs and rendered maps, sensor connections/data reads, turn generation, rerouting, Strava/Health Connect, and Apple Watch bridging are separate next increments. Apple Watch HR is not treated as a native Android BLE source.
