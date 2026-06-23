package com.jsh.erp.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.filter.ValueFilter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;

public class ResponseJsonUtil {
    public static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    static {
        FORMAT.setTimeZone(TimeZone.getTimeZone("GMT+8"));
    }

    /**
     * 响应过滤器：createTime/modifyTime/updateTime 保持原 Date 序列化，
     * 其他 Date 字段格式化为 yyyy-MM-dd。
     */
    public static final class ResponseFilter implements ValueFilter {
        @Override
        public Object apply(Object object, String name, Object value) {
            if ("createTime".equals(name) || "modifyTime".equals(name) || "updateTime".equals(name)) {
                return value;
            }
            if (value instanceof Date) {
                return FORMAT.format(value);
            }
            return value;
        }
    }

    /**
     * 成功的 json 串。fastjson2 默认即不输出循环引用 $ref、Map 非 String key 转 String，
     * 因此不再显式传 SerializerFeature。
     */
    public static String backJson(ResponseCode responseCode) {
        if (responseCode != null) {
            return JSON.toJSONString(responseCode, new ResponseFilter());
        }
        return null;
    }

    public static String returnJson(Map<String, Object> map, String message, int code) {
        map.put("message", message);
        return backJson(new ResponseCode(code, map));
    }

    public static String returnStr(Map<String, Object> objectMap, int res) {
        if (res > 0) {
            return returnJson(objectMap, ErpInfo.OK.name, ErpInfo.OK.code);
        } else if (res == -1) {
            return returnJson(objectMap, ErpInfo.TEST_USER.name, ErpInfo.TEST_USER.code);
        } else {
            return returnJson(objectMap, ErpInfo.ERROR.name, ErpInfo.ERROR.code);
        }
    }
}
