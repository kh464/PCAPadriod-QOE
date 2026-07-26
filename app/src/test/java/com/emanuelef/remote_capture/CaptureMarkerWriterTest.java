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

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CaptureMarkerWriterTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private JsonArray parseArray(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    @Test
    public void testGetMarkerFileName() {
        assertEquals("capture.markers.json", CaptureMarkerWriter.getMarkerFileName("capture.pcap"));
        assertEquals("capture.markers.json", CaptureMarkerWriter.getMarkerFileName("capture.pcapng"));
        assertEquals("capture.markers.json", CaptureMarkerWriter.getMarkerFileName("capture.PCAP"));
        assertEquals("capture.markers.json", CaptureMarkerWriter.getMarkerFileName("capture.PCAPNG"));
        assertEquals("capture.dump.markers.json", CaptureMarkerWriter.getMarkerFileName("capture.dump"));
    }

    @Test
    public void testLabelsUseChineseCharacters() {
        assertEquals("\u5f00", CaptureMarkerWriter.LABEL_OPEN);
        assertEquals("\u95ed", CaptureMarkerWriter.LABEL_CLOSE);
    }

    @Test
    public void testAppendMarkerJsonCreatesArray() {
        String json = CaptureMarkerWriter.appendMarkerJson(null, 1234L, CaptureMarkerWriter.LABEL_OPEN);

        JsonArray markers = parseArray(json);
        assertEquals(1, markers.size());

        JsonObject marker = markers.get(0).getAsJsonObject();
        assertEquals(1234L, marker.get("timestamp").getAsLong());
        assertEquals(CaptureMarkerWriter.LABEL_OPEN, marker.get("label").getAsString());
    }

    @Test
    public void testAppendMarkerJsonAppendsToArray() {
        String existing = "[{\"timestamp\":1000,\"label\":\"" + CaptureMarkerWriter.LABEL_OPEN + "\"}]";

        String json = CaptureMarkerWriter.appendMarkerJson(existing, 2000L, CaptureMarkerWriter.LABEL_CLOSE);

        JsonArray markers = parseArray(json);
        assertEquals(2, markers.size());
        assertEquals(1000L, markers.get(0).getAsJsonObject().get("timestamp").getAsLong());
        assertEquals(CaptureMarkerWriter.LABEL_OPEN, markers.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals(2000L, markers.get(1).getAsJsonObject().get("timestamp").getAsLong());
        assertEquals(CaptureMarkerWriter.LABEL_CLOSE, markers.get(1).getAsJsonObject().get("label").getAsString());
    }

    @Test(expected = JsonSyntaxException.class)
    public void testAppendMarkerJsonRejectsMalformedJson() {
        CaptureMarkerWriter.appendMarkerJson("[", 1234L, CaptureMarkerWriter.LABEL_OPEN);
    }

    @Test(expected = JsonSyntaxException.class)
    public void testAppendMarkerJsonRejectsNonArrayJson() {
        CaptureMarkerWriter.appendMarkerJson("{\"timestamp\":1234}", 1234L, CaptureMarkerWriter.LABEL_OPEN);
    }

    @Test
    public void testAppendMarkerCreatesAndUpdatesUtf8File() throws Exception {
        File markerFile = new File(tempFolder.getRoot(), "capture.markers.json");

        assertTrue(CaptureMarkerWriter.appendMarker(markerFile, 1234L, CaptureMarkerWriter.LABEL_OPEN));
        assertTrue(CaptureMarkerWriter.appendMarker(markerFile, 2345L, CaptureMarkerWriter.LABEL_CLOSE));

        byte[] bytes = Files.readAllBytes(markerFile.toPath());
        String json = new String(bytes, StandardCharsets.UTF_8);
        JsonArray markers = parseArray(json);

        assertEquals(2, markers.size());
        assertEquals(1234L, markers.get(0).getAsJsonObject().get("timestamp").getAsLong());
        assertEquals(CaptureMarkerWriter.LABEL_OPEN, markers.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals(2345L, markers.get(1).getAsJsonObject().get("timestamp").getAsLong());
        assertEquals(CaptureMarkerWriter.LABEL_CLOSE, markers.get(1).getAsJsonObject().get("label").getAsString());
        assertTrue(json.contains(CaptureMarkerWriter.LABEL_OPEN));
        assertTrue(json.contains(CaptureMarkerWriter.LABEL_CLOSE));
    }

    @Test
    public void testAppendMarkerCreatesParentDirectory() throws Exception {
        File markerFile = new File(tempFolder.getRoot(), "nested/capture.markers.json");

        assertTrue(CaptureMarkerWriter.appendMarker(markerFile, 1234L, CaptureMarkerWriter.LABEL_OPEN));

        String json = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8);
        JsonArray markers = parseArray(json);
        assertEquals(1, markers.size());
        assertEquals(CaptureMarkerWriter.LABEL_OPEN, markers.get(0).getAsJsonObject().get("label").getAsString());
    }

    @Test
    public void testAppendMarkerReturnsFalseWhenParentCannotBeCreated() throws Exception {
        File fileInsteadOfDir = tempFolder.newFile("not_a_directory");
        File markerFile = new File(fileInsteadOfDir, "nested/capture.markers.json");

        assertFalse(CaptureMarkerWriter.appendMarker(markerFile, 1234L, CaptureMarkerWriter.LABEL_OPEN));
        assertFalse(markerFile.exists());
    }

    @Test
    public void testAppendMarkerDoesNotOverwriteMalformedFile() throws Exception {
        File markerFile = tempFolder.newFile("capture.markers.json");
        String malformedJson = "[";
        Files.write(markerFile.toPath(), malformedJson.getBytes(StandardCharsets.UTF_8));

        assertFalse(CaptureMarkerWriter.appendMarker(markerFile, 1234L, CaptureMarkerWriter.LABEL_OPEN));

        String content = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8);
        assertEquals(malformedJson, content);
    }

    @Test
    public void testAppendMarkerAppendsToUri() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File markerFile = tempFolder.newFile("capture.markers.json");
        Uri markerUri = Uri.fromFile(markerFile);

        assertTrue(CaptureMarkerWriter.appendMarker(context, markerUri, 1234L, CaptureMarkerWriter.LABEL_OPEN));
        assertTrue(CaptureMarkerWriter.appendMarker(context, markerUri, 2345L, CaptureMarkerWriter.LABEL_CLOSE));

        String json = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8);
        JsonArray markers = parseArray(json);

        assertEquals(2, markers.size());
        assertEquals(1234L, markers.get(0).getAsJsonObject().get("timestamp").getAsLong());
        assertEquals(CaptureMarkerWriter.LABEL_OPEN, markers.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals(2345L, markers.get(1).getAsJsonObject().get("timestamp").getAsLong());
        assertEquals(CaptureMarkerWriter.LABEL_CLOSE, markers.get(1).getAsJsonObject().get("label").getAsString());
    }

    @Test
    public void testAppendMarkerUriDoesNotOverwriteMalformedJson() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File markerFile = tempFolder.newFile("capture.markers.json");
        Uri markerUri = Uri.fromFile(markerFile);
        String malformedJson = "[";
        Files.write(markerFile.toPath(), malformedJson.getBytes(StandardCharsets.UTF_8));

        assertFalse(CaptureMarkerWriter.appendMarker(context, markerUri, 1234L, CaptureMarkerWriter.LABEL_OPEN));

        String content = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8);
        assertEquals(malformedJson, content);
    }
}
