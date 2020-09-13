package com.shop.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.api.IUserService;
import com.entity.Result;
import com.constant.ShopCode;
import com.exception.CastException;
import com.shop.mapper.TradeUserMapper;
import com.shop.mapper.TradeUserMoneyLogMapper;
import com.shop.pojo.TradeUser;
import com.shop.pojo.TradeUserMoneyLog;
import com.shop.pojo.TradeUserMoneyLogExample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Date;

@Component
@Service(interfaceClass = IUserService.class)
public class UserServiceImpl implements IUserService{

    @Autowired
    private TradeUserMapper userMapper;

    @Autowired
    private TradeUserMoneyLogMapper userMoneyLogMapper;

    @Override
    public TradeUser findOne(Long userId) {
        if(userId==null){
            CastException.cast(ShopCode.SHOP_REQUEST_PARAMETER_VALID);
        }
        return userMapper.selectByPrimaryKey(userId);
    }

    @Override
    public Result updateMoneyPaid(TradeUserMoneyLog userMoneyLog) {
        //1.校验参数是否合法
        if(userMoneyLog==null ||
                userMoneyLog.getUserId()==null ||
                userMoneyLog.getOrderId()==null ||
                userMoneyLog.getUseMoney()==null||
                userMoneyLog.getUseMoney().compareTo(BigDecimal.ZERO)<=0){
            CastException.cast(ShopCode.SHOP_REQUEST_PARAMETER_VALID);
        }

        //2.查询订单余额使用日志，封装了日志中的信息
        TradeUserMoneyLogExample userMoneyLogExample = new TradeUserMoneyLogExample();
        //创建Criteria对象，并将Criteria对象放入集合中后，返回Criteria对象
        TradeUserMoneyLogExample.Criteria criteria = userMoneyLogExample.createCriteria();
        //将订单Id放入Criterion对象
        criteria.andOrderIdEqualTo(userMoneyLog.getOrderId());
        //将用户id放入Criterion对象
        criteria.andUserIdEqualTo(userMoneyLog.getUserId());
        //从trade_user_money_log表查询用户使用余额（订单余额？）的数量
        //如果使用了余额（订单余额？）代表已付款？
        int r = userMoneyLogMapper.countByExample(userMoneyLogExample);
        //查询user_id, order_id, money_log_type, use_money, create_time等信息
        TradeUser tradeUser = userMapper.selectByPrimaryKey(userMoneyLog.getUserId());

        //3.扣减余额...
        if(userMoneyLog.getMoneyLogType().intValue()==ShopCode.SHOP_USER_MONEY_PAID.getCode().intValue()){
            if(r>0){
                //已经付款
                CastException.cast(ShopCode.SHOP_ORDER_PAY_STATUS_IS_PAY);
            }
            //扣减用户的余额（数据库中的余额？并不是订单中的余额？），重新设置用户状态
            tradeUser.setUserMoney(new BigDecimal(tradeUser.getUserMoney()).subtract(userMoneyLog.getUseMoney()).longValue());
            //扣减完余额之后，更新用户状态（更新用户所有状态，并不只是余额状态？）
            userMapper.updateByPrimaryKey(tradeUser);
        }
        //4.回退余额... 先判断userMoneyLog中余额的使用状态？是已付款还是未付款？
        if(userMoneyLog.getMoneyLogType().intValue()==ShopCode.SHOP_USER_MONEY_REFUND.getCode().intValue()){
            if(r<0){
                //如果没有支付,则不能回退余额
                CastException.cast(ShopCode.SHOP_ORDER_PAY_STATUS_NO_PAY);
            }
            //防止多次退款
            TradeUserMoneyLogExample userMoneyLogExample2 = new TradeUserMoneyLogExample();
            TradeUserMoneyLogExample.Criteria criteria1 = userMoneyLogExample2.createCriteria();
            criteria1.andOrderIdEqualTo(userMoneyLog.getOrderId());
            criteria1.andUserIdEqualTo(userMoneyLog.getUserId());
            criteria1.andMoneyLogTypeEqualTo(ShopCode.SHOP_USER_MONEY_REFUND.getCode());
            //查询退款状态，已经退过款还是没有退款
            int r2 = userMoneyLogMapper.countByExample(userMoneyLogExample2);
            if(r2>0){
                CastException.cast(ShopCode.SHOP_USER_MONEY_REFUND_ALREADY);
            }
            //退款
            tradeUser.setUserMoney(new BigDecimal(tradeUser.getUserMoney()).add(userMoneyLog.getUseMoney()).longValue());
            //更新用户状态
            userMapper.updateByPrimaryKey(tradeUser);
        }
        //5.记录订单余额使用日志
        userMoneyLog.setCreateTime(new Date());
        //将日志信息放入数据库
        userMoneyLogMapper.insert(userMoneyLog);
        //返回操作的结果，封装进Result对象
        return new Result(ShopCode.SHOP_SUCCESS.getSuccess(),ShopCode.SHOP_SUCCESS.getMessage());
    }
}
