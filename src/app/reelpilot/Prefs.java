package app.reelpilot;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** Все настройки приложения в одном месте. */
public class Prefs {
    public static final String[] ACTIONS = {"like", "repost", "readc", "writec"};
    public static final String[] ACTION_TITLES = {
            "Лайк", "Репост", "Открыть и полистать комментарии", "Написать комментарий из списка"};

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    public boolean on(String a) { return sp.getBoolean(a + "_on", !a.equals("writec")); }
    public int min(String a) { return sp.getInt(a + "_min", 180); }
    public int max(String a) { return sp.getInt(a + "_max", 360); }

    public void setAction(String a, boolean on, int min, int max) {
        if (min < 10) min = 10;
        if (max < min) max = min;
        sp.edit().putBoolean(a + "_on", on).putInt(a + "_min", min).putInt(a + "_max", max).apply();
    }

    /** Листать самим приложением (выключить, если включена автопрокрутка в самом Instagram). */
    public boolean selfScroll() { return sp.getBoolean("self_scroll", true); }
    /** Если конец ролика определить не удалось — сколько смотреть (сек). */
    public int fbMin() { return sp.getInt("fb_min", 20); }
    public int fbMax() { return sp.getInt("fb_max", 40); }
    /** Максимум времени на один рилс (сек). */
    public int maxWatch() { return sp.getInt("max_watch", 180); }

    public void setWatch(boolean self, int fbMin, int fbMax, int maxWatch) {
        if (fbMin < 3) fbMin = 3;
        if (fbMax < fbMin) fbMax = fbMin;
        if (maxWatch < 10) maxWatch = 10;
        sp.edit().putBoolean("self_scroll", self).putInt("fb_min", fbMin).putInt("fb_max", fbMax)
                .putInt("max_watch", maxWatch).apply();
    }

    public String commentsRaw() { return sp.getString("comments", ""); }
    public void setComments(String s) { sp.edit().putString("comments", s).apply(); }

    public List<String> comments() {
        List<String> out = new ArrayList<String>();
        for (String line : commentsRaw().split("\n")) {
            String t = line.trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }
}
