package studio.tingye.reader;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** A single, temporary output URI granted to the system camera. */
public final class CameraFile extends android.content.ContentProvider {
  static File file(Context c) {
    return new File(c.getCacheDir(), "word-camera.jpg");
  }

  static Uri uri(Context c) {
    return Uri.parse("content://" + c.getPackageName() + ".wordphoto/capture");
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    if (!uri(getContext()).equals(u)) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.parseMode(mode));
  }

  @Override
  public String getType(Uri u) {
    return "image/jpeg";
  }

  @Override
  public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
    return null;
  }

  @Override
  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
