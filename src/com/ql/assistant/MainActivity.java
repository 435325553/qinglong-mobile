package com.ql.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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

    static final int C_BG = 0xFF0D1117;
    static final int C_CARD = 0xFF161E26;
    static final int C_FIELD = 0xFF1E2731;
    static final int C_TEXT = 0xFFE6EDF3;
    static final int C_SUB = 0xFF8B98A5;
    static final int C_ACC = 0xFF2BD96A;
    static final int C_WARN = 0xFFFFA657;
    static final int C_RED = 0xFFF85149;
    static final int C_LINE = 0xFF243040;

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
    private String fBase = "", fUser = "", fPass = "", fId = "", fSecret = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("ql", MODE_PRIVATE);
        ignoreCert = sp.getBoolean("ignoreCert", false);
        remember = sp.getBoolean("remember", true);
        loginMode = sp.getString("mode", "pwd");
        fBase = sp.getString("base", "");
        fUser = sp.getString("user", "");
        fPass = sp.getString("pwd", "");
        fId = sp.getString("openId", "");
        fSecret = sp.getString("openSecret", "");
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
        s.addState(new int[]{android.R.attr.state_pressed}, cardBgColor(0xFF202B37));
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
        subtitle = tv("未连接", 11, C_SUB, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        subtitle.setLayoutParams(slp);
        subtitle.setSingleLine(true);
        titleBox.addView(subtitle);
        header.addView(titleBox);

        statePill = tv("离线", 10, C_SUB, false);
        statePill.setPadding(dp(8), dp(3), dp(8), dp(3));
        statePill.setBackground(bg(0xFF1E2731, 10));
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
        statePill.setBackground(bg(connected ? 0xFF12281A : 0xFF1E2731, 10));
        subtitle.setText(connected ? shortHost(client.baseUrl) : "未连接面板");
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
        box.setBackground(bg(0xFF2A1717, 12));
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

        TextView btn = button("登录", C_ACC, 0xFF06210F);
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
        e.setHintTextColor(0xFF5A6773);
        e.setSingleLine(true);
        e.setBackground(bg(C_FIELD, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setMinHeight(dp(46));
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
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

    private void fillCronList(LinearLayout holder) {
        List<Cron> list = new ArrayList<>();
        String q = searchCron.toLowerCase(Locale.ROOT);
        for (Cron c : crons) {
            if (q.length() == 0 || c.name.toLowerCase(Locale.ROOT).contains(q)
                    || c.command.toLowerCase(Locale.ROOT).contains(q)) {
                list.add(c);
            }
        }
        Collections.sort(list, new Comparator<Cron>() {
            public int compare(Cron a, Cron b) {
                return Long.compare(b.id, a.id);
            }
        });
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
        TextView meta = tv(metaText + " · 点击查看操作", 11, 0xFF6B7885, false);
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

    private String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ");
    }

    private void showCronActions(final Cron c) {
        final String[] items = {
                "立即运行", "停止运行",
                c.isDisabled == 1 ? "启用该任务" : "停用该任务",
                "查看日志", "复制命令"};

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(c.name.length() > 0 ? c.name : ("任务 #" + c.id));
        b.setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                if (which == 0) cronCmd(c, "run", "已提交运行");
                else if (which == 1) cronCmd(c, "stop", "已提交停止");
                else if (which == 2) cronCmd(c, c.isDisabled == 1 ? "enable" : "disable",
                        c.isDisabled == 1 ? "已启用" : "已停用");
                else if (which == 3) loadLog(c);
                else copy(c.command);
            }
        });
        b.show();
    }

    private void cronCmd(final Cron c, final String action, final String okMsg) {
        pool.execute(new Runnable() {
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("ids", new JSONArray().put(c.id));
                    client.put("/api/crons/" + action, body);
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
        box.setBackgroundColor(0xFF10161C);
        box.setPadding(dp(12), dp(6), dp(12), dp(6));
        ScrollView sv = new ScrollView(this);
        TextView t = tv(content, 11, 0xFFC9D4E0, false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setLineSpacing(dp(1), 1.0f);
        sv.addView(t);
        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout wrap = new LinearLayout(this);
        wrap.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

        AlertDialog d = new AlertDialog.Builder(this)
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
                TextView r = tv(e.remarks, 11, 0xFF6B7885, false);
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
                "复制变量值",
                e.status == 1 ? "启用该变量" : "禁用该变量",
                "复制 名称=值"};
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) copy(e.value);
                        else if (which == 2) copy(e.name + "=" + e.value);
                        else envCmd(e, e.status == 1 ? "enable" : "disable");
                    }
                })
                .show();
    }

    private void envCmd(final EnvItem e, final String action) {
        pool.execute(new Runnable() {
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("ids", new JSONArray().put(e.id));
                    client.put("/api/envs/" + action, body);
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

        TextView save = button("保存并重新登录", C_ACC, 0xFF06210F);
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
        about.addView(field("客户端", "青龙助手 1.2"));
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
        new AlertDialog.Builder(this)
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

    private TextView modeTab(String label, String mode) {
        boolean on = mode.equals(loginMode);
        TextView t = tv(label, 13, on ? C_ACC : C_SUB, on);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable g = bg(on ? 0xFF12281A : C_FIELD, 10);
        if (on) g.setStroke(dp(1), 0x662BD96A);
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ql", s == null ? "" : s));
        toast("已复制到剪贴板");
    }
}
