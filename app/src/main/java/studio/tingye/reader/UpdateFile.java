package studio.tingye.reader;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants the installer access to one verified APK, never to an arbitrary file. */
public final class UpdateFile extends ContentProvider {
  static File file(Context context) { return new File(context.getCacheDir(),"slovo-update.apk"); }
  static Uri uri(Context context) { return Uri.parse("content://"+context.getPackageName()+".updates/slovo-update.apk"); }
  private void check(Uri uri) throws FileNotFoundException {
    if(!uri.equals(uri(getContext())) || !file(getContext()).isFile())throw new FileNotFoundException();
  }
  @Override public boolean onCreate() { return true; }
  @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
    check(uri);if(!"r".equals(mode))throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(file(getContext()),ParcelFileDescriptor.MODE_READ_ONLY);
  }
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
    try { check(uri); } catch(FileNotFoundException missing) { return null; }
    String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
    MatrixCursor cursor=new MatrixCursor(columns); Object[] row=new Object[columns.length];
    for(int i=0;i<columns.length;i++) {
      if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))row[i]="Slovo.apk";
      else if(OpenableColumns.SIZE.equals(columns[i]))row[i]=file(getContext()).length();
    }
    cursor.addRow(row);return cursor;
  }
  @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
}
