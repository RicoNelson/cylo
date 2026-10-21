# Cyclocomp App Improvement Plan

## Purpose

Improve Cyclocomp into a reliable, easy-to-read cycling computer for a handlebar-mounted Android phone, especially the Samsung Galaxy A70 running Android 11.

The main priorities are:

1. Make recording reliable so a ride is not lost.
2. Make the ride screen readable with one glance while cycling.
3. Make pause, resume, finish, route, sensor, and history actions obvious.
4. Keep the map useful without allowing it to take over the screen.
5. Preserve the local-first and privacy-first design.

This plan covers product behavior, layout, interaction, reliability, and validation. It does not require implementing every Cyclemeter feature immediately.

## Product principles

- **Ride first:** the active ride is always the most important screen.
- **Readable outdoors:** large numbers, high contrast, short labels, and no dense tables while riding.
- **Few decisions while moving:** controls should be obvious and forgiving.
- **Reliable before advanced:** recording and saving come before analytics and social features.
- **Local by default:** rides and imported courses remain on the phone unless the user exports them.
- **Honest status:** stale GPS, disconnected sensors, missing data, and estimated guidance must be clearly identified.
- **Android 11 compatible:** permission flows and Bluetooth discovery must work correctly on the target phone.

## Current app baseline

Cyclocomp currently contains:

- GPS ride recording through a foreground service.
- Speed, distance, moving time, GPS accuracy, pause, resume, and end controls.
- Auto-pause logic.
- GPX ride saving and sharing.
- GPX route import and course-line display.
- Basic off-course evaluation and turn cues.
- Bluetooth Low Energy discovery for heart-rate, speed/cadence, and power sensors.
- Ride, Routes, History, and Devices screens.
- Jetpack Compose and Material 3 UI.
- osmdroid map rendering.

The current implementation is a functional V1 slice. The next work should improve the primary ride experience and reliability instead of adding a large number of new features at once.

## Target information architecture

### Bottom navigation

Keep four top-level destinations:

1. **Ride** — active recording and live metrics.
2. **Routes** — import, inspect, select, and remove a course.
3. **History** — completed rides and GPX export.
4. **Devices** — BLE sensor discovery and connection.

Settings can be added later from a compact action in the Ride or Devices screen. It should not become a fifth primary destination until it contains enough content to justify permanent navigation space.

## Primary Ride screen

The Ride screen should be the center of the product. It should work before a course or sensor is configured.

### Header

Show:

- App or ride title.
- Current ride state.
- GPS state.
- Optional active course name.

Ride states must be visually distinct:

| State | Meaning | Main action |
|---|---|---|
| Ready | No ride is recording | Start ride |
| Waiting for GPS | User started, but no usable fix yet | Keep waiting or return to setup |
| Recording | GPS points are being accepted | Pause ride |
| Auto-paused | The rider stopped | Resume automatically when movement returns |
| Manually paused | User paused the ride | Resume or Finish |
| Saving | Ride is being written to storage | Show progress and prevent duplicate finish actions |
| Saved | Ride completed successfully | View summary or share GPX |
| Save failed | Data is still available but write failed | Retry saving |

### Three swipeable pages

Use a horizontal pager with visible, tappable tabs. Swiping should remain available, but the user must not need to discover swiping to change pages.

#### Page 1: Primary

Order from top to bottom:

1. Ride state and GPS status.
2. Large current speed.
3. Distance and moving time.
4. Compact map.
5. Course guidance, if a course is active.
6. Optional average speed, maximum speed, or heart rate.

Recommended map size: approximately 25–30% of the available page height. The map should support route and ride-track visibility without becoming a full-screen map during normal recording.

#### Page 2: Sensors

Show:

- Current heart rate and ride average.
- Current cadence and ride average.
- Current power and ride average.
- Sensor connection state.
- Timestamp or freshness indicator for live readings.
- Button to manage sensors.

Values must distinguish between:

- Live reading.
- Stale reading.
- Sensor disconnected.
- Sensor connected but no reading yet.
- Sensor type not available.

#### Page 3: Course

