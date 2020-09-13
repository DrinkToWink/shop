package com.exception;

import com.constant.ShopCode;

/**
 * 自定义异常，将错误信息封装到，这个类的shopCode属性中
 */
public class CustomerException extends RuntimeException{

    private ShopCode shopCode;

    public CustomerException(ShopCode shopCode) {
        this.shopCode = shopCode;
    }
}
