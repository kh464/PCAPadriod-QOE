# Launcher App Visibility Design

## Background

The target-app selector currently builds its list from `PackageManager.getInstalledPackages(0)`. On the connected Xiaomi device, the debug APK receives only one visible package (PCAPdroid itself), even though third-party applications such as Douyin (`com.ss.android.ugc.aweme`) are installed. The selector therefore shows `无匹配` and cannot configure an application filter.

The manifest already requests `QUERY_ALL_PACKAGES`, but that permission is not sufficient on the current device/installation combination. The application selector needs a package-visibility-compatible fallback that can discover user-launchable applications.

## Goal

Make all installed, launchable third-party applications visible and selectable in the “Target apps” screen, including live-streaming applications such as Douyin, Kuaishou, Bilibili, Douyu, and Huya, without maintaining a hard-coded live-platform list.

## Non-goals

- Do not preselect or automatically enable any application.
- Do not change the VPN capture filter semantics or UID-based filtering.
- Do not add a hard-coded list of live-streaming package names.
- Do not expose background-only system packages that cannot be launched by a tester merely to satisfy this selector.

## Design

### Manifest visibility

Add a `<queries>` declaration for the `MAIN` + `LAUNCHER` intent. This makes packages with a user-launchable activity queryable on Android package-visibility-restricted devices. Keep the existing `QUERY_ALL_PACKAGES` permission for devices where it is honored, and use the launcher query as a compatibility path rather than replacing the existing behavior.

### App loading

Update `AppsLoader.asyncLoadAppsInfo()` to merge two package sources:

1. Packages returned by `getInstalledPackages(0)`, preserving the existing behavior.
2. Packages returned by `queryIntentActivities(MAIN, LAUNCHER)`, which supplies the fallback set on the affected device.

Merge by package name before constructing `AppDescriptor` objects. Then retain the current UID de-duplication rule: Android VPN application allowlists are UID-based, so multiple packages sharing one UID must be represented by one descriptor. Exclude the PCAPdroid package as before, sort using the existing `AppDescriptor` ordering, and keep the current system-app visibility filter in `AppsTogglesAdapter`.

If a launcher result cannot be resolved to a `PackageInfo`, skip that result and continue loading the remaining applications. A package-query failure must not crash the selector.

### Data flow

`AppFilterActivity` continues to persist selected package names in `PREF_APP_FILTER`. The selector only changes the discovery list. At capture start, `CaptureService` continues to resolve selected package names to UIDs and passes them to `VpnService.Builder.addAllowedApplication()`. Therefore selecting Douyin in the UI and selecting `com.ss.android.ugc.aweme` through the existing capture API produce the same filter behavior.

## Testing and acceptance

- Add unit-testable package-merge behavior if the existing test structure permits it; otherwise verify the loader through the real device.
- Build the `standardDebug` APK and install it on the connected device.
- Open “Target apps”, search for `com.ss.android.ugc.aweme` or the localized Douyin label, and verify one selectable Douyin row is shown.
- Select Douyin, return to the main screen, and verify the target-app description identifies Douyin rather than “capture all apps”.
- Start a PCAP capture, generate real Douyin traffic, and verify the capture starts without errors.
- Stop the capture and verify the PCAP output is non-empty. Report clearly which results use real device traffic versus constructed/unit-test data.

## Risks and mitigations

- Launcher queries may return multiple activities for one package: deduplicate by package name and then UID.
- A package can be visible through the launcher query but fail a subsequent package-info lookup: skip it with a diagnostic log.
- Applications without a launcher activity remain unavailable in this selector; they are not launchable through the normal manual test workflow and are outside this change's target.
