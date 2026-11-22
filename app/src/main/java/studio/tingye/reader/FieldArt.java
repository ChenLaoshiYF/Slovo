package studio.tingye.reader;

import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/** All photographs, including their Gaussian-filtered companions, are packaged for offline use. */
final class FieldArt extends Drawable {
  static final int[] LAND = {
    R.drawable.land_mountain,
    R.drawable.land_sands,
    R.drawable.land_lake,
    R.raw.land_park,
    R.drawable.land_river,
    R.drawable.land_caucasus
  };
  static final int[] MIST = {
    R.drawable.mist_mountain,
    R.drawable.mist_sands,
    R.drawable.mist_lake,
    R.raw.mist_park,
    R.drawable.mist_river,
    R.drawable.mist_caucasus
  };
  static final String[] PLACES = {"库兹涅茨克阿拉套", "恰拉沙地", "杜斯霍尔湖", "叶卡捷琳娜公园", "小伊斯特拉河", "普谢拜"};
  static final String[] RUSSIAN = {
    "Кузнецкий Алатау", "Чарские пески", "Дус-Холь", "Екатерининский парк", "Малая Истра", "Псебай"
  };
  static final String SCENE_PREF = "landscapePhoto";
  static final String[] SCENES = {
    "volcano_coast",
    "baikal_ice",
    "altai_autumn",
    "neva_evening",
    "karelia_shore",
    "kamchatka_ridge",
    "altai_mist",
    "sochi_hills",
    "neva_fortress",
    "moscow_river",
    "px_8784370",
    "px_14570079",
    "px_33797096",
    "px_7432586",
    "px_37207880",
    "px_9149214",
    "px_15419244",
    "px_12336336",
    "px_8761691",
    "px_11075496",
    "px_32079922",
    "px_11642601",
    "px_10007897",
    "px_37200453",
    "px_1207947",
    "px_32149769",
    "px_28853928",
    "px_8612083",
    "px_11735265",
    "px_10282740",
    "px_9740984",
    "px_11095294",
    "px_29215119",
    "px_10049779",
    "px_10057620",
    "px_35997994",
    "px_19758124",
    "px_12361273",
    "px_9199570",
    "px_8910285",
    "px_40500073",
    "px_7030179",
    "px_30537784",
    "px_8581427",
    "px_7038471",
    "px_8594335",
    "px_5656675",
    "px_11977672",
    "px_29849742",
    "px_7445218",
    "px_18704599",
  };
  static final String[] SCENE_LABELS = {
    "堪察加 · 科里亚克火山",
    "贝加尔湖 · 萨满岩",
    "阿尔泰 · 秋日河谷",
    "圣彼得堡 · 涅瓦河",
    "卡累利阿 · 湖畔森林",
    "堪察加 · 火山山脊",
    "阿尔泰 · 雾中河谷",
    "索契 · 山间村庄",
    "圣彼得堡 · 彼得保罗要塞",
    "莫斯科 · 日落河岸",
    "贝加尔湖 · 冰晶日落",
    "贝加尔湖 · 暮色冰面",
    "贝加尔湖 · 冰崖",
    "阿尔泰 · 雪后河谷",
    "阿尔泰 · 青绿河谷",
    "阿尔泰 · 峡谷阴云",
    "阿尔泰 · 岩坡与河",
    "阿尔泰 · 楚亚河口",
    "阿尔泰 · 科什阿加奇",
    "阿尔泰 · 雪山与蓝湖",
    "圣彼得堡 · 运河日落",
    "圣彼得堡 · 滴血救世主教堂",
    "圣彼得堡 · 运河暮色",
    "莫斯科 · 克里姆林宫夕照",
    "莫斯科 · 灯桥倒影",
    "莫斯科 · 河畔晴昼",
    "莫斯科 · 城际天际线",
    "莫斯科 · 暮空云层",
    "莫斯科 · 冬日白桦",
    "喀山 · 白色城墙",
    "捷里别尔卡 · 海岸断崖",
    "捷里别尔卡 · 北冰洋浪花",
    "捷里别尔卡 · 冬日出海",
    "捷里别尔卡 · 雪中旧船",
    "捷里别尔卡 · 小港雪景",
    "捷里别尔卡 · 日落雪原",
    "达吉斯坦 · 峡谷岩壁",
    "索契 · 远处雪峰",
    "索契 · 高加索山脊",
    "索契 · 山间冬景",
    "卡累利阿 · 湖上风帆",
    "卡累利阿 · 山岩俯瞰",
    "卡累利阿 · 冬日林岸",
    "卡累利阿 · 鲁斯凯阿拉",
    "卡累利阿 · 秋色倒影",
    "拉多加湖 · 岩屿树林",
    "克拉斯诺亚尔斯克 · 林间河湾",
    "克拉斯诺亚尔斯克 · 雾林岩柱",
    "乌斯季库特 · 雪落白桦",
    "西伯利亚 · 雪山冻河",
    "克拉斯诺亚尔斯克 · 叶尼塞河",
  };
  static final String[] SCENE_RUSSIAN = {
    "Корякская сопка",
    "Скала Шаманка",
    "Осенний Алтай",
    "Нева",
    "Карелия",
    "Камчатка",
    "Алтай",
    "Сочи",
    "Петропавловская крепость",
    "Москва-река",
    "Лёд Байкала",
    "Закат на Байкале",
    "Ледяные скалы Байкала",
    "Алтай зимой",
    "Долина Алтая",
    "Горы Алтая",
    "Река на Алтае",
    "Чуйозы",
    "Кош-Агач",
    "Горное озеро Алтая",
    "Канал на закате",
    "Спас на Крови",
    "Вечерний канал",
    "Кремль на закате",
    "Вечерняя Москва",
    "Кремлёвская набережная",
    "Москва-Сити",
    "Вечерние облака",
    "Берёзы зимой",
    "Казанский кремль",
    "Берег Териберки",
    "Волны Териберки",
    "Териберка зимой",
    "Старые суда",
    "Зимняя гавань",
    "Закат в Териберке",
    "Ущелье Дагестана",
    "Снежные вершины Сочи",
    "Хребет в Сочи",
    "Зима в Сочи",
    "Парус в Карелии",
    "Скалы Карелии",
    "Зимняя Карелия",
    "Рускеала",
    "Осень в Карелии",
    "Ладожское озеро",
    "Река под Красноярском",
    "Красноярские Столбы",
    "Усть-Кут",
    "Сибирь зимой",
    "Енисей",
  };
  static final int PHOTO = 0, LIGHT = 1, DARK = 2, NIGHT = 3;
  static final LruCache<Integer, Bitmap> images =
      new LruCache<Integer, Bitmap>(11 * 1024) {
        @Override
        protected int sizeOf(Integer k, Bitmap b) {
          return Math.max(1, b.getByteCount() / 1024);
        }
      };
  final Bitmap image;
  final int mode;
  final float radius, density;
  final RectF area = new RectF();
  final Path contour=new Path(), focusContour=new Path();
  final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  final Matrix matrix = new Matrix();
  final BitmapShader photograph;
  Shader shade, gleam;
  private boolean focused, selected, enabled = true;
  private int opacity = 255;
  private ColorFilter filter;

