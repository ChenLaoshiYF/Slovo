package studio.tingye.reader;

import java.util.Locale;

/** A rough Mandarin sound cue; Russian audio and stress remain the pronunciation reference. */
final class SoundHint {
  private static final String LETTERS="абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
  private static final String[] HINTS={"阿","布","夫","格","德","耶","哟","日","兹","伊","伊","克","勒","姆","恩","奥","普","尔","斯","特","乌","弗","赫","茨","奇","什","希","","厄","","诶","尤","雅"};
  static String forWord(String word) {
    StringBuilder out=new StringBuilder();
    String text=word.toLowerCase(Locale.ROOT).replace("́","");
    // Combine common consonant-vowel syllables before handling consonant clusters.
    String vowels="аоэуиы";
    String consonants="бвгдклмнпрстфх";
    String[] syllables={"巴波贝布比贝","瓦沃韦乌维韦","嘎戈格古吉格","达多德杜迪德",
        "卡科克库基克","拉洛勒卢利勒","马莫梅穆米梅","纳诺内努尼内","帕波佩普皮佩",
        "拉罗雷鲁里雷","萨索瑟苏西瑟","塔托特图提特","法佛费夫菲费","哈霍赫胡希赫"};
    for(int n=0;n<text.length();n++) {
      char ch=text.charAt(n);
      int onset=consonants.indexOf(ch);
      int vowel=n+1<text.length()?vowels.indexOf(text.charAt(n+1)):-1;
      if(onset>=0 && vowel>=0){out.append(syllables[onset].charAt(vowel));n++;continue;}
      int i=LETTERS.indexOf(ch);
      if(i>=0)out.append(HINTS[i]);
      else if(ch==' ' || ch=='-')out.append('·');
    }
    return out.toString();
  }
}
