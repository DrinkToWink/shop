package com.shop.service;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.fastjson.JSON;
import com.api.ICouponService;
import com.api.IGoodsService;
import com.api.IOrderService;
import com.api.IUserService;
import com.constant.ShopCode;
import com.entity.MQEntity;
import com.entity.Result;
import com.exception.CastException;
import com.shop.mapper.TradeOrderMapper;
import com.shop.pojo.*;
import com.utils.IDWorker;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Date;

@Component
@Service(interfaceClass = IOrderService.class)
public class OrderServiceImpl implements IOrderService {

    @Reference
    private IGoodsService goodsService;

    @Reference
    private IUserService userService;

    @Reference
    private ICouponService couponService;

    @Value("${mq.order.topic}")//读取配置文件中这个属性的值
    private String topic;

    @Value("${mq.order.tag.cancel}")//读取配置文件中这个属性的值
    private String tag;

    @Autowired
    private TradeOrderMapper orderMapper;

    @Autowired
    private IDWorker idWorker;

    @Autowired//springboot集成rocketmq时，发送消息要用到这个对象
    private RocketMQTemplate rocketMQTemplate;

    @Override
    public Result confirmOrder(TradeOrder order) {
        //1.校验订单，这个订单是从前台传过来
        checkOrder(order);
        //2.生成预订单，订单状态为不可见
        Long orderId = savePreOrder(order);
        try {
            //3.扣减库存
            reduceGoodsNum(order);
            //4.扣减优惠券
            updateCouponStatus(order);
            //5.使用余额
            reduceMoneyPaid(order);
            //模拟异常抛出
            //CastException.cast(ShopCode.SHOP_FAIL);
            //6.确认订单
            updateOrderStatus(order);
            //7.返回成功状态
            return new Result(ShopCode.SHOP_SUCCESS.getSuccess(),ShopCode.SHOP_SUCCESS.getMessage());
        } catch (Exception e) {
            //1.确认订单失败,发送消息，将需要发送到mq队列中的信息封装到MQEntity对象中
            MQEntity mqEntity = new MQEntity();
            mqEntity.setOrderId(orderId);
            mqEntity.setUserId(order.getUserId());
            mqEntity.setUserMoney(order.getMoneyPaid());
            mqEntity.setGoodsId(order.getGoodsId());
            mqEntity.setGoodsNum(order.getGoodsNumber());
            mqEntity.setCouponId(order.getCouponId());
            //2.返回订单确认失败消息
            try {//将mqEntity对象利用json，变成json类型的字符串
                sendCancelOrder(topic,tag,order.getOrderId().toString(), JSON.toJSONString(mqEntity));
            } catch (Exception e1) {
                e1.printStackTrace();
            }
            return new Result(ShopCode.SHOP_FAIL.getSuccess(),ShopCode.SHOP_FAIL.getMessage());
        }
    }

    /**
     * 校验订单
     * @param order 订单对象，封装了订单的所有信息
     */
    private void checkOrder(TradeOrder order) {
        //1.校验订单是否存在,订单是从前台传过来的
        if (order == null) {
            //抛出异常，会打印出错误信息的状态码，进行提示
            CastException.cast(ShopCode.SHOP_ORDER_INVALID);
        }
        //2.通过dubbo远程调用商品服务，传入订单id，查出商品对象，校验订单中的商品是否存在
        TradeGoods goods = goodsService.findOne(order.getGoodsId());
        if (goods == null) {
            CastException.cast(ShopCode.SHOP_GOODS_NO_EXIST);
        }
        //3.通过dubbo远程调用用户服务，传入订单对象中的用户id，查出用户对象，校验下单用户是否存在
        TradeUser user = userService.findOne(order.getUserId());
        if (user == null) {
            CastException.cast(ShopCode.SHOP_USER_NO_EXIST);
        }
        //4.校验商品单价是否合法，订单中的商品单价是否等于商品的实际单价
        if (order.getGoodsPrice().compareTo(goods.getGoodsPrice()) != 0) {
            CastException.cast(ShopCode.SHOP_GOODS_PRICE_INVALID);
        }
        //5.校验订单商品数量是否合法，购买的商品数量是否大于库存
        if (order.getGoodsNumber() > goods.getGoodsNumber()) {
            CastException.cast(ShopCode.SHOP_GOODS_NUM_NOT_ENOUGH);
        }
        System.out.println(("校验订单通过"));
    }