  FieldArt(Resources r, int place, int mode, float corner) {
    this(r, mode, corner, (mode == PHOTO || mode == NIGHT ? LAND : MIST)[place]);
  }

  static FieldArt scenePreview(Resources resources, int scene) {
    int resource = resources.getIdentifier("photo_" + SCENES[scene], "drawable", "studio.tingye.reader");
    return new FieldArt(resources, PHOTO, 15f, resource);
  }

  private FieldArt(Resources r, int mode, float corner, int resource) {
    this.mode = mode;
    density = r.getDisplayMetrics().density;
    radius = corner * density;
    image = bitmap(r, resource);
    photograph = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
  }

  static Bitmap bitmap(Resources r, int id) {
    Bitmap b = images.get(id);
    if (b == null) {
      BitmapFactory.Options size = new BitmapFactory.Options();
      size.inScaled = false;
      b = BitmapFactory.decodeResource(r, id, size);
      b.prepareToDraw();
      images.put(id, b);
    }
    return b;
  }

  @Override
  protected void onBoundsChange(Rect b) {
    area.set(b);
    if (b.isEmpty()) return;
    SoftCorners.trace(contour,area.left,area.top,area.right,area.bottom,radius);
    SoftCorners.trace(focusContour,area.left+density,area.top+density,area.right-density,
        area.bottom-density,Math.max(0,radius-density));
    float scale = Math.max(area.width() / image.getWidth(), area.height() / image.getHeight());
    matrix.setScale(scale, scale);
    matrix.postTranslate(
        area.centerX() - image.getWidth() * scale * .5f,
        area.centerY() - image.getHeight() * scale * .5f);
    photograph.setLocalMatrix(matrix);
    int[] colors;
    if (mode == LIGHT) colors = new int[] {0xf0ffffff, 0xcdedf5f1, 0xd4d8e8e4};
    else if (mode == DARK) colors = new int[] {0xe82a5d57, 0xeb244a45, 0xf11b3c37};
    else if (mode == NIGHT) colors = new int[] {0xd6152931, 0xb4113540, 0xfb152f32};
    else colors = new int[] {0x40102224, 0x26102121, 0xb0092427};
    shade = new LinearGradient(0, area.top, 0, area.bottom, colors, null, Shader.TileMode.CLAMP);
    gleam =
        new LinearGradient(
            area.left,
            area.top,
            area.right,
            area.bottom,
            new int[] {0xf4ffffff, 0x36ffffff, 0x1c325859, 0xbaffffff},
            null,
            Shader.TileMode.CLAMP);
  }

