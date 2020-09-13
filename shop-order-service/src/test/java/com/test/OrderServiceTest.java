package com.test;

import com.api.IOrderService;
import com.shop.OrderServiceApplication;
import com.shop.pojo.TradeOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.math.BigDecimal;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = OrderServiceApplication.class)
public class OrderServiceTest {

    @Autowired
    private IOrderService orderService;

    @Test
    public void confirmOrder() throws IOException {

        //这里的Id要跟数据库中的一样
        Long coupouId = 345988230098857984L;
        Long goodsId = 345959443973935104L;
        Long userId = 345963634385633280L;
        //创建订单对象，并且设置一些值
        TradeOrder order = new TradeOrder();
        order.setGoodsId(goodsId);
        order.setUserId(userId);
        order.setCouponId(coupouId);
        order.setAddress("北京");
        order.setGoodsNumber(1);
        order.setGoodsPrice(new BigDecimal(50));
        order.setShippingFee(new BigDecimal(10));
        order.setOrderAmount(new BigDecimal(50));
        order.setMoneyPaid(new BigDecimal(100));
        orderService.confirmOrder(order);

        System.in.read();

    }

}
