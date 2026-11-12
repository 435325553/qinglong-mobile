package com.ql.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // 调色板：深色 / 浅色两版共用这一组字段，运行时切换后重绘界面
    int C_BG, C_CARD, C_FIELD, C_TEXT, C_SUB, C_ACC, C_WARN, C_RED, C_LINE;
    int C_PRESS, C_PILL, C_ACC_BG, C_ACC_LINE, C_ON_ACC, C_HINT, C_ERR_BG, C_META, C_LOG_BG, C_LOG_TEXT;

    /** 深色 / 浅色 */
    private void applyTheme(String theme) {
        boolean dark = !"light".equals(theme);
        if (dark) {
            C_BG = 0xFF0D1117; C_CARD = 0xFF161E26; C_FIELD = 0xFF1E2731;
            C_TEXT = 0xFFE6EDF3; C_SUB = 0xFF8B98A5; C_ACC = 0xFF2BD96A;
            C_WARN = 0xFFFFA657; C_RED = 0xFFF85149; C_LINE = 0xFF243040;
            C_PRESS = 0xFF202B37; C_PILL = 0xFF1E2731; C_ACC_BG = 0xFF12281A;
            C_ACC_LINE = 0x662BD96A; C_ON_ACC = 0xFF06210F; C_HINT = 0xFF5A6773;
            C_ERR_BG = 0xFF2A1717; C_META = 0xFF6B7885;
            C_LOG_BG = 0xFF10161C; C_LOG_TEXT = 0xFFC9D4E0;
        } else {
            C_BG = 0xFFF4F6F8; C_CARD = 0xFFFFFFFF; C_FIELD = 0xFFE9EDF1;
            C_TEXT = 0xFF11181F; C_SUB = 0xFF65707C; C_ACC = 0xFF0F9147;
            C_WARN = 0xFFB45309; C_RED = 0xFFC92C27; C_LINE = 0xFFDEE3E9;
            C_PRESS = 0xFFE6EBF0; C_PILL = 0xFFE8EDF2; C_ACC_BG = 0xFFE3F6EA;
            C_ACC_LINE = 0x550F9147; C_ON_ACC = 0xFFFFFFFF; C_HINT = 0xFF9AA4B0;
            C_ERR_BG = 0xFFFDECEB; C_META = 0xFF8892A0;
            C_LOG_BG = 0xFFF1F4F7; C_LOG_TEXT = 0xFF25313C;
        }
        themeName = dark ? "dark" : "light";
        applySystemBars(dark);
    }

    /** 对话框跟着当前主题走，浅色版用浅色对话框 */
    private AlertDialog.Builder dlg() {
        return "dark".equals(themeName)
                ? new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                : new AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    /** 状态栏 / 导航栏跟着主题走，浅色主题用深色图标 */
    private void applySystemBars(boolean dark) {
        try {
            getWindow().setStatusBarColor(C_BG);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(C_BG));
            getWindow().setNavigationBarColor(C_BG);
            View decor = getWindow().getDecorView();
            int f = decor.getSystemUiVisibility() & ~0x2000 & ~0x10; // LIGHT_STATUS_BAR / LIGHT_NAVIGATION_BAR
            if (!dark) f |= 0x2000 | 0x10;
            decor.setSystemUiVisibility(f);
        } catch (Exception e) {
            // 个别 ROM 不支持，忽略
        }
    }

    static class Cron {
        long id;
        String name = "";
        String command = "";
        String schedule = "";
        int isDisabled = 0;
        int status = 1;
        long lastRun = 0;   // 上次执行耗时（秒）
        long lastExec = 0;  // 上次执行时间（毫秒）
    }

    static class EnvItem {
        long id;
        String name = "";
        String value = "";
        String remarks = "";
        int status = 0;
    }

    /** 画一只眼睛：睁眼=内容可见，闭眼带斜杠=已隐藏 */
    static class EyeDrawable extends Drawable {
        private int color;
        private boolean open;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        EyeDrawable(int color, boolean open) {
            this.color = color;
            this.open = open;
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void setOpen(boolean o) {
            open = o;
            invalidateSelf();
        }

        boolean isOpen() {
            return open;
        }

        void setColor(int c) {
            color = c;
            invalidateSelf();
        }

        public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.centerX(), cy = b.centerY();
            float w = b.width(), h = b.height();
            float ex = w * 0.46f, ey = h * 0.24f;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.2f, h * 0.08f));
            path.reset();
            path.moveTo(cx - ex, cy);
            path.cubicTo(cx - ex * 0.45f, cy - ey * 1.9f, cx + ex * 0.45f, cy - ey * 1.9f, cx + ex, cy);
            path.cubicTo(cx + ex * 0.45f, cy + ey * 1.9f, cx - ex * 0.45f, cy + ey * 1.9f, cx - ex, cy);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, Math.max(1.5f, h * 0.14f), p);
            if (!open) {
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - ex * 0.9f, cy + ey * 1.15f, cx + ex * 0.9f, cy - ey * 1.15f, p);
            }
        }

        public void setAlpha(int a) { p.setAlpha(a); }

        public void setColorFilter(android.graphics.ColorFilter cf) { p.setColorFilter(cf); }

        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

        private int iw = 44, ih = 32;

        void setIntrinsic(int w, int h) {
            iw = w;
            ih = h;
        }

        public int getIntrinsicWidth() { return iw; }

        public int getIntrinsicHeight() { return ih; }
    }

    private SharedPreferences sp;
    private QlClient client;
    private String token;
    private boolean ignoreCert;
    private boolean remember;

    private int tab = 0;
    private final List<Cron> crons = new ArrayList<>();
    private final List<EnvItem> envs = new ArrayList<>();
    private JSONObject sys;
    private boolean loading = false;
    private String errMsg = null;
    private String searchCron = "";
    private String sortMode = "last";
    private String searchEnv = "";
    private boolean envShowValue = false;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout contentBox;
    private LinearLayout tabBar;
    private TextView subtitle;
    private TextView statePill;
    private ProgressBar busyBar;

    private String loginMode = "pwd";
    private String themeName = "dark";
    private boolean hideHost = false;
    private String fBase = "", fUser = "", fPass = "", fId = "", fSecret = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("ql", MODE_PRIVATE);
        themeName = sp.getString("theme", "dark");
        applyTheme(themeName);
        hideHost = sp.getBoolean("hideHost", false);
        ignoreCert = sp.getBoolean("ignoreCert", false);
        remember = sp.getBoolean("remember", true);
        loginMode = sp.getString("mode", "pwd");
        fBase = sp.getString("base", "");
        fUser = sp.getString("user", "");
        fPass = sp.getString("pwd", "");
        fId = sp.getString("openId", "");
        fSecret = sp.getString("openSecret", "");
        sortMode = sp.getString("cronSort", "last");
        String savedToken = sp.getString("token", "");
        client = new QlClient(fBase, ignoreCert);
        client.prefix = "open".equals(loginMode) ? "/open" : "/api";
        if (remember && savedToken.length() > 0) {
            client.token = savedToken;
            token = savedToken;
        }
        setContentView(buildRoot());
        if (token != null) {
            refreshData();
        } else if (remember && fBase.length() > 0 && hasCredential()) {
            autoLogin();
        } else {
            render();
        }
    }

    private boolean hasNetwork() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean onWifi() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected() && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLanHost(String url) {
        try {
            String h = QlClient.normalize(url);
            int s1 = h.indexOf("://");
            if (s1 > 0) h = h.substring(s1 + 3);
            int slash = h.indexOf('/');
            if (slash > 0) h = h.substring(0, slash);
            int colon = h.indexOf(':');
            if (colon > 0) h = h.substring(0, colon);
            if (h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("127.")) return true;
            if (h.startsWith("172.")) {
                String[] seg = h.split(".");
                int second = Integer.parseInt(seg[1]);
                return second >= 16 && second <= 31;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** 发请求前先判断网络，能提前说清原因 */
    private String preflight(String base) {
        if (!hasNetwork()) return "手机当前没有网络：WiFi 和移动数据都没连上";
        if (isLanHost(base) && !onWifi()) {
            return "面板是局域网地址（" + shortHost(QlClient.normalize(base))
                    + "），但手机现在没连 WiFi，连不上，请连和面板同一个 WiFi";
        }
        return null;
    }

    private boolean hasCredential() {
        if ("open".equals(loginMode)) return fId.length() > 0 && fSecret.length() > 0;
        return fUser.length() > 0 && fPass.length() > 0;
    }

    // ---------------- 历史服务器 ----------------

    private static final int HIST_MAX = 8;

    private JSONArray histArray() {
        try {
            return new JSONArray(sp.getString("servers", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private int histCount() {
        return histArray().length();
    }

    /** 登录成功后记一条：同一地址只留最新，最多 8 条 */
    private void pushHistory(String base, String mode, String account, String secret) {
        if (base == null || base.length() == 0) return;
        JSONArray old = histArray();
        JSONArray now = new JSONArray();
        try {
            now.put(new JSONObject()
                    .put("base", base)
                    .put("mode", mode)
                    .put("account", account == null ? "" : account)
                    .put("secret", remember && secret != null ? secret : "")
                    .put("ts", System.currentTimeMillis()));
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            if (base.equalsIgnoreCase(o.optString("base", ""))) continue;
            now.put(o);
            if (now.length() >= HIST_MAX) break;
        }
        sp.edit().putString("servers", now.toString()).apply();
    }

    private void removeHistory(String base) {
        JSONArray old = histArray();
        JSONArray now = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject o = old.optJSONObject(i);
            if (o == null) continue;
            if (base.equalsIgnoreCase(o.optString("base", ""))) continue;
            now.put(o);
        }
        sp.edit().putString("servers", now.toString()).apply();
    }

    private void clearHistory() {
        sp.edit().putString("servers", "[]").apply();
    }

    /** 点历史记录：填入地址与凭据，本机存过密钥就直接连 */
    private void useHistory(String base, String mode, String account, String secret) {
        errMsg = null;
        fBase = base;
        loginMode = mode;
        if ("open".equals(mode)) {
            fId = account != null && account.length() > 0 ? account : sp.getString("openId", "");
            fSecret = secret != null && secret.length() > 0 ? secret : sp.getString("openSecret", "");
            if (fId.length() > 0 && fSecret.length() > 0) {
                startOpenLogin(base, fId, fSecret, remember, ignoreCert);
                return;
            }
            toast("已填入地址，" + (fId.length() == 0 ? "补个 Client ID" : "补个 Secret") + "就能连");
        } else {
            fUser = account != null && account.length() > 0 ? account : sp.getString("user", "");
            fPass = secret != null && secret.length() > 0 ? secret : sp.getString("pwd", "");
            if (fUser.length() > 0 && fPass.length() > 0) {
                startPwdLogin(base, fUser, fPass, remember, ignoreCert);
                return;
            }
            toast("已填入地址，" + (fUser.length() == 0 ? "补个用户名" : "补个密码") + "就能连");
        }
        render();
    }

    private android.graphics.drawable.StateListDrawable rowPressBg() {
        android.graphics.drawable.StateListDrawable s =
                new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, bg(C_PRESS, 10));
        s.addState(new int[]{}, bg(0x00000000, 10));
        return s;
    }

    /** 登录页顶部的历史服务器卡片 */
    private void renderHistory() {
        JSONArray arr = histArray();
        if (arr.length() == 0) return;

        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        head.setLayoutParams(hlp);
        TextView title = tv("历史服务器 · " + arr.length(), 13, C_TEXT, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(title);
        TextView clr = tv("清空", 11, C_SUB, false);
        clr.setPadding(dp(10), dp(4), dp(2), dp(4));
        clr.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg()
                        .setTitle("清空历史服务器")
                        .setMessage("只清空地址列表，不影响已保存的登录状态。")
                        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                clearHistory();
                                render();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        head.addView(clr);
        c.addView(head);

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String base = o.optString("base", "");
            final String mode = o.optString("mode", "pwd");
            final String acc = o.optString("account", "");
            final String sec = o.optString("secret", "");

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(9), dp(6), dp(9));
            row.setBackground(rowPressBg());
            row.setClickable(true);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(2);
            row.setLayoutParams(rlp);

            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView b = tv(shortHost(base), 13, C_TEXT, false);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            String meta = ("open".equals(mode) ? "Client ID" : "账号密码")
                    + (acc.length() > 0 ? " · " + acc : "")
                    + (sec.length() > 0 ? " · 已存凭据" : "");
            TextView m = tv(meta, 11, C_SUB, false);
            m.setSingleLine(true);
            txt.addView(b);
            txt.addView(m);
            row.addView(txt);

            TextView go = tv("连接", 12, C_ACC, true);
            go.setPadding(dp(10), dp(4), dp(2), dp(4));
            row.addView(go);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    useHistory(base, mode, acc, sec);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    dlg()
                            .setTitle("删除这条记录")
                            .setMessage(base)
                            .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    removeHistory(base);
                                    render();
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    return true;
                }
            });
            c.addView(row);
        }

        TextView tip = tv("点一下直接连，长按删除", 11, C_META, false);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(8);
        tip.setLayoutParams(tl);
        c.addView(tip);
        contentBox.addView(c);
    }


    private void autoLogin() {
        if ("open".equals(loginMode)) startOpenLogin(fBase, fId, fSecret, true, ignoreCert);
        else startPwdLogin(fBase, fUser, fPass, true, ignoreCert);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }

    // ---------------- 基础 UI ----------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private GradientDrawable bg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable cardBg() {
        GradientDrawable g = bg(C_CARD, 14);
        g.setStroke(dp(1), C_LINE);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(cardBg());
        int p = dp(14);
        c.setPadding(p, dp(12), p, dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private GradientDrawable cardBgColor(int color) {
        GradientDrawable g = bg(color, 14);
        g.setStroke(dp(1), C_LINE);
        return g;
    }

    /** 可点击卡片：按下有高亮反馈，触控面积整块 48dp+ */
    private LinearLayout pressCard() {
        LinearLayout c = card();
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, cardBgColor(C_PRESS));
        s.addState(new int[]{}, cardBgColor(C_CARD));
        c.setBackground(s);
        c.setClickable(true);
        return c;
    }

    private View tabBarItem(final int index, String label) {
        TextView t = tv(label, 14, index == tab ? C_ACC : C_SUB, index == tab);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(0, dp(16), 0, dp(16));
        t.setMinimumHeight(dp(54));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tab = index;
                render();
            }
        });
        return t;
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // 顶栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(12), dp(10));
        header.setBackgroundColor(C_BG);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleBox.setLayoutParams(tlp);
        titleBox.addView(tv("青龙助手", 17, C_TEXT, true));

        // 地址 + 眼睛：可隐藏/显示服务器地址
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = dp(2);
        subRow.setLayoutParams(srlp);

        subtitle = tv("未连接", 11, C_SUB, false);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        subRow.addView(subtitle);

        ImageView eye = new ImageView(this);
        eye.setImageDrawable(new EyeDrawable(C_SUB, !hideHost));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(30), dp(24));
        elp.leftMargin = dp(6);
        eye.setLayoutParams(elp);
        eye.setPadding(dp(2), dp(2), dp(2), dp(2));
        eye.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideHost = !hideHost;
                sp.edit().putBoolean("hideHost", hideHost).apply();
                ImageView iv = (ImageView) v;
                Drawable d = iv.getDrawable();
                if (d instanceof EyeDrawable) ((EyeDrawable) d).setOpen(!hideHost);
                render();
                toast(hideHost ? "服务器地址已隐藏" : "服务器地址已显示");
            }
        });
        subRow.addView(eye);
        titleBox.addView(subRow);
        header.addView(titleBox);

        statePill = tv("离线", 10, C_SUB, false);
        statePill.setPadding(dp(8), dp(3), dp(8), dp(3));
        statePill.setBackground(bg(C_PILL, 10));
        header.addView(statePill);

        TextView refresh = tv("刷新", 12, C_ACC, true);
        refresh.setPadding(dp(12), dp(6), dp(6), dp(6));
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (token == null) {
                    render();
                } else {
                    refreshData();
                }
            }
        });
        header.addView(refresh);
        root.addView(header);

        // 进度条
        busyBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busyBar.setIndeterminate(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        busyBar.setLayoutParams(blp);
        busyBar.setVisibility(View.GONE);
        root.addView(busyBar);

        // 内容区
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        sv.setLayoutParams(slp2);
        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setPadding(dp(14), dp(6), dp(14), dp(30));
        applyContentWidth();
        sv.addView(contentBox);
        root.addView(sv);

        // 底部标签
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(C_CARD);
        tabBar.setPadding(dp(4), 0, dp(4), 0);
        tabBar.setMinimumHeight(dp(54));
        tabBar.addView(tabBarItem(0, "概览"));
        tabBar.addView(tabBarItem(1, "任务"));
        tabBar.addView(tabBarItem(2, "变量"));
        tabBar.addView(tabBarItem(3, "设置"));
        root.addView(tabBar);

        return root;
    }

    /** 手机上铺满宽度，横屏/平板上限 560dp 居中，避免卡片被拉得过长 */
    private void applyContentWidth() {
        if (contentBox == null) return;
        int screen = getResources().getDisplayMetrics().widthPixels;
        int maxWidth = dp(560) + dp(28);
        int w = Math.min(screen, maxWidth);
        contentBox.setLayoutParams(new ScrollView.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyContentWidth();
    }

    @Override
    public void onBackPressed() {
        if (token != null && tab != 0) {
            tab = 0;
            render();
            return;
        }
        super.onBackPressed();
    }

    // ---------------- 渲染 ----------------

    private void render() {
        if (contentBox == null) return;
        busyBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        contentBox.removeAllViews();
        boolean connected = token != null && client != null && client.baseUrl.length() > 0;
        statePill.setText(connected ? (loading ? "同步中" : "已连接") : "未登录");
        statePill.setTextColor(connected ? C_ACC : C_SUB);
        statePill.setBackground(bg(connected ? C_ACC_BG : C_PILL, 10));
        subtitle.setText(connected ? (hideHost ? "•••••••• 地址已隐藏" : shortHost(client.baseUrl))
                : "未连接面板");
        if (errMsg != null) contentBox.addView(errorBanner(errMsg));
        if (token == null) {
            renderLogin();
        } else {
            if (tab == 0) renderOverview();
            else if (tab == 1) renderCrons();
            else if (tab == 2) renderEnvs();
            else renderSettings();
        }
        // 标签栏高亮
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            t.setTextColor(i == tab ? C_ACC : C_SUB);
            t.setTypeface(t.getTypeface(), i == tab ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private String shortHost(String url) {
        if (url == null) return "";
        String s = url.replace("http://", "").replace("https://", "");
        if (s.length() > 28) s = "…" + s.substring(s.length() - 26);
        return s;
    }

    private View errorBanner(String msg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(bg(C_ERR_BG, 12));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.setLayoutParams(lp);
        box.addView(tv(msg, 12, C_RED, false));
        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copy(msg);
            }
        });
        return box;
    }

    private LinearLayout field(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView k = tv(label, 12, C_SUB, false);
        k.setLayoutParams(new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = tv(value, 13, C_TEXT, false);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        v.setGravity(Gravity.END);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(k);
        row.addView(v);
        return row;
    }

    private void sectionTitle(String s) {
        TextView t = tv(s, 13, C_SUB, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(2);
        lp.leftMargin = dp(2);
        t.setLayoutParams(lp);
        contentBox.addView(t);
    }

    private TextView pill(String text, int color) {
        TextView t = tv(text, 10, color, true);
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable g = bg((color & 0x00FFFFFF) | 0x22000000, 9);
        g.setStroke(dp(1), (color & 0x00FFFFFF) | 0x44000000);
        t.setBackground(g);
        return t;
    }

    // ---------------- 登录页 ----------------

    private void renderLogin() {
        renderHistory();
        LinearLayout c = card();
        c.addView(tv("连接青龙面板", 15, C_TEXT, true));
        TextView sub = tv2("面板账号密码，或 OpenAPI 的 Client ID / Secret", 12, C_SUB);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        sub.setLayoutParams(subLp);
        c.addView(sub);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(12);
        bar.setLayoutParams(barLp);
        bar.addView(modeTab("账号密码", "pwd"));
        bar.addView(modeTab("Client ID", "open"));
        c.addView(bar);

        final EditText eBase = input("面板地址，如 http://192.168.1.10:5700", fBase, false);
        c.addView(fieldLabel("面板地址"));
        c.addView(eBase);
        watch(eBase, 0);

        if ("open".equals(loginMode)) {
            EditText e1 = input("Client ID（St_ 开头）", fId, false);
            EditText e2 = input("Client Secret", fSecret, true);
            c.addView(fieldLabel("Client ID"));
            c.addView(e1);
            watch(e1, 3);
            c.addView(fieldLabel("Client Secret"));
            c.addView(e2);
            watch(e2, 4);
        } else {
            EditText e1 = input("用户名", fUser, false);
            EditText e2 = input("密码", fPass, true);
            c.addView(fieldLabel("用户名"));
            c.addView(e1);
            watch(e1, 1);
            c.addView(fieldLabel("密码"));
            c.addView(e2);
            watch(e2, 2);
        }

        final CheckBox cbRemember = check("记住，下次自动登录", remember);
        final CheckBox cbIgnore = check("忽略 HTTPS 证书校验（自签名证书时使用）", ignoreCert);
        c.addView(cbRemember);
        c.addView(cbIgnore);

        TextView btn = button("登录", C_ACC, C_ON_ACC);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean rem = cbRemember.isChecked();
                boolean ic = cbIgnore.isChecked();
                if (fBase.length() == 0) {
                    toast("请填写面板地址");
                    return;
                }
                if ("open".equals(loginMode)) {
                    if (fId.length() == 0 || fSecret.length() == 0) {
                        toast("请填写 Client ID 与 Client Secret");
                        return;
                    }
                    startOpenLogin(fBase, fId, fSecret, rem, ic);
                } else {
                    if (fUser.length() == 0) {
                        toast("请填写用户名");
                        return;
                    }
                    startPwdLogin(fBase, fUser, fPass, rem, ic);
                }
            }
        });
        c.addView(btn);
        contentBox.addView(c);

        LinearLayout tip = card();
        tip.addView(tv("两种登录方式", 13, C_SUB, true));
        tip.addView(tv2("· 账号密码：面板登录账号，功能最全\n"
                + "· Client ID：青龙「应用管理」里创建应用的 ID 与密钥，走 OpenAPI（/open），"
                + "能查任务、跑任务、管变量\n"
                + "· 地址支持 http/https 与反代路径；自签名证书请勾选忽略校验", 12, C_SUB));
        contentBox.addView(tip);
    }

    private TextView tv2(String s, float sp, int color) {
        TextView t = tv(s, sp, color, false);
        t.setLineSpacing(dp(2), 1.0f);
        return t;
    }

    private TextView fieldLabel(String s) {
        TextView t = tv(s, 11, C_SUB, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(5);
        t.setLayoutParams(lp);
        return t;
    }

    private EditText input(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(13);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_HINT);
        e.setSingleLine(true);
        e.setBackground(bg(C_FIELD, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setMinHeight(dp(46));
        if (password) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            // 输入框右侧的眼睛：点一下显示 / 再点隐藏
            final EyeDrawable eye = new EyeDrawable(C_SUB, false);
            eye.setIntrinsic(dp(20), dp(14));
            e.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, eye, null);
            e.setCompoundDrawablePadding(dp(8));
            e.setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View v, MotionEvent ev) {
                    if (ev.getAction() != MotionEvent.ACTION_UP) return false;
                    Drawable d = e.getCompoundDrawablesRelative()[2];
                    if (d == null) return false;
                    int x = (int) ev.getX();
                    int edge = e.getWidth() - e.getPaddingRight();
                    if (x < edge - d.getBounds().width() - dp(6)) return false;
                    boolean show = !eye.isOpen();
                    eye.setOpen(show);
                    int pos = e.getText().length();
                    e.setInputType(show
                            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    try {
                        e.setSelection(Math.min(pos, e.getText().length()));
                    } catch (Exception ignore) {
                    }
                    return true;
                }
            });
        }
        else e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private EditText inputFull(EditText e) {
        e.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private CheckBox check(String label, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextSize(12);
        cb.setTextColor(C_SUB);
        cb.setChecked(checked);
        cb.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{C_ACC, C_SUB}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        cb.setLayoutParams(lp);
        return cb;
    }

    private TextView button(String label, int bgColor, int textColor) {
        TextView b = tv(label, 14, textColor, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(12), 0, dp(12));
        b.setBackground(bg(bgColor, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------- 概览 ----------------

    private void renderOverview() {
        int enabled = 0, running = 0;
        for (Cron c : crons) {
            if (c.isDisabled == 0) enabled++;
            if (c.status == 0) running++;
        }

        LinearLayout stat = card();
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(4), 0, dp(10));
        row1.addView(statCell(String.valueOf(crons.size()), "任务总数"));
        row1.addView(statCell(String.valueOf(enabled), "已启用"));
        stat.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(statCell(String.valueOf(running), "运行中"));
        row2.addView(statCell(String.valueOf(envs.size()), "变量数"));
        stat.addView(row2);
        contentBox.addView(stat);

        LinearLayout info = card();
        info.addView(tv("面板信息", 13, C_TEXT, true));
        info.addView(field("地址", shortHost(client.baseUrl)));
        String ver = str(sys, "version");
        if (ver.length() == 0) ver = str(sys, "ql_version");
        if (ver.length() == 0) ver = "-";
        info.addView(field("版本", ver));
        String node = str(sys, "node");
        if (node.length() > 0) info.addView(field("Node", node));
        String plat = (str(sys, "platform") + " " + str(sys, "arch")).trim();
        if (plat.length() > 0) info.addView(field("系统", plat));
        String mem = str(sys, "memory");
        if (mem.length() > 0) info.addView(field("内存", mem));
        String disk = str(sys, "disk");
        if (disk.length() > 0) info.addView(field("磁盘", disk));
        contentBox.addView(info);

        sectionTitle("最近执行");
        List<Cron> recent = new ArrayList<>();
        for (Cron c : crons) {
            if (c.lastExec > 0) recent.add(c);
        }
        Collections.sort(recent, new Comparator<Cron>() {
            public int compare(Cron a, Cron b) {
                return Long.compare(b.lastExec, a.lastExec);
            }
        });
        if (recent.isEmpty()) {
            LinearLayout c0 = card();
            c0.addView(tv("暂无执行记录", 12, C_SUB, false));
            contentBox.addView(c0);
        }
        int n = 0;
        for (Cron c : recent) {
            if (n++ >= 8) break;
            LinearLayout rowC = pressCard();
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = tv(c.name.length() > 0 ? c.name : c.command, 13, C_TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            top.addView(name);
            TextView time = tv(ago(c.lastExec), 11, C_SUB, false);
            top.addView(time);
            rowC.addView(top);
            rowC.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    tab = 1;
                    render();
                }
            });
            contentBox.addView(rowC);
        }
    }

    private LinearLayout statCell(String num, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView n = tv(num, 22, C_ACC, true);
        n.setGravity(Gravity.CENTER);
        TextView l = tv(label, 11, C_SUB, false);
        l.setGravity(Gravity.CENTER);
        box.addView(n);
        box.addView(l);
        return box;
    }

    private String str(JSONObject o, String key) {
        if (o == null) return "";
        String v = o.optString(key, "");
        if (v == null || v.equals("null")) return "";
        return v;
    }

    private String dur(long seconds) {
        if (seconds < 60) return seconds + "秒";
        long m = seconds / 60;
        if (m < 60) return m + "分" + (seconds % 60) + "秒";
        long h = m / 60;
        return h + "小时" + (m % 60) + "分";
    }

    private String ago(long ms) {
        if (ms <= 0) return "未执行";
        long d = System.currentTimeMillis() - ms;
        if (d < 0) d = 0;
        long m = d / 60000;
        if (m < 1) return "刚刚";
        if (m < 60) return m + " 分钟前";
        long h = m / 60;
        if (h < 24) return h + " 小时前";
        long day = h / 24;
        if (day < 7) return day + " 天前";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }

    // ---------------- 任务 ----------------

    private void renderCrons() {
        LinearLayout tools = card();
        LinearLayout trow = new LinearLayout(this);
        trow.setOrientation(LinearLayout.HORIZONTAL);
        TextView up = miniButton("上传脚本");
        up.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickScript();
            }
        });
        TextView add = miniButton("新建任务");
        LinearLayout.LayoutParams alp = (LinearLayout.LayoutParams) add.getLayoutParams();
        alp.rightMargin = 0;
        add.setLayoutParams(alp);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openCronEditor(null);
            }
        });
        trow.addView(up);
        trow.addView(add);
        tools.addView(trow);
        contentBox.addView(tools);

        LinearLayout searchBox = card();
        EditText e = inputFull(input("搜索任务名称 / 命令", searchCron, false));
        e.setBackground(bg(C_FIELD, 10));
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) {
                searchCron = s.toString().trim();
                renderListOnly();
            }
        });
        searchBox.setPadding(dp(10), dp(10), dp(10), dp(10));
        searchBox.addView(e);

        LinearLayout sortRow = new LinearLayout(this);
        sortRow.setOrientation(LinearLayout.HORIZONTAL);
        sortRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = dp(6);
        sortRow.setLayoutParams(srlp);
        TextView sortBtn = tv("排序：" + sortLabel(sortMode) + "  ▾", 11, C_ACC, true);
        sortBtn.setPadding(dp(2), dp(4), dp(8), dp(4));
        sortBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSortDialog();
            }
        });
        sortRow.addView(sortBtn);
        TextView cnt = tv("共 " + crons.size() + " 条", 11, C_META, false);
        cnt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        cnt.setGravity(Gravity.END);
        sortRow.addView(cnt);
        searchBox.addView(sortRow);
        contentBox.addView(searchBox);

        contentBox.addView(listHolder());
    }

    private LinearLayout listHolder;

    /** 只刷新列表，避免输入框失焦 */
    private void renderListOnly() {
        if (listHolder == null) {
            render();
            return;
        }
        listHolder.removeAllViews();
        fillCronList(listHolder);
    }

    private View listHolder() {
        listHolder = new LinearLayout(this);
        listHolder.setOrientation(LinearLayout.VERTICAL);
        listHolder.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fillCronList(listHolder);
        return listHolder;
    }

    private String[] sortKeys() {
        return new String[]{"last", "created", "name", "status", "stale"};
    }

    private String sortLabel(String mode) {
        if ("created".equals(mode)) return "创建时间（新的在前）";
        if ("name".equals(mode)) return "名称 A→Z";
        if ("status".equals(mode)) return "状态优先（运行中在前）";
        if ("stale".equals(mode)) return "最久未执行在前";
        return "最近执行（默认）";
    }

    private void showSortDialog() {
        final String[] keys = sortKeys();
        String[] labels = new String[keys.length];
        for (int i = 0; i < keys.length; i++) labels[i] = sortLabel(keys[i]);
        int checked = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(sortMode)) checked = i;
        dlg()
                .setTitle("任务排序")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        sortMode = keys[which];
                        sp.edit().putString("cronSort", sortMode).apply();
                        d.dismiss();
                        render();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Comparator<Cron> cronComparator() {
        return new Comparator<Cron>() {
            public int compare(Cron a, Cron b) {
                if ("created".equals(sortMode)) {
                    return Long.compare(b.id, a.id);
                }
                if ("name".equals(sortMode)) {
                    String x = (a.name.length() > 0 ? a.name : a.command).toLowerCase(Locale.ROOT);
                    String y = (b.name.length() > 0 ? b.name : b.command).toLowerCase(Locale.ROOT);
                    int r = x.compareTo(y);
                    return r != 0 ? r : Long.compare(b.id, a.id);
                }
                if ("status".equals(sortMode)) {
                    int ra = statusRank(a), rb = statusRank(b);
                    if (ra != rb) return ra - rb;
                    return Long.compare(b.lastExec, a.lastExec);
                }
                if ("stale".equals(sortMode)) {
                    int r = Long.compare(a.lastExec, b.lastExec);   // 没跑过的排最前
                    return r != 0 ? r : Long.compare(a.id, b.id);
                }
                // 默认：最近执行在前，没执行过的垫底
                int r = Long.compare(b.lastExec, a.lastExec);
                return r != 0 ? r : Long.compare(b.id, a.id);
            }
        };
    }

    private int statusRank(Cron c) {
        if (c.status == 0) return 0;        // 运行中
        if (c.isDisabled == 1) return 2;    // 已停用
        return 1;                            // 已启用
    }

    private void fillCronList(LinearLayout holder) {
        List<Cron> list = new ArrayList<>();
        String q = searchCron.toLowerCase(Locale.ROOT);
        for (Cron c : crons) {
            if (q.length() == 0 || c.name.toLowerCase(Locale.ROOT).contains(q)
                    || c.command.toLowerCase(Locale.ROOT).contains(q)) {
                list.add(c);
            }
        }
        Collections.sort(list, cronComparator());
        holder.removeAllViews();
        if (list.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(tv(loading ? "正在同步…" : "没有匹配的任务", 12, C_SUB, false));
            holder.addView(empty);
            return;
        }
        for (final Cron c : list) holder.addView(cronRow(c));
    }

    private View cronRow(final Cron c) {
        LinearLayout box = pressCard();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = tv(c.name.length() > 0 ? c.name : c.command, 14, C_TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(name);
        if (c.status == 0) {
            top.addView(pill("运行中", C_WARN));
        } else if (c.isDisabled == 1) {
            top.addView(pill("已停用", C_SUB));
        } else {
            top.addView(pill("已启用", C_ACC));
        }
        box.addView(top);

        TextView cmd = tv(c.schedule + "   " + oneLine(c.command), 11, C_SUB, false);
        cmd.setSingleLine(true);
        cmd.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        cmd.setLayoutParams(lp);
        box.addView(cmd);

        String metaText = ago(c.lastExec);
        if (c.lastRun > 0) metaText = metaText + " · 耗时 " + dur(c.lastRun);
        TextView meta = tv(metaText + " · 点击查看操作", 11, C_META, false);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(6);
        meta.setLayoutParams(lp2);
        box.addView(meta);

        box.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showCronActions(c);
            }
        });
        return box;
    }

    private TextView miniButton(String label) {
        TextView t = tv(label, 12, C_ACC, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(9), dp(10), dp(9));
        GradientDrawable g = bg(C_FIELD, 9);
        g.setStroke(dp(1), C_LINE);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ");
    }

    private void showCronActions(final Cron c) {
        final String[] items = {
                "编辑任务",
                "立即运行", "停止运行",
                c.isDisabled == 1 ? "启用该任务" : "停用该任务",
                "查看日志", "复制命令"};

        AlertDialog.Builder b = dlg();
        b.setTitle(c.name.length() > 0 ? c.name : ("任务 #" + c.id));
        b.setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which == 0) openCronEditor(c);
                else if (which == 1) cronCmd(c, "run", "已提交运行");
                else if (which == 2) cronCmd(c, "stop", "已提交停止");
                else if (which == 3) cronCmd(c, c.isDisabled == 1 ? "enable" : "disable",
                        c.isDisabled == 1 ? "已启用" : "已停用");
                else if (which == 4) loadLog(c);
                else copy(c.command);
            }
        });
        b.show();
    }

    private void cronCmd(final Cron c, final String action, final String okMsg) {
        pool.execute(new Runnable() {
            public void run() {
                try {
                    // 青龙 2.24 这几个接口要纯数组 body：[id]
                    JSONArray body = new JSONArray().put(c.id);
                    client.sendJson("PUT", "/api/crons/" + action, body.toString());
                    ui.post(new Runnable() {
                        public void run() {
                            toast(okMsg);
                            refreshData();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            toast(describe(e));
                        }
                    });
                }
            }
        });
    }

    private void loadLog(final Cron c) {
        toast("正在读取日志…");
        pool.execute(new Runnable() {
            public void run() {
                String content = null;
                String err = null;
                try {
                    JSONObject r = client.get("/api/crons/" + c.id + "/log?tail=true&limit=200000");
                    Object data = r.opt("data");
                    if (data instanceof JSONArray) {
                        JSONArray arr = (JSONArray) data;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < arr.length(); i++) sb.append(arr.optString(i)).append("\n");
                        content = sb.toString();
                    } else if (data instanceof String) {
                        content = (String) data;
                    } else if (data != null) {
                        JSONObject d = r.optJSONObject("data");
                        if (d != null) content = d.optString("content", d.toString());
                    }
                    if (content == null || content.trim().length() == 0) {
                        content = "暂无日志内容";
                    }
                } catch (Exception e) {
                    err = describe(e);
                }
                final String fc = content;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (fe != null) {
                            toast(fe);
                        } else {
                            showLog(c, fc);
                        }
                    }
                });
            }
        });
    }

    private void showLog(Cron c, String content) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_LOG_BG);
        box.setPadding(dp(12), dp(6), dp(12), dp(6));
        ScrollView sv = new ScrollView(this);
        TextView t = tv(content, 11, C_LOG_TEXT, false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setLineSpacing(dp(1), 1.0f);
        sv.addView(t);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

        AlertDialog d = dlg()
                .setTitle("日志 · " + (c.name.length() > 0 ? c.name : ("#" + c.id)))
                .setView(wrap)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ---------------- 环境变量 ----------------

    private void renderEnvs() {
        LinearLayout head = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = tv("共 " + envs.size() + " 条变量", 13, C_TEXT, true);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(t);
        final TextView toggle = tv(envShowValue ? "隐藏值" : "显示值", 12, C_ACC, true);
        toggle.setPadding(dp(10), dp(8), dp(10), dp(8));
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                envShowValue = !envShowValue;
                render();
            }
        });
        top.addView(toggle);
        TextView addEnv = tv("＋新建", 12, C_ACC, true);
        addEnv.setPadding(dp(10), dp(8), dp(4), dp(8));
        addEnv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openEnvEditor(null);
            }
        });
        top.addView(addEnv);
        head.addView(top);

        EditText e = input("搜索变量名 / 备注", searchEnv, false);
        e.setBackground(bg(C_FIELD, 10));
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) {
                searchEnv = s.toString().trim();
                if (envHolder != null) {
                    envHolder.removeAllViews();
                    fillEnvList(envHolder);
                }
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(10);
        e.setLayoutParams(elp);
        head.addView(e);
        contentBox.addView(head);

        envHolder = new LinearLayout(this);
        envHolder.setOrientation(LinearLayout.VERTICAL);
        fillEnvList(envHolder);
        contentBox.addView(envHolder);
    }

    private LinearLayout envHolder;

    private void fillEnvList(LinearLayout holder) {
        String q = searchEnv.toLowerCase(Locale.ROOT);
        int n = 0;
        for (final EnvItem e : envs) {
            if (q.length() > 0 && !e.name.toLowerCase(Locale.ROOT).contains(q)
                    && !e.remarks.toLowerCase(Locale.ROOT).contains(q)) continue;
            n++;
            LinearLayout box = pressCard();
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = tv(e.name, 13, C_TEXT, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            top.addView(name);
            if (e.status == 1) top.addView(pill("已禁用", C_SUB));
            else top.addView(pill("正常", C_ACC));
            box.addView(top);

            String show = e.value;
            if (!envShowValue && show.length() > 0) {
                show = show.length() <= 8 ? "••••••" : show.substring(0, 4) + "••••••" + show.substring(show.length() - 4);
            }
            TextView val = tv(show.length() == 0 ? "(空)" : show, 11, C_SUB, false);
            val.setSingleLine(true);
            val.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            val.setLayoutParams(lp);
            box.addView(val);
            if (e.remarks.length() > 0) {
                TextView r = tv(e.remarks, 11, C_META, false);
                r.setSingleLine(true);
                r.setEllipsize(android.text.TextUtils.TruncateAt.END);
                box.addView(r);
            }
            box.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showEnvActions(e);
                }
            });
            holder.addView(box);
        }
        if (n == 0) {
            LinearLayout empty = card();
            empty.addView(tv(loading ? "正在同步…" : "没有匹配的变量", 12, C_SUB, false));
            holder.addView(empty);
        }
    }

    private void showEnvActions(final EnvItem e) {
        final String[] items = {
                "编辑变量",
                "复制变量值",
                e.status == 1 ? "启用该变量" : "禁用该变量",
                "复制 名称=值"};
        dlg()
                .setTitle(e.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) openEnvEditor(e);
                        else if (which == 1) copy(e.value);
                        else if (which == 3) copy(e.name + "=" + e.value);
                        else envCmd(e, e.status == 1 ? "enable" : "disable");
                    }
                })
                .show();
    }

    private void envCmd(final EnvItem e, final String action) {
        pool.execute(new Runnable() {
            public void run() {
                try {
                    JSONArray body = new JSONArray().put(e.id);
                    client.sendJson("PUT", "/api/envs/" + action, body.toString());
                    ui.post(new Runnable() {
                        public void run() {
                            toast("enable".equals(action) ? "已启用" : "已禁用");
                            refreshData();
                        }
                    });
                } catch (final Exception ex) {
                    ui.post(new Runnable() {
                        public void run() {
                            toast(describe(ex));
                        }
                    });
                }
            }
        });
    }

    // ---------------- 编辑：任务 / 变量 / 上传脚本 ----------------

    private static final int REQ_SCRIPT = 2001;
    private static final String[] MODE_KEYS = {"day", "week", "hour", "min", "month", "raw"};
    private static final String[] MODE_LABELS = {"每天", "每周", "每小时", "每 N 分钟", "每月", "自定义"};
    private static final int[] WEEK_VALUES = {1, 2, 3, 4, 5, 6, 0};
    private static final String[] WEEK_LABELS = {"一", "二", "三", "四", "五", "六", "日"};

    static class Sched {
        String mode = "day";
        int hour = 8, minute = 0, dom = 1, everyMin = 30, minInHour = 0;
        final java.util.TreeSet<Integer> weeks = new java.util.TreeSet<Integer>();
        String raw = "";
    }

    private int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private boolean isNum(String s) {
        if (s == null || s.length() == 0) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** 把青龙的 cron 表达式翻成人能看懂的模式（能翻就翻，翻不出就落到自定义） */
    private Sched parseSchedule(String expr) {
        Sched s = new Sched();
        s.raw = expr == null ? "" : expr.trim();
        if (s.raw.length() == 0) return s;
        String[] f = s.raw.split("\\s+");
        if (f.length != 5) {
            s.mode = "raw";
            return s;
        }
        String mn = f[0], hr = f[1], dom = f[2], mon = f[3], dow = f[4];
        try {
            if (mn.startsWith("*/") && isNum(mn.substring(2)) && hr.equals("*") && dom.equals("*")
                    && mon.equals("*") && dow.equals("*")) {
                s.mode = "min";
                s.everyMin = Integer.parseInt(mn.substring(2));
                return s;
            }
            if (isNum(mn) && hr.equals("*") && dom.equals("*") && mon.equals("*") && dow.equals("*")) {
                s.mode = "hour";
                s.minInHour = Integer.parseInt(mn);
                return s;
            }
            if (isNum(mn) && isNum(hr)) {
                int m = Integer.parseInt(mn), h = Integer.parseInt(hr);
                if (dom.equals("*") && mon.equals("*")) {
                    if (dow.equals("*")) {
                        s.mode = "day";
                        s.minute = m;
                        s.hour = h;
                        return s;
                    }
                    if (dow.indexOf('/') < 0) {
                        s.mode = "week";
                        s.minute = m;
                        s.hour = h;
                        for (String d : dow.split(",")) {
                            if (isNum(d)) s.weeks.add(Integer.parseInt(d));
                        }
                        if (s.weeks.isEmpty()) s.mode = "raw";
                        return s;
                    }
                }
                if (isNum(dom) && mon.equals("*") && dow.equals("*")) {
                    s.mode = "month";
                    s.minute = m;
                    s.hour = h;
                    s.dom = Integer.parseInt(dom);
                    return s;
                }
            }
        } catch (Exception ignore) {
        }
        s.mode = "raw";
        return s;
    }

    /** 把模式 + 参数拼回 cron（显示给人看的永远是"每天 08:30"这种） */
    private String buildSchedule(Sched s) {
        if ("raw".equals(s.mode)) return s.raw.trim();
        String m = String.valueOf(clampI(s.minute, 0, 59));
        String h = String.valueOf(clampI(s.hour, 0, 23));
        if ("day".equals(s.mode)) return m + " " + h + " * * *";
        if ("week".equals(s.mode)) {
            StringBuilder w = new StringBuilder();
            for (Integer d : s.weeks) {
                if (w.length() > 0) w.append(",");
                w.append(d);
            }
            if (w.length() == 0) w.append("1");
            return m + " " + h + " * * " + w;
        }
        if ("hour".equals(s.mode)) return clampI(s.minInHour, 0, 59) + " * * * *";
        if ("min".equals(s.mode)) return "*/" + clampI(s.everyMin, 1, 59) + " * * * *";
        if ("month".equals(s.mode)) return m + " " + h + " " + clampI(s.dom, 1, 31) + " * *";
        return s.raw.trim();
    }

    private String hhmm(int hour, int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", clampI(hour, 0, 23), clampI(minute, 0, 59));
    }

    /** 人话描述，显示在编辑框里 */
    private String describeSchedule(String expr) {
        Sched s = parseSchedule(expr);
        if ("day".equals(s.mode)) return "每天 " + hhmm(s.hour, s.minute) + " 跑一次";
        if ("week".equals(s.mode)) {
            StringBuilder w = new StringBuilder();
            for (Integer d : s.weeks) {
                int idx = 0;
                for (int i = 0; i < WEEK_VALUES.length; i++) if (WEEK_VALUES[i] == d) idx = i;
                if (w.length() > 0) w.append("、");
                w.append("周").append(WEEK_LABELS[idx]);
            }
            return "每周 " + w + " " + hhmm(s.hour, s.minute) + " 跑一次";
        }
        if ("hour".equals(s.mode)) return "每小时的第 " + s.minInHour + " 分跑一次";
        if ("min".equals(s.mode)) return "每 " + s.everyMin + " 分钟跑一次";
        if ("month".equals(s.mode)) return "每月 " + s.dom + " 日 " + hhmm(s.hour, s.minute) + " 跑一次";
        return "自定义：" + expr;
    }

    private void openCronEditor(Cron existing) {
        new CronEditor(existing, existing == null).show();
    }

    /** 用已有内容预填，但仍按"新建"提交 */
    private void openCronEditor(Cron prefill, boolean asNew) {
        new CronEditor(prefill, asNew).show();
    }

    /** 任务编辑：名称 / 命令 / 定时（每天·每周·每小时…）/ 启用 */
    private class CronEditor {
        final boolean isNew;
        final Cron src;
        final Sched s;
        EditText eName, eCmd, eTime, eHourMin, eEveryMin, eDom, eRaw;
        CheckBox cbOn;
        LinearLayout weekRow;
        TextView preview, timeLabel, domLabel;
        final TextView[] chips = new TextView[MODE_KEYS.length];
        final TextView[] weekChips = new TextView[WEEK_VALUES.length];

        CronEditor(Cron existing, boolean newMode) {
            isNew = newMode;
            src = existing;
            s = parseSchedule(existing == null ? "" : existing.schedule);
            if (isNew) {
                s.mode = "day";
                s.hour = 8;
                s.minute = 0;
            }
        }

        int modeIndex() {
            for (int i = 0; i < MODE_KEYS.length; i++) if (MODE_KEYS[i].equals(s.mode)) return i;
            return MODE_KEYS.length - 1;
        }

        void show() {
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(4), dp(16), dp(4));

            eName = input("任务名（可留空）", src == null ? "" : src.name, false);
            box.addView(fieldLabel("任务名称"));
            box.addView(eName);

            eCmd = input("命令，如 task sign.js、python3 run.py、bash job.sh", src == null ? "" : src.command, false);
            eCmd.setSingleLine(false);
            eCmd.setMinLines(2);
            eCmd.setTypeface(android.graphics.Typeface.MONOSPACE);
            box.addView(fieldLabel("执行命令"));
            box.addView(eCmd);

            box.addView(fieldLabel("定时规则"));
            HorizontalScrollView hs = new HorizontalScrollView(MainActivity.this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout bar = new LinearLayout(MainActivity.this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < MODE_KEYS.length; i++) {
                final int idx = i;
                chips[i] = chip(MODE_LABELS[i], false, new Runnable() {
                    public void run() {
                        s.mode = MODE_KEYS[idx];
                        refresh();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(6);
                chips[i].setLayoutParams(lp);
                bar.addView(chips[i]);
            }
            hs.addView(bar);
            box.addView(hs);

            eTime = input("08:30", hhmm(s.hour, s.minute), false);
            eTime.setInputType(InputType.TYPE_CLASS_DATETIME);
            timeLabel = fieldLabel("几点几分（时:分）");
            box.addView(timeLabel);
            box.addView(eTime);
            eTime.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                public void onTextChanged(CharSequence c, int a, int b, int d) {}
                public void afterTextChanged(Editable ed) {
                    parseTime(ed.toString());
                    updatePreview();
                }
            });

            weekRow = new LinearLayout(MainActivity.this);
            weekRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < WEEK_VALUES.length; i++) {
                final int val = WEEK_VALUES[i];
                weekChips[i] = chip(WEEK_LABELS[i], false, new Runnable() {
                    public void run() {
                        if (s.weeks.contains(val)) s.weeks.remove(val);
                        else s.weeks.add(val);
                        refresh();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = dp(4);
                weekChips[i].setLayoutParams(lp);
                weekRow.addView(weekChips[i]);
            }
            box.addView(fieldLabel("星期几（可多选）"));
            box.addView(weekRow);

            eHourMin = input("0-59", String.valueOf(s.minInHour), false);
            eHourMin.setInputType(InputType.TYPE_CLASS_NUMBER);
            box.addView(fieldLabel("每小时的第几分跑（0-59）"));
            box.addView(eHourMin);
            eHourMin.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                public void onTextChanged(CharSequence c, int a, int b, int d) {}
                public void afterTextChanged(Editable ed) {
                    s.minInHour = parseInt(ed.toString(), 0, 0, 59);
                    updatePreview();
                }
            });

            eEveryMin = input("1-59", String.valueOf(s.everyMin), false);
            eEveryMin.setInputType(InputType.TYPE_CLASS_NUMBER);
            box.addView(fieldLabel("每隔几分钟跑一次（1-59）"));
            box.addView(eEveryMin);
            eEveryMin.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                public void onTextChanged(CharSequence c, int a, int b, int d) {}
                public void afterTextChanged(Editable ed) {
                    s.everyMin = parseInt(ed.toString(), 30, 1, 59);
                    updatePreview();
                }
            });

            eDom = input("1-31", String.valueOf(s.dom), false);
            eDom.setInputType(InputType.TYPE_CLASS_NUMBER);
            domLabel = fieldLabel("每月第几日（1-31）");
            box.addView(domLabel);
            box.addView(eDom);
            eDom.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                public void onTextChanged(CharSequence c, int a, int b, int d) {}
                public void afterTextChanged(Editable ed) {
                    s.dom = parseInt(ed.toString(), 1, 1, 31);
                    updatePreview();
                }
            });

            eRaw = input("5 段：分 时 日 月 周", src == null ? "" : src.schedule, false);
            eRaw.setTypeface(android.graphics.Typeface.MONOSPACE);
            box.addView(fieldLabel("原始表达式（高级）"));
            box.addView(eRaw);
            eRaw.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
                public void onTextChanged(CharSequence c, int a, int b, int d) {}
                public void afterTextChanged(Editable ed) {
                    s.raw = ed.toString().trim();
                    updatePreview();
                }
            });

            preview = tv2("", 11, C_SUB);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(12);
            preview.setLayoutParams(plp);
            box.addView(preview);

            cbOn = check("启用这个任务", isNew ? true : (src == null || src.isDisabled == 0));
            box.addView(cbOn);

            ScrollView sv = new ScrollView(MainActivity.this);
            sv.addView(box);
            refresh();

            AlertDialog d = dlg()
                    .setTitle(isNew ? "新建任务" : "编辑任务")
                    .setView(sv)
                    .setPositiveButton("保存", null)
                    .setNegativeButton("取消", null)
                    .create();
            final AlertDialog dd = d;
            d.setOnShowListener(new DialogInterface.OnShowListener() {
                public void onShow(DialogInterface dia) {
                    ((AlertDialog) dia).getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(new View.OnClickListener() {
                                public void onClick(View v) {
                                    save(dd);
                                }
                            });
                }
            });
            d.show();
        }

        void parseTime(String text) {
            String t = text == null ? "" : text.trim();
            int sep = t.indexOf(':');
            if (sep < 0) sep = t.indexOf('：');
            try {
                if (sep > 0) {
                    s.hour = clampI(Integer.parseInt(t.substring(0, sep).trim()), 0, 23);
                    s.minute = clampI(Integer.parseInt(t.substring(sep + 1).trim()), 0, 59);
                }
            } catch (Exception ignore) {
            }
        }

        int parseInt(String text, int def, int lo, int hi) {
            try {
                return clampI(Integer.parseInt(text.trim()), lo, hi);
            } catch (Exception e) {
                return def;
            }
        }

        void refresh() {
            int cur = modeIndex();
            for (int i = 0; i < chips.length; i++) {
                boolean on = i == cur;
                chips[i].setTextColor(on ? C_ACC : C_SUB);
                chips[i].setTypeface(chips[i].getTypeface(),
                        on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                GradientDrawable g = bg(on ? C_ACC_BG : C_FIELD, 9);
                if (on) g.setStroke(dp(1), C_ACC_LINE);
                chips[i].setBackground(g);
            }
            boolean isDay = "day".equals(s.mode), isWeek = "week".equals(s.mode);
            boolean isHour = "hour".equals(s.mode), isMin = "min".equals(s.mode);
            boolean isMonth = "month".equals(s.mode), isRaw = "raw".equals(s.mode);
            eTime.setVisibility((isDay || isWeek || isMonth) ? View.VISIBLE : View.GONE);
            timeLabel.setVisibility((isDay || isWeek || isMonth) ? View.VISIBLE : View.GONE);
            weekRow.setVisibility(isWeek ? View.VISIBLE : View.GONE);
            eHourMin.setVisibility(isHour ? View.VISIBLE : View.GONE);
            eEveryMin.setVisibility(isMin ? View.VISIBLE : View.GONE);
            eDom.setVisibility(isMonth ? View.VISIBLE : View.GONE);
            domLabel.setVisibility(isMonth ? View.VISIBLE : View.GONE);
            eRaw.setVisibility(isRaw ? View.VISIBLE : View.GONE);
            for (int i = 0; i < weekChips.length; i++) {
                boolean on = s.weeks.contains(WEEK_VALUES[i]);
                weekChips[i].setTextColor(on ? C_ACC : C_SUB);
                GradientDrawable g = bg(on ? C_ACC_BG : C_FIELD, 8);
                if (on) g.setStroke(dp(1), C_ACC_LINE);
                weekChips[i].setBackground(g);
            }
            updatePreview();
        }

        void updatePreview() {
            if (preview == null) return;
            String expr = buildSchedule(s);
            preview.setText("到时这样跑：" + describeSchedule(expr) + "\n表达式：" + expr);
        }

        String currentSchedule() {
            parseTime(eTime.getText().toString());
            s.minInHour = parseInt(eHourMin.getText().toString(), s.minInHour, 0, 59);
            s.everyMin = parseInt(eEveryMin.getText().toString(), 30, 1, 59);
            s.dom = parseInt(eDom.getText().toString(), 1, 1, 31);
            s.raw = eRaw.getText().toString().trim();
            return buildSchedule(s);
        }

        void save(final AlertDialog d) {
            final String name = eName.getText().toString().trim();
            final String cmd = eCmd.getText().toString().trim();
            if (cmd.length() == 0) {
                toast("命令不能为空");
                return;
            }
            final String expr = currentSchedule();
            if (expr.length() == 0) {
                toast("定时规则不能为空");
                return;
            }
            String[] parts = expr.split("\\s+");
            if (parts.length < 5) {
                toast("表达式至少 5 段：分 时 日 月 周");
                return;
            }
            toast("正在保存…");
            pool.execute(new Runnable() {
                public void run() {
                    try {
                        JSONObject body = new JSONObject();
                        body.put("name", name);
                        body.put("command", cmd);
                        body.put("schedule", expr);
                        long newId = 0;
                        if (isNew) {
                            JSONObject r = client.post("/api/crons", body);
                            JSONObject d0 = r.optJSONObject("data");
                            if (d0 != null) newId = d0.optLong("id", 0);
                        } else {
                            body.put("id", src.id);
                            client.put("/api/crons", body);
                        }
                        boolean want = cbOn.isChecked();
                        boolean currently = isNew ? true : (src != null && src.isDisabled == 0);
                        long target = isNew ? newId : (src == null ? 0 : src.id);
                        if (target > 0 && want != currently) {
                            JSONArray ids = new JSONArray().put(target);
                            client.sendJson("PUT", "/api/crons/" + (want ? "enable" : "disable"), ids.toString());
                        }
                        ui.post(new Runnable() {
                            public void run() {
                                d.dismiss();
                                toast(isNew ? "任务已创建" : "任务已更新");
                                refreshData();
                            }
                        });
                    } catch (final Exception e) {
                        ui.post(new Runnable() {
                            public void run() {
                                toast("保存失败：" + describe(e));
                            }
                        });
                    }
                }
            });
        }
    }

    private TextView chip(String label, boolean on, final Runnable onClick) {
        TextView t = tv(label, 12, on ? C_ACC : C_SUB, on);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = bg(on ? C_ACC_BG : C_FIELD, 9);
        if (on) g.setStroke(dp(1), C_ACC_LINE);
        t.setBackground(g);
        t.setSingleLine(true);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onClick.run();
            }
        });
        return t;
    }

    /** 变量编辑：名称 / 值 / 备注 / 启用 */
    private void openEnvEditor(final EnvItem existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), dp(4));

        final EditText eName = input("变量名，如 JD_COOKIE", existing == null ? "" : existing.name, false);
        box.addView(fieldLabel("名称（字母或下划线开头，只能字母数字下划线）"));
        box.addView(eName);

        final EditText eValue = input("变量的值", existing == null ? "" : existing.value, false);
        eValue.setSingleLine(false);
        eValue.setMinLines(3);
        box.addView(fieldLabel("值"));
        box.addView(eValue);

        final EditText eRemarks = input("备注（可留空）", existing == null ? "" : existing.remarks, false);
        box.addView(fieldLabel("备注"));
        box.addView(eRemarks);

        final CheckBox cbEnabled = check("启用这个变量", existing == null || existing.status == 0);
        box.addView(cbEnabled);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        AlertDialog.Builder b = dlg()
                .setTitle(existing == null ? "新建变量" : "编辑变量")
                .setView(sv)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null);
        if (existing != null) b.setNeutralButton("删除", null);
        final AlertDialog d = b.create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface dia) {
                ((AlertDialog) dia).getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                String name = eName.getText().toString().trim();
                                String value = eValue.getText().toString();
                                String remarks = eRemarks.getText().toString().trim();
                                boolean on = cbEnabled.isChecked();
                                if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                                    toast("变量名只能用字母、数字、下划线，且不能以数字开头");
                                    return;
                                }
                                if (name.length() == 0) {
                                    toast("名称不能为空");
                                    return;
                                }
                                if (existing == null && value.length() == 0) {
                                    toast("新建变量时值不能为空（青龙要求必填）");
                                    return;
                                }
                                saveEnv(d, existing, name, value, remarks, on);
                            }
                        });
                if (existing != null) {
                    ((AlertDialog) dia).getButton(AlertDialog.BUTTON_NEUTRAL)
                            .setOnClickListener(new View.OnClickListener() {
                                public void onClick(View v) {
                                    confirmDeleteEnv(d, existing);
                                }
                            });
                }
            }
        });
        d.show();
    }

    private void saveEnv(final AlertDialog d, final EnvItem existing,
                         final String name, final String value, final String remarks, final boolean enabled) {
        toast("正在保存…");
        pool.execute(new Runnable() {
            public void run() {
                try {
                    JSONArray created = null;
                    if (existing == null) {
                        JSONArray arr = new JSONArray();
                        JSONObject o = new JSONObject();
                        o.put("name", name);
                        o.put("value", value);
                        o.put("remarks", remarks);
                        arr.put(o);
                        JSONObject r = client.sendJson("POST", "/api/envs", arr.toString());
                        created = arrOf(r);
                    } else {
                        JSONObject o = new JSONObject();
                        o.put("id", existing.id);
                        o.put("name", name);
                        o.put("value", value);
                        o.put("remarks", remarks);
                        client.put("/api/envs", o);
                    }
                    // 启用/停用：新建后按返回的 id 处理，编辑时按原 id 处理
                    long targetId = existing == null ? 0 : existing.id;
                    if (existing == null && created != null && created.length() > 0) {
                        JSONObject first = created.optJSONObject(0);
                        if (first != null) targetId = first.optLong("id", 0);
                    }
                    boolean needToggle = existing == null
                            ? !enabled
                            : (enabled == (existing.status == 1));
                    if (targetId > 0 && needToggle) {
                        JSONArray ids = new JSONArray().put(targetId);
                        client.sendJson("PUT", "/api/envs/" + (enabled ? "enable" : "disable"), ids.toString());
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            d.dismiss();
                            toast(existing == null ? "变量已创建" : "变量已更新");
                            refreshData();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            toast("保存失败：" + describe(e));
                        }
                    });
                }
            }
        });
    }

    private void confirmDeleteEnv(final AlertDialog editor, final EnvItem e) {
        dlg()
                .setTitle("删除变量")
                .setMessage(e.name + "\n删了就没了，确认？")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dd, int w) {
                        editor.dismiss();
                        pool.execute(new Runnable() {
                            public void run() {
                                try {
                                    client.sendJson("DELETE", "/api/envs",
                                            new JSONArray().put(e.id).toString());
                                    ui.post(new Runnable() {
                                        public void run() {
                                            toast("已删除");
                                            refreshData();
                                        }
                                    });
                                } catch (final Exception ex) {
                                    ui.post(new Runnable() {
                                        public void run() {
                                            toast("删除失败：" + describe(ex));
                                        }
                                    });
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private JSONArray arrOf(JSONObject r) {
        if (r == null) return null;
        JSONArray a = r.optJSONArray("data");
        if (a != null) return a;
        JSONObject d = r.optJSONObject("data");
        if (d != null) return d.optJSONArray("data");
        return null;
    }

    // ---------------- 上传脚本 ----------------

    private void pickScript() {
        if (client == null || token == null) {
            toast("先登录再上传");
            return;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择脚本文件"), REQ_SCRIPT);
        } catch (Exception e) {
            toast("这台手机没有可用的文件选择器");
        }
    }

    private String uriName(Uri uri) {
        String name = "";
        try {
            android.database.Cursor cur = getContentResolver().query(uri, null, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        int idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) name = cur.getString(idx);
                    }
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ignore) {
        }
        if (name == null || name.length() == 0) {
            name = uri.getLastPathSegment();
            if (name != null && name.indexOf('/') > 0) name = name.substring(name.lastIndexOf('/') + 1);
        }
        return name == null ? "script.js" : name;
    }

    private byte[] uriBytes(Uri uri, int[] sizeOut) throws Exception {
        java.io.InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new Exception("读不到这个文件");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = is.read(buf)) > 0) {
            total += n;
            if (total > 3 * 1024 * 1024) {
                is.close();
                throw new Exception("文件超过 3MB，青龙脚本一般没这么大");
            }
            bos.write(buf, 0, n);
        }
        is.close();
        if (sizeOut != null && sizeOut.length > 0) sizeOut[0] = total;
        return bos.toByteArray();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SCRIPT || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = uriName(uri);
        final byte[] bytes;
        try {
            bytes = uriBytes(uri, new int[1]);
        } catch (final Exception e) {
            toast("读文件失败：" + e.getMessage());
            return;
        }
        final EditText eDir = input("留空=scripts 根目录；或填子目录，如 my", "", false);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(18), dp(4), dp(18), dp(4));
        wrap.addView(tv2("文件：" + name + "（" + (bytes.length / 1024 + 1) + "KB）", 12, C_SUB));
        wrap.addView(fieldLabel("上传到哪个目录"));
        wrap.addView(eDir);
        dlg()
                .setTitle("上传脚本")
                .setView(wrap)
                .setPositiveButton("上传", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        uploadScript(name, eDir.getText().toString().trim(), bytes);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void uploadScript(final String name, final String dir, final byte[] bytes) {
        toast("正在上传…");
        pool.execute(new Runnable() {
            public void run() {
                try {
                    client.upload("/api/scripts", name, dir, bytes);
                    ui.post(new Runnable() {
                        public void run() {
                            toast("已上传：" + (dir.length() > 0 ? dir + "/" : "scripts/") + name);
                            askCreateTask(name, dir);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            toast("上传失败：" + describe(e));
                        }
                    });
                }
            }
        });
    }

    private String suggestCommand(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) return "python3 " + name;
        if (lower.endsWith(".sh")) return "bash " + name;
        if (lower.endsWith(".js") || lower.endsWith(".ts")) return "task " + name;
        return "task " + name;
    }

    private void askCreateTask(final String filename, final String dir) {
        dlg()
                .setTitle("顺手建个任务？")
                .setMessage("脚本已上传，要不要现在按它建一个定时任务？\n命令会先填成：" + suggestCommand(filename))
                .setPositiveButton("建任务", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Cron c = new Cron();
                        c.name = filename;
                        c.command = suggestCommand(filename);
                        c.schedule = "0 8 * * *";
                        openCronEditor(c, true);
                    }
                })
                .setNegativeButton("不用", null)
                .show();
    }

    // ---------------- 设置 ----------------

    private void renderSettings() {
        LinearLayout c = card();
        c.addView(tv("连接设置", 13, C_TEXT, true));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(10);
        bar.setLayoutParams(barLp);
        bar.addView(modeTab("账号密码", "pwd"));
        bar.addView(modeTab("Client ID", "open"));
        c.addView(bar);

        final EditText eBase = input("http://192.168.1.10:5700", fBase, false);
        c.addView(fieldLabel("面板地址"));
        c.addView(eBase);
        watch(eBase, 0);

        if ("open".equals(loginMode)) {
            EditText e1 = input("Client ID", fId, false);
            EditText e2 = input("Client Secret", fSecret, true);
            c.addView(fieldLabel("Client ID"));
            c.addView(e1);
            watch(e1, 3);
            c.addView(fieldLabel("Client Secret"));
            c.addView(e2);
            watch(e2, 4);
        } else {
            EditText e1 = input("用户名", fUser, false);
            EditText e2 = input("密码", fPass, true);
            c.addView(fieldLabel("用户名"));
            c.addView(e1);
            watch(e1, 1);
            c.addView(fieldLabel("密码"));
            c.addView(e2);
            watch(e2, 2);
        }

        final CheckBox cbRemember = check("记住，下次自动登录", remember);
        final CheckBox cbIgnore = check("忽略 HTTPS 证书校验", ignoreCert);
        c.addView(cbRemember);
        c.addView(cbIgnore);

        TextView save = button("保存并重新登录", C_ACC, C_ON_ACC);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (fBase.length() == 0) {
                    toast("请填写面板地址");
                    return;
                }
                boolean rem = cbRemember.isChecked();
                boolean ic = cbIgnore.isChecked();
                if ("open".equals(loginMode)) startOpenLogin(fBase, fId, fSecret, rem, ic);
                else startPwdLogin(fBase, fUser, fPass, rem, ic);
            }
        });
        c.addView(save);
        contentBox.addView(c);

        // 外观：黑白两版
        LinearLayout look = card();
        LinearLayout lrow = new LinearLayout(this);
        lrow.setOrientation(LinearLayout.HORIZONTAL);
        lrow.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = tv("外观", 13, C_TEXT, true);
        lt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        lrow.addView(lt);
        lrow.addView(tv("dark".equals(themeName) ? "深色" : "浅色", 11, C_SUB, false));
        look.addView(lrow);
        TextView lsub = tv2("深色版护眼，浅色版白天看得清，切换立即生效", 11, C_SUB);
        LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lslp.topMargin = dp(4);
        lsub.setLayoutParams(lslp);
        look.addView(lsub);

        LinearLayout lbar = new LinearLayout(this);
        lbar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lbLp.topMargin = dp(10);
        lbar.setLayoutParams(lbLp);
        lbar.addView(themeTab("深色", "dark"));
        lbar.addView(themeTab("浅色", "light"));
        look.addView(lbar);
        contentBox.addView(look);

        LinearLayout out = pressCard();
        TextView logout = tv("退出登录", 13, C_RED, true);
        logout.setGravity(Gravity.CENTER);
        logout.setPadding(0, dp(10), 0, dp(10));
        logout.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                token = null;
                if (client != null) client.token = null;
                sp.edit().remove("token").apply();
                errMsg = null;
                tab = 0;
                render();
            }
        });
        out.addView(logout);
        contentBox.addView(out);

        LinearLayout about = card();
        about.addView(tv("关于", 13, C_TEXT, true));
        about.addView(field("客户端", "青龙助手 " + versionName()));
        about.addView(field("外观", "dark".equals(themeName) ? "深色版" : "浅色版"));
        about.addView(field("历史服务器", histCount() + " 条"));
        if (histCount() > 0) {
            TextView wipe = tv("清空历史服务器", 12, C_SUB, false);
            wipe.setPadding(dp(2), dp(8), dp(2), dp(4));
            wipe.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    clearHistory();
                    toast("历史服务器已清空");
                    render();
                }
            });
            about.addView(wipe);
        }
        about.addView(field("兼容", "青龙面板 2.x"));
        about.addView(field("当前方式", "open".equals(loginMode) ? "Client ID (OpenAPI)" : "账号密码"));
        about.addView(field("说明", "凭据只存本机"));
        contentBox.addView(about);
    }

    // ---------------- 网络 ----------------

    private void startPwdLogin(final String base, final String user, final String pass,
                               final boolean rem, final boolean ic) {
        String warn = preflight(base);
        if (warn != null) {
            loading = false;
            errMsg = warn;
            render();
            return;
        }
        loading = true;
        errMsg = null;
        render();
        pool.execute(new Runnable() {
            public void run() {
                try {
                    QlClient c = new QlClient(base, ic);
                    c.prefix = "/api";
                    JSONObject body = new JSONObject();
                    body.put("username", user);
                    body.put("password", pass);
                    JSONObject r = c.post("/api/user/login", body);
                    String tk = tokenOf(r);
                    if (tk.length() == 0) throw new QlClient.ApiException(0, "面板已响应但没有返回 token");
                    applyLogin(c, tk, "pwd", base, user, pass, rem, ic);
                } catch (Exception e) {
                    if (e instanceof QlClient.ApiException && ((QlClient.ApiException) e).code == 420) {
                        ui.post(new Runnable() {
                            public void run() {
                                loading = false;
                                token = null;
                                errMsg = "该面板开了「两步验证」，请输入手机/验证器上的 6 位验证码";
                                render();
                                showTwoFactorDialog(base, user, pass, rem, ic);
                            }
                        });
                        return;
                    }
                    fail(e);
                }
            }
        });
    }

    private void startOpenLogin(final String base, final String id, final String secret,
                                final boolean rem, final boolean ic) {
        String warn = preflight(base);
        if (warn != null) {
            loading = false;
            errMsg = warn;
            render();
            return;
        }
        loading = true;
        errMsg = null;
        render();
        pool.execute(new Runnable() {
            public void run() {
                try {
                    QlClient c = new QlClient(base, ic);
                    c.prefix = "/open";
                    String tk = "";
                    Exception first = null;
                    // 新版用 client_id/client_secret，旧版用 appid/appsecret，两种都试
                    String[] queries = {
                            "/api/auth/token?client_id=" + enc(id) + "&client_secret=" + enc(secret),
                            "/api/auth/token?appid=" + enc(id) + "&appsecret=" + enc(secret)};
                    for (String q : queries) {
                        try {
                            tk = tokenOf(c.get(q));
                            if (tk.length() > 0) break;
                        } catch (Exception e) {
                            if (first == null) first = e;
                        }
                    }
                    if (tk.length() == 0) {
                        if (first != null) throw first;
                        throw new QlClient.ApiException(0, "Client ID / Secret 校验未通过");
                    }
                    applyLogin(c, tk, "open", base, id, secret, rem, ic);
                } catch (Exception e) {
                    fail(e);
                }
            }
        });
    }

    private void showTwoFactorDialog(final String base, final String user, final String pass,
                                     final boolean rem, final boolean ic) {
        final EditText e = input("6 位动态验证码", "", false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(18), dp(6), dp(18), 0);
        wrap.addView(e);
        dlg()
                .setTitle("两步验证")
                .setView(wrap)
                .setPositiveButton("验证并登录", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final String code = e.getText().toString().trim();
                        if (code.length() == 0) {
                            toast("请输入验证码");
                            return;
                        }
                        loading = true;
                        errMsg = null;
                        render();
                        pool.execute(new Runnable() {
                            public void run() {
                                try {
                                    QlClient c = new QlClient(base, ic);
                                    c.prefix = "/api";
                                    JSONObject body = new JSONObject();
                                    body.put("code", code);
                                    body.put("username", user);
                                    body.put("password", pass);
                                    JSONObject r = c.put("/api/user/two-factor/login", body);
                                    String tk = tokenOf(r);
                                    if (tk.length() == 0) throw new QlClient.ApiException(0, "验证码不正确或已过期");
                                    applyLogin(c, tk, "pwd", base, user, pass, rem, ic);
                                } catch (Exception ex) {
                                    fail(ex);
                                }
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String tokenOf(JSONObject r) {
        JSONObject d = r.optJSONObject("data");
        String tk = d != null ? d.optString("token", "") : "";
        if (tk.length() == 0) tk = r.optString("token", "");
        return tk;
    }

    private void applyLogin(final QlClient c, final String tk, final String mode,
                            final String base, final String a, final String b2,
                            final boolean rem, final boolean ic) {
        c.token = tk;
        client = c;
        token = tk;
        loginMode = mode;
        ignoreCert = ic;
        remember = rem;
        fBase = c.baseUrl;
        SharedPreferences.Editor ed = sp.edit()
                .putString("base", c.baseUrl)
                .putString("mode", mode)
                .putBoolean("ignoreCert", ic)
                .putBoolean("remember", rem);
        if ("open".equals(mode)) {
            fId = a;
            fSecret = b2;
            ed.putString("openId", a);
            if (rem) ed.putString("openSecret", b2);
            else ed.remove("openSecret");
        } else {
            fUser = a;
            fPass = b2;
            ed.putString("user", a);
            if (rem) ed.putString("pwd", b2);
            else ed.remove("pwd");
        }
        if (rem) ed.putString("token", tk);
        else ed.remove("token");
        ed.apply();
        pushHistory(c.baseUrl, mode, a, b2);
        ui.post(new Runnable() {
            public void run() {
                loading = false;
                errMsg = null;
                toast("登录成功");
                tab = 0;
                refreshData();
            }
        });
    }

    private void fail(Exception e) {
        final Exception fe = e;
        ui.post(new Runnable() {
            public void run() {
                loading = false;
                token = null;
                errMsg = describeLogin(fe);
                render();
            }
        });
    }

    private String describeLogin(Exception e) {
        if (e instanceof QlClient.ApiException) {
            QlClient.ApiException ae = (QlClient.ApiException) e;
            String m = ae.getMessage();
            if (m == null || m.length() == 0) m = "面板拒绝了这个请求";
            if (ae.code == 403) m = m + "（面板把本机 IP 拉黑了，去青龙后台「IP 黑名单」解封）";
            if (ae.code == 410) m = m + "（失败次数过多，按提示秒数后再试）";
            if (ae.code == 401) m = m + "（账号或密码不对，或者登录已失效）";
            if (ae.code == 450) m = m + "（面板还没初始化，先在网页端完成初始化）";
            return "登录失败(" + ae.code + ")：" + m;
        }
        Throwable t = e;
        int guard = 0;
        while (t.getCause() != null && t.getCause() != t && guard++ < 5) t = t.getCause();
        String cls = t.getClass().getSimpleName();
        String raw = t.getMessage() == null ? "" : t.getMessage();
        String friendly;
        if (cls.contains("UnknownHost")) friendly = "找不到这个地址：域名或 IP 写错了，或者手机不在面板所在的网络";
        else if (cls.contains("ConnectException") || cls.contains("Connect"))
            friendly = "连接被拒绝：青龙没在跑、端口不对，或者被防火墙/反代挡住了";
        else if (cls.contains("Timeout")) friendly = "连接超时：面板没响应，检查地址、端口和网络";
        else if (cls.contains("NoRouteToHost")) friendly = "网络不可达：手机和面板不在同一个网络";
        else if (cls.contains("SSL") || cls.contains("Cert") || raw.contains("Trust"))
            friendly = "HTTPS 证书校验失败：面板用了自签名证书，先勾选「忽略 HTTPS 证书校验」";
        else if (cls.contains("MalformedURL") || cls.contains("IllegalArgument") || cls.contains("URISyntax"))
            friendly = "地址格式不对，应该像 http://192.168.1.10:5700";
        else if (cls.contains("Socket")) friendly = "网络连接被中断，再点一次登录";
        else friendly = "请求没成功";
        String hint = "";
        if (isLanHost(fBase) && !onWifi()) hint = "（面板是局域网地址，手机要连同一个 WiFi）";
        String detail = raw.length() == 0 ? "" : "\n详情：" + raw;
        return "登录失败：" + friendly + hint + detail;
    }

    private void watch(EditText e, final int which) {
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) {
                String v = s.toString();
                if (which == 0) fBase = v;
                else if (which == 1) fUser = v;
                else if (which == 2) fPass = v;
                else if (which == 3) fId = v;
                else fSecret = v;
            }
        });
    }

    /** 深色 / 浅色切换：存下来后整体重建界面 */
    private TextView themeTab(String label, final String theme) {
        boolean on = theme.equals(themeName);
        TextView t = tv(label, 13, on ? C_ACC : C_SUB, on);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable g = bg(on ? C_ACC_BG : C_FIELD, 10);
        if (on) g.setStroke(dp(1), C_ACC_LINE);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (theme.equals(themeName)) return;
                sp.edit().putString("theme", theme).apply();
                applyTheme(theme);
                setContentView(buildRoot());
                render();
                toast("dark".equals(themeName) ? "已切换到深色版" : "已切换到浅色版");
            }
        });
        return t;
    }

    private TextView modeTab(String label, String mode) {
        boolean on = mode.equals(loginMode);
        TextView t = tv(label, 13, on ? C_ACC : C_SUB, on);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable g = bg(on ? C_ACC_BG : C_FIELD, 10);
        if (on) g.setStroke(dp(1), C_ACC_LINE);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(8);
        t.setLayoutParams(lp);
        final String m = mode;
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loginMode = m;
                errMsg = null;
                render();
            }
        });
        return t;
    }

    private String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private void refreshData() {
        if (client == null) return;
        loading = true;
        errMsg = null;
        render();
        pool.execute(new Runnable() {
            public void run() {
                final StringBuilder err = new StringBuilder();
                boolean authFail = false;
                try {
                    JSONObject s = client.get("/api/system");
                    JSONObject d = s.optJSONObject("data");
                    sys = d != null ? d : s;
                } catch (Exception e) {
                    if (isAuth(e)) authFail = true;
                    err.append("面板信息：").append(describe(e));
                }
                if (!authFail) {
                    try {
                        JSONArray arr = list(client.get("/api/crons?searchValue=&page=1&size=200"));
                        crons.clear();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o != null) crons.add(parseCron(o));
                            }
                        }
                    } catch (Exception e) {
                        if (isAuth(e)) authFail = true;
                        if (err.length() > 0) err.append("\n");
                        err.append("任务列表：").append(describe(e));
                    }
                    try {
                        JSONArray arr = list(client.get("/api/envs?searchValue=&page=1&size=300"));
                        envs.clear();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o != null) envs.add(parseEnv(o));
                            }
                        }
                    } catch (Exception e) {
                        if (isAuth(e)) authFail = true;
                        if (err.length() > 0) err.append("\n");
                        err.append("环境变量：").append(describe(e));
                    }
                }
                final boolean fa = authFail;
                final String msg = err.length() > 0 ? err.toString() : null;
                ui.post(new Runnable() {
                    public void run() {
                        loading = false;
                        if (fa) {
                            token = null;
                            if (client != null) client.token = null;
                            errMsg = "登录已失效，请重新登录";
                            if (remember && hasCredential()) {
                                autoLogin();
                                return;
                            }
                        } else {
                            errMsg = msg;
                        }
                        render();
                    }
                });
            }
        });
    }

    private boolean isAuth(Exception e) {
        return e instanceof QlClient.ApiException && ((QlClient.ApiException) e).code == 401;
    }

    /** 兼容不同版本返回结构，取出数组 */
    private JSONArray list(JSONObject r) {
        if (r == null) return null;
        JSONArray a = r.optJSONArray("data");
        if (a != null) return a;
        JSONObject d = r.optJSONObject("data");
        if (d != null) {
            a = d.optJSONArray("data");
            if (a != null) return a;
            a = d.optJSONArray("list");
            if (a != null) return a;
            a = d.optJSONArray("items");
            if (a != null) return a;
        }
        a = r.optJSONArray("list");
        return a;
    }

    private Cron parseCron(JSONObject o) {
        Cron c = new Cron();
        c.id = o.optLong("id", 0);
        c.name = pick(o, "name", "title");
        c.command = pick(o, "command");
        c.schedule = pick(o, "schedule");
        c.isDisabled = o.optInt("isDisabled", 0);
        c.status = o.optInt("status", 1);
        c.lastExec = pickTs(o, "last_execution_time");
        if (c.lastExec == 0) c.lastExec = pickTs(o, "last_executed_at");
        c.lastRun = Math.max(0, o.optLong("last_running_time", 0));
        if (c.lastRun > 86400L) c.lastRun = 0;
        return c;
    }

    private EnvItem parseEnv(JSONObject o) {
        EnvItem e = new EnvItem();
        e.id = o.optLong("id", 0);
        e.name = pick(o, "name");
        e.value = pick(o, "value");
        e.remarks = pick(o, "remarks", "remark");
        e.status = o.optInt("status", 0);
        return e;
    }

    private long pickTs(JSONObject o, String key) {
        if (o == null) return 0;
        Object v = o.opt(key);
        if (v == null || v == JSONObject.NULL) return 0;
        long raw;
        if (v instanceof Number) {
            raw = ((Number) v).longValue();
        } else {
            try {
                raw = Long.parseLong(String.valueOf(v).trim());
            } catch (Exception e) {
                return 0;
            }
        }
        if (raw < 100000000L) return 0;                 // 太小：多半是耗时秒数，不是时间戳
        if (raw < 100000000000L) raw = raw * 1000L;     // 青龙返回秒级时间戳，统一成毫秒
        if (raw > System.currentTimeMillis() + 86400000L) return 0;
        return raw;
    }

    private String pick(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && v.length() > 0 && !v.equals("null")) return v;
        }
        return "";
    }

    // ---------------- 工具 ----------------

    private String describe(Exception e) {
        String s = describeLogin(e);
        return s.startsWith("登录失败：") ? s.substring(5) : s;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.3";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ql", s == null ? "" : s));
        toast("已复制到剪贴板");
    }
}