Show:

- Course name.
- Distance remaining or distance to the next course point.
- Next turn hint when geometry supports it.
- On-course or off-course state.
- Distance from the route.
- Button to manage or replace the course.

Turn hints generated only from GPX geometry must be labelled as estimated. Cyclocomp should not imply road-aware navigation until it has road-network routing.

### Fixed ride controls

Keep the main control area at the bottom of the Ride screen.

| Ride state | Controls |
|---|---|
| Ready | Start ride |
| Recording | Pause ride |
| Auto-paused | Resume when moving; allow manual resume |
| Manually paused | Resume and Finish |
| Saving | Saving indicator; disable duplicate actions |
| Saved | View summary, Share GPX, New ride |
| Save failed | Retry saving; keep current ride data |

Finishing a ride must require confirmation. The confirmation should show distance and moving time so the user can catch an accidental tap.

## Map behavior

### Normal ride mode

- Map is compact and passive on the Primary page.
- Do not allow map gestures to accidentally change the ride pager.
- Recenter controls should be available only when the map is interactive.
- Keep the current rider position visible.
- Draw the active course and recorded track with different colors.
- Show start and finish markers for imported routes.

### Expanded map mode

Add a deliberate map expansion action later. It should open a full-screen map only when the user asks for it. The expanded view should provide:

- Zoom in and out.
- Recenter.
- Course guidance.
- Off-course warning.
- Return to ride metrics.

### Offline behavior

Recording must continue if map tiles are unavailable. Map loading failures should not stop GPS recording or course-distance calculations.

## Ride recording reliability

### GPS handling

- Ignore locations with invalid coordinates, invalid accuracy, invalid timestamps, or impossible speeds.
- Do not count a large GPS gap as continuous movement.
- Do not draw a straight line across a long GPS outage.
- Show when the last accepted GPS fix is stale.
- Keep the latest valid point separate from the last accepted recording point.
- Maintain a clear distinction between GPS unavailable and rider stationary.

### Pause and resume

- Manual pause must stop counting moving time.
- Resume must start a new track segment so the paused period is not drawn as a false straight line.
- Auto-pause must not destroy the current ride state.
- Resume after an app recreation must be explicit if the ride was recovered in a paused state.

### Save and recovery

- Save GPX atomically.
- Show saving progress.
- Do not silently swallow save errors.
- Preserve ride data when saving fails.
- Retry without requiring the rider to record the ride again.
- Periodically write a private recovery checkpoint while recording.
- On app/service restart, offer to recover an interrupted ride.
- Clear the recovery checkpoint only after the completed GPX is safely saved.

### Foreground service

- Keep the location foreground service visible while recording.
- Use a clear notification title and text.
- Ensure pause, resume, and finish produce consistent notification behavior.
- Stop location updates and the service after a successful finish.
- Do not keep GPS running when there is no active ride.

## Routes workflow

### Empty state

Explain the purpose in one sentence:

> Import a GPX course to see it on the ride map and get basic course guidance.

Provide one primary action: **Import GPX course**.

### Loaded state

Show:

- Course name.
- Total distance.
- Number of route points.
- Number of estimated turn cues.
- Route preview map.
- **Use this course** action.
- Replace course action.
- Remove course action.

### Import behavior

- Use the Android document picker.
- Validate that the file is actually GPX.
- Reject malformed or excessively large files with a readable error.
- Do not block the UI while parsing.
- Persist the selected course across app restarts.
- Keep the previous course until a replacement has been parsed successfully.

## History workflow

### History list

Each item should show:

- Date and time.
- Distance.
- Moving time.
- Average speed when available.
- File size or export format only as secondary information.

The list should be sorted newest first and support refresh after a ride is saved.

### Ride summary

Add a summary screen or expandable detail view with:

- Distance.
- Elapsed time.
- Moving time.
- Average and maximum speed.
- Average and maximum heart rate, cadence, and power when available.
- Route map and track.
- GPX share/export action.

If a metric was not recorded, show “Not available” rather than zero.

### Export

