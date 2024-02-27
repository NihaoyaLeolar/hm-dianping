package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    // RedisTemplate 处理的值为String，我们的List中不是String，所以需要对List中的东西序列化存储
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result getTypeList() {
        String key = RedisConstants.TYPE_LIST_KEY;

        //从redis中查询缓存
        List<String> listFromRedisWithJson = stringRedisTemplate.opsForList().range(key, 0, -1);

        //没有缓存
        if (listFromRedisWithJson == null || listFromRedisWithJson.isEmpty()) {
            //从数据库读取
            List<ShopType> listFromMysql = query().orderByAsc("sort").list();
            //数据库中也不存在
            if(listFromMysql == null || listFromMysql.isEmpty()) {
               return Result.fail("商户类型信息不存在");
            }
            //存入redis
            stringRedisTemplate.opsForList().rightPushAll(key, toJson(listFromMysql));
            return Result.ok(listFromMysql);
        }

        //反序列化并返回
        return Result.ok(toList(listFromRedisWithJson));
    }

    private List<String> toJson(List<ShopType> shopTypeList) {
        List<String> stringList = new ArrayList<>();
        for(ShopType type : shopTypeList) {
            String jsonStr = JSONUtil.toJsonStr(type);
            stringList.add(jsonStr);
        }
        return stringList;
    }

    private List<ShopType> toList(List<String> list) {
        List<ShopType> shopTypeList = new ArrayList<>();
        for(String json : list) {
            shopTypeList.add(JSONUtil.toBean(json, ShopType.class));
        }
        return shopTypeList;
    }
}
