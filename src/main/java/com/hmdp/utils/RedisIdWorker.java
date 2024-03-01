package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 为了增加ID的安全性，我们可以不直接使用Redis自增的数值，而是拼接一些其它信息：
 * 组合部分：
 *  1.符号位：1bit，永远为0
 *  2.时间戳：31bit，以秒为单位，可以使用69年
 *  3.序列号：32bit，秒内的计数器，支持每秒产生2^32个不同ID
 */
@Component
public class RedisIdWorker {

    // 起始时间： LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0,0);
    //          long second = time.toEpochSecond(ZoneOffset.UTC);
    //          System.out.println(second);
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    //序列号位数
    private static final int COUNT_BITS = 32;

    @Resource
    StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix) {

        // 1. 生成时间戳: 当前时间-初始时间  （同一秒钟的时间戳是一样的）
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        // redis自增长的上限是2的64次方，64位的，不能一直用同一个数据来自增来形成数据（不能只有一个key）
        // 就算是同一个业务也不能一直用一个key的来做自增长来生成序列号
        // 这里采用一天一个key，不仅不用担心超过上限，也方便日后做每日订单的统计
        // 2.1 获取当前日期，精确到天 : eg 20221124
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2 自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr" + keyPrefix + ":" + date);

        // 3. 拼成返回
        // 位运算：将时间戳左移动32位，再填入序列号(或运算：原本是0还是0，原本是1还是1)
        return timestamp << COUNT_BITS | count;
    }

}
