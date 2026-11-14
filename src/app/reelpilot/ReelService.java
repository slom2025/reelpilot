package app.reelpilot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Главная часть: служба специальных возможностей.
 * Видит экран Instagram, делает свайпы и нажатия, показывает плавающую кнопку Старт/Стоп.
 */
public class ReelService extends AccessibilityService {

    static final String IG = "com.instagram.android";
    static volatile ReelService instance;

    // Пороги сравнения кадров (средняя разница яркости 0..255)
    static final float MATCH = 9f;      // кадр «такой же, как в начале ролика»
    static final float DIVERGE = 20f;   // кадр «точно уже другой»
    static final int START_FRAMES = 6;  // сколько кадров начала ролика запоминаем

    final Handler h = new Handler(Looper.getMainLooper());
    final Random rnd = new Random();
    Prefs p;

    volatile boolean running = false;
    volatile boolean busy = false;      // идёт действие (лайк/комменты и т.п.)
    boolean swiping = false;

    // Плавающая кнопка
    WindowManager wm;
    TextView btn;
    WindowManager.LayoutParams lp;

    // Состояние текущего рилса
    int reelNo = 0;
    long reelStart;
    long fallbackMs;
    final List<int[]> startSigs = new ArrayList<int[]>();
    boolean diverged;
    int matchStreak;
    float lastDist = -1;
    float prevProgress = -1;
    boolean progressSeen;

    // Скриншоты для определения конца ролика
    boolean shotsOk = Build.VERSION.SDK_INT >= 30;
    boolean shotPending;
    long lastShot;
    int shotFails;

    // Таймеры действий
    final long[] nextAt = new long[Prefs.ACTIONS.length];
    long lastActionEnd;
    int lastCommentIdx = -1;
    long pausedAt;
    boolean lastReels = true;

