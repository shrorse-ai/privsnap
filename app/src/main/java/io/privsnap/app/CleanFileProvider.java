package io.privsnap.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class CleanFileProvider extends ContentProvider {

    public static File getShareDir(Context context) {
        File dir = new File(context.getCacheDir(), "clean_share");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static Uri getUriForFile(Context context, File file) {
        String authority = context.getPackageName() + ".provider";
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath("clean")
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveFile(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null || uri == null) {
            throw new FileNotFoundException("Invalid URI");
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException("Invalid file name");
        }
        File target = new File(getShareDir(context), name);
        if (!target.exists() || !target.isFile()) {
            throw new FileNotFoundException("File not found: " + name);
        }
        return target;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolveFile(uri);
            String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = file.length();
                } else {
                    row[i] = null;
                }
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = uri != null ? uri.getLastPathSegment() : "";
        if (name != null && name.toLowerCase().endsWith(".png")) {
            return "image/png";
        }
        return "image/jpeg";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolveFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
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
