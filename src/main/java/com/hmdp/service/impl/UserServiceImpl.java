package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
// ServiceImpl<UserMapper, User>： 这是 MyBatis-Plus 框架提供的通用 Service 实现类。
// 通过继承这个类，你可以获得一些常见的 CRUD（创建、读取、更新、删除）操作的实现，而不需要手动编写这些基本的数据库操作。
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);  //导入的hutool依赖工具

        //4. 保存验证码到session
        session.setAttribute("code", code);

        //5. 发送验证码
        log.debug("发送短信验证码成功，验证码：{}" + code);

        //6. 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号（不同请求，每一个请求都要做独立的校验）
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //2. 校验验证码
        Object cacheCode = session.getAttribute("code");   //拿到发送的验证码，之前存在了session中
        String code = loginForm.getCode();    //拿到用户填写的验证码
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            //3. 不一致，返回错误信息
            return Result.fail("验证码错误");
        }

        //4. 一致，根据手机号查询用户 select * from tb_user where phone = ? (mybatisplus 可以轻松实现这个查询) -> query直接得到tb_user表
        User user = query().eq("phone", phone).one();

        //5. 判断用户是否存在
        if (user == null) {
            //6. 不存在，创建新用户，存入数据库
            user = creatUserWithPhone(phone);
        }

        //7. 保存用户信息到session
//        session.setAttribute("user", user);
        // 为了防止用户信息泄露，新建一个UserDTO类，使用工具将user对象中对应字段拷贝到UserDTO新建对象中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User creatUserWithPhone(String phone) {
        //1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        //2. 保存用户
        save(user);
        return user;
    }
}
