# Capture Marker Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two system-level floating marker buttons during PCAP file capture and append manual network-quality events to a sibling `.markers.json` file.

**Architecture:** Keep capture lifecycle in `CaptureService`, move overlay UI into a new `CaptureMarkerOverlayService`, and isolate marker filename/JSON writing in `CaptureMarkerWriter` so the core behavior is unit tested. `MainActivity` only handles user-facing overlay permission guidance before a manual PCAP file capture.

**Tech Stack:** Android Java, `VpnService`, `WindowManager.TYPE_APPLICATION_OVERLAY`, Android `ContentResolver`/MediaStore, Gson `JsonArray`/`JsonObject`/`JsonParser`, Robolectric/JUnit4.

---

## File Structure

- Create `app/src/main/java/com/emanuelef/remote_capture/CaptureMarkerWriter.java`
  - Owns marker filename derivation, JSON append, and file/URI writes.
  - Pure JSON methods are testable without Android UI.
- Create `app/src/main/java/com/emanuelef/remote_capture/CaptureMarkerOverlayService.java`
  - Owns the system overlay view and click handling for `开` / `闭`.
- Create `app/src/test/java/com/emanuelef/remote_capture/CaptureMarkerWriterTest.java`
  - Covers filename derivation and JSON append/file creation behavior.
- Modify `app/src/main/AndroidManifest.xml`
  - Add `SYSTEM_ALERT_WINDOW` permission.
  - Register the overlay service.
- Modify `app/src/main/java/com/emanuelef/remote_capture/activities/MainActivity.java`
  - Before starting a manual PCAP file capture, explain missing overlay permission and let the user open settings or continue without markers.
- Modify `app/src/main/java/com/emanuelef/remote_capture/CaptureService.java`
  - Start/stop `CaptureMarkerOverlayService` for active `Prefs.DumpMode.PCAP_FILE` captures.
- Modify `app/src/main/res/values/strings.xml`
  - Add overlay permission and marker write failure strings.

## Task 1: Marker Writer

**Files:**
- Create: `app/src/main/java/com/emanuelef/remote_capture/CaptureMarkerWriter.java`
- Create: `app/src/test/java/com/emanuelef/remote_capture/CaptureMarkerWriterTest.java`

- [ ] **Step 1: Write failing tests for filename derivation and JSON append**

Create `app/src/test/java/com/emanuelef/remote_capture/CaptureMarkerWriterTest.java`:

```java
package com.emanuelef.remote_capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
public class CaptureMarkerWriterTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void markerFileNameFromPcap() {
        assertEquals("capture.markers.json", CaptureMarkerWriter.getMarkerFileName("capture.pcap"));
    }

    @Test
    public void markerFileNameFromPcapng() {
        assertEquals("capture.markers.json", CaptureMarkerWriter.getMarkerFileName("capture.pcapng"));
    }

    @Test
    public void markerFileNameFromUnknownExtension() {
        assertEquals("capture.dump.markers.json", CaptureMarkerWriter.getMarkerFileName("capture.dump"));
    }

    @Test
    public void appendMarkerJsonCreatesArrayWhenMissing() throws Exception {
        String json = CaptureMarkerWriter.appendMarkerJson(null, 1785072600123L, CaptureMarkerWriter.LABEL_OPEN);

        assertEquals("[{\"timestamp\":1785072600123,\"label\":\"\\u5f00\"}]",
                JsonParser.parseString(json).toString());
    }

    @Test
    public void appendMarkerJsonAppendsExistingArray() throws Exception {
        String existing = "[{\"timestamp\":1,\"label\":\"\\u5f00\"}]";

        String json = CaptureMarkerWriter.appendMarkerJson(existing, 2L, CaptureMarkerWriter.LABEL_CLOSE);

        assertEquals("[{\"timestamp\":1,\"label\":\"\\u5f00\"},{\"timestamp\":2,\"label\":\"\\u95ed\"}]",
                JsonParser.parseString(json).toString());
    }

    @Test(expected = JsonSyntaxException.class)
    public void appendMarkerJsonRejectsMalformedJson() throws Exception {
        CaptureMarkerWriter.appendMarkerJson("{", 2L, CaptureMarkerWriter.LABEL_CLOSE);
    }

    @Test(expected = JsonSyntaxException.class)
    public void appendMarkerJsonRejectsObjectJson() throws Exception {
        CaptureMarkerWriter.appendMarkerJson("{\"timestamp\":1}", 2L, CaptureMarkerWriter.LABEL_CLOSE);
    }

    @Test
    public void appendMarkerCreatesFile() throws Exception {
        File marker = temp.newFile("capture.markers.json");
        assertTrue(marker.delete());

        assertTrue(CaptureMarkerWriter.appendMarker(marker, 10L, CaptureMarkerWriter.LABEL_OPEN));

        String json = Files.readString(marker.toPath(), StandardCharsets.UTF_8);
        assertEquals("[{\"timestamp\":10,\"label\":\"\\u5f00\"}]",
                JsonParser.parseString(json).toString());
    }

    @Test
    public void appendMarkerDoesNotOverwriteMalformedFile() throws Exception {
        File marker = temp.newFile("capture.markers.json");
        Files.writeString(marker.toPath(), "{", StandardCharsets.UTF_8);

        assertFalse(CaptureMarkerWriter.appendMarker(marker, 10L, CaptureMarkerWriter.LABEL_OPEN));

        assertEquals("{", Files.readString(marker.toPath(), StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 2: Run tests and verify they fail because the class is missing**

Run:

```powershell
.\gradlew.bat testStandardDebugUnitTest --tests com.emanuelef.remote_capture.CaptureMarkerWriterTest
```

Expected: compilation fails with `cannot find symbol class CaptureMarkerWriter`.

- [ ] **Step 3: Implement `CaptureMarkerWriter`**

Create `app/src/main/java/com/emanuelef/remote_capture/CaptureMarkerWriter.java`:

```java
package com.emanuelef.remote_capture;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

