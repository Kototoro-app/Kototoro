package com.github.catvod.crawler;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.util.Base64;
import android.view.Surface;
import android.view.WindowManager;

import com.github.catvod.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SpiderApi {

    private final String localAddress;
    private final String remoteAddress;
    private final String port;

    public SpiderApi() {
        this(Proxy.getAddress(true), Proxy.getAddress(false), String.valueOf(Proxy.getPort()));
    }

    public SpiderApi(String address, String port) {
        this(address, address, port);
    }

    public SpiderApi(String localAddress, String remoteAddress, String port) {
        this.localAddress = normalizeAddress(localAddress);
        this.remoteAddress = normalizeAddress(remoteAddress == null ? localAddress : remoteAddress);
        this.port = port == null || port.trim().isEmpty() || "-1".equals(port.trim()) ? "" : port.trim();
    }

    public String getAddress(boolean local) {
        return local ? localAddress : remoteAddress;
    }

    public String getPort() {
        return port;
    }

    public void log(String message) {
        SpiderDebug.log(message);
    }

    public int getScreenOrientation() {
        try {
            Activity activity = App.getInstance().getCurrentActivity();
            Context context = activity == null ? App.getInstance() : activity;
            int orientation = context.getResources().getConfiguration().orientation;
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            int rotation = manager == null ? Surface.ROTATION_0 : manager.getDefaultDisplay().getRotation();
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            }
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return rotation == Surface.ROTATION_90
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            }
            return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        } catch (Throwable ignored) {
            return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
    }

    public String multiReq(JsonArray requests) {
        if (requests == null || requests.size() == 0) {
            return "";
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(requests.size(), 6));
        try {
            ArrayList<Future<String>> futures = new ArrayList<>();
            for (JsonElement element : requests) {
                if (element.isJsonObject()) {
                    futures.add(executor.submit(() -> request(element.getAsJsonObject())));
                }
            }
            JsonArray result = new JsonArray();
            for (Future<String> future : futures) {
                result.add(toJsonElement(future.get()));
            }
            return result.toString();
        } catch (Throwable error) {
            SpiderDebug.log(error);
            return "";
        } finally {
            executor.shutdownNow();
        }
    }

    public String webParse(String url, String flag) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String encoded = Base64.encodeToString(
            url.getBytes(StandardCharsets.UTF_8),
            Base64.URL_SAFE | Base64.NO_WRAP
        );
        return "proxy://go=SuperParse&flag=" + (flag == null ? "" : flag) + "&url=" + encoded;
    }

    private static String request(JsonObject request) {
        try {
            String url = getString(request, "url");
            if (url.isEmpty()) {
                return "";
            }
            Request.Builder builder = new Request.Builder()
                .url(url)
                .headers(getHeaders(request.get("headers")));
            if ("POST".equalsIgnoreCase(getString(request, "method"))) {
                builder.post(getRequestBody(request));
            }
            try (Response response = OkHttp.client().newCall(builder.build()).execute()) {
                return response.body() == null ? "" : response.body().string();
            }
        } catch (Throwable error) {
            SpiderDebug.log(error);
            return "";
        }
    }

    private static JsonElement toJsonElement(String text) {
        if (text == null) {
            return new JsonPrimitive("");
        }
        try {
            String value = text.trim();
            if (value.startsWith("{") || value.startsWith("[")) {
                return JsonParser.parseString(value);
            }
        } catch (Throwable ignored) {
        }
        return new JsonPrimitive(text);
    }

    private static RequestBody getRequestBody(JsonObject request) {
        JsonElement data = request.get("data");
        if (data == null || data.isJsonNull()) {
            return RequestBody.create(null, "");
        }
        if ("form".equalsIgnoreCase(getString(request, "postType")) && data.isJsonObject()) {
            FormBody.Builder builder = new FormBody.Builder();
            for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject().entrySet()) {
                builder.add(entry.getKey(), entry.getValue().getAsString());
            }
            return builder.build();
        }
        String value = data.isJsonPrimitive() ? data.getAsString() : data.toString();
        return RequestBody.create(null, value);
    }

    private static Headers getHeaders(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return new Headers.Builder().build();
        }
        try {
            HashMap<String, String> headers = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                headers.put(entry.getKey(), entry.getValue().getAsString());
            }
            return Headers.of(headers);
        } catch (Throwable ignored) {
            return new Headers.Builder().build();
        }
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String normalizeAddress(String address) {
        if (address == null || address.trim().isEmpty() || address.contains(":-1/")) {
            return "";
        }
        String value = address.trim();
        return value.endsWith("/") ? value : value + "/";
    }
}