    /**
     * 生成预订单
     * @param order 传入订单对象
     * @return
     */
    private Long savePreOrder(TradeOrder order) {
        //1. 设置订单状态为不可见
        order.setOrderStatus(ShopCode.SHOP_ORDER_NO_CONFIRM.getCode());
        //2. 生成并设置订单ID，使用的是雪花算法生成的
        long orderId = idWorker.nextId();
        order.setOrderId(orderId);
        //3. 核算订单运费，将订单的总价传入，返回运费的值
        BigDecimal shippingFee = calculateShippingFee(order.getOrderAmount());
        //判断订单中的运费跟核算的运费是否相等
        if(order.getShippingFee().compareTo(shippingFee)!=0){
            CastException.cast(ShopCode.SHOP_ORDER_SHIPPINGFEE_INVALID);
        }
        //4. 核算订单总金额是否合法，商品单价乘以商品数量
        BigDecimal orderAmount = order.getGoodsPrice().multiply(new BigDecimal(order.getGoodsNumber()));
        //在加上运费
        orderAmount.add(shippingFee);
        //订单总价是否等于商品价格加上运费
        if(order.getOrderAmount().compareTo(orderAmount)!=0){
            CastException.cast(ShopCode.SHOP_ORDERAMOUNT_INVALID);
        }
        //5.判断用户是否使用余额，首先获取订单的价格
        BigDecimal moneyPaid = order.getMoneyPaid();
        if(moneyPaid!=null){
            //5.1 订单中余额是否合法，疑问：订单中的余额是个啥玩意？
            int r = moneyPaid.compareTo(BigDecimal.ZERO);
            //返回值为-1，表示订单价格小于0，直接抛出异常
            if(r==-1){
                CastException.cast(ShopCode.SHOP_MONEY_PAID_LESS_ZERO);
            }
            //返回值为1，表示订单价格大于0
            if(r==1){
                //根据订单获取用户id，根据用户id查询出用户对象，远程dubbo调用查询
                TradeUser user = userService.findOne(order.getUserId());
                //判断订单的价格是否大于数据库中的用户余额，大于直接抛出异常
                if(moneyPaid.compareTo(new BigDecimal(user.getUserMoney()))==1){
                    CastException.cast(ShopCode.SHOP_MONEY_PAID_INVALID);
                }
            }
        }else{//就是订单价格为空
            order.setMoneyPaid(BigDecimal.ZERO);
        }
        //6.判断用户是否使用优惠券，先根据订单查询出订单中优惠券couponId的id
        Long couponId = order.getCouponId();
        //如果订单中的优惠券id不为空，说明使用了优惠券
        if(couponId!=null){
            //调用优惠券couponService，使用couponId查询出TradeCoupon对象
            TradeCoupon coupon = couponService.findOne(couponId);
            //6.1 判断优惠券是否存在
            if(coupon==null){
                CastException.cast(ShopCode.SHOP_COUPON_NO_EXIST);
            }
            //6.2 判断优惠券是否已经被使用
            if(coupon.getIsUsed().intValue()==ShopCode.SHOP_COUPON_ISUSED.getCode().intValue()){
                CastException.cast(ShopCode.SHOP_COUPON_ISUSED);
            }
            //将优惠券的金额，传入order对象
            order.setCouponPaid(coupon.getCouponPrice());
        }else{//为了后续处理的方便将优惠券的金额传入对象，不过这时金额为0
            order.setCouponPaid(BigDecimal.ZERO);
        }
        //7.核算订单支付金额    订单总金额-余额-优惠券金额
        BigDecimal payAmount = order.getOrderAmount().subtract(order.getMoneyPaid()).subtract(order.getCouponPaid());
        order.setPayAmount(payAmount);
        //8.设置下单时间
        order.setAddTime(new Date());
        //9.将订单数据，存入订单表，本地调用
        orderMapper.insert(order);
        //10.返回订单ID
        return orderId;
    }

    /**
     * 核算运费
     * @param orderAmount
     * @return 返回的是运费的值
     */
    private BigDecimal calculateShippingFee(BigDecimal orderAmount) {
        if(orderAmount.compareTo(new BigDecimal(100))==1){
            return BigDecimal.ZERO;
        }else{
            return new BigDecimal(10);
        }
    }

