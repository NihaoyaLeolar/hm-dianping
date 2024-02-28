package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        // Shop shop = queryWithMutex(id);

        /// 利用逻辑过期来解决缓存你击穿问题（通过预热，保证热点key都在redis中，不存在缓存穿透问题）
        Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }


    //为异步重载缓存创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;

        // 1. 从redis中查询商铺缓存
        String RedisDataJsonWithShop = stringRedisTemplate.opsForValue().get(key);

        // 2. 未命中，说明数据库中也不存在（因为预热阶段已经把热点key都放入了），直接返回null
        if (StrUtil.isBlank(RedisDataJsonWithShop)) {
            return null;
        }

        // 3. 命中，则查询逻辑时间是否过期
        // 这里不能这么写: JSONUtil.toBean(shopJson, RedisData<Shop>.class);
        // 因为传入泛型会被擦除，但是意思是这个意思。
        RedisData<Shop> shopRedisData = JSONUtil.toBean(
                RedisDataJsonWithShop,
                new TypeReference<RedisData<Shop>>() {
                },
                false
        );

        // 3.1 过期了
        if (shopRedisData.getExpireTime().isBefore(LocalDateTime.now())) {
            // 4. 需要缓存重建
            // 4.1 获取锁
            String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            // 4.1.1 如果取锁成功
            if (tryLock(lockKey)) {
                // 5. 做Double Check，
                RedisDataJsonWithShop = stringRedisTemplate.opsForValue().get(key);
                shopRedisData = JSONUtil.toBean(
                        RedisDataJsonWithShop,
                        new TypeReference<RedisData<Shop>>() {
                        },
                        false
                );
                // 6. 二次检查：如果数据已经被重载入redis（逻辑时间未过期），则直接返回
                if (shopRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
                    return shopRedisData.getData();
                }

                // 7. 异步地开启独立线程来重载缓存
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        //重建缓存。因为是异步的，所以不等待新鲜值，依旧在最下面返回旧值
                        this.saveShop2Redis(id, 20L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unLock(lockKey);
                    }
                });

            }
            // 4.1.2 如果取锁失败，直接在下面返回未更新数据
        }

        // 3.2 未过期，这是一次正常查询，直接返回数据
        return shopRedisData.getData();
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
    public Shop saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1. 查询数据
        Shop shop = getById(id);
        Thread.sleep(200);   //模拟缓存重建的延迟
        // 2. 封装逻辑过期时间
        RedisData<Shop> redisData = new RedisData<>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

        return shop;
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
