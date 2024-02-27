package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    //本类对象不是由Spring创建的，是我们手动创建的，所以这里不能用@Autowire或@Resource来依赖注入
    //所以就是哪里用了哪里注入，通过在我们自己手动创建时，调用构造函数注入
    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 获取请求头中的token（前端收到token会放到请求头中）
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {  // 采用工具类判空，可能是null也可能无值
            // 这里其实在之前修改拦截器时就要这么写，现在这次测试才发现问题，怪不得更新商户需要token
            // 之前那么写，对于所有的请求都会需要token了
            // 这个拦截器只是做token的ttl更新，对于没有登录验证的，都是直接放行，不做任何拦截的
            return true;
        }
        // 2. 基于token获取到redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        // 3. 判断用户是否存在
        if(userMap.isEmpty()) {
            //4. 不存在，不拦截，直接放行（ThreadLocal中不会存下user）
            return true;
        }
        // 5. 将查询到的HashMap数据，再转为userDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 6. 存在，将用户信息保存在ThreadLocal（为了保证线程安全，这里没太懂）
        UserHolder.saveUser(userDTO);

        // 7. 刷新token有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户，避免内存泄露
        UserHolder.removeUser();
    }
}
