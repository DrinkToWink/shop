package com.shop.mq;

import com.alibaba.fastjson.JSON;
import com.constant.ShopCode;
import com.entity.MQEntity;
import com.shop.mapper.TradeGoodsMapper;
import com.shop.mapper.TradeGoodsNumberLogMapper;
import com.shop.mapper.TradeMqConsumerLogMapper;
import com.shop.pojo.*;
import com.shop.pojo.TradeGoods;
import com.shop.pojo.TradeMqConsumerLog;
import com.shop.pojo.TradeMqConsumerLogExample;
import com.shop.pojo.TradeMqConsumerLogKey;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Date;

@Component
@RocketMQMessageListener(topic = "${mq.order.topic}",//消息的topic
        consumerGroup = "${mq.order.consumer.group.name}",//消费者的组
        messageModel = MessageModel.BROADCASTING )//消息类型，广播
public class CancelMQListener implements RocketMQListener<MessageExt>{


    @Value("${mq.order.consumer.group.name}")
    private String groupName;

    @Autowired
    private TradeGoodsMapper goodsMapper;

    @Autowired
    private TradeMqConsumerLogMapper mqConsumerLogMapper;

    @Autowired
    private TradeGoodsNumberLogMapper goodsNumberLogMapper;

    @Override
    public void onMessage(MessageExt messageExt) {
        String msgId=null;
        String tags=null;
        String keys=null;
        String body=null;
        try {
            //1. 解析消息内容
            msgId = messageExt.getMsgId();
            tags= messageExt.getTags();
            keys= messageExt.getKeys();
            body= new String(messageExt.getBody(),"UTF-8");

            System.out.println(("接受消息成功"));

            //2. 查询消息消费记录
            TradeMqConsumerLogKey primaryKey = new TradeMqConsumerLogKey();
            primaryKey.setMsgTag(tags);
            primaryKey.setMsgKey(keys);
            primaryKey.setGroupName(groupName);
            //根据TradeMqConsumerLogkey对象，将消息的状态从trade_mq_consumer_log表中查询出来
            //把查询出来的信息封装进TradeMqConsumerLog对象中
            //TradeMqConsumerLog记录了消息消费的状态，根据这个进行下一步的处理
            TradeMqConsumerLog mqConsumerLog = mqConsumerLogMapper.selectByPrimaryKey(primaryKey);

            if(mqConsumerLog!=null){
                //3. 判断如果消费过...
                //3.1 获得消息处理状态
                Integer status = mqConsumerLog.getConsumerStatus();
                //消息被处理成功...返回
                if(ShopCode.SHOP_MQ_MESSAGE_STATUS_SUCCESS.getCode().intValue()==status.intValue()){
                    System.out.println(("消息:" + msgId + ",已经处理过"));
                    return;
                }

                //消息正在处理...返回
                if(ShopCode.SHOP_MQ_MESSAGE_STATUS_PROCESSING.getCode().intValue()==status.intValue()){
                    System.out.println(("消息:" + msgId + ",正在处理"));
                    return;
                }

                //消息处理失败
                if(ShopCode.SHOP_MQ_MESSAGE_STATUS_FAIL.getCode().intValue()==status.intValue()){
                    //获得消息处理次数
                    Integer times = mqConsumerLog.getConsumerTimes();
                    if(times>3){
                        System.out.println(("消息:" + msgId + ",消息处理超过3次,不能再进行处理了"));
                        return;
                    }
                    //将消息状态设置为正在处理
                    mqConsumerLog.setConsumerStatus(ShopCode.SHOP_MQ_MESSAGE_STATUS_PROCESSING.getCode());

                    //使用数据库乐观锁进行消息的处理（乐观锁在哪？没看出来...）
                    //将消息的状态进行设置并保存在trade_mq_consumer_log表中
                    TradeMqConsumerLogExample example = new TradeMqConsumerLogExample();
                    //创建Criteria对象的实例，并返回，oredCriteria集合中也保存了一个
                    TradeMqConsumerLogExample.Criteria criteria = example.createCriteria();
                    //利用Criteria对象，将信息封装进Criteria对象中
                    criteria.andMsgTagEqualTo(mqConsumerLog.getMsgTag());
                    criteria.andMsgKeyEqualTo(mqConsumerLog.getMsgKey());
                    criteria.andGroupNameEqualTo(groupName);
                    criteria.andConsumerTimesEqualTo(mqConsumerLog.getConsumerTimes());
                    //将TradeMqConsumerLog对象和TradeMqConsumerLogExample对象传入，进行数据库的修改
                    //更新trade_mq_consumer_log表的信息，这里乐观锁的机制藏在了sql语句中
                    int r = mqConsumerLogMapper.updateByExampleSelective(mqConsumerLog, example);
                    if(r<=0){
                        //未修改成功,其他线程并发修改
                        System.out.println(("并发修改,稍后处理"));
                    }
                }
            }else{
                //4. 判断如果没有消费过...
                mqConsumerLog = new TradeMqConsumerLog();
                mqConsumerLog.setMsgTag(tags);
                mqConsumerLog.setMsgKey(keys);
                mqConsumerLog.setGroupName(groupName);
                mqConsumerLog.setConsumerStatus(ShopCode.SHOP_MQ_MESSAGE_STATUS_PROCESSING.getCode());
                mqConsumerLog.setMsgBody(body);
                mqConsumerLog.setMsgId(msgId);
                mqConsumerLog.setConsumerTimes(0);
                //将消息处理信息添加到数据库
                mqConsumerLogMapper.insert(mqConsumerLog);
            }

            //5. 回退库存，将消息对象（json类型的字符串）转化为MQEntity对象，消息对象中封装了订单信息
            MQEntity mqEntity = JSON.parseObject(body, MQEntity.class);
            //根据订单信息，局偶去商品id，mqEntity对象中有订单信息
            Long goodsId = mqEntity.getGoodsId();
            //根据订单id获取商品对象
            TradeGoods goods = goodsMapper.selectByPrimaryKey(goodsId);
            //获取没有消费前的商品数量=现在的商品数量+消费的商品数量
            goods.setGoodsNumber(goods.getGoodsNumber()+mqEntity.getGoodsNum());
            //将商品信息，重新插入数据库
            goodsMapper.updateByPrimaryKey(goods);

            //6. 将消息的处理状态改为成功
            mqConsumerLog.setConsumerStatus(ShopCode.SHOP_MQ_MESSAGE_STATUS_SUCCESS.getCode());
            mqConsumerLog.setConsumerTimestamp(new Date());
            //将信息更新到trade_mq_consumer_log表中
            mqConsumerLogMapper.updateByPrimaryKey(mqConsumerLog);
            System.out.println(("回退库存成功"));
        } catch (Exception e) {
            e.printStackTrace();
            TradeMqConsumerLogKey primaryKey = new TradeMqConsumerLogKey();
            //将信息封装进TradeMqConsumerLogkey对象
            primaryKey.setMsgTag(tags);
            primaryKey.setMsgKey(keys);
            primaryKey.setGroupName(groupName);
            //根据TradeMqConsumerLogkey对象的信息，查询出TradeMqConsumerLog对象
            //里面封装了操作商品回退的日志信息
            TradeMqConsumerLog mqConsumerLog = mqConsumerLogMapper.selectByPrimaryKey(primaryKey);
            if(mqConsumerLog==null){//为空，代表第一次处理就失败了
                //数据库未有记录
                mqConsumerLog = new TradeMqConsumerLog();
                mqConsumerLog.setMsgTag(tags);
                mqConsumerLog.setMsgKey(keys);
                mqConsumerLog.setGroupName(groupName);
                mqConsumerLog.setConsumerStatus(ShopCode.SHOP_MQ_MESSAGE_STATUS_FAIL.getCode());
                mqConsumerLog.setMsgBody(body);
                mqConsumerLog.setMsgId(msgId);
                //将修改失败的次数设置为1，因为这是第一次进行处理，失败了
                mqConsumerLog.setConsumerTimes(1);
                //将信息保存进商品回退的日志信息表
                mqConsumerLogMapper.insert(mqConsumerLog);
            }else{//不是第一次操作，就将商品操作失败的次数加上1
                mqConsumerLog.setConsumerTimes(mqConsumerLog.getConsumerTimes()+1);
                //将操作商品回退的日志信息保存进trade_mq_consumer_log表
                mqConsumerLogMapper.updateByPrimaryKeySelective(mqConsumerLog);
            }
        }

    }
}
