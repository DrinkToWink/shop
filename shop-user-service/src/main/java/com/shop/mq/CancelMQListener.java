package com.shop.mq;

import com.alibaba.fastjson.JSON;
import com.api.IUserService;
import com.constant.ShopCode;
import com.entity.MQEntity;
import com.shop.pojo.TradeUserMoneyLog;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;

@Component
@RocketMQMessageListener(topic = "${mq.order.topic}",//消息的topic
        consumerGroup = "${mq.order.consumer.group.name}",//消费者组
        messageModel = MessageModel.BROADCASTING )//广播
public class CancelMQListener implements RocketMQListener<MessageExt>{


    @Autowired
    private IUserService userService;

    @Override
    public void onMessage(MessageExt messageExt) {

        try {
            //1.解析消息,将消息通过utf-8的方式解码成字符串
            String body = new String(messageExt.getBody(), "UTF-8");
            //利用json将字符串，变成MQEntity对象
            MQEntity mqEntity = JSON.parseObject(body, MQEntity.class);
            System.out.println(("接收到消息"));
            if(mqEntity.getUserMoney()!=null && mqEntity.getUserMoney().compareTo(BigDecimal.ZERO)>0){
                //2.调用业务层,进行余额修改
                TradeUserMoneyLog userMoneyLog = new TradeUserMoneyLog();
                userMoneyLog.setUseMoney(mqEntity.getUserMoney());
                //将余额的类型（余额回退），传入业务层，
                userMoneyLog.setMoneyLogType(ShopCode.SHOP_USER_MONEY_REFUND.getCode());
                userMoneyLog.setUserId(mqEntity.getUserId());
                userMoneyLog.setOrderId(mqEntity.getOrderId());
                userService.updateMoneyPaid(userMoneyLog);
                System.out.println(("余额回退成功"));
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            System.out.println(("余额回退失败"));
        }

    }
}
