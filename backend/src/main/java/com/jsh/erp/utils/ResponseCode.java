package com.jsh.erp.utils;

/**
 * @author jishenghua qq752718920  2018-10-7 15:26:27
 */
public class ResponseCode {

    public final int code;
    public final Object data;

    public ResponseCode(int code, Object data) {
        this.code = code;
        this.data = data;
    }
}
