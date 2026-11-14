package app.reelpilot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Журнал событий: что приложение сделало и что не получилось. Показывается в окне приложения. */
public class Journal {
    private static final List<String> lines = new ArrayList<String>();
    public static volatile String lastDump = "";
    private static final SimpleDateFormat F = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static synchronized void add(String s) {
        lines.add(F.format(new Date()) + "  " + s);
        while (lines.size() > 300) lines.remove(0);
        android.util.Log.i("ReelPilot", s);
    }

    public static synchronized String tail(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, lines.size() - n); i < lines.size(); i++) b.append(lines.get(i)).append('\n');
        return b.toString();
    }
}