    /**
     * Корень окна Instagram, если он сейчас верхнее приложение.
     * Системные окошки и предупреждения поверх (не приложения) не считаются, чтобы не сбивать таймер.
     */
    AccessibilityNodeInfo igRoot() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                if (getPackageName().contentEquals(r.getPackageName())) continue;
                return IG.contentEquals(r.getPackageName()) ? r : null; // первое сверху окно приложения
            }
        } catch (Throwable ignored) { }
        AccessibilityNodeInfo r = getRootInActiveWindow();
        return r != null && r.getPackageName() != null && IG.contentEquals(r.getPackageName()) ? r : null;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        p = new Prefs(this);
        showButton();
        Journal.add("Служба включена. Открой Reels и нажми ▶ на плавающей кнопке.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacksAndMessages(null);
        try { if (btn != null) wm.removeView(btn); } catch (Throwable ignored) { }
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Плавающая кнопка

    void showButton() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        btn = new TextView(this);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15);
        btn.setPadding(32, 20, 32, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(60);
        bg.setColor(0xCC222222);
        btn.setBackground(bg);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 24;
        lp.y = 140;
        btn.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved; boolean longDone;
            final Runnable longPress = new Runnable() {
                public void run() { longDone = true; dumpScreen(); }
            };
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = ev.getRawX(); sy = ev.getRawY(); ox = lp.x; oy = lp.y;
                        moved = false; longDone = false;
                        h.postDelayed(longPress, 900);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = ev.getRawX() - sx, dy = ev.getRawY() - sy;
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) { moved = true; h.removeCallbacks(longPress); }
                        if (moved) { lp.x = ox + (int) dx; lp.y = oy + (int) dy; wm.updateViewLayout(btn, lp); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        h.removeCallbacks(longPress);
                        if (!moved && !longDone) toggle();
                        return true;
                }
                return false;
            }
        });
        wm.addView(btn, lp);
        updateButton("▶ Старт");
    }

    void updateButton(String text) {
        if (btn == null) return;
        btn.setText(text);
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (running) flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON; // экран не гаснет, пока работаем
        if (lp.flags != flags) { lp.flags = flags; try { wm.updateViewLayout(btn, lp); } catch (Throwable ignored) { } }
    }

    void toggle() {
        if (running) stop("Остановлено кнопкой");
        else start();
    }

    void start() {
        p = new Prefs(this);
        running = true;
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < Prefs.ACTIONS.length; i++) nextAt[i] = now + randSec(p.min(Prefs.ACTIONS[i]), p.max(Prefs.ACTIONS[i]));
        lastActionEnd = now;
        shotFails = 0;
        shotsOk = Build.VERSION.SDK_INT >= 30;
        pausedAt = 0;
        resetReel();
        Journal.add("Старт" + (p.selfScroll() ? "" : " (листает сам Instagram, я только делаю действия)"));
        h.removeCallbacks(tick);
        h.post(tick);
    }

    void stop(String why) {
        running = false;
        h.removeCallbacks(tick);
        updateButton("▶ Старт");
        Journal.add(why);
    }

    // ------------------------------------------------------------------ Главный цикл

    final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            h.postDelayed(this, 500);
            try { step(); } catch (Throwable t) { Journal.add("Ошибка: " + t); }
        }
    };

    void step() {
        long now = SystemClock.elapsedRealtime();
        if (busy || swiping) return;

        AccessibilityNodeInfo root = igRoot();
        if (root == null) {
            // Instagram закрыт или поверх открыто другое приложение: ставим ролик на паузу, а не сбрасываем
            if (pausedAt == 0) pausedAt = now;
            updateButton("■ Стоп (жду Instagram)");
            return;
        }
        if (pausedAt != 0) {
            long away = now - pausedAt;
            pausedAt = 0;
            if (away > 60000) resetReel();
            else { reelStart += away; lastShot = now; }
        }
        boolean reels = find(root, new Match() {
            public boolean ok(AccessibilityNodeInfo x) {
                String id = x.getViewIdResourceName();
                return id != null && id.contains("clips");
            }
        }) != null;
        if (reels != lastReels) { lastReels = reels; Journal.add(reels ? "Вижу Reels" : "Не вижу Reels — открой вкладку Reels (значок с видео внизу)"); }

        long watched = now - reelStart;
        updateButton("■ Стоп  " + (watched / 60000) + ":" + String.format("%02d", (watched / 1000) % 60)
                + (reels ? "" : "\nоткрой Reels"));

        // 1) Действия по случайному таймеру
        if (watched > 3000 && now - lastActionEnd > 15000) {
            for (int i = Prefs.ACTIONS.length - 1; i >= 0; i--) {
                String a = Prefs.ACTIONS[i];
                if (p.on(a) && now >= nextAt[i]) {
                    nextAt[i] = now + randSec(p.min(a), p.max(a));
                    runAction(a);
                    return;
                }
            }
        }

        if (!p.selfScroll()) return;

        // 2) Конец ролика по полосе прогресса (если Instagram её показывает)
        if (checkProgress(root)) { Journal.add("Конец ролика (по полосе прогресса), " + watched / 1000 + " с"); next(); return; }

        // 3) Страховка: максимум времени на рилс
        if (watched > p.maxWatch() * 1000L) { Journal.add("Достигнут максимум " + p.maxWatch() + " с, листаю"); next(); return; }

        // 4) Конец ролика по повтору кадров
        if (shotsOk) {
            if (!diverged && watched > Math.max(25000, fallbackMs) && !progressSeen) {
                Journal.add("Картинка почти не меняется (статичный ролик), листаю");
                next();
                return;
            }
            if (watched > 1200 && !shotPending && now - lastShot >= 1000) takeShot();
        } else if (!progressSeen && watched > fallbackMs) {
            Journal.add("Конец не определить, смотрел " + watched / 1000 + " с по таймеру");
            next();
        }
    }

    void resetReel() {
        reelNo++;
        reelStart = SystemClock.elapsedRealtime();
        fallbackMs = randSec(p.fbMin(), p.fbMax());
        startSigs.clear();
        diverged = false;
        matchStreak = 0;
        prevProgress = -1;
        progressSeen = false;
    }

    long randSec(int min, int max) {
        if (max < min) max = min;
        return (min + rnd.nextInt(max - min + 1)) * 1000L;
    }

    /** Свайп вверх к следующему рилсу. */
    void next() {
        swiping = true;
        int[] wh = screen();
        Path path = new Path();
        path.moveTo(wh[0] * 0.5f, wh[1] * 0.72f);
        path.lineTo(wh[0] * 0.5f, wh[1] * 0.25f);
        gesture(path, 0, 320);
        h.postDelayed(new Runnable() {
            public void run() { swiping = false; resetReel(); }
        }, 1300);
    }

    // ------------------------------------------------------------------ Полоса прогресса

    /** true, если нашли полосу прогресса и ролик дошёл до конца (или начался заново). */
    boolean checkProgress(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo n = find(root, new Match() {
            public boolean ok(AccessibilityNodeInfo x) {
                AccessibilityNodeInfo.RangeInfo r = x.getRangeInfo();
                return r != null && r.getMax() > r.getMin();
            }
        });
        if (n == null) return false;
        AccessibilityNodeInfo.RangeInfo r = n.getRangeInfo();
        float v = (r.getCurrent() - r.getMin()) / (r.getMax() - r.getMin());
        if (!progressSeen) { progressSeen = true; Journal.add("Нашёл полосу прогресса ролика"); }
        boolean ended = v >= 0.985f || (prevProgress > 0.8f && v < 0.2f);
        prevProgress = v;
        return ended;
    }

    // ------------------------------------------------------------------ Сравнение кадров

    void takeShot() {
        shotPending = true;
        lastShot = SystemClock.elapsedRealtime();
        final int myReel = reelNo;
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                public void onSuccess(ScreenshotResult res) {
                    shotPending = false;
                    shotFails = 0;
                    try {
                        HardwareBuffer hb = res.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                        hw.recycle();
                        hb.close();
                        int[] sig = signature(sw);
                        sw.recycle();
                        if (running && !busy && !swiping && myReel == reelNo) onFrame(sig);
                    } catch (Throwable t) {
                        Journal.add("Ошибка кадра: " + t);
                    }
                }
                public void onFailure(int code) {
                    shotPending = false;
                    if (++shotFails >= 6) {
                        shotsOk = false;
                        Journal.add("Снимки экрана недоступны (код " + code + "), перехожу на таймер " + p.fbMin() + "–" + p.fbMax() + " с");
                    }
                }
            });
        } catch (Throwable t) {
            shotPending = false;
            shotsOk = false;
            Journal.add("Снимки экрана не поддерживаются: " + t);
        }
    }

    /** Маленький «отпечаток» видео: 12×16 значений яркости из середины экрана (без кнопок и подписи). */
    static int[] signature(Bitmap b) {
        int w = b.getWidth(), hh = b.getHeight();
        int x0 = (int) (w * 0.08), y0 = (int) (hh * 0.16);
        int cw = (int) (w * 0.64), ch = (int) (hh * 0.48);
        Bitmap crop = Bitmap.createBitmap(b, x0, y0, cw, ch);
        Bitmap small = Bitmap.createScaledBitmap(crop, 12, 16, true);
        int[] px = new int[12 * 16];
        small.getPixels(px, 0, 12, 0, 0, 12, 16);
        int[] g = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10;
        }
        if (small != crop) small.recycle();
        crop.recycle();
        return g;
    }

    static float dist(int[] a, int[] b) {
        long s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / (float) a.length;
    }

    static float contrast(int[] a) {
        long sum = 0;
        for (int v : a) sum += v;
        float mean = sum / (float) a.length, d = 0;
        for (int v : a) d += Math.abs(v - mean);
        return d / a.length;
    }

    /**
     * Ролики в Reels крутятся по кругу. Запоминаем первые кадры ролика;
     * когда картинка сначала заметно поменялась, а потом снова совпала с началом — ролик закончился.
     */
    void onFrame(int[] sig) {
        long watched = SystemClock.elapsedRealtime() - reelStart;
        if (contrast(sig) < 6) return; // почти однотонный кадр (чёрный экран, загрузка) — не показателен
        if (startSigs.size() < START_FRAMES) { startSigs.add(sig); return; }
        float best = Float.MAX_VALUE;
        for (int[] s : startSigs) best = Math.min(best, dist(sig, s));
        lastDist = best;
        if (best > DIVERGE) diverged = true;
        if (diverged && watched > 7000 && best < MATCH) {
            matchStreak++;
            if (matchStreak >= 2 || best < MATCH / 2) {
                Journal.add("Конец ролика (пошёл повтор), " + watched / 1000 + " с, разница " + Math.round(best));
                next();
            }
        } else {
            matchStreak = 0;
        }
    }

    // ------------------------------------------------------------------ Действия

    void runAction(final String a) {
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (a.equals("like")) doLike();
                    else if (a.equals("repost")) doRepost();
                    else if (a.equals("readc")) doComments(false);
                    else if (a.equals("writec")) doComments(true);
                } catch (Throwable t) {
                    Journal.add("Ошибка действия: " + t);
                } finally {
                    lastActionEnd = SystemClock.elapsedRealtime();
                    busy = false;
                }
            }
        }).start();
    }

    /** Лайк двойным тапом по видео: так лайк только ставится, но никогда не снимается. */
    void doLike() {
        int[] wh = screen();
        float x = wh[0] * 0.45f, y = wh[1] * 0.42f;
        Path p1 = new Path(); p1.moveTo(x, y);
        Path p2 = new Path(); p2.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p1, 0, 40))
                .addStroke(new GestureDescription.StrokeDescription(p2, 140, 40))
                .build();
        dispatchGesture(g, null, null);
        sleep(800);
        Journal.add("❤ Лайк");
    }

    void doRepost() {
        AccessibilityNodeInfo b = findAll(new Match() {
            public boolean ok(AccessibilityNodeInfo x) {
                String s = label(x);
                return (s.contains("repost") || s.contains("репост"))
                        && !s.contains("remove") && !s.contains("undo") && !s.contains("отмен") && !s.contains("удал")
                        && !x.isSelected() && x.isVisibleToUser();
            }
        });
        if (b == null) { Journal.add("Репост: кнопка не найдена (нажми долго на плавающую кнопку на рилсе — запишу экран для настройки)"); return; }
        click(b);
        sleep(1500);
        // В некоторых версиях после нажатия появляется окошко подтверждения
        AccessibilityNodeInfo confirm = findAll(new Match() {
            public boolean ok(AccessibilityNodeInfo x) {
                String s = label(x);
                return x.isVisibleToUser() && (s.equals("repost") || s.equals("репостнуть") || s.equals("сделать репост"))
                        && String.valueOf(x.getClassName()).contains("Button");
            }
        });
        if (confirm != null) { click(confirm); sleep(1000); }
        Journal.add("🔁 Репост");
    }

    void doComments(boolean write) {
        AccessibilityNodeInfo b = findAll(new Match() {
            public boolean ok(AccessibilityNodeInfo x) {
                String s = label(x);
                return x.isVisibleToUser() && (s.equals("comment") || s.equals("комментарий") || s.equals("комментировать")
                        || s.equals("comments") || s.equals("комментарии"));
            }
        });
        if (b == null) { Journal.add("Комменты: кнопка не найдена"); return; }
        click(b);
        sleep(2200);
        if (findInput() == null) { Journal.add("Комменты: окно не открылось"); return; }

        // Листаем комментарии
        int scrolls = 2 + rnd.nextInt(3);
        for (int i = 0; i < scrolls && running; i++) {
            scrollComments();
            sleep(2500 + rnd.nextInt(2500));
        }

        if (write && running) {
            List<String> list = p.comments();
            if (list.isEmpty()) {
                Journal.add("Комментарий: список пуст — впиши тексты в приложении");
            } else {
                int idx = rnd.nextInt(list.size());
                if (list.size() > 1 && idx == lastCommentIdx) idx = (idx + 1) % list.size();
                lastCommentIdx = idx;
                postComment(list.get(idx));
            }
        }
        closeSheet();
        Journal.add(write ? "💬 Комменты: открыл, полистал, написал" : "💬 Комменты: открыл и полистал");
    }

    void postComment(String text) {
        AccessibilityNodeInfo in = findInput();
        if (in == null) { Journal.add("Комментарий: нет поля ввода"); return; }
        click(in);
        sleep(1200);
        in = findInput();
        if (in == null) { Journal.add("Комментарий: поле ввода пропало"); return; }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        in.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        sleep(1500);
        AccessibilityNodeInfo post = findAll(new Match() {
            public boolean ok(AccessibilityNodeInfo x) {
                String s = label(x);
                return x.isVisibleToUser() && (s.equals("post") || s.equals("опубликовать") || s.equals("отправить")
                        || s.equals("send") || s.equals("publish"));
            }
        });
        if (post == null) { Journal.add("Комментарий: кнопка «Опубликовать» не найдена, текст не отправлен"); return; }
        click(post);
        sleep(2000);
        Journal.add("Комментарий отправлен: «" + text + "»");
    }

    void scrollComments() {
        AccessibilityNodeInfo list = findAll(new Match() {
            public boolean ok(AccessibilityNodeInfo x) {
                String c = String.valueOf(x.getClassName());
                return x.isScrollable() && x.isVisibleToUser() && (c.contains("RecyclerView") || c.contains("ListView"));
            }
        });
        if (list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return;
        int[] wh = screen();
        Path path = new Path();
        path.moveTo(wh[0] * 0.5f, wh[1] * 0.80f);
        path.lineTo(wh[0] * 0.5f, wh[1] * 0.60f);
        gesture(path, 0, 400);
    }

    /** Закрываем окно комментариев кнопкой «Назад» (не больше 3 раз, чтобы не уйти из Reels). */
    void closeSheet() {
        for (int i = 0; i < 3; i++) {
            if (findInput() == null) return;
            performGlobalAction(GLOBAL_ACTION_BACK);
            sleep(1000);
        }
    }

    AccessibilityNodeInfo findInput() {
        return findAll(new Match() {
            public boolean ok(AccessibilityNodeInfo x) {
                return x.isVisibleToUser() && (x.isEditable() || String.valueOf(x.getClassName()).contains("EditText"));
            }
        });
    }

    // ------------------------------------------------------------------ Помощники

    interface Match { boolean ok(AccessibilityNodeInfo x); }

    static String label(AccessibilityNodeInfo x) {
        CharSequence d = x.getContentDescription();
        CharSequence t = x.getText();
        String s = d != null && d.length() > 0 ? d.toString() : (t != null ? t.toString() : "");
        return s.trim().toLowerCase();
    }

    static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Match m) {
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < 2500) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            if (n == null) continue;
            if (m.ok(n)) return n;
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }

    /** Ищем во всех окнах Instagram (окно комментариев может быть отдельным). */
    AccessibilityNodeInfo findAll(Match m) {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null || !IG.contentEquals(r.getPackageName())) continue;
                AccessibilityNodeInfo n = find(r, m);
                if (n != null) return n;
            }
        } catch (Throwable ignored) { }
        return find(getRootInActiveWindow(), m);
    }

    void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        while (c != null && !c.isClickable()) c = c.getParent();
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        // Не нажимается программно — тапаем по координатам
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        gesture(path, 0, 60);
    }

    void gesture(Path path, long start, long dur) {
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, start, dur)).build(), null, null);
    }

    int[] screen() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** Долгое нажатие на плавающую кнопку: записываем, что видно на экране, чтобы настроить поиск кнопок. */
    void dumpScreen() {
        StringBuilder b = new StringBuilder();
        b.append("Android ").append(Build.VERSION.RELEASE).append(", ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", снимки: ").append(shotsOk).append(", последняя разница кадров: ").append(lastDist).append('\n');
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                b.append("== окно ").append(r.getPackageName()).append('\n');
                dumpNode(r, 0, b);
            }
        } catch (Throwable t) { b.append(t); }
        String s = b.toString();
        if (s.length() > 60000) s = s.substring(0, 60000);
        Journal.lastDump = s;
        for (int i = 0; i < s.length(); i += 3000)
            android.util.Log.i("ReelPilotDump", s.substring(i, Math.min(s.length(), i + 3000)));
        Journal.add("Экран записан (" + s.length() + " символов). Открой приложение → «Скопировать отчёт».");
        Toast.makeText(this, "Экран записан", Toast.LENGTH_SHORT).show();
    }

    static void dumpNode(AccessibilityNodeInfo n, int depth, StringBuilder b) {
        if (n == null || depth > 40 || b.length() > 60000) return;
        for (int i = 0; i < depth; i++) b.append(' ');
        String cls = String.valueOf(n.getClassName());
        b.append(cls.substring(cls.lastIndexOf('.') + 1));
        if (n.getViewIdResourceName() != null) b.append(" id=").append(n.getViewIdResourceName().replace("com.instagram.android:id/", ""));
        if (n.getContentDescription() != null) b.append(" desc=\"").append(n.getContentDescription()).append('"');
        if (n.getText() != null) b.append(" text=\"").append(n.getText()).append('"');
        if (n.isClickable()) b.append(" [click]");
        if (n.isScrollable()) b.append(" [scroll]");
        if (n.isSelected()) b.append(" [selected]");
        if (n.getRangeInfo() != null) b.append(" range=").append(n.getRangeInfo().getCurrent()).append('/').append(n.getRangeInfo().getMax());
        b.append('\n');
        for (int i = 0; i < n.getChildCount(); i++) dumpNode(n.getChild(i), depth + 1, b);
    }
}
