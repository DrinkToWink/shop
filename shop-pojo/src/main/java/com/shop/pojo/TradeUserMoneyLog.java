package com.shop.pojo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

//封装了用户余额的扣减日志
public class TradeUserMoneyLog extends TradeUserMoneyLogKey implements Serializable {
    private BigDecimal useMoney;

    private Date createTime;

    public BigDecimal getUseMoney() {
        return useMoney;
    }

    public void setUseMoney(BigDecimal useMoney) {
        this.useMoney = useMoney;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
}