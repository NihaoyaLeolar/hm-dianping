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

//拦截器写完后，还需要配置拦截器：在config下新建MvcConfig类，实现WebMvcConfigurer
//现在这个拦截器只判断是否有用户，做权限拦截，token的刷新放到RefreshTokenInterceptor做
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 判断是否需要拦截（ThreadLocal中是否有用户）(上一个拦截器已经把redis中查询到的用户放到ThreadLocal中了)
        if(UserHolder.getUser() == null) {
            // 设置状态码
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 2. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户，避免内存泄露
        UserHolder.removeUser();
    }
}
