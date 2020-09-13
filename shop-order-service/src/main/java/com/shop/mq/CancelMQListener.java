package com.shop.mq;

import com.alibaba.fastjson.JSON;
import com.constant.ShopCode;
import com.entity.MQEntity;
import com.shop.mapper.TradeOrderMapper;
import com.shop.pojo.TradeOrder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.UnsupportedEncodingException;


@Slf4j
@Component
@RocketMQMessageListener(topic = "${mq.order.topic}",//消息的topic
        consumerGroup = "${mq.order.consumer.group.name}",//消费者的组
        messageModel = MessageModel.BROADCASTING )//消费消息的类型，广播
public class CancelMQListener implements RocketMQListener<MessageExt>{

    @Autowired
    private TradeOrderMapper orderMapper;

    @Override
    public void onMessage(MessageExt messageExt) {

        try {
            //1. 解析消息内容
            String body = new String(messageExt.getBody(),"UTF-8");
            MQEntity mqEntity = JSON.parseObject(body, MQEntity.class);
            System.out.println(("接受消息成功"));
            //2. 查询订单
            TradeOrder order = orderMapper.selectByPrimaryKey(mqEntity.getOrderId());
            //3.更新订单状态为取消，并将数据更新到订单表
            order.setOrderStatus(ShopCode.SHOP_ORDER_CANCEL.getCode());
            orderMapper.updateByPrimaryKey(order);
            System.out.println(("订单状态设置为取消"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            System.out.println(("订单取消失败"));
        }
    }
}
