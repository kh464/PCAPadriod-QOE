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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CaptureMarkerWriterTest {
    private static final String FAILING_READ_AUTHORITY = "com.emanuelef.remote_capture.failing_read_marker";
    private static final String EMPTY_READ_AUTHORITY = "com.emanuelef.remote_capture.empty_read_marker";
    private static final String NO_RELATIVE_PATH_AUTHORITY = "com.emanuelef.remote_capture.no_relative_path_marker";
    private static final String RELATIVE_PATH_AUTHORITY = "com.emanuelef.remote_capture.relative_path_marker";
    private static final String DATA_PATH_AUTHORITY = "com.emanuelef.remote_capture.data_path_marker";

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
    public void testGetMarkerUriForFileUriUsesSameDirectory() {
        Context context = ApplicationProvider.getApplicationContext();
        File pcapFile = new File(tempFolder.getRoot(), "capture.pcapng");

        Uri markerUri = CaptureMarkerWriter.getMarkerUri(context, Uri.fromFile(pcapFile), "capture.pcapng");

        assertEquals(Uri.fromFile(new File(tempFolder.getRoot(), "capture.markers.json")), markerUri);
    }

    @Test
    public void testGetMarkerUriForFileUriWithoutPathReturnsNull() {
        Context context = ApplicationProvider.getApplicationContext();

        assertNull(CaptureMarkerWriter.getMarkerUri(context, Uri.parse("file:"), "capture.pcap"));
    }

    @Test
    public void testGetMarkerUriForContentUriWithoutRelativePathReturnsNull() {
        Context context = ApplicationProvider.getApplicationContext();
        ShadowContentResolver.registerProviderInternal(NO_RELATIVE_PATH_AUTHORITY, new NoRelativePathProvider());

        Uri pcapUri = Uri.parse("content://" + NO_RELATIVE_PATH_AUTHORITY + "/capture.pcapng");

        assertNull(CaptureMarkerWriter.getMarkerUri(context, pcapUri, "capture.pcapng"));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.Q)
    public void testGetMarkerUriForNonMediaStoreUriDoesNotQueryRelativePath() {
        Context context = ApplicationProvider.getApplicationContext();
        RelativePathProvider provider = new RelativePathProvider();
        ShadowContentResolver.registerProviderInternal(RELATIVE_PATH_AUTHORITY, provider);

        Uri pcapUri = Uri.parse("content://" + RELATIVE_PATH_AUTHORITY + "/capture.pcapng");

        assertNull(CaptureMarkerWriter.getMarkerUri(context, pcapUri, "capture.pcapng"));
        assertFalse(provider.wasQueried());
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.P)
    public void testGetMarkerUriForContentUriWithDataPathUsesSameDirectory() {
        Context context = ApplicationProvider.getApplicationContext();
        File pcapFile = new File(tempFolder.getRoot(), "capture.pcap");
        ShadowContentResolver.registerProviderInternal(DATA_PATH_AUTHORITY, new DataPathProvider(pcapFile.getAbsolutePath()));

        Uri pcapUri = Uri.parse("content://" + DATA_PATH_AUTHORITY + "/capture.pcap");

        assertEquals(Uri.fromFile(new File(tempFolder.getRoot(), "capture.markers.json")),
                CaptureMarkerWriter.getMarkerUri(context, pcapUri, "capture.pcap"));
    }

    @Test
    @Config(sdk = Build.VERSION_CODES.Q)
    public void testGetMarkerUriForContentUriWithDataPathOnQReturnsNull() {
        Context context = ApplicationProvider.getApplicationContext();
        File pcapFile = new File(tempFolder.getRoot(), "capture.pcap");
        ShadowContentResolver.registerProviderInternal(DATA_PATH_AUTHORITY, new DataPathProvider(pcapFile.getAbsolutePath()));

        Uri pcapUri = Uri.parse("content://" + DATA_PATH_AUTHORITY + "/capture.pcap");

        assertNull(CaptureMarkerWriter.getMarkerUri(context, pcapUri, "capture.pcap"));
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
    public void testAppendMarkerFileUriCreatesMissingFile() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File markerFile = new File(tempFolder.getRoot(), "missing/capture.markers.json");
        Uri markerUri = Uri.fromFile(markerFile);

        assertTrue(CaptureMarkerWriter.appendMarker(context, markerUri, 1234L, CaptureMarkerWriter.LABEL_OPEN));

        String json = new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8);
        JsonArray markers = parseArray(json);

        assertEquals(1, markers.size());
        assertEquals(1234L, markers.get(0).getAsJsonObject().get("timestamp").getAsLong());
        assertEquals(CaptureMarkerWriter.LABEL_OPEN, markers.get(0).getAsJsonObject().get("label").getAsString());
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

    @Test
    public void testAppendMarkerUriReturnsFalseWithoutWritingWhenReadFails() {
        Context context = ApplicationProvider.getApplicationContext();
        FailingReadProvider provider = new FailingReadProvider();
        ShadowContentResolver.registerProviderInternal(FAILING_READ_AUTHORITY, provider);

        Uri markerUri = Uri.parse("content://" + FAILING_READ_AUTHORITY + "/capture.markers.json");

        assertFalse(CaptureMarkerWriter.appendMarker(context, markerUri, 1234L, CaptureMarkerWriter.LABEL_OPEN));
        assertFalse(provider.wasOutputOpened());
    }

    @Test
    public void testAppendMarkerUriReturnsFalseWithoutWritingWhenContentIsEmpty() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        EmptyReadProvider provider = new EmptyReadProvider(tempFolder.newFile("empty.markers.json"));
        ShadowContentResolver.registerProviderInternal(EMPTY_READ_AUTHORITY, provider);

        Uri markerUri = Uri.parse("content://" + EMPTY_READ_AUTHORITY + "/capture.markers.json");

        assertFalse(CaptureMarkerWriter.appendMarker(context, markerUri, 1234L, CaptureMarkerWriter.LABEL_OPEN));
        assertFalse(provider.wasOutputOpened());
    }

    public static class FailingReadProvider extends ContentProvider {
        private boolean mOutputOpened;

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (mode.contains("r") && !mode.contains("w"))
                throw new FileNotFoundException("read failed");

            mOutputOpened = true;
            throw new FileNotFoundException("write should not be attempted");
        }

        public boolean wasOutputOpened() {
            return mOutputOpened;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }
    }

    public static class EmptyReadProvider extends ContentProvider {
        private final File mFile;
        private boolean mOutputOpened;

        public EmptyReadProvider(File file) {
            mFile = file;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (mode.contains("w")) {
                mOutputOpened = true;
                throw new FileNotFoundException("write should not be attempted");
            }

            return ParcelFileDescriptor.open(mFile, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        public boolean wasOutputOpened() {
            return mOutputOpened;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }
    }

    public static class NoRelativePathProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(new String[]{"not_relative_path"});
            cursor.addRow(new Object[]{"Downloads/PCAPdroid/"});
            return cursor;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }
    }

    public static class RelativePathProvider extends ContentProvider {
        private boolean mQueried;

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            mQueried = true;
            MatrixCursor cursor = new MatrixCursor(new String[]{MediaStore.MediaColumns.RELATIVE_PATH});
            cursor.addRow(new Object[]{"Downloads/PCAPdroid/"});
            return cursor;
        }

        public boolean wasQueried() {
            return mQueried;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }
    }

    public static class DataPathProvider extends ContentProvider {
        private final String mDataPath;

        public DataPathProvider(String dataPath) {
            mDataPath = dataPath;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(new String[]{MediaStore.MediaColumns.DATA});
            cursor.addRow(new Object[]{mDataPath});
            return cursor;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }
    }
}
