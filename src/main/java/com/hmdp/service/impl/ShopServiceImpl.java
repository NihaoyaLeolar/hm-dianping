package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {

        // 解决了缓存穿透的查询
        // Shop shop = queryWithPassThrough(id);

        // 利用互斥锁解决缓存击穿问题(依旧包括解决了缓存穿透)
        Shop shop = queryWithMutex(id);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;

        // 1. 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断缓存是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 命中，缓存存在，反序列化，然后直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 避免缓存穿透，如果不是blank，命中的可能是我们存入过的空值
        if (shopJson != null) {
            return null;
        }

        // 4. 缓存不存在，开始实现缓存重建
        // 4.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;

        try {
            boolean isLock = tryLock(lockKey);

            // 4.2 判断取锁是否成功
            if (!isLock) {
                // 4.3 失败：休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 4.4 获取锁成功，做DoubleCheck，如果存在则无需重建缓存
            // 4.4.1. 再次从redis中查询商铺缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 4.4.2. 判断缓存是否存在
            if (StrUtil.isNotBlank(shopJson)) {
                // 4.4.3. 命中，缓存已经存在，反序列化，然后直接返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 4.5 获取锁成功且还未建立缓存：查询数据库，将数据写入redis
            shop = getById(id);
            Thread.sleep(200); //模拟缓存重建的延时较长的问题

            // 5. 还不存在，返回错误
            if (shop == null) {
                // 避免缓存穿透，当缓存和数据库都不存在时，写入空值，ttl短一点只给2分钟
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 6. 存在，把数据写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);


        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放互斥锁
            unLock(lockKey);
        }

        // 8. 返回
        return shop;
    }

    // 封装解决了缓存穿透问题的商户查询
    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;

        // 1. 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断缓存是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，反序列化，然后直接返回
            return JSONUtil.toBean(shopJson, Shop.class);

        }

        //避免缓存穿透，如果不是blank，命中的可能是我们存入过的空值
        if (shopJson != null) {
            return null;
        }

        // 4. 不存在，根据id查询数据库
        Shop shop = getById(id);

        // 5. 还不存在，返回错误
        if (shop == null) {
            // 避免缓存穿透，当缓存和数据库都不存在时，写入空值，ttl短一点只给2分钟
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6. 存在，把数据写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7. 返回
        return shop;
    }

    private boolean tryLock(String key) {
        //10s的锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // return flag;  //fLag自动拆箱可能产生空指针引用问题
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    // 对于热点key，需要定期提前写入redis
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1. 查询数据
        Shop shop = getById(id);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional  //设置这个方法为一个事务，控制本方法执行的原子性
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        // 1. 先更新数据库
        updateById(shop);
        // 2. 再删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
    }


}
