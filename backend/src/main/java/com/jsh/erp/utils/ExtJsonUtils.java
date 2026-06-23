package com.jsh.erp.utils;

import com.alibaba.fastjson2.JSON;

/**
 * JSON 序列化工具。
 *
 * 历史：原 fastjson 1.x 时代曾在此类实现 ext 字段展开 + NaN/Infinity 处理。
 * fastjson2 默认即 NaN/Infinity 写 null、循环引用不输出 $ref，自定义 codec 不再必要；
 * 全项目搜索 "ext" 字段也不存在于任何实体，ext 展开逻辑实际从未生效，
 * 因此一并删除。保留 toJSONString 入口以兼容外部调用方。
 *
 * @author jishenghua qq752718920  2018-10-7 15:26:27
 */
public class ExtJsonUtils {

    public static String toJSONString(Object object) {
        return JSON.toJSONString(object);
    }
}
