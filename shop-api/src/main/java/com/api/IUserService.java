package com.api;

import com.entity.Result;
import com.shop.pojo.TradeUser;
import com.shop.pojo.TradeUserMoneyLog;

public interface IUserService {
    TradeUser findOne(Long userId);

    Result updateMoneyPaid(TradeUserMoneyLog userMoneyLog);
}