public class CaptureMarkerWriter {
    private static final String TAG = "CaptureMarkerWriter";
    private static final String MARKER_SUFFIX = ".markers.json";

    public static final String LABEL_OPEN = "\u5f00";
    public static final String LABEL_CLOSE = "\u95ed";

    public static String getMarkerFileName(String pcapFname) {
        String baseName = pcapFname;
        String lower = pcapFname.toLowerCase(Locale.ROOT);

        if(lower.endsWith(".pcapng"))
            baseName = pcapFname.substring(0, pcapFname.length() - 7);
        else if(lower.endsWith(".pcap"))
            baseName = pcapFname.substring(0, pcapFname.length() - 5);

        return baseName + MARKER_SUFFIX;
    }

    public static String appendMarkerJson(@Nullable String existingJson, long timestamp, String label)
            throws JsonSyntaxException {
        JsonArray markers;

        if((existingJson == null) || existingJson.isBlank())
            markers = new JsonArray();
        else {
            JsonElement parsed = JsonParser.parseString(existingJson);
            if(!parsed.isJsonArray())
                throw new JsonSyntaxException("Marker JSON root is not an array");
            markers = parsed.getAsJsonArray();
        }

        JsonObject marker = new JsonObject();
        marker.addProperty("timestamp", timestamp);
        marker.addProperty("label", label);
        markers.add(marker);

        return markers.toString();
    }

