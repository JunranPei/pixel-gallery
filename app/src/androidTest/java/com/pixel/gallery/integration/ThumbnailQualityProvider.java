package com.pixel.gallery.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Test APK process has no target-app libraries: use framework/Java APIs only. */
public class ThumbnailQualityProvider extends ContentProvider {
    private final AtomicInteger originalReads = new AtomicInteger();
    private final AtomicInteger bytesWritten = new AtomicInteger();
    private File directory;

    @Override public boolean onCreate() {
        directory = new File(getContext().getCacheDir(), "thumbnail-quality-fixtures");
        directory.mkdirs();
        try {
            for (String name : new String[]{"tiny", "adequate", "unknown", "rotated", "small", "missing", "mismatch", "odd", "slow", "native-large", "native-rotated", "native-pipe", "native-wide", "native-orientation-1", "native-orientation-2", "native-orientation-3", "native-orientation-4", "native-orientation-5", "native-orientation-6", "native-orientation-7", "native-orientation-8"}) {
                int width = name.startsWith("native-") ? 8193 : name.equals("small") ? 80 : name.equals("odd") ? 1601 : 1600;
                int height = name.startsWith("native-") ? 65 : name.equals("small") ? 60 : name.equals("odd") ? 1201 : 1200;
                Bitmap original = name.equals("native-wide") ? Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false,
                    android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)) : Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                original.eraseColor(Color.rgb(80, 170, 220));
                for (int x = 0; x < width; x += 40) for (int y = 0; y < height; y += 40) original.setPixel(x, y, Color.RED);
                if (name.startsWith("native-orientation-")) {
                    android.graphics.Canvas canvas = new android.graphics.Canvas(original);
                    android.graphics.Paint paint = new android.graphics.Paint();
                    paint.setColor(Color.rgb(225, 45, 75)); canvas.drawRect(0, 0, width / 2, height, paint);
                    paint.setColor(Color.rgb(35, 100, 220)); canvas.drawRect(width / 2, 0, width, height, paint);
                }
                File source = new File(directory, name + "-original.jpg");
                write(original, source, 94);
                if (name.equals("rotated") || name.equals("native-rotated") || name.startsWith("native-orientation-")) {
                    ExifInterface exif = new ExifInterface(source.getAbsolutePath());
                    int orientation = name.startsWith("native-orientation-") ? Integer.parseInt(name.substring(name.lastIndexOf('-') + 1)) : ExifInterface.ORIENTATION_ROTATE_90;
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
                    exif.saveAttributes();
                }
                int tw = 160, th = 120;
                if (name.equals("adequate")) { tw = 1024; th = 768; }
                if (name.equals("small")) { tw = 40; th = 30; }
                if (name.equals("rotated") || name.equals("mismatch")) { tw = 120; th = 160; }
                Bitmap thumb = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
                thumb.eraseColor(Color.BLUE);
                write(thumb, new File(directory, name + "-thumb.jpg"), 90);
            }
        } catch (IOException error) { throw new IllegalStateException(error); }
        return true;
    }

    private static void write(Bitmap bitmap, File file, int quality) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw new IOException("JPEG encode failed");
        } finally { bitmap.recycle(); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String name = uri.getLastPathSegment();
        String[] columns = projection != null ? projection : new String[]{"width", "height"};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case "width": row[i] = name.startsWith("native-") ? 8193 : name.equals("unknown") ? 0 : name.equals("small") ? 80 : name.equals("odd") ? 1601 : 1600; break;
                case "height": row[i] = name.startsWith("native-") ? 65 : name.equals("unknown") ? 0 : name.equals("small") ? 60 : name.equals("odd") ? 1201 : 1200; break;
                case "_display_name": row[i] = name + ".jpg"; break;
                case "mime_type": row[i] = "image/jpeg"; break;
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }

    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, Bundle opts, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        if (opts == null || !opts.containsKey(android.content.ContentResolver.EXTRA_SIZE)) {
            return new AssetFileDescriptor(openFile(uri, "r"), 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        }
        File file = new File(directory, uri.getLastPathSegment() + "-thumb.jpg");
        return new AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length());
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        originalReads.incrementAndGet();
        if (uri.getLastPathSegment().equals("missing")) throw new FileNotFoundException("Generated original intentionally unavailable");
        if (uri.getLastPathSegment().equals("slow") || uri.getLastPathSegment().equals("native-pipe")) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> {
                    try (java.io.FileInputStream input = new java.io.FileInputStream(new File(directory, uri.getLastPathSegment() + "-original.jpg"));
                         ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        byte[] chunk = new byte[512];
                        int count;
                        while ((count = input.read(chunk)) != -1) {
                            output.write(chunk, 0, count);
                            bytesWritten.addAndGet(count);
                            Thread.sleep(10);
                        }
                    } catch (IOException | InterruptedException expectedWhenCancelled) { }
                }, "thumbnail-fixture-writer").start();
                return pipe[0];
            } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        }
        return ParcelFileDescriptor.open(new File(directory, uri.getLastPathSegment() + "-original.jpg"), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("reset")) { originalReads.set(0); bytesWritten.set(0); }
        Bundle result = new Bundle();
        result.putInt("originalReads", originalReads.get());
        result.putInt("bytesWritten", bytesWritten.get());
        return result;
    }
    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
