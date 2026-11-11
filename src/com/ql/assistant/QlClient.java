package com.ql.assistant;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** 青龙面板 HTTP 客户端（仅依赖 org.json，无第三方库） */
public class QlClient {

    public static class ApiException extends Exception {
        public int code;
        public ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    public String baseUrl;
    public String token;
    public boolean ignoreCert;
    /** /api 走账号登录，/open 走 Client ID(OpenAPI)，服务端会把 /open 改写到 /api */
    public String prefix = "/api";

    public QlClient(String base, boolean ignoreCert) {
        this.baseUrl = normalize(base);
        this.ignoreCert = ignoreCert;
    }

    public static String normalize(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() == 0) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public JSONObject get(String path) throws Exception {
        return request("GET", path, null);
    }

    public JSONObject put(String path, JSONObject body) throws Exception {
        return request("PUT", path, body == null ? "{}" : body.toString());
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body == null ? "{}" : body.toString());
    }

    public JSONObject request(String method, String path, String body) throws Exception {
        if (baseUrl == null || baseUrl.length() == 0) {
            throw new ApiException(0, "未配置面板地址");
        }
        HttpURLConnection conn = null;
        try {
            String full = path;
            if ("/open".equals(prefix)) {
                if (full.startsWith("/api/")) full = "/open/" + full.substring(5);
                else if (full.equals("/api")) full = "/open";
            }
            URL url = new URL(baseUrl + full);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection && ignoreCert) {
                trustAll((HttpsURLConnection) conn);
            }
            conn.setRequestMethod(method);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "QlAssistant/1.0");
            if (token != null && token.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }
            int status = conn.getResponseCode();
            String text;
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            text = readAll(is);

            JSONObject json = null;
            try {
                json = new JSONObject(text);
            } catch (Exception ignore) {
                // 非 JSON（例如登录页 HTML）：多半是地址填错或需要登录
            }
            if (status == 401) {
                throw new ApiException(401, msgOf(json, "登录已失效，请重新登录"));
            }
            if (json == null) {
                String snip = text == null ? "" : text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                if (snip.length() > 60) snip = snip.substring(0, 60) + "…";
                throw new ApiException(status, "返回的不是青龙接口数据（HTTP " + status + "）"
                        + (snip.length() > 0 ? "，内容：" + snip : "，地址可能指向了别的服务或页面"));
            }
            int code = json.optInt("code", status);
            if (code != 200 && status >= 400) {
                throw new ApiException(code, msgOf(json, "请求失败(" + code + ")"));
            }
            if (code == 401) {
                throw new ApiException(401, msgOf(json, "登录已失效，请重新登录"));
            }
            if (code != 200) {
                throw new ApiException(code, msgOf(json, "请求失败(" + code + ")"));
            }
            return json;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String msgOf(JSONObject json, String def) {
        if (json == null) return def;
        String m = json.optString("message", "");
        if (m == null || m.length() == 0) m = json.optString("msg", "");
        if (m == null || m.length() == 0) m = json.optString("error", "");
        return (m == null || m.length() == 0) ? def : m;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void trustAll(HttpsURLConnection c) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String host, SSLSession session) { return true; }
            });
        } catch (Exception ignore) {
        }
    }
}
