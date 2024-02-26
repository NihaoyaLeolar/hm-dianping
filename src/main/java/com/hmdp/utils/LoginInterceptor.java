package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

//拦截器写完后，还需要配置拦截器：在config下新建MvcConfig类，实现WebMvcConfigurer
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        //1. 获取session
        HttpSession session = request.getSession();
        //2. 获取session中的用户
        Object user = session.getAttribute("user");
        //3. 判断用户是否存在
        if(user == null) {
            //4. 不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }
        //5. 存在，将用户信息保存在ThreadLocal（为了保证线程安全，这里没太懂）
        UserHolder.saveUser((UserDTO) user);
        
        //6. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户，避免内存泄露
        UserHolder.removeUser();
    }
}
