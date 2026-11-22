package studio.tingye.reader;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.*;

final class PhotoWords {
  private static final String[] LANGUAGES = {"rus", "eng", "chi_sim"};
  static Bitmap bitmap(Context c, Uri u, int cap) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    try (InputStream in = c.getContentResolver().openInputStream(u)) {
      BitmapFactory.decodeStream(in, null, options);
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("无法读取图片");
    options.inSampleSize = 1;
    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > cap * 1.35)
      options.inSampleSize *= 2;
    options.inJustDecodeBounds = false;
    Bitmap b;
    try (InputStream in = c.getContentResolver().openInputStream(u)) {
      b = BitmapFactory.decodeStream(in, null, options);
    }
    if (b == null) throw new IOException("无法解码图片");
    int orientation = 1;
    try (InputStream in = c.getContentResolver().openInputStream(u)) {
      orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
    } catch (IOException ignored) {
    }
    Matrix m = new Matrix();
    switch (orientation) {
      case 2:
        m.setScale(-1, 1);
        break;
      case 3:
        m.setRotate(180);
        break;
      case 4:
        m.setScale(1, -1);
        break;
      case 5:
        m.setRotate(90);
        m.postScale(-1, 1);
        break;
      case 6:
        m.setRotate(90);
        break;
      case 7:
        m.setRotate(270);
        m.postScale(-1, 1);
        break;
      case 8:
        m.setRotate(270);
        break;
      default:
        return b;
    }
    Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    if (rotated != b) b.recycle();
    return rotated;
  }

  private static synchronized File models(Context c) throws IOException {
    File folder = new File(c.getFilesDir(), "photo-ocr/tessdata");
    folder.mkdirs();
    for (String language : LANGUAGES) {
      File model = new File(folder, language + ".traineddata");
      if (model.isFile() && model.length() > 1000000) continue;
      File tmp = new File(folder, language + ".part");
      try (InputStream in = c.getAssets().open("ocr/" + model.getName());
          OutputStream o = new FileOutputStream(tmp)) {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
      }
      if (!tmp.renameTo(model)) throw new IOException("识别模型未就绪");
    }
    return folder;
  }

  static PhotoText scan(Context c, Uri u) throws Exception {
    File folder = models(c);
    Bitmap b = bitmap(c, u, 2200);
    TessBaseAPI api = new TessBaseAPI();
    try {
      if (!api.init(folder.getParent(), "rus+eng+chi_sim")) throw new IOException("文字识别初始化失败");
      api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
      api.setImage(b);
      PhotoText result = new PhotoText(api.getUTF8Text());
      if (result.text.isEmpty()) {
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT);
        api.setImage(b);
        result = new PhotoText(api.getUTF8Text());
      }
      return result;
    } finally {
      api.recycle();
      b.recycle();
    }
  }

}
