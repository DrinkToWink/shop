package com.shop.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.api.IGoodsService;
import com.constant.ShopCode;
import com.entity.Result;
import com.exception.CastException;
import com.shop.mapper.TradeGoodsMapper;
import com.shop.mapper.TradeGoodsNumberLogMapper;
import com.shop.pojo.TradeGoods;
import com.shop.pojo.TradeGoodsNumberLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@Service(interfaceClass = IGoodsService.class)
public class GoodsServiceImpl implements IGoodsService {

    @Autowired
    private TradeGoodsMapper goodsMapper;

    @Autowired
    private TradeGoodsNumberLogMapper goodsNumberLogMapper;

    //传入订单中商品的id，进行商品对象的查询
    public TradeGoods findOne(Long goodsId) {
        //判断goodsId是否为空，若为空，直接抛出异常提示用户
        if (goodsId == null) {
            CastException.cast(ShopCode.SHOP_REQUEST_PARAMETER_VALID);
        }
        //不为空，则从数据库中查询出商品对象，返回商品对象
        return goodsMapper.selectByPrimaryKey(goodsId);
    }

    //传入封装了信息的对象goodsNumberLog，进行商品库存扣减的操作，远程dubbo传递的参数
    public Result reduceGoodsNum(TradeGoodsNumberLog goodsNumberLog) {
        if (goodsNumberLog == null ||
                goodsNumberLog.getGoodsNumber() == null ||
                goodsNumberLog.getOrderId() == null ||
                goodsNumberLog.getGoodsNumber() == null ||
                goodsNumberLog.getGoodsNumber().intValue() <= 0) {
            CastException.cast(ShopCode.SHOP_REQUEST_PARAMETER_VALID);
        }
        //根据商品id查询出商品对象
        TradeGoods goods = goodsMapper.selectByPrimaryKey(goodsNumberLog.getGoodsId());
        //判断商品的数量是不是小于当前要购买的数量
        if(goods.getGoodsNumber()<goodsNumberLog.getGoodsNumber()){
            //库存不足
            CastException.cast(ShopCode.SHOP_GOODS_NUM_NOT_ENOUGH);
        }
        //减库存，设置当前商品的数量
        goods.setGoodsNumber(goods.getGoodsNumber()-goodsNumberLog.getGoodsNumber());

        //传入当前商品对象，将商品数量进行更新
        goodsMapper.updateByPrimaryKey(goods);

        //记录商品扣减的日志，
        goodsNumberLog.setGoodsNumber(-(goodsNumberLog.getGoodsNumber()));

        //记录当前日期的日志
        goodsNumberLog.setLogTime(new Date());

        //将商品扣减的日志信息插入trade_goods_number_log表
        goodsNumberLogMapper.insert(goodsNumberLog);

        //将操作的结果，通过远程dubbo传递给调用者
        return new Result(ShopCode.SHOP_SUCCESS.getSuccess(),ShopCode.SHOP_SUCCESS.getMessage());
    }



}