    /**
     * 扣减库存
     * @param order
     */
    private void reduceGoodsNum(TradeOrder order) {
        //这个类封装了商品扣减的日志信息
        TradeGoodsNumberLog goodsNumberLog = new TradeGoodsNumberLog();
        //将订单中的订单id、商品id、商品数量封装进TradeGoodsNumberLog对象
        goodsNumberLog.setOrderId(order.getOrderId());
        goodsNumberLog.setGoodsId(order.getGoodsId());
        goodsNumberLog.setGoodsNumber(order.getGoodsNumber());
        //dubbo远程调用goodsService服务，减少商品库存，传入goodsNumberLog，封装了需要用到的属性
        Result result = goodsService.reduceGoodsNum(goodsNumberLog);
        //判断是否扣减成功
        if(result.getSuccess().equals(ShopCode.SHOP_FAIL.getSuccess())){
            CastException.cast(ShopCode.SHOP_REDUCE_GOODS_NUM_FAIL);
        }
        System.out.println(("订单:" + order.getOrderId() + "扣减库存成功"));
    }

    /**
     * 扣减优惠券
     * @param order
     */
    private void updateCouponStatus(TradeOrder order) {
        //判断当前订单中使用的优惠券对象是否为空
        if(order.getCouponId()!=null){
            //dubbo远程调用couponService服务，查询出优惠券对象
            TradeCoupon coupon = couponService.findOne(order.getCouponId());
            //设置coupon对象的OrderId、IsUsed、UsedTime
            coupon.setOrderId(order.getOrderId());
            coupon.setIsUsed(ShopCode.SHOP_COUPON_ISUSED.getCode());
            coupon.setUsedTime(new Date());
            //dubbo远程调用couponService服务，传入coupon对象，更新优惠券状态
            //主要是将优惠券的状态设置为“已使用”，该优惠券所在的订单、优惠券使用的日期
            Result result =  couponService.updateCouponStatus(coupon);
            //将更新的结果返回，并进行判断
            if(result.getSuccess().equals(ShopCode.SHOP_FAIL.getSuccess())){
                CastException.cast(ShopCode.SHOP_COUPON_USE_FAIL);
            }
            //打印出消息
            System.out.println(("订单:" + order.getOrderId() + ",使用优惠券"));
        }
    }

    /**
     * 扣减余额
     * @param order
     */
    private void reduceMoneyPaid(TradeOrder order) {
        //订单中的余额不为空，且大于0
        if(order.getMoneyPaid()!=null && order.getMoneyPaid().compareTo(BigDecimal.ZERO)==1){
            //创建封装用户余额的日志信息的对象
            TradeUserMoneyLog userMoneyLog = new TradeUserMoneyLog();
            //使用日志对象封装OrderId、UserId、MoneyId
            userMoneyLog.setOrderId(order.getOrderId());
            userMoneyLog.setUserId(order.getUserId());
            userMoneyLog.setUseMoney(order.getMoneyPaid());
            userMoneyLog.setMoneyLogType(ShopCode.SHOP_USER_MONEY_PAID.getCode());
            //将封装了日志信息的userMoneyLog对象传进去，更新余额信息，返回更新结果
            Result result = userService.updateMoneyPaid(userMoneyLog);
            if(result.getSuccess().equals(ShopCode.SHOP_FAIL.getSuccess())){
                CastException.cast(ShopCode.SHOP_USER_MONEY_REDUCE_FAIL);
            }
            System.out.println(("订单:" + order.getOrderId() + ",扣减余额成功"));
        }
    }

    /**
     * 确认订单
     * @param order
     */
    private void updateOrderStatus(TradeOrder order) {
        //将订单状态设置为可见
        order.setOrderStatus(ShopCode.SHOP_ORDER_CONFIRM.getCode());
        //将支付状态设置为，已支付
        order.setPayStatus(ShopCode.SHOP_ORDER_PAY_STATUS_NO_PAY.getCode());
        //设置订单的确认时间为当前时间
        order.setConfirmTime(new Date());
        //更新订单状态，将信息保存在trade_order表中
        int r = orderMapper.updateByPrimaryKey(order);
        if(r<=0){
            CastException.cast(ShopCode.SHOP_ORDER_CONFIRM_FAIL);
        }
        System.out.println(("订单:" + order.getOrderId() + "确认订单成功"));
    }

    /**
     * 发送订单确认失败消息，相当于是生产者
     * @param topic 消息的topic
     * @param tag 消息的tag
     * @param keys
     * @param body 消息的具体内容
     */
    private void sendCancelOrder(String topic, String tag, String keys, String body) throws InterruptedException, RemotingException, MQClientException, MQBrokerException {
        //将json类型的字符串变成字节数组，默认编码应该是utf-8
        Message message = new Message(topic,tag,keys,body.getBytes());
        //将消息发送到rocketmq的队列中
        rocketMQTemplate.getProducer().send(message);
    }

}