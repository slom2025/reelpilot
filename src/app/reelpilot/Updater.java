package app.reelpilot;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Обновление из папки release/ в GitHub: читаем version.json, сравниваем номер версии,
 * скачиваем APK и отдаём системе на установку (она спросит «Обновить?»).
 */
public class Updater {
    public static final String REPO = "slom2025/reelpilot";
    static final String BASE = "https://raw.githubusercontent.com/" + REPO + "/main/release/";
    public static final String ACTION_INSTALL_STATUS = "app.reelpilot.INSTALL_STATUS";

    public interface Ui { void status(String s); }

    final Activity act;
    final Ui ui;
    final Handler h = new Handler(Looper.getMainLooper());

    public Updater(Activity a, Ui ui) { this.act = a; this.ui = ui; }

    void say(final String s) { h.post(new Runnable() { public void run() { ui.status(s); } }); }

    int myVersion() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** quiet = проверка при запуске: молчим, если обновлений нет или нет интернета. */
    public void check(final boolean quiet, final boolean install) {
        if (!quiet) say("Проверяю обновления…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject rel = new JSONObject(new String(get(BASE + "version.json?t=" + System.currentTimeMillis()), "UTF-8"));
                    int remote = rel.getInt("versionCode");
                    int mine = myVersion();
                    if (remote <= mine) { if (!quiet) say("У тебя последняя версия (" + rel.optString("versionName", "" + remote) + ")"); return; }
                    String apkUrl = BASE + rel.optString("apk", "ReelPilot.apk");
                    String notes = rel.optString("notes", "").trim();
                    if (!install) {
                        say("Есть новая версия " + rel.optString("versionName", "" + remote) + "! Нажми «Проверить обновления», чтобы поставить."
                                + (notes.length() > 0 ? "\nЧто нового: " + notes : ""));
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
                        say("Разреши приложению ставить обновления: включи переключатель и вернись сюда, потом нажми кнопку ещё раз.");
                        h.post(new Runnable() { public void run() {
                            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + act.getPackageName())));
                        } });
                        return;
                    }
                    say("Скачиваю версию " + remote + "…");
                    byte[] apk = get(apkUrl);
                    say("Устанавливаю…");
                    install(apk);
                } catch (Throwable t) {
                    if (!quiet) say("Не получилось проверить: " + t.getMessage());
                }
            }
        }).start();
    }

    static byte[] get(String url) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "ReelPilot");
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                url = c.getHeaderField("Location");
                c.disconnect();
                continue;
            }
            if (code != 200) throw new Exception("сервер ответил " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            c.disconnect();
            return out.toByteArray();
        }
        throw new Exception("слишком много перенаправлений");
    }

    void install(byte[] apk) throws Exception {
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(act.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        OutputStream os = s.openWrite("update.apk", 0, apk.length);
        os.write(apk);
        s.fsync(os);
        os.close();
        Intent cb = new Intent(act, MainActivity.class).setAction(ACTION_INSTALL_STATUS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
        PendingIntent p = PendingIntent.getActivity(act, id, cb, flags);
        s.commit(p.getIntentSender());
        s.close();
    }

    /** Система вернула ответ установщика: если нужно подтверждение — показываем окно «Обновить?». */
    public static void onInstallStatus(Activity a, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) a.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(a, "Обновление не установилось: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                    Toast.LENGTH_LONG).show();
        }
    }
}
