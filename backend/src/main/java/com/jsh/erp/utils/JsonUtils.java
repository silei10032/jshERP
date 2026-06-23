package com.jsh.erp.utils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * Created by jishenghua 2018-5-11 09:48:08
 *
 * @author jishenghua
 */
public class JsonUtils {

    public static JSONObject ok(){
        JSONObject obj = new JSONObject();
        JSONObject tmp = new JSONObject();
        tmp.put("message", "成功");
        obj.put("code", 200);
        obj.put("data", tmp);
        return obj;
    }

}
