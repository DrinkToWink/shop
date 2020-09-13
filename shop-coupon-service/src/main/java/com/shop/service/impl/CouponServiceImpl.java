package com.shop.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.api.ICouponService;
import com.constant.ShopCode;
import com.entity.Result;
import com.exception.CastException;
import com.shop.mapper.TradeCouponMapper;
import com.shop.pojo.TradeCoupon;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Service(interfaceClass = ICouponService.class)
public class CouponServiceImpl implements ICouponService{

    @Autowired
    private TradeCouponMapper couponMapper;

    @Override
    public TradeCoupon findOne(Long coupouId) {
        if(coupouId==null){
            CastException.cast(ShopCode.SHOP_REQUEST_PARAMETER_VALID);
        }
        //若coupouId不为空，则查询出TradeCoupon（优惠券）对象
        return couponMapper.selectByPrimaryKey(coupouId);
    }

    //更新优惠券TradeCoupon对象，并返回结果
    public Result updateCouponStatus(TradeCoupon coupon) {
        if(coupon==null||coupon.getCouponId()==null){
            CastException.cast(ShopCode.SHOP_REQUEST_PARAMETER_VALID);
        }
        //更新优惠券状态
        couponMapper.updateByPrimaryKey(coupon);
        return new Result(ShopCode.SHOP_SUCCESS.getSuccess(),ShopCode.SHOP_SUCCESS.getMessage());
    }
}
