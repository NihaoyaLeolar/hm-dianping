package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVouncher(Long voucherId) {

        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2. 查询秒杀是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        if (beginTime.isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }

        // 3. 查询秒杀是否结束
        LocalDateTime endTime = voucher.getEndTime();
        if (endTime.isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }

        // 4. 查询库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }

        //把原本的5-8过程封装到createVoucherOrder方法中，为了加锁，事务标签也要移过去，因为上面只有查询语句

        Long userId = UserHolder.getUser().getId();

        // 用悲观锁解决一人多单问题
        // 这里toString是确保以id值加锁，而不是以userId对象、userId2String加锁
        // Returns a canonical representation for the string object. 这里涉及到常量池
        synchronized (userId.toString().intern()) {

            //获取代理对象（与事务有关），需要配置：在pom文件中加入aspectj依赖，并在启动类加上注解@EnableAspectJAutoProxy(exposeProxy = true)
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();

            //当只有这一行时，相当于：this.createVoucherOrder(voucherId)这样会事务失效，但是这里不太理解
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional  // 订单新增和库存扣减，涉及到两张表的业务，所以要加上事务，如果出现问题，可以回滚
    public Result createVoucherOrder(Long voucherId) {

        // public synchronized Result createVoucherOrder(Long voucherId)
        // 这里不应该把锁加到方法名上，应该只需要对用户来加锁：同一个用户请求一把锁、不同用户不同锁，把锁的范围缩小，性能更好

        // 5. 确保一人一单
        Long userId = UserHolder.getUser().getId();

        // synchronized (userId.toString().intern()) {
        // 这里也不能只锁住下面，因为考虑到事务的回滚，在事务没结束前就释放了锁。所以锁一定要包含在事务的外层

        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过了");
        }

        // 6. 扣减库存，这里用乐观锁的思路解决超卖问题
        boolean success = seckillVoucherService.update()   //mybatisplus给的模版写法
                .setSql("stock = stock - 1")    //set stock = stock - 1
                .eq("voucher_id", voucherId)
                .gt("stock", 0)        //where id = ? and stock > ?
                .update();
        if (!success) {
            //扣库存失败
            return Result.fail("库存不足！");
        }

        // 7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2 用户id
        voucherOrder.setUserId(userId);
        // 7.3 优惠券id
        voucherOrder.setVoucherId(voucherId);
        // 7.4 把订单写入数据库
        save(voucherOrder);

        // 8. 反回订单id
        return Result.ok(orderId);
    }

}
