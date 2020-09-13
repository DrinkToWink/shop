package com.shop.mapper;

import com.shop.pojo.TradeGoods;
import com.shop.pojo.TradeGoodsExample;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

//这是mybatis的注解，不是spring的注解，这个接口中不能定义同名的方法
//作用：猜测，该接口必须要跟相同名称的xml文件放在同一个包下
//作用：猜测，mybatis会为该接口生成（代理）对象，并将该对象放入spring的ioc容器中
@Mapper
public interface TradeGoodsMapper {
    int countByExample(TradeGoodsExample example);

    int deleteByExample(TradeGoodsExample example);

    int deleteByPrimaryKey(Long goodsId);

    int insert(TradeGoods record);

    int insertSelective(TradeGoods record);

    List<TradeGoods> selectByExample(TradeGoodsExample example);

    TradeGoods selectByPrimaryKey(Long goodsId);

    int updateByExampleSelective(@Param("record") TradeGoods record, @Param("example") TradeGoodsExample example);

    int updateByExample(@Param("record") TradeGoods record, @Param("example") TradeGoodsExample example);

    int updateByPrimaryKeySelective(TradeGoods record);

    int updateByPrimaryKey(TradeGoods record);
}