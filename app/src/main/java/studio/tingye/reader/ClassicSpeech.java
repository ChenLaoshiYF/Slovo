package studio.tingye.reader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Small bundled auxiliary voice for Chinese and English OCR text. */
final class ClassicSpeech {
  static { System.loadLibrary("slovo_pronunciation"); }
  private static native short[] generate(byte[] folder,byte[] text,byte[] language);
  static float[] synthesize(File folder,String text,String language) throws IOException {
    short[] pcm=generate(folder.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
        text.getBytes(StandardCharsets.UTF_8),language.getBytes(StandardCharsets.UTF_8));
    if(pcm==null || pcm.length==0)throw new IOException("Auxiliary voice failed");
    float[] result=new float[pcm.length];
    for(int i=0;i<pcm.length;i++)result[i]=pcm[i]/32768f;
    return result;
  }
}
