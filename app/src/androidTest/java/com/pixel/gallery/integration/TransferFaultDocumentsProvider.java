package com.pixel.gallery.integration;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract;
import android.net.Uri;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Test APK only. Operates exclusively in a configured UUID-owned TransferEdge directory. */
public class TransferFaultDocumentsProvider extends ContentProvider {
    private File root;
    private String fault = "none";
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"configure".equals(method)) {
            // DocumentsContract's public client protocol, with no system picker root registration.
            try {
                Uri uri=extras.getParcelable("uri");
                Bundle result=new Bundle();
                String id=DocumentsContract.getDocumentId(uri);
                if("android:createDocument".equals(method)) {
                    String created=createDocument(id,extras.getString("mime_type"),extras.getString("_display_name"));
                    result.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,created));return result;
                }
                if("android:renameDocument".equals(method)) {
                    String renamed=renameDocument(id,extras.getString("_display_name"));
                    result.putParcelable("uri",DocumentsContract.buildDocumentUriUsingTree(uri,renamed));return result;
                }
                if("android:deleteDocument".equals(method)) { deleteDocument(id);return result; }
                throw new UnsupportedOperationException(method);
            } catch(FileNotFoundException e) { throw new IllegalStateException(e); }
        }
        try {
            File candidate = new File(extras.getString("root")).getCanonicalFile();
            if (!candidate.getPath().matches("/storage/emulated/0/Pictures/TransferEdge_[0-9a-f-]{36}/[^/]+")) throw new SecurityException("Unowned test root");
            root=candidate;fault=extras.getString("fault","none");
            return Bundle.EMPTY;
        } catch(IOException e) { throw new IllegalArgumentException(e); }
    }
    private File file(String id) throws FileNotFoundException {
        try {
            if(root==null) throw new FileNotFoundException("Not configured");
            File result="root".equals(id)?root:new File(root,id).getCanonicalFile();
            if(!result.equals(root) && !result.getPath().startsWith(root.getPath()+"/")) throw new SecurityException("Escaped test root");
            return result;
        } catch(IOException e) { throw new FileNotFoundException(e.toString()); }
    }
    private static final String[] COLS={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED,Document.COLUMN_FLAGS};
    private void add(MatrixCursor cursor,File f) {
        MatrixCursor.RowBuilder row=cursor.newRow();
        for(String column:cursor.getColumnNames()) {
            switch(column) {
                case Document.COLUMN_DOCUMENT_ID: row.add(f.equals(root)?"root":f.getName());break;
                case Document.COLUMN_DISPLAY_NAME: row.add(f.getName());break;
                case Document.COLUMN_MIME_TYPE: row.add(f.isDirectory()?Document.MIME_TYPE_DIR:"image/png");break;
                case Document.COLUMN_SIZE: row.add("size".equals(fault)?null:f.length());break;
                case Document.COLUMN_LAST_MODIFIED: row.add(f.lastModified());break;
                case Document.COLUMN_FLAGS: row.add(Document.FLAG_SUPPORTS_WRITE|Document.FLAG_SUPPORTS_DELETE|Document.FLAG_SUPPORTS_RENAME|Document.FLAG_DIR_SUPPORTS_CREATE);break;
                default:row.add(null);
            }
        }
    }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String s,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String s,String[] args) { throw new UnsupportedOperationException(); }
    @Override public Cursor query(Uri uri,String[] projection,String s,String[] args,String order) {
        try { return uri.getLastPathSegment().equals("children") ? queryChildDocuments(DocumentsContract.getDocumentId(uri),projection,order):queryDocument(DocumentsContract.getDocumentId(uri),projection); }
        catch(FileNotFoundException e) { throw new IllegalArgumentException(e); }
    }
    public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        MatrixCursor c=new MatrixCursor(projection==null?COLS:projection);File f=file(id);if(f.exists())add(c,f);return c;
    }
    public Cursor queryChildDocuments(String id,String[] projection,String order) throws FileNotFoundException {
        MatrixCursor c=new MatrixCursor(projection==null?COLS:projection);File[] children=file(id).listFiles();
        if(children!=null)for(File f:children)add(c,f);return c;
    }
    public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        if("permission".equals(fault))throw new SecurityException("Injected permission denial");
        if(!"root".equals(parent) || name.contains("/")) throw new FileNotFoundException("Invalid test name");
        File f=file(name);
        try { if(!f.createNewFile())throw new IOException("Exists");return name; }
        catch(IOException e) { throw new FileNotFoundException(e.toString()); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        String id=DocumentsContract.getDocumentId(uri);
        File f=file(id);
        if(mode.contains("w") && "slow".equals(fault)) {
            try { Thread.sleep(800); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if(mode.contains("w") && "write".equals(fault))throw new FileNotFoundException("Injected write failure");
        if(!mode.contains("w") && "corrupt".equals(fault) && f.length()>0) {
            try(RandomAccessFile out=new RandomAccessFile(f,"rw")) {int b=out.read();out.seek(0);out.write(b^1);}
            catch(IOException e) { throw new FileNotFoundException(e.toString()); }
        }
        return ParcelFileDescriptor.open(f,ParcelFileDescriptor.parseMode(mode));
    }
    public String renameDocument(String id,String name) throws FileNotFoundException {
        if("rename".equals(fault))throw new FileNotFoundException("Injected commit failure");
        File from=file(id),to=file(name);if(to.exists() || !from.renameTo(to))throw new FileNotFoundException("Rename failed");return name;
    }
    public void deleteDocument(String id) throws FileNotFoundException {
        File f=file(id);if(f.equals(root))throw new SecurityException("Cannot delete root");if(f.exists() && !f.delete())throw new FileNotFoundException("Delete failed");
    }
}
