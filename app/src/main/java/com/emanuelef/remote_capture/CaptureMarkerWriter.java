/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class CaptureMarkerWriter {
    public static final String LABEL_OPEN = "开";
    public static final String LABEL_CLOSE = "闭";

    private static final Gson GSON = new Gson();
    private static final String MARKER_SUFFIX = ".markers.json";

    private CaptureMarkerWriter() {}

    public static String getMarkerFileName(String captureFileName) {
        String lowerName = captureFileName.toLowerCase(Locale.ROOT);

        if (lowerName.endsWith(".pcap"))
            return captureFileName.substring(0, captureFileName.length() - ".pcap".length()) + MARKER_SUFFIX;

        if (lowerName.endsWith(".pcapng"))
            return captureFileName.substring(0, captureFileName.length() - ".pcapng".length()) + MARKER_SUFFIX;

        return captureFileName + MARKER_SUFFIX;
    }

    public static Uri getMarkerUri(Context context, Uri pcapUri, String pcapFname) {
        try {
            String markerName = getMarkerFileName(pcapFname);

            if (ContentResolver.SCHEME_FILE.equals(pcapUri.getScheme())) {
                String path = pcapUri.getPath();
                if ((path == null) || path.isEmpty())
                    return null;

                File parentDir = new File(path).getParentFile();
                return (parentDir != null) ? Uri.fromFile(new File(parentDir, markerName)) : null;
            }

            return getMarkerUriInSameRelativePath(context, pcapUri, markerName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Uri getMarkerUriInSameRelativePath(Context context, Uri pcapUri, String markerName) {
        ContentResolver resolver = context.getContentResolver();
        String relativePath;

        try (Cursor cursor = resolver.query(pcapUri,
                new String[]{MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
            if ((cursor == null) || !cursor.moveToFirst())
                return null;

            int index = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            if (index < 0)
                return null;

            relativePath = cursor.getString(index);
            if ((relativePath == null) || relativePath.isEmpty())
                return null;
        } catch (RuntimeException e) {
            return null;
        }

        Uri externalUri = MediaStore.Files.getContentUri("external");
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " +
                MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        String[] selectionArgs = new String[]{relativePath, markerName};

        try (Cursor cursor = resolver.query(externalUri,
                new String[]{MediaStore.MediaColumns._ID}, selection, selectionArgs, null)) {
            if ((cursor != null) && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return ContentUris.withAppendedId(externalUri, id);
            }
        } catch (RuntimeException e) {
            return null;
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, markerName);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            return resolver.insert(externalUri, values);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String appendMarkerJson(String existingJson, long timestamp, String label) {
        JsonArray markers;

        if (existingJson == null) {
            markers = new JsonArray();
        } else {
            JsonElement root = JsonParser.parseString(existingJson);

            if (!root.isJsonArray())
                throw new JsonSyntaxException("Expected a JSON array");

            markers = root.getAsJsonArray();
        }

        JsonObject marker = new JsonObject();
        marker.addProperty("timestamp", timestamp);
        marker.addProperty("label", label);
        markers.add(marker);

        return GSON.toJson(markers);
    }

    public static boolean appendMarker(File markerFile, long timestamp, String label) {
        try {
            String existingJson = markerFile.exists() ? readUtf8(markerFile) : null;
            String updatedJson = appendMarkerJson(existingJson, timestamp, label);

            writeUtf8(markerFile, updatedJson);
            return true;
        } catch (IOException | JsonSyntaxException e) {
            return false;
        }
    }

    public static boolean appendMarker(Context context, Uri markerUri, long timestamp, String label) {
        if (ContentResolver.SCHEME_FILE.equals(markerUri.getScheme())) {
            String path = markerUri.getPath();
            return (path != null) && appendMarker(new File(path), timestamp, label);
        }

        ContentResolver resolver = context.getContentResolver();

        try {
            String existingJson = readUtf8(resolver, markerUri);
            String updatedJson = appendMarkerJson(existingJson, timestamp, label);

            try (OutputStream out = resolver.openOutputStream(markerUri, "rwt")) {
                if (out == null)
                    return false;

                out.write(updatedJson.getBytes(StandardCharsets.UTF_8));
            }

            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String readUtf8(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return readUtf8(in);
        }
    }

    private static String readUtf8(ContentResolver resolver, Uri markerUri) throws IOException {
        try (InputStream in = resolver.openInputStream(markerUri)) {
            if (in == null)
                return null;

            String content = readUtf8(in);
            return content.isEmpty() ? null : content;
        }
    }

    private static String readUtf8(InputStream in) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;

            while ((read = in.read(buffer)) != -1)
                out.write(buffer, 0, read);

            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeUtf8(File file, String content) throws IOException {
        File parent = file.getParentFile();

        if ((parent != null) && !parent.exists() && !parent.mkdirs() && !parent.exists())
            throw new IOException("Could not create parent directory: " + parent);

        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }
}
