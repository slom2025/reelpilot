package app.reelpilot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Окно настроек. Вся разметка собрана прямо в коде. */
public class MainActivity extends Activity {

    Prefs p;
    LinearLayout box;
    TextView status, log;
    CheckBox[] actOn = new CheckBox[Prefs.ACTIONS.length];
    EditText[] actMin = new EditText[Prefs.ACTIONS.length];
    EditText[] actMax = new EditText[Prefs.ACTIONS.length];
    CheckBox self;
    EditText fbMin, fbMax, maxWatch, comments;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, pad * 3);
        sv.addView(box);
        setContentView(sv);

        title("Рилс Пилот");
        final TextView upd = text("");
        final Updater up = new Updater(this, new Updater.Ui() {
            public void status(String m) { upd.setText(m); }
        });
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        upd.setText("Версия " + ver);
        button("Проверить обновления", new View.OnClickListener() {
            public void onClick(View v) { up.check(false, true); }
        });
        up.check(true, false);
        handleIntent(getIntent());
        text("Досматривает рилс до конца и листает дальше. Иногда ставит лайк, репост, читает и пишет комментарии.");
        TextView warn = text("Важно: автоматические лайки и комментарии противоречат правилам Instagram. Аккаунт могут ограничить — риск на тебе.");
        warn.setTextColor(0xFFB00020);

        status = text("");
        status.setTypeface(null, Typeface.BOLD);
        button("1. Включить в «Спец. возможностях»", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        });
        button("2. Открыть Instagram", new View.OnClickListener() {
            public void onClick(View v) {
                save();
                Intent i = getPackageManager().getLaunchIntentForPackage(ReelService.IG);
                if (i != null) startActivity(i);
                else Toast.makeText(MainActivity.this, "Instagram не установлен", Toast.LENGTH_SHORT).show();
            }
        });
        text("3. Зайди в Reels и нажми ▶ на плавающей кнопке. Её можно перетаскивать пальцем.");

        header("Действия (случайный промежуток, в секундах)");
        for (int i = 0; i < Prefs.ACTIONS.length; i++) {
            String a = Prefs.ACTIONS[i];
            actOn[i] = new CheckBox(this);
            actOn[i].setText(Prefs.ACTION_TITLES[i]);
            actOn[i].setChecked(p.on(a));
            box.addView(actOn[i]);
            LinearLayout row = row();
            row.addView(small("  раз в  от "));
            actMin[i] = num(p.min(a), row);
            row.addView(small(" до "));
            actMax[i] = num(p.max(a), row);
            row.addView(small(" сек"));
        }

        header("Тексты комментариев (каждый с новой строки)");
        comments = new EditText(this);
        comments.setHint("Например:\nСупер!\nХаха, жиза\nКрасиво 🔥");
        comments.setText(p.commentsRaw());
        comments.setMinLines(4);
        comments.setGravity(Gravity.TOP);
        comments.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.addView(comments);

        header("Досмотр");
        self = new CheckBox(this);
        self.setText("Листать самому (сними галочку, если включил «Автопрокрутку» в самом Instagram)");
        self.setChecked(p.selfScroll());
        box.addView(self);
        LinearLayout r1 = row();
        r1.addView(small("Если конец не виден: от "));
        fbMin = num(p.fbMin(), r1);
        r1.addView(small(" до "));
        fbMax = num(p.fbMax(), r1);
        r1.addView(small(" сек"));
        LinearLayout r2 = row();
        r2.addView(small("Максимум на один рилс: "));
        maxWatch = num(p.maxWatch(), r2);
        r2.addView(small(" сек"));

        button("Сохранить", new View.OnClickListener() {
            public void onClick(View v) {
                save();
                Toast.makeText(MainActivity.this, "Сохранено. Нажми Стоп и Старт, чтобы применить.", Toast.LENGTH_SHORT).show();
            }
        });

        header("Журнал");
        button("Скопировать отчёт для Claude", new View.OnClickListener() {
            public void onClick(View v) {
                String rep = "=== Журнал ===\n" + Journal.tail(120) + "\n=== Экран ===\n" + Journal.lastDump;
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("report", rep));
                Toast.makeText(MainActivity.this, "Скопировано, вставь в чат", Toast.LENGTH_SHORT).show();
            }
        });
        text("Если что-то не нажимается: открой рилс, подержи палец на плавающей кнопке 1 секунду (запишется экран), потом нажми кнопку выше.");
        log = text("");
        log.setTextSize(12);
        log.setTypeface(Typeface.MONOSPACE);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    void handleIntent(Intent i) {
        if (i != null && Updater.ACTION_INSTALL_STATUS.equals(i.getAction())) Updater.onInstallStatus(this, i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = ReelService.instance != null;
        status.setText(on ? "✅ Служба включена" : "❌ Служба выключена — нажми кнопку 1");
        status.setTextColor(on ? 0xFF1B7F3B : 0xFFB00020);
        log.setText(Journal.tail(40));
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    void save() {
        for (int i = 0; i < Prefs.ACTIONS.length; i++)
            p.setAction(Prefs.ACTIONS[i], actOn[i].isChecked(), val(actMin[i]), val(actMax[i]));
        p.setWatch(self.isChecked(), val(fbMin), val(fbMax), val(maxWatch));
        p.setComments(comments.getText().toString());
    }

    // ---- маленькие помощники для разметки

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    int val(EditText e) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return 0; }
    }

    TextView title(String s) {
        TextView t = text(s);
        t.setTextSize(24);
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    TextView header(String s) {
        TextView t = text(s);
        t.setTextSize(17);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        return t;
    }

    TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(6), 0, dp(6));
        box.addView(t);
        return t;
    }

    TextView small(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        return t;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(r);
        return r;
    }

    EditText num(int v, LinearLayout row) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(v));
        e.setMinWidth(dp(64));
        e.setGravity(Gravity.CENTER);
        row.addView(e);
        return e;
    }

    Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        box.addView(btn);
        return btn;
    }
}
