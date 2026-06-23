package com.jsh.erp.utils;

import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class HttpClient {
    private static final Logger logger = LoggerFactory.getLogger(HttpClient.class);

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /**
     * GET 请求，返回解析后的 JSON 对象
     */
    public static JSONObject httpGet(String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException(String.format("%s查询出现异常", url));
            }
            ResponseBody body = response.body();
            String entity = body != null ? body.string() : "";
            return JSONObject.parseObject(entity);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(String.format("%s查询出现异常", url));
        }
    }

    /**
     * POST 请求，body 为 JSON 字符串，返回响应体字符串
     */
    public static String httpPost(String url, String param) {
        RequestBody body = RequestBody.create(param, JSON_MEDIA_TYPE);
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = CLIENT.newCall(request).execute()) {
            int statusCode = response.code();
            ResponseBody respBody = response.body();
            String data = respBody != null ? respBody.string() : "";
            logger.info("状态:{},数据:{}", statusCode, data);
            return data;
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }
}
