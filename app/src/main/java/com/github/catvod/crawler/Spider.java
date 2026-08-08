package com.github.catvod.crawler;

import android.content.Context;

import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

public abstract class Spider {

    public static JSONObject empty = new JSONObject();
    public String siteKey;

    protected static Context mContext;

    public void init(Context context) throws Exception {
        mContext = context;
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public void initApi(SpiderApi api) {}

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContent(key, quick);
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "";
    }

    public String liveContent(String url) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return null;
    }

    public Object[] proxyLocal(Map<String, String> params) throws Exception {
        return null;
    }

    public String action(String action) throws Exception {
        return null;
    }

    public void cancelByTag() {}

    public void destroy() {}

    public static Dns safeDns() {
        return OkHttp.dns();
    }

    public static OkHttpClient client() {
        return OkHttp.client();
    }
}
