package com.shop.mq;

import com.alibaba.fastjson.JSON;
import com.constant.ShopCode;
import com.entity.MQEntity;
import com.shop.mapper.TradeCouponMapper;
import com.shop.pojo.TradeCoupon;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.UnsupportedEncodingException;

@Component
@RocketMQMessageListener(topic = "${mq.order.topic}",//消息的topic ，配置文件获得
        consumerGroup = "${mq.order.consumer.group.name}",//消费者的组，配置文件获得
        messageModel = MessageModel.BROADCASTING)//消息接收的类型，广播
public class CancelMQListener implements RocketMQListener<MessageExt> {//Message的子类

    @Autowired
    private TradeCouponMapper couponMapper;

    @Override
    public void onMessage(MessageExt message) {

        try {
            //1. 解析消息内容，将字节数组按照utf-8转化成字符串
            String body = new String(message.getBody(), "UTF-8");
            //利用json，将字符串变成指定类型（MQEntity）的对象
            MQEntity mqEntity = JSON.parseObject(body, MQEntity.class);
            System.out.println(("接收到消息"));
            if(mqEntity.getCouponId()!=null){
                //2. 查询优惠券信息
                TradeCoupon coupon = couponMapper.selectByPrimaryKey(mqEntity.getCouponId());
                //3.更改优惠券状态
                coupon.setUsedTime(null);
                coupon.setIsUsed(ShopCode.SHOP_COUPON_UNUSED.getCode());
                coupon.setOrderId(null);
                couponMapper.updateByPrimaryKey(coupon);
            }
            System.out.println(("回退优惠券成功"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            System.out.println(("回退优惠券失败"));
        }
    }

}
