# Launcher App Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every installed third-party application with a launcher activity visible and selectable in PCAPdroid's Target apps screen, including Douyin and other live-streaming applications.

**Architecture:** Keep the existing installed-package discovery path, add an Android `MAIN`/`LAUNCHER` package-visibility declaration, and merge launcher-discovered packages as a fallback. Deduplicate by package name before creating descriptors and retain the existing UID deduplication because Android VPN allowlists operate on UIDs.

**Tech Stack:** Android Manifest package visibility, Java `PackageManager`/`ResolveInfo`, AndroidX LoaderManager, Gradle Android build, ADB real-device acceptance testing.

---

## Existing changes to preserve

`app/src/test/java/com/emanuelef/remote_capture/CaptureMarkerWriterTest.java` already has an uncommitted test-runner annotation added during device-test setup. Do not reset, overwrite, or include that unrelated existing edit in the feature commits unless it is explicitly needed by a test command.

### Task 1: Declare launcher package visibility

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:3-10`

- [ ] **Step 1: Add the launcher intent query inside the manifest.**

Insert this block directly after the `<manifest>` namespace declarations and before the permission declarations:

```xml
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>
```

Keep the existing `QUERY_ALL_PACKAGES` declaration. The launcher query is the compatibility path for devices that still hide packages from `getInstalledPackages()` even when that permission is granted.

- [ ] **Step 2: Validate the manifest syntax and diff.**

Run:

```powershell
git diff --check -- app/src/main/AndroidManifest.xml
```

Expected: no whitespace or XML-related diff errors.

- [ ] **Step 3: Commit the manifest change.**

```powershell
git add app/src/main/AndroidManifest.xml
git commit -m "fix: declare launcher package visibility"
```

### Task 2: Merge launcher-visible packages into the selector

**Files:**
- Modify: `app/src/main/java/com/emanuelef/remote_capture/AppsLoader.java:20-95`

- [ ] **Step 1: Add the Android package-query imports.**

Add these imports alongside the existing Android imports:

```java
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
```

- [ ] **Step 2: Add a package-source merge helper before `asyncLoadAppsInfo()`.**

Implement a helper that retains packages returned by `getInstalledPackages()` and fills missing packages from the launcher query. Use package name as the first deduplication key, resolve a full `PackageInfo` for each launcher result, and skip a result if it cannot be resolved:

```java
private ArrayList<PackageInfo> getVisiblePackages(PackageManager pm) {
    ArrayMap<String, PackageInfo> packages = new ArrayMap<>();

    for (PackageInfo pkg : Utils.getInstalledPackages(pm, 0)) {
        if ((pkg != null) && (pkg.applicationInfo != null))
            packages.put(pkg.packageName, pkg);
    }

    Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
    launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

    try {
        List<ResolveInfo> launchers = pm.queryIntentActivities(launcherIntent, 0);

        for (ResolveInfo resolveInfo : launchers) {
            ActivityInfo activityInfo = resolveInfo.activityInfo;
            if ((activityInfo == null) || (activityInfo.applicationInfo == null))
                continue;

            String packageName = activityInfo.applicationInfo.packageName;
            if (packages.containsKey(packageName))
                continue;

            try {
                packages.put(packageName, Utils.getPackageInfo(pm, packageName, 0));
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Could not resolve launcher package " + packageName);
            }
        }
    } catch (RuntimeException e) {
        Log.w(TAG, "Could not query launcher packages: " + e.getMessage());
    }

    return new ArrayList<>(packages.values());
}
```

- [ ] **Step 3: Use the merged package list in `asyncLoadAppsInfo()`.**

Replace:

```java
List<PackageInfo> packs = Utils.getInstalledPackages(pm, 0);
```

with:

```java
List<PackageInfo> packs = getVisiblePackages(pm);
```

Leave the existing loop's behavior intact: exclude PCAPdroid itself, retain one descriptor per UID, preserve Termux handling, and sort with the existing `AppDescriptor` ordering.

- [ ] **Step 4: Compile the Java source without starting a native host test build.**

From the feature worktree, run:

```powershell
$env:ANDROID_HOME='C:\Users\20856\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:JAVA_HOME='D:\soft\Java-21\jdk'
$env:Path='D:\soft\mingw64\bin;C:\Users\20856\AppData\Local\Android\Sdk\platform-tools;C:\Users\20856\AppData\Local\Android\Sdk\cmdline-tools\latest\bin;C:\Users\20856\AppData\Local\Android\Sdk\cmake\3.22.1\bin;'+$env:Path
& .\gradlew.bat compileStandardDebugJavaWithJavac -x buildHostZstdJni --no-daemon
```

Expected: `BUILD SUCCESSFUL`. The SDK XML compatibility warning may remain; it is non-fatal.

- [ ] **Step 5: Review the implementation diff and commit it.**

Run:

```powershell
git diff --check
git diff -- app/src/main/java/com/emanuelef/remote_capture/AppsLoader.java
```

Then commit only the feature source:

```powershell
git add app/src/main/java/com/emanuelef/remote_capture/AppsLoader.java
git commit -m "fix: show launcher apps in target app selector"
```

### Task 3: Build, install, and verify on the real phone

**Files:**
- Build artifact: `app/build/outputs/apk/standard/debug/app-standard-debug.apk`
- Device package: `com.emanuelef.remote_capture.debug`

- [ ] **Step 1: Build the installable debug APK.**

Run:

```powershell
& .\gradlew.bat assembleStandardDebug -PdoNotStrip --no-daemon
```

Expected: `BUILD SUCCESSFUL` and an APK at `app/build/outputs/apk/standard/debug/app-standard-debug.apk`.

- [ ] **Step 2: Install and launch the APK on the authorized device.**

Run:

```powershell
adb install -r -t "app\build\outputs\apk\standard\debug\app-standard-debug.apk"
adb shell am start -W -n com.emanuelef.remote_capture.debug/com.emanuelef.remote_capture.activities.MainActivity
```

Expected: install reports `Success`, the device remains `7d914ef9 device`, and MainActivity opens without a crash.

- [ ] **Step 3: Verify the selector discovers Douyin.**

Open the Target apps selector through the main-screen `app_filter_text` row, wait for the list loader, tap the search action, and enter `com.ss.android.ugc.aweme`. Dump the hierarchy:

```powershell
adb shell uiautomator dump --compressed /sdcard/target-apps.xml
adb shell cat /sdcard/target-apps.xml
```

Expected: the hierarchy contains a row with package text `com.ss.android.ugc.aweme`, an application label (抖音 on the test phone), and a clickable/checked switch. The screen must no longer show `无匹配` for this query.

- [ ] **Step 4: Verify selection persists in the main screen.**

Tap the Douyin row or its switch, go back to MainActivity, and dump the hierarchy again:

```powershell
adb shell uiautomator dump --compressed /sdcard/main-after-filter.xml
adb shell cat /sdcard/main-after-filter.xml
```

Expected: the target-app description identifies Douyin and no longer says `捕获所有应用`.

- [ ] **Step 5: Verify filtered capture with real Douyin traffic.**

Start PCAP-file capture from the UI, accept the Android VPN request if shown, launch the installed Douyin package, and perform real browsing actions. Capture a log slice while the target app is active:

```powershell
adb shell monkey -p com.ss.android.ugc.aweme 1
Start-Sleep -Seconds 8
adb logcat -d -t 500 | Select-String -Pattern 'Setting app filter|Get uid|pcapdroid-native|CaptureService' | Select-Object -Last 120
```

Expected: logs show the app filter containing `com.ss.android.ugc.aweme` and real packet processing. Stop capture, confirm the PCAP file is non-empty, and report the exact file path.

- [ ] **Step 6: Verify no regression and working-tree scope.**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors; only the pre-existing `CaptureMarkerWriterTest.java` test-runner edit remains uncommitted after the two feature commits. Report that device acceptance used real phone/Wi-Fi traffic; unit-test fixtures, if run, are constructed data.

## Verification notes

- A full `testStandardDebugUnitTest` run may still require the host zstd JNI build and a Visual Studio `nmake` toolchain. The device acceptance test is the authoritative check for the package-visibility issue because it exercises the vendor Android PackageManager.
- The change intentionally discovers launchable apps rather than hard-coding live-platform package names. Background-only packages without launcher activities remain outside the manual target-app selector.

