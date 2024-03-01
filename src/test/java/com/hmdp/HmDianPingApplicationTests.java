package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;


    @Test
    void testSaveShop() throws InterruptedException {
        // 为了过期方便，只设置了10秒的逻辑过期时间
        shopService.saveShop2Redis(1L, 10L);
    }

    // 为测试准备一个线程池，给500个线程
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(300);

        //定义任务
        Runnable task = () -> {
            for(int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("Id: " + id);
            }
            latch.countDown();
        };

        long begin = System.currentTimeMillis();

        //提交300次任务，一共会输出3w个Id
        for(int i = 0; i < 300; i++) {
            es.submit(task);   //3w id
        }

        // 等待任务结束，因为线程池是异步的
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time: " + (end - begin));

    }

}
