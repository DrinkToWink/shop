package com.api;

import com.entity.Result;
import com.shop.pojo.TradeOrder;

public interface IOrderService {

    /**
     * 下单接口
     * @param order
     * @return
     */
    public Result confirmOrder(TradeOrder order);

}
