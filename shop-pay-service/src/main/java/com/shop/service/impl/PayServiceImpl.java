package com.shop.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import com.api.IPayService;
import com.constant.ShopCode;
import com.entity.Result;
import com.exception.CastException;
import com.shop.mapper.TradeMqProducerTempMapper;
import com.shop.mapper.TradePayMapper;
import com.shop.pojo.TradeMqProducerTemp;
import com.shop.pojo.TradePay;
import com.shop.pojo.TradePayExample;
import com.utils.IDWorker;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.Date;

@Component
@Service(interfaceClass = IPayService.class)
public class PayServiceImpl implements IPayService{

    @Autowired
    private TradePayMapper tradePayMapper;

    @Autowired
    private TradeMqProducerTempMapper mqProducerTempMapper;

    @Autowired
    private ThreadPoolTaskExecutor threadPoolTaskExecutor;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private IDWorker idWorker;

    @Value("${rocketmq.producer.group}")//从配置文件中获取到的值，防止硬编码
    private String groupName;

    @Value("${mq.topic}")//从配置文件中获取到的值，防止硬编码
    private String topic;

    @Value("${mq.pay.tag}")
    private String tag;

    //订单支付信息的对象，这个对象是从前台页面传过来的
    //判断订单是否支付，如果已经支付过了，直接抛出异常，没有支付的话，将没有支付的信息保存到数据库中
    public Result createPayment(TradePay tradePay) {
        if(tradePay==null || tradePay.getOrderId()==null){
            CastException.cast(ShopCode.SHOP_REQUEST_PARAMETER_VALID);
        }
        //1.判断订单支付状态
        TradePayExample example = new TradePayExample();
        TradePayExample.Criteria criteria = example.createCriteria();
        criteria.andOrderIdEqualTo(tradePay.getOrderId());
        //设置已经支付的状态，保存进TradePayExample
        criteria.andIsPaidEqualTo(ShopCode.SHOP_PAYMENT_IS_PAID.getCode());
        //根据已经支付的状态，查询已经支付的订单，返回已经支付的订单数量
        int r = tradePayMapper.countByExample(example);
        if(r>0){//大于0说明已经支付过了，直接抛出异常
            CastException.cast(ShopCode.SHOP_PAYMENT_IS_PAID);
        }
        //2.设置订单的状态为未支付
        tradePay.setIsPaid(ShopCode.SHOP_ORDER_PAY_STATUS_NO_PAY.getCode());
        //3.保存支付编号
        tradePay.setPayId(idWorker.nextId());
        //将信息保存到trade_pay表中，这个表保存的就是订单的支付信息
        tradePayMapper.insert(tradePay);
        return new Result(ShopCode.SHOP_SUCCESS.getSuccess(),ShopCode.SHOP_SUCCESS.getMessage());
    }

    //如果支付成功，则调用这个方法，将支付成功的消息发送到mq的队列中去，若信息发送失败，则会保存到数据库
    public Result callbackPayment(TradePay tradePay) throws InterruptedException, RemotingException, MQClientException, MQBrokerException {
        System.out.println(("支付回调"));
        //1. 判断用户支付状态
        if(tradePay.getIsPaid().intValue()==ShopCode.SHOP_ORDER_PAY_STATUS_IS_PAY.getCode().intValue()){
            //2. 更新支付订单状态为已支付
            Long payId = tradePay.getPayId();
            TradePay pay = tradePayMapper.selectByPrimaryKey(payId);
            //判断支付订单是否存在
            if(pay==null){
                CastException.cast(ShopCode.SHOP_PAYMENT_NOT_FOUND);
            }
            pay.setIsPaid(ShopCode.SHOP_ORDER_PAY_STATUS_IS_PAY.getCode());
            int r = tradePayMapper.updateByPrimaryKeySelective(pay);
            System.out.println(("支付订单状态改为已支付"));
            if(r==1){
                //3. 创建支付成功的消息
                TradeMqProducerTemp tradeMqProducerTemp = new TradeMqProducerTemp();
                tradeMqProducerTemp.setId(String.valueOf(idWorker.nextId()));
                tradeMqProducerTemp.setGroupName(groupName);
                tradeMqProducerTemp.setMsgTopic(topic);
                tradeMqProducerTemp.setMsgTag(tag);
                tradeMqProducerTemp.setMsgKey(String.valueOf(tradePay.getPayId()));
                tradeMqProducerTemp.setMsgBody(JSON.toJSONString(tradePay));
                tradeMqProducerTemp.setCreateTime(new Date());
                //4. 将消息持久化数据库，当消息发送到mq队列之后，再从数据库中将消息删除
                mqProducerTempMapper.insert(tradeMqProducerTemp);
                System.out.println(("将支付成功消息持久化到数据库"));
                //在线程池中进行处理，因为消息发送之后，需要等待mq返回的处理结果，如果处理时间过长
                //则会导致主线程阻塞的时间过长，所以就开启一个线程池，来进行等待mq返回的处理结果
                threadPoolTaskExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        //5. 发送消息到MQ
                        SendResult result = null;
                        try {//阻塞，等待
                            result = sendMessage(topic, tag, String.valueOf(tradePay.getPayId()), JSON.toJSONString(tradePay));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }//消息成功发送到mq队列中，则删除刚刚数据库中保存的消息
                        if(result.getSendStatus().equals(SendStatus.SEND_OK)){
                            System.out.println(("消息发送成功"));
                            //6. 等待发送结果,如果MQ接受到消息,删除发送成功的消息
                            mqProducerTempMapper.deleteByPrimaryKey(tradeMqProducerTemp.getId());
                            System.out.println(("持久化到数据库的消息删除"));
                        }
                    }
                });
            }
            return new Result(ShopCode.SHOP_SUCCESS.getSuccess(),ShopCode.SHOP_SUCCESS.getMessage());
        }else{
            CastException.cast(ShopCode.SHOP_PAYMENT_PAY_ERROR);
            return new Result(ShopCode.SHOP_FAIL.getSuccess(),ShopCode.SHOP_FAIL.getMessage());
        }
    }

    /**
     * 发送支付成功消息
     * @param topic
     * @param tag
     * @param key
     * @param body
     */
    private SendResult sendMessage(String topic, String tag, String key, String body) throws InterruptedException, RemotingException, MQClientException, MQBrokerException {
        if(StringUtils.isEmpty(topic)){
            CastException.cast(ShopCode.SHOP_MQ_TOPIC_IS_EMPTY);
        }
        if(StringUtils.isEmpty(body)){
            CastException.cast(ShopCode.SHOP_MQ_MESSAGE_BODY_IS_EMPTY);
        }
        Message message = new Message(topic,tag,key,body.getBytes());
        //将消息发送到mq的队列中
        SendResult sendResult = rocketMQTemplate.getProducer().send(message);
        return sendResult;
    }
}