- Share GPX through the Android share sheet.
- Show a readable error if no compatible sharing app is available.
- Keep exports deliberate and user initiated.

## Devices workflow

### Permission and scan states

Show separate states for:

- Bluetooth disabled.
- Location disabled.
- Required permission missing.
- Scanning.
- Scan completed with no compatible sensors.
- Scan failure.
- Sensor connecting.
- Sensor connected.
- Sensor disconnected.

Android 11 requires location permission and enabled Location for BLE discovery. Explain this directly in the screen instead of leaving the user to infer it.

### Sensor list

Separate:

1. **Saved sensors** — sensors the app will attempt to reconnect to.
2. **Nearby sensors** — newly discovered sensors.

Use clear actions:

- Connect.
- Disconnect for this ride.
- Forget saved sensor.
- Retry connection.
- Stop scan.

Do not use “Disconnect” when the actual action removes the saved sensor.

### BLE reliability

- Serialize GATT descriptor writes.
- Do not issue overlapping Bluetooth operations.
- Clear telemetry when a sensor disconnects or data becomes stale.
- Prevent duplicate connections to the same address.
- Handle connection callbacks from an old GATT session safely.
- Show battery only when the sensor actually exposes battery data.

## Settings to add after the core flow

Add a settings surface after the core ride flow is stable:

- Distance and speed units.
- Auto-pause on/off.
- Auto-pause threshold.
- Screen-on during ride.
- Map-follow behavior.
- Default ride page.
- Wheel circumference for speed sensors.
- Maximum heart rate and heart-rate zones.
- Theme and high-contrast mode.
- Data retention and recovery-file cleanup.

Avoid hard-coded training assumptions such as a fixed maximum heart rate of 190 BPM.

## Visual and interaction system

### Material 3

Use Material 3 consistently:

- Filled buttons for primary actions.
- Tonal or outlined buttons for secondary actions.
- Cards for metric groups.
- Navigation bars for top-level destinations.
- Tabs for the ride pager.
- Snackbars for recoverable errors.
- Dialogs for destructive or irreversible actions.

### Outdoor readability

- Use high contrast.
- Prefer large numeric values.
- Keep labels short.
- Avoid relying on color alone for GPS, pause, or sensor state.
- Provide text labels and icons together for important actions.
- Keep touch targets at least 48dp high.
- Keep the bottom control area reachable with one thumb.

### Accessibility

- Add content descriptions for map controls and sensor actions.
- Verify TalkBack labels.
- Ensure text remains readable with Android large-font settings.
- Avoid icons made only from text symbols where a Material icon is available.
- Confirm focus order follows the ride workflow.

## Performance and battery

- Request location only while a ride is active or while explicitly waiting to start.
- Stop BLE scanning after a short timeout.
- Avoid repeated map overlay recreation on every GPS update.
- Reuse map layers and update their points.
- Avoid loading an entire large GPX file into memory when a streaming parser is practical.
- Move GPX parsing, listing, and saving from the main thread.
- Test a two-hour ride with the screen on and a two-hour ride with the screen off.

## Implementation phases

### Phase 1 — Reliability foundation

**Goal:** No active ride is silently lost.

Work:

- Define explicit ride UI states.
- Correct pause/resume/finish behavior.
- Add save progress and save failure handling.
- Add recovery checkpoints.
- Recover interrupted rides into a paused state.
- Validate GPS timestamps, accuracy, and impossible movement.
- Separate paused track segments.
- Add tests for save failure and recovery.

Exit criteria:

- A ride survives activity recreation.
- A ride survives service restart with recovery.
- A failed save leaves the ride available for retry.
- A pause does not create a false track segment.
- A successful save removes the recovery checkpoint.

### Phase 2 — Ride screen redesign

**Goal:** The rider can understand and control the app at a glance.

Work:

- Replace the current pager indicator with tappable tabs plus swipe support.
- Make the map compact on Primary.
- Create fixed ride controls.
- Add explicit GPS and ride-state indicators.
- Add finish confirmation.
- Add stale-data labels.
- Keep the primary page usable without scrolling on the A70.

Exit criteria:

