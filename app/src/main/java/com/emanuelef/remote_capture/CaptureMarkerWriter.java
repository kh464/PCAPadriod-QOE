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
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public final class CaptureMarkerWriter {
    public static final String LABEL_OPEN = "开";
    public static final String LABEL_CLOSE = "闭";

    private static final Gson GSON = new Gson();
    private static final String MARKER_SUFFIX = ".markers.json";

    private CaptureMarkerWriter() {}

    public static String getMarkerFileName(String captureFileName) {
        if (captureFileName.endsWith(".pcap"))
            return captureFileName.substring(0, captureFileName.length() - ".pcap".length()) + MARKER_SUFFIX;

        if (captureFileName.endsWith(".pcapng"))
            return captureFileName.substring(0, captureFileName.length() - ".pcapng".length()) + MARKER_SUFFIX;

        return captureFileName + MARKER_SUFFIX;
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

    private static String readUtf8(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;

            while ((read = in.read(buffer)) != -1)
                out.write(buffer, 0, read);

            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeUtf8(File file, String content) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }
}
