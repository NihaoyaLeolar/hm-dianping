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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
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
    @Transactional  // 订单新增和库存扣减，涉及到两张表的业务，所以要加上事务，如果出现问题，可以回滚
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

        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .update();
        if(!success){
            //扣库存失败
            return Result.fail("库存不足！");
        }

        // 6. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2 用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 6.3 优惠券id
        voucherOrder.setVoucherId(voucherId);
        // 6.4 把订单写入数据库
        save(voucherOrder);

        // 7. 反回订单id
        return Result.ok(orderId);
    }

}