- A new user can start, pause, resume, and finish without explanation.
- The speed value is the dominant element.
- The map does not consume the whole screen.
- Important controls remain visible in portrait orientation.
- Screen-off recording continues.

### Phase 3 — Routes and history

**Goal:** Courses and completed rides are understandable and reusable.

Work:

- Persist the selected course.
- Improve GPX validation and import feedback.
- Add course preview and Use course action.
- Add ride summary details.
- Display distance, moving time, and average speed in history.
- Improve GPX sharing errors.

Exit criteria:

- A selected course remains after restarting the app.
- A malformed GPX produces a useful message.
- A completed ride appears in History immediately after saving.
- A ride can be shared through the Android share sheet.

### Phase 4 — Devices and sensors

**Goal:** BLE sensors are predictable and understandable.

Work:

- Separate saved and nearby sensors.
- Add complete permission and scan states.
- Serialize GATT subscriptions.
- Handle reconnect and disconnect safely.
- Expire stale telemetry.
- Add Forget and Retry actions.

Exit criteria:

- Android 11 can discover a compatible BLE sensor with clear instructions.
- A disconnected sensor does not continue showing a live value indefinitely.
- Duplicate GATT connections do not occur.
- A sensor can be removed from saved devices.

### Phase 5 — Settings and personalization

**Goal:** Riders can adapt the app to their equipment and preferences.

Work:

- Units.
- Auto-pause configuration.
- Screen-on preference.
- Wheel circumference.
- Heart-rate zones.
- Ride page defaults.
- High-contrast display options.

Exit criteria:

- Settings are persisted and applied consistently.
- The app has no unexplained hard-coded training values.
- The default experience remains simple for a new rider.

### Phase 6 — Advanced capabilities

Only begin after the previous phases are stable:

- Full-screen offline map mode.
- Better turn guidance.
- Laps and manual markers.
- Structured intervals.
- Charts and long-term analytics.
- Health Connect integration.
- Strava export or sharing.
- Live tracking with explicit privacy controls.
- ANT+ support if the device and library strategy justify it.

## Test plan

### Unit tests

Test:

- Ride state transitions.
- Manual pause duration.
- Auto-pause and resume.
- GPS outage handling.
- Impossible speed and teleport rejection.
- Track segmentation.
- GPX parsing and validation.
- Route distance and off-course calculations.
- Atomic save behavior.
- Recovery checkpoint read/write.
- Sensor telemetry parsing and stale-value expiry.

### Android 11 device tests

Use the Galaxy A70 for:

1. First launch and permission flow.
2. Location disabled before Start.
3. Precise location denied.
4. Start with weak GPS.
5. Screen-off recording for at least 30 minutes.
6. Activity recreation during recording.
7. App moved to the background.
8. Manual pause and resume.
9. Auto-pause at a traffic stop.
10. Finish and save.
11. Simulated or observed storage failure.
12. GPX import and replacement.
13. Route persistence after restart.
14. BLE scan with Location off.
15. BLE scan with Bluetooth off.
16. Sensor disconnect while recording.
17. History refresh and GPX share.
18. Large font and TalkBack checks.

### Field ride validation

Use short rides first:

- 5–10 minutes around the neighborhood.
- 30–45 minutes with several stops.
- 90 minutes with the screen off intermittently.
- A route with a GPX course.
- A ride with a heart-rate sensor.

Record:

- Battery percentage before and after.
- Number of GPS gaps.
- Whether speed and distance feel plausible.
- Whether the map remains responsive.
- Whether controls are easy to press while stopped.
- Whether the app recovers after interruption.

## Definition of done for the next release

The next release is ready when:

- Core ride states are explicit and correct.
- Recording survives normal background use and activity recreation.
- Completed rides are saved or remain retryable after failure.
- The Primary screen has a compact map and readable speed.
- Tabs and swipes both navigate ride pages.
- Routes persist and malformed imports are explained.
- History shows useful ride summaries.
- BLE states and stale readings are clear.
- Android 11 field testing passes.
- Unit tests cover the risky ride, GPX, storage, and sensor logic.
