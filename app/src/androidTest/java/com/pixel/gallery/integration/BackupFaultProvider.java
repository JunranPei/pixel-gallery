package com.pixel.gallery.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Pure Java: the separately launched test-provider process has no target-app Kotlin runtime. */
public class BackupFaultProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{"stream.json", null});
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String operation = uri.getLastPathSegment();
        if ("denied".equals(operation)) throw new SecurityException("Injected provider denial");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            if ("write-failure".equals(operation)) {
                pipe[0].closeWithError("Injected provider write failure");
                return pipe[1];
            }
            if (!"r".equals(mode)) throw new FileNotFoundException("Read only provider");
            new Thread(new Runnable() {
                @Override public void run() {
                    ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                    try {
                        if ("read-failure".equals(operation)) {
                            stream.write("{\"format\":\"pixel-gallery-labels\"".getBytes(StandardCharsets.UTF_8));
                            stream.flush();
                            pipe[1].closeWithError("Injected provider read failure");
                        } else {
                            stream.write(("{\"format\":\"pixel-gallery-labels\",\"version\":1,"
                                + "\"exportedAt\":\"2026-10-20T00:00:00Z\",\"labels\":[],\"media\":[]}").getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (IOException expected) {
                        try { pipe[1].closeWithError(expected.toString()); } catch (IOException ignored) { }
                    } finally { try { stream.close(); } catch (IOException ignored) { } }
                }
            }).start();
            return pipe[0];
        } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
    }
}
