package com.exception;

import com.constant.ShopCode;
import lombok.extern.slf4j.Slf4j;


/**
 * 异常抛出类
 */
@Slf4j
public class CastException {
    public static void cast(ShopCode shopCode) {
        //log.error(shopCode.toString());
        //出现异常就打印出错的状态码，进行信息的提示
        System.err.println(shopCode.toString());
        //抛出一个自定义的异常
        throw new CustomerException(shopCode);
    }
}