  @Override
  public void draw(Canvas c) {
    if (area.isEmpty()) return;
    brush.setColor(-1);
    brush.setAlpha(opacity);
    brush.setColorFilter(filter);
    brush.setShader(photograph);
    brush.setStyle(Paint.Style.FILL);
    c.drawPath(contour,brush);
    brush.setShader(shade);
    c.drawPath(contour,brush);
    if (mode == LIGHT || mode == DARK) {
      brush.setShader(gleam);
      brush.setStyle(Paint.Style.STROKE);
      brush.setStrokeWidth(1.35f * density);
      c.drawPath(contour,brush);
      brush.setStyle(Paint.Style.FILL);
      brush.setShader(null);
      if (focused || selected) {
        brush.setColor(mode == DARK ? 0xffffe2a6 : 0xff4a745d);
        brush.setStrokeWidth(density * 2);
        brush.setStyle(Paint.Style.STROKE);
        c.drawPath(focusContour,brush);
        brush.setStyle(Paint.Style.FILL);
      }
      if (!enabled) {
        brush.setColor(0x789da79f);
        c.drawPath(contour,brush);
      }
    }
    brush.setShader(null);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  @Override public boolean isStateful() { return mode == LIGHT || mode == DARK; }

  @Override protected boolean onStateChange(int[] state) {
    boolean focus = false, choice = false, active = false;
    for (int flag : state) {
      focus |= flag == android.R.attr.state_focused;
      choice |= flag == android.R.attr.state_selected || flag == android.R.attr.state_checked;
      active |= flag == android.R.attr.state_enabled;
    }
    if (focus == focused && choice == selected && active == enabled) return false;
    focused = focus; selected = choice; enabled = active; invalidateSelf(); return true;
  }

  @Override public void setAlpha(int a) { opacity = a; invalidateSelf(); }
  @Override public void setColorFilter(ColorFilter f) { filter = f; invalidateSelf(); }
}
