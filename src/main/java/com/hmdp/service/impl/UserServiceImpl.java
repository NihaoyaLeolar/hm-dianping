package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);  //导入的hutool依赖工具

        //4. 保存验证码到redis，并设置两分钟有效期 -> set key value ex 120
        stringRedisTemplate.opsForValue().set(
                LOGIN_CODE_KEY + phone,
                code,
                LOGIN_CODE_TTL,
                TimeUnit.MINUTES
        );

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

        //2. 从redis中取出验证码，校验用户输入的验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();    //拿到用户填写的验证码
        if (cacheCode == null || !cacheCode.equals(code)) {
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

        //7. 保存用户信息到Redis

        // 7.1 生成token作为登录令牌
        String token = UUID.randomUUID().toString(true);

        // 7.2 以token为key，将UserDTO对象转换为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);  //利用工具，从user到userDTO
//        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);

        //上面的方法运行报错：java.lang.Long cannot be cast to java.lang.String
        //原因是StringRedisTemplate extends RedisTemplate<String, String>
        //这个api在存储的时候，对象应该是String类型，或者是String类型组成的！
        //我们的userDTO中有Long类型的id，到Map中还是Long，于是RedisTemplate执行时报错

        Map<String, Object> userMap = BeanUtil.beanToMap( //利用工具，从userDTO到userMap
                userDTO,
                new HashMap<>(),
                CopyOptions.create()   //自己指定传递参数的方式，默认的都是同类型copy，这里全部将字段转为String类型
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );


        // 7.3 保存用户数据到Redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        // 7.4 设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES); //参考session，设置有效期为30分钟

        //8. 返回token
        return Result.ok(token);
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