    public static boolean appendMarker(File markerFile, long timestamp, String label) {
        try {
            String existing = markerFile.exists()
                    ? Files.readString(markerFile.toPath(), StandardCharsets.UTF_8)
                    : null;
            String updated = appendMarkerJson(existing, timestamp, label);

            File parent = markerFile.getParentFile();
            if((parent != null) && !parent.exists() && !parent.mkdirs())
                return false;

            Files.writeString(markerFile.toPath(), updated, StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not append marker to file: " + e);
            return false;
        }
    }

    public static boolean appendMarker(Context ctx, Uri markerUri, long timestamp, String label) {
        ContentResolver resolver = ctx.getContentResolver();

        try {
            String existing = readUriText(resolver, markerUri);
            String updated = appendMarkerJson(existing, timestamp, label);

            try(OutputStream out = resolver.openOutputStream(markerUri, "rwt")) {
                if(out == null)
                    return false;
                out.write(updated.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not append marker to uri: " + e);
            return false;
        }
    }

    private static @Nullable String readUriText(ContentResolver resolver, Uri uri) throws IOException {
        try(InputStream in = resolver.openInputStream(uri)) {
            if(in == null)
                return null;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while((n = in.read(buf)) > 0)
                out.write(buf, 0, n);

            String rv = out.toString(StandardCharsets.UTF_8);
            return rv.isBlank() ? null : rv;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    public static @Nullable Uri getMarkerUri(Context ctx, Uri pcapUri, String pcapFname) {
        String markerName = getMarkerFileName(pcapFname);

        if("file".equalsIgnoreCase(pcapUri.getScheme())) {
            File pcapFile = new File(pcapUri.getPath());
            File parent = pcapFile.getParentFile();
            return (parent != null) ? Uri.fromFile(new File(parent, markerName)) : null;
        }

        Uri mediaStoreUri = getMediaStoreMarkerUri(ctx, pcapUri, markerName);
        if(mediaStoreUri != null)
            return mediaStoreUri;

        // Default PCAPdroid file captures are stored in Downloads/PCAPdroid.
        return Utils.getDownloadsUri(ctx, markerName);
    }

    private static @Nullable Uri getMediaStoreMarkerUri(Context ctx, Uri pcapUri, String markerName) {
        String[] projection = {
                MediaStore.MediaColumns.RELATIVE_PATH,
        };

        try(Cursor cursor = ctx.getContentResolver().query(pcapUri, projection, null, null, null)) {
            if((cursor == null) || !cursor.moveToFirst())
                return null;

            int relPathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            if(relPathIdx < 0)
                return null;

            String relPath = cursor.getString(relPathIdx);
            if((relPath == null) || relPath.isBlank())
                return null;

            return getOrCreateMediaStoreUri(ctx, relPath, markerName);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not derive MediaStore marker uri: " + e);
            return null;
        }
    }

    private static @Nullable Uri getOrCreateMediaStoreUri(Context ctx, String relPath, String markerName) {
        Uri externalUri = MediaStore.Files.getContentUri("external");
        String selectQuery = MediaStore.MediaColumns.RELATIVE_PATH + "='" + relPath + "' AND " +
                MediaStore.MediaColumns.DISPLAY_NAME + "='" + markerName + "'";

        try(Cursor cursor = ctx.getContentResolver().query(
                externalUri,
                new String[]{MediaStore.MediaColumns._ID},
                selectQuery,
                null,
                null)) {
            if((cursor != null) && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return ContentUris.withAppendedId(externalUri, id);
            }
        } catch (RuntimeException ignored) {}

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, markerName);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");

        try {
            return ctx.getContentResolver().insert(externalUri, values);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not create MediaStore marker uri: " + e);
            return null;
        }
    }
}
```

- [ ] **Step 4: Run writer tests and verify they pass**

Run:

```powershell
.\gradlew.bat testStandardDebugUnitTest --tests com.emanuelef.remote_capture.CaptureMarkerWriterTest
```

Expected: tests pass.

- [ ] **Step 5: Commit marker writer**

Run:

```powershell
git add app/src/main/java/com/emanuelef/remote_capture/CaptureMarkerWriter.java app/src/test/java/com/emanuelef/remote_capture/CaptureMarkerWriterTest.java
git commit -m "Add capture marker writer"
```

## Task 2: Overlay Permission Prompt

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/emanuelef/remote_capture/activities/MainActivity.java`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

Add these localized strings near the other permission strings in `app/src/main/res/values/strings.xml`:

```xml
<string name="overlay_permission_notice">PCAPdroid needs the display over other apps permission to show the manual network quality marker buttons while you use other apps. You can continue the capture without markers.</string>
<string name="continue_without_markers">Continue without markers</string>
<string name="marker_write_failed">Could not save marker</string>
```

- [ ] **Step 2: Add manifest permission**

In `app/src/main/AndroidManifest.xml`, add the permission next to the other top-level permissions:

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

- [ ] **Step 3: Add imports to `MainActivity`**

Add these imports to `app/src/main/java/com/emanuelef/remote_capture/activities/MainActivity.java`:

```java
import android.provider.Settings;
```

The file already imports `android.net.Uri`; reuse it for the settings intent URI.

- [ ] **Step 4: Add permission helper methods to `MainActivity`**

Add these methods near `checkPermissions()` in `MainActivity`:

```java
private boolean shouldRequestMarkerOverlayPermission() {
    if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
        return false;

    return (Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.PCAP_FILE) && !Settings.canDrawOverlays(this);
}

private void openOverlayPermissionSettings() {
    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()));

    try {
        startActivity(intent);
    } catch (ActivityNotFoundException e) {
        Utils.openAppSettings(this, getPackageName());
    }
}

private void showMarkerOverlayPermissionDialog() {
    AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.overlay_permission_notice)
            .setPositiveButton(R.string.open_settings, (d, whichButton) -> openOverlayPermissionSettings())
            .setNegativeButton(R.string.continue_without_markers, (d, whichButton) -> continueStartCapture())
            .setOnCancelListener(d -> continueStartCapture())
            .show();

    dialog.setCanceledOnTouchOutside(false);
}
```

- [ ] **Step 5: Split `startCapture()` continuation**

In `MainActivity.startCapture()`, add the overlay check after `showRemoteServerAlert()` and before TLS decryption setup:

```java
public void startCapture() {
    if (VpnReconnectService.isAvailable())
        VpnReconnectService.stopService();

    if(showRemoteServerAlert())
        return;

    if(shouldRequestMarkerOverlayPermission()) {
        showMarkerOverlayPermissionDialog();
        return;
    }

    continueStartCapture();
}
```

Create `continueStartCapture()` by moving the existing body of `startCapture()` after the `showRemoteServerAlert()` block into this new method:

```java
private void continueStartCapture() {
    if(Prefs.getTlsDecryptionEnabled(mPrefs) && MitmAddon.isSupportedTarget()) {
        if (MitmAddon.needsSetup(this)) {
            Intent intent = new Intent(this, MitmSetupWizard.class);
            startActivity(intent);
            return;
        }

        if (!MitmAddon.getNewVersionAvailable(this).isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.update_available)
                    .setMessage(R.string.mitm_addon_update_available)
                    .setCancelable(false)
                    .setPositiveButton(R.string.update_action, (dialog, whichButton) -> {
                        Intent intent = new Intent(this, MitmSetupWizard.class);
                        startActivity(intent);
                    })
                    .setNegativeButton(R.string.cancel_action, (dialog, whichButton) -> {
                        MitmAddon.ignoreNewVersion(this);
                        startCapture();
                    })
                    .show();

            return;
        }
    }

    if(!Prefs.isRootCaptureEnabled(mPrefs) && (Utils.getRunningVpn(this) != null)) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.active_vpn_detected)
                .setMessage(R.string.disconnect_vpn_confirm)
                .setPositiveButton(R.string.ok, (dialog, whichButton) -> doStartCaptureService(null))
                .setNegativeButton(R.string.cancel_action, (dialog, whichButton) -> {})
                .show();
    } else
        doStartCaptureService(null);
}
```

- [ ] **Step 6: Build compile check**

Run:

```powershell
.\gradlew.bat compileStandardDebugJavaWithJavac
```

Expected: compilation succeeds.

- [ ] **Step 7: Commit permission prompt**

Run:

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/java/com/emanuelef/remote_capture/activities/MainActivity.java app/src/main/res/values/strings.xml
git commit -m "Prompt for marker overlay permission"
```

## Task 3: Overlay Service

**Files:**
- Create: `app/src/main/java/com/emanuelef/remote_capture/CaptureMarkerOverlayService.java`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create overlay service class**

Create `app/src/main/java/com/emanuelef/remote_capture/CaptureMarkerOverlayService.java`:

```java
package com.emanuelef.remote_capture;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

public class CaptureMarkerOverlayService extends Service {
    private static final String TAG = "CaptureMarkerOverlay";
    private static final String EXTRA_MARKER_URI = "marker_uri";

    private WindowManager mWindowManager;
    private View mOverlayView;
    private WindowManager.LayoutParams mLayoutParams;
    private Uri mMarkerUri;
    private int mStartX;
    private int mStartY;
    private float mTouchStartX;
    private float mTouchStartY;

    public static boolean canDrawOverlays(Context ctx) {
        return (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) || Settings.canDrawOverlays(ctx);
    }

    public static Intent buildIntent(Context ctx, Uri markerUri) {
        Intent intent = new Intent(ctx, CaptureMarkerOverlayService.class);
        intent.putExtra(EXTRA_MARKER_URI, markerUri.toString());
        return intent;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(!canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        String markerUri = (intent != null) ? intent.getStringExtra(EXTRA_MARKER_URI) : null;
        if((markerUri == null) || markerUri.isBlank()) {
            Log.w(TAG, "Missing marker uri");
            stopSelf();
            return START_NOT_STICKY;
        }

        mMarkerUri = Uri.parse(markerUri);

        if(mOverlayView == null)
            showOverlay();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showOverlay() {
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mOverlayView = buildOverlayView();

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        mLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mLayoutParams.gravity = Gravity.TOP | Gravity.START;
        mLayoutParams.x = 24;
        mLayoutParams.y = 160;

        try {
            mWindowManager.addView(mOverlayView, mLayoutParams);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not add overlay: " + e);
            mOverlayView = null;
            stopSelf();
        }
    }

    private View buildOverlayView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(8, 8, 8, 8);
        layout.setBackgroundColor(0xCC222222);

        Button open = new Button(this);
        open.setText(CaptureMarkerWriter.LABEL_OPEN);
        open.setOnClickListener(v -> recordMarker(CaptureMarkerWriter.LABEL_OPEN));

        Button close = new Button(this);
        close.setText(CaptureMarkerWriter.LABEL_CLOSE);
        close.setOnClickListener(v -> recordMarker(CaptureMarkerWriter.LABEL_CLOSE));

        layout.addView(open);
        layout.addView(close);
        layout.setOnTouchListener(this::onOverlayTouch);

        return layout;
    }

    private boolean onOverlayTouch(View view, MotionEvent event) {
        if(mLayoutParams == null || mWindowManager == null)
            return false;

        switch(event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mStartX = mLayoutParams.x;
                mStartY = mLayoutParams.y;
                mTouchStartX = event.getRawX();
                mTouchStartY = event.getRawY();
                return false;
            case MotionEvent.ACTION_MOVE:
                mLayoutParams.x = mStartX + (int) (event.getRawX() - mTouchStartX);
                mLayoutParams.y = mStartY + (int) (event.getRawY() - mTouchStartY);
                mWindowManager.updateViewLayout(mOverlayView, mLayoutParams);
                return true;
            default:
                return false;
        }
    }

    private void recordMarker(String label) {
        if((mMarkerUri == null) || !CaptureMarkerWriter.appendMarker(this, mMarkerUri, System.currentTimeMillis(), label)) {
            Utils.showToast(this, R.string.marker_write_failed);
        }
    }

    private void removeOverlay() {
        if((mWindowManager != null) && (mOverlayView != null)) {
            try {
                mWindowManager.removeView(mOverlayView);
            } catch (RuntimeException ignored) {}
        }

        mOverlayView = null;
        mWindowManager = null;
        mLayoutParams = null;
    }
}
```

- [ ] **Step 2: Register the service**

In `app/src/main/AndroidManifest.xml`, add this inside `<application>` near the other services:

```xml
<service
    android:name=".CaptureMarkerOverlayService"
    android:exported="false" />
```

- [ ] **Step 3: Build compile check**

Run:

```powershell
.\gradlew.bat compileStandardDebugJavaWithJavac
```

Expected: compilation succeeds.

- [ ] **Step 4: Commit overlay service**

Run:

```powershell
git add app/src/main/java/com/emanuelef/remote_capture/CaptureMarkerOverlayService.java app/src/main/AndroidManifest.xml
git commit -m "Add capture marker overlay service"
```

## Task 4: CaptureService Lifecycle Integration

**Files:**
- Modify: `app/src/main/java/com/emanuelef/remote_capture/CaptureService.java`

- [ ] **Step 1: Add overlay lifecycle field**

Add this field near the other service state fields in `CaptureService`:

```java
private boolean mMarkerOverlayStarted;
```

- [ ] **Step 2: Start marker overlay after capture thread starts**

In `CaptureService.onStartCommand()`, immediately after:

```java
mCaptureThread = new Thread(this, "PacketCapture");
mCaptureThread.start();
```

add:

```java
startMarkerOverlayIfNeeded();
```

- [ ] **Step 3: Stop marker overlay on service shutdown paths**

In `CaptureService.onDestroy()`, call this before `super.onDestroy()`:

```java
stopMarkerOverlay();
```

In `CaptureService.stopAndJoinThreads()`, after `mDumper = null;`, add:

```java
stopMarkerOverlay();
```

This covers both normal native-thread termination and Android service destruction.

- [ ] **Step 4: Add helper methods to `CaptureService`**

Add these methods near the other private service helpers:

```java
private void startMarkerOverlayIfNeeded() {
    mMarkerOverlayStarted = false;

    if((mSettings == null) || (mSettings.dump_mode != Prefs.DumpMode.PCAP_FILE))
        return;
    if((mPcapUri == null) || (mPcapFname == null) || mPcapFname.isBlank())
        return;
    if(!CaptureMarkerOverlayService.canDrawOverlays(this)) {
        Log.w(TAG, "Marker overlay permission missing");
        return;
    }

    Uri markerUri = CaptureMarkerWriter.getMarkerUri(this, mPcapUri, mPcapFname);
    if(markerUri == null) {
        Log.w(TAG, "Could not create marker URI for " + mPcapFname);
        return;
    }

    try {
        startService(CaptureMarkerOverlayService.buildIntent(this, markerUri));
        mMarkerOverlayStarted = true;
    } catch (RuntimeException e) {
        Log.w(TAG, "Could not start marker overlay: " + e);
    }
}

private void stopMarkerOverlay() {
    if(!mMarkerOverlayStarted)
        return;

    try {
        stopService(new Intent(this, CaptureMarkerOverlayService.class));
    } catch (RuntimeException e) {
        Log.w(TAG, "Could not stop marker overlay: " + e);
    }

    mMarkerOverlayStarted = false;
}
```

- [ ] **Step 5: Add imports if needed**

`CaptureService` already imports `android.content.Intent` and `android.net.Uri`, so no new imports should be required for this task.

- [ ] **Step 6: Run writer tests**

Run:

```powershell
.\gradlew.bat testStandardDebugUnitTest --tests com.emanuelef.remote_capture.CaptureMarkerWriterTest
```

Expected: tests pass.

- [ ] **Step 7: Build compile check**

Run:

```powershell
.\gradlew.bat compileStandardDebugJavaWithJavac
```

Expected: compilation succeeds.

- [ ] **Step 8: Commit lifecycle integration**

Run:

```powershell
git add app/src/main/java/com/emanuelef/remote_capture/CaptureService.java
git commit -m "Show marker overlay during PCAP captures"
```

## Task 5: Final Verification

**Files:**
- No planned code changes.

- [ ] **Step 1: Run focused unit test**

Run:

```powershell
.\gradlew.bat testStandardDebugUnitTest --tests com.emanuelef.remote_capture.CaptureMarkerWriterTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run broader unit tests for touched area**

Run:

```powershell
.\gradlew.bat testStandardDebugUnitTest --tests com.emanuelef.remote_capture.UtilsTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Compile the app Java sources**

Run:

```powershell
.\gradlew.bat compileStandardDebugJavaWithJavac
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual Android verification**

Install/run the `standardDebug` build on a device or emulator where overlay permission can be granted.

Manual checks:

1. Set dump mode to PCAP file.
2. Start capture without overlay permission.
3. Confirm the permission dialog appears and `Continue without markers` starts capture without buttons.
4. Stop capture.
5. Grant display-over-other-apps permission for PCAPdroid in Android settings.
6. Start PCAP file capture again.
7. Switch to another app and confirm two floating buttons are visible.
8. Tap `开`, then tap `闭`.
9. Stop capture and confirm the overlay disappears.
10. Inspect Downloads/PCAPdroid and confirm `<pcap basename>.markers.json` exists next to the PCAP/PCAPNG file and contains two marker objects with labels `\u5f00` and `\u95ed`.

- [ ] **Step 5: Confirm git status**

Run:

```powershell
git status --short
```

Expected: no uncommitted changes.

## Self-Review

- Spec coverage:
  - System-level floating buttons: Task 3.
  - Only during PCAP file capture: Task 4.
  - Overlay permission flow: Task 2.
  - Sibling marker JSON with PCAP basename: Task 1 and Task 4.
  - Append timestamp plus `开`/`闭`: Task 1 and Task 3.
  - Capture continues if permission/write fails: Task 2 and Task 3.
- Completion marker scan: the plan has no intentional unfinished markers or undefined implementation steps.
- Type consistency:
  - `CaptureMarkerWriter.LABEL_OPEN` / `LABEL_CLOSE` are used by tests and overlay service.
  - `CaptureMarkerOverlayService.canDrawOverlays()` is used by `CaptureService`.
  - `CaptureMarkerOverlayService.buildIntent(Context, Uri)` is used by `CaptureService`.
