package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData<T> {
    private LocalDateTime expireTime;  //逻辑过期时间
    private T data;  //对应的数据，相当于本类对原数据进行一次封装
}
