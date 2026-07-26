# Capture Marker Overlay Design

## Goal

Add a manual network-quality marker feature for PCAP file captures. While PCAPdroid is actively capturing traffic to a local PCAP/PCAPNG file, the phone should show two system-level floating buttons:

- `\u5f00` (`开`): record the time when the user notices poor network quality.
- `\u95ed` (`闭`): record the time when the user notices the network has recovered.

The buttons must remain available when the user leaves PCAPdroid and switches to other apps. They are marker controls only; they do not start or stop packet capture.

## Scope

This feature applies only when the current capture uses `Prefs.DumpMode.PCAP_FILE`. Other capture modes, such as no dump, HTTP server, UDP exporter, and TCP exporter, do not have a stable local PCAP sibling file target and will not show the marker overlay.

The overlay is shown only during an active PCAP file capture. It is automatically removed when capture stops or when the app can no longer host the overlay.

## Chosen Approach

Use a new dedicated overlay service, tentatively named `CaptureMarkerOverlayService`.

`CaptureService` remains responsible for capture lifecycle and for knowing the current PCAP output target. When a PCAP file capture starts and overlay permission is available, `CaptureService` starts `CaptureMarkerOverlayService` with enough information to locate the sibling marker JSON. When capture stops, `CaptureService` stops the overlay service.

This keeps WindowManager and overlay UI logic out of the already-large capture service, while still letting capture lifecycle drive marker availability.

## Permission Model

The feature requires Android's system overlay permission via `SYSTEM_ALERT_WINDOW`.

PCAPdroid should declare the permission in `AndroidManifest.xml`. Before starting a PCAP file capture with markers enabled, the app should check whether `Settings.canDrawOverlays(context)` is true.

If permission is missing, PCAPdroid should guide the user to the system overlay permission screen. The first implementation should not block packet capture if permission is missing; capture can continue normally, but marker buttons will not appear until permission is granted.

## File Naming

The marker JSON file uses the current PCAP basename and lives next to the PCAP output file:

```text
PCAPdroid_2026_07_26_213000.pcap
PCAPdroid_2026_07_26_213000.markers.json
```

```text
PCAPdroid_2026_07_26_213000.pcapng
PCAPdroid_2026_07_26_213000.markers.json
```

The extension replacement rule is:

1. If the PCAP filename ends with `.pcap` or `.pcapng`, remove that extension.
2. Append `.markers.json`.
3. Create or update that file in the same directory/container as the PCAP file.

## JSON Format

The marker file stores an array of marker objects:

```json
[
  {
    "timestamp": 1785072600123,
    "label": "\u5f00"
  },
  {
    "timestamp": 1785072660456,
    "label": "\u95ed"
  }
]
```

`timestamp` is Unix epoch time in milliseconds from `System.currentTimeMillis()`. `label` is exactly `\u5f00` (`开`) or `\u95ed` (`闭`).

When the file does not exist, it is created with a one-item array. When it exists, the new marker is appended while preserving valid JSON.

## Data Flow

1. The user starts capture from `MainActivity`.
2. `MainActivity` creates `CaptureSettings` and starts `CaptureService` through `CaptureHelper`.
3. `CaptureService` prepares the PCAP output URI/name for `Prefs.DumpMode.PCAP_FILE`.
4. If overlay permission exists, `CaptureService` starts `CaptureMarkerOverlayService`.
5. The overlay service displays two floating buttons: `\u5f00` (`开`) and `\u95ed` (`闭`).
6. On each click, the overlay service appends the marker to the sibling JSON file.
7. When capture stops, `CaptureService` stops `CaptureMarkerOverlayService`.

## Components

### CaptureService Integration

`CaptureService` will decide whether the marker overlay should run. It already owns the capture mode, PCAP URI, and PCAP filename. It should start the overlay service only when:

- capture is active,
- `mSettings.dump_mode == Prefs.DumpMode.PCAP_FILE`,
- the PCAP output URI and filename are known,
- system overlay permission is granted.

It should stop the overlay service on capture shutdown.

### CaptureMarkerOverlayService

The new service owns:

- creating/removing the system overlay view,
- rendering the `\u5f00` (`开`) and `\u95ed` (`闭`) buttons,
- handling button clicks,
- writing marker JSON entries.

The overlay should use `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` on supported Android versions. The UI should be small, simple, and movable only if that can be implemented without broadening the feature. The first version can use a fixed position that does not cover the app's main content too aggressively.

### Marker File Writer

Marker writing should be isolated behind a small helper, for example `CaptureMarkerWriter`, so JSON append behavior can be unit tested without Android overlay UI.

The helper should:

- derive the marker filename from the PCAP filename,
- create the marker JSON file if absent,
- append to an existing valid marker array,
- recover gracefully from missing or malformed existing JSON by reporting failure rather than corrupting the file.

## Error Handling

If overlay permission is missing, the marker overlay is not shown and capture continues.

If marker file creation or writing fails, the overlay remains visible and the app should show a short toast or log message. Packet capture must not stop because marker recording failed.

If the existing marker JSON is malformed, the first implementation should avoid overwriting it silently. It should log or notify the failure and skip the append.

## Testing

Unit tests should cover marker filename derivation and JSON append behavior, including:

- missing marker file,
- existing marker file with valid array,
- `.pcap` and `.pcapng` filename inputs,
- malformed existing JSON.

Manual Android testing should cover:

- granting overlay permission,
- starting PCAP file capture and seeing both buttons outside PCAPdroid,
- clicking `\u5f00` (`开`) and `\u95ed` (`闭`),
- stopping capture and verifying the overlay disappears,
- verifying the sibling `.markers.json` file contains the expected marker events.

## Non-Goals

This feature does not automatically detect network quality.

This feature does not control capture start/stop.

This feature does not display marker history in the UI.

This feature does not run for non-file dump modes.
