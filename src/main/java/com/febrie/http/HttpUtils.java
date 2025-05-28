package com.febrie.http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class HttpUtils {

    public static @NotNull HttpURLConnection getDefaultConnection(URL url) throws IOException {
        HttpURLConnection cn = getEmptyConnection(url);
        cn.setRequestProperty("Content-Type", "application/json");
        return cn;
    }

    public static @NotNull HttpURLConnection getEmptyConnection(@NotNull URL url) throws IOException {
        HttpURLConnection cn = (HttpURLConnection) url.openConnection();
        cn.setReadTimeout(10000);
        cn.setConnectTimeout(10000);
        return cn;
    }

}
