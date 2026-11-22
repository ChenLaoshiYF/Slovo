package studio.tingye.reader;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import org.json.JSONArray;

/** Shuffle from the full catalog while excluding a persistent window of recently seen photos. */
final class SceneRotation {
  private static final String RECENT = "recentScenery";
  private final Random random = new Random();

  int next(SharedPreferences prefs) {
    int window = Math.min(30,FieldArt.SCENES.length-1);
    ArrayList<String> recent = new ArrayList<>();
    HashSet<String> used = new HashSet<>();
    try {
      JSONArray stored = new JSONArray(prefs.getString(RECENT,"[]"));
      for (int i=0;i<stored.length() && recent.size()<window;i++) {
        String id=stored.optString(i);
        if (used.add(id)) recent.add(id);
      }
    } catch (org.json.JSONException ignored) {}
    ArrayList<Integer> fresh = new ArrayList<>(), differentPlaces = new ArrayList<>();
    String previous = recent.isEmpty()?"":region(recent.get(0));
    for (int i=0;i<FieldArt.SCENES.length;i++) {
      String id=FieldArt.SCENES[i];
      if (used.contains(id)) continue;
      fresh.add(i);
      if (!region(id).equals(previous)) differentPlaces.add(i);
    }
    ArrayList<Integer> choices=differentPlaces.isEmpty()?fresh:differentPlaces;
    int scene=choices.get(random.nextInt(choices.size()));
    recent.add(0,FieldArt.SCENES[scene]);
    if (recent.size()>window) recent.remove(recent.size()-1);
    prefs.edit().putString(RECENT,new JSONArray(recent).toString()).apply();
    return scene;
  }

  private static String region(String id) {
    for (int i=0;i<FieldArt.SCENES.length;i++) {
      if (id.equals(FieldArt.SCENES[i])) return FieldArt.SCENE_LABELS[i].split(" · ",2)[0];
    }
    return "";
  }
}
