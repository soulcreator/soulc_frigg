package com.iotechn.unimall.biz.quartz;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dobbinsoft.fw.core.Const;
import com.dobbinsoft.fw.core.exception.AdminServiceException;
import com.dobbinsoft.fw.core.exception.ServiceException;
import com.dobbinsoft.fw.support.component.LockComponent;
import com.dobbinsoft.fw.support.component.MachineComponent;
import com.dobbinsoft.fw.support.mq.DelayedMessageQueue;
import com.iotechn.unimall.biz.client.erp.ErpClient;
import com.iotechn.unimall.biz.service.order.OrderBizService;
import com.iotechn.unimall.data.constant.LockConst;
import com.iotechn.unimall.data.domain.*;
import com.iotechn.unimall.data.dto.UserDTO;
import com.iotechn.unimall.data.enums.*;
import com.iotechn.unimall.data.exception.ExceptionDefinition;
import com.iotechn.unimall.data.mapper.*;
import com.iotechn.unimall.data.properties.UnimallOrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by rize on 2019/7/21.
 */
@Component
@EnableScheduling
public class CheckQuartz {

    private static final Logger logger = LoggerFactory.getLogger(CheckQuartz.class);

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderBizService orderBizService;
    @Autowired
    private GroupShopMapper groupShopMapper;
    @Autowired
    private SpuMapper spuMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private VipOrderMapper vipOrderMapper;
    @Autowired
    private ErpClient erpClient;
    @Autowired
    private UnimallOrderProperties unimallOrderProperties;
    @Autowired
    private LockComponent lockComponent;
    @Autowired
    private DelayedMessageQueue delayedMessageQueue;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private StringRedisTemplate userRedisTemplate;
    @Autowired
    private MachineComponent machineComponent;

    /**
     * ???????????????????????????????????????Redis???????????????????????????
     * ????????????????????????,?????????????????????????????????????????????????????????
     */
    @Scheduled(cron = "0 * * * * ?")
    public void checkOrderStatus() {
        if (lockComponent.tryLock(LockConst.SCHEDULED_ORDER_STATUS_CHECK_LOCK, 30)) {
            Date now = new Date();
            // 1.???????????????????????????????????????????????????redis???????????????????????????????????????????????????????????????????????????
            QueryWrapper<OrderDO> cancelWrapper = new QueryWrapper<OrderDO>();
            cancelWrapper.select("id", "order_no");
            cancelWrapper.eq("status", OrderStatusType.UNPAY.getCode());
            Date cancelTime = new Date(now.getTime() - unimallOrderProperties.getAutoCancelTime() * 1000);
            cancelWrapper.lt("gmt_update", cancelTime);
            List<OrderDO> cancelList = orderMapper.selectList(cancelWrapper);
            if (!CollectionUtils.isEmpty(cancelList)) {
                cancelList.stream().forEach(item -> {
                    delayedMessageQueue.publishTask(DMQHandlerType.ORDER_AUTO_CANCEL.getCode(), item.getOrderNo(), 1);
                });
            }
            // 2.???????????????????????????????????????????????????redis???????????????????????????????????????????????????????????????????????????
            QueryWrapper<OrderDO> confirmWrapper = new QueryWrapper<OrderDO>();
            confirmWrapper.select("id", "order_no");
            confirmWrapper.eq("status", OrderStatusType.WAIT_CONFIRM.getCode());
            Date confirmTime = new Date(now.getTime() - unimallOrderProperties.getAutoConfirmTime() * 1000);
            confirmWrapper.lt("gmt_update", confirmTime);
            List<OrderDO> confirmList = orderMapper.selectList(confirmWrapper);
            if (!CollectionUtils.isEmpty(confirmList)) {
                confirmList.stream().forEach(item -> {
                    delayedMessageQueue.publishTask(DMQHandlerType.ORDER_AUTO_CONFIRM.getCode(), item.getOrderNo(), 1);
                });
            }
        }
    }

    /**
     * ??????60s?????????,??????????????????????????????,????????????
     */
    @Scheduled(cron = "10 * * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void groupShopStart() throws Exception {
        if (lockComponent.tryLock(LockConst.GROUP_SHOP_START_LOCK, 30)) {
            Date now = new Date();
            /**
             * 1. ?????? ?????????????????????????????????
             */
            // 1.1 ?????????????????????,??????????????????????????????
            List<GroupShopDO> groupShopDOList = groupShopMapper.selectList(new QueryWrapper<GroupShopDO>()
                    .le("gmt_start", now)
                    .gt("gmt_end", now)
                    .eq("status", StatusType.LOCK.getCode()));
            if (groupShopDOList != null) {
                for (GroupShopDO groupShopDO : groupShopDOList) {
                    groupShopDO.setGmtUpdate(now);
                    groupShopDO.setStatus(StatusType.ACTIVE.getCode());
                    SpuDO spuDO = spuMapper.selectById(groupShopDO.getSpuId());
                    if (spuDO == null) {
                        throw new AdminServiceException(ExceptionDefinition.GROUP_SHOP_SPU_NO_EXITS);
                    }

                    // 1.2 ?????????????????????????????????
                    if (spuDO.getStatus().equals(StatusType.ACTIVE.getCode())) {
                        if (groupShopMapper.updateById(groupShopDO) <= 0) {
                            throw new AdminServiceException(ExceptionDefinition.GROUP_SHOP_SPU_UPDATE_SQL_QUERY_ERROR);
                        }
                    }
                }
            }
        }


    }

    @Scheduled(cron = "10 * * * * ?")
    public void groupShopEnd() throws Exception {
        if (lockComponent.tryLock(LockConst.GROUP_SHOP_END_LOCK, 30)) {
            Date now = new Date();
            /**
             * 2. ?????? ?????????????????????????????????,?????????????????????????????????
             */
            QueryWrapper<GroupShopDO> wrapper = new QueryWrapper<GroupShopDO>()
                    .eq("status", StatusType.ACTIVE.getCode())
                    .and(i -> i
                            .gt("gmt_start", now)
                            .or()
                            .le("gmt_end", now));

            List<GroupShopDO> lockGroupShopDOList = groupShopMapper.selectList(wrapper);
            // 2.2 ???????????????????????????????????????????????????????????????,?????????????????????????????????????????????????????????,????????????????????????????????????????????????????????????
            if (!CollectionUtils.isEmpty(lockGroupShopDOList)) {
                for (GroupShopDO groupShopDO : lockGroupShopDOList) {
                    transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                        @Override
                        protected void doInTransactionWithoutResult(TransactionStatus parentTransactionStatus) {
                            try {
                                // 2.1 ???????????????????????????.
                                GroupShopDO lockGroupShopDO = new GroupShopDO();
                                lockGroupShopDO.setId(groupShopDO.getId());
                                lockGroupShopDO.setStatus(StatusType.LOCK.getCode());
                                lockGroupShopDO.setGmtUpdate(now);
                                groupShopMapper.updateById(lockGroupShopDO);

                                // 2.2.1???????????????????????????
                                List<OrderDO> lockOrderList = orderMapper.selectList(
                                        new QueryWrapper<OrderDO>()
                                                .eq("group_shop_id", groupShopDO.getId())
                                                .eq("status", OrderStatusType.GROUP_SHOP_WAIT.getCode()));

                                if (CollectionUtils.isEmpty(lockOrderList)) {
                                    // ?????????????????????????????????
                                    return;
                                }

                                if (groupShopDO.getAutomaticRefund() == GroupShopAutomaticRefundType.YES.getCode() && groupShopDO.getBuyerNum().compareTo(groupShopDO.getMinNum()) < 0) {
                                    // 2.2.2.1.??????
                                    logger.info("[????????????] ???????????? GroupShopId:" + groupShopDO.getId());
                                    for (OrderDO orderDO : lockOrderList) {
                                        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                                            @Override
                                            protected void doInTransactionWithoutResult(TransactionStatus transactionStatus) {
                                                try {
                                                    // ????????????RPC????????????????????????????????????????????????????????????????????????????????????????????????
                                                    orderBizService.groupShopStatusRefund(orderDO.getOrderNo());
                                                    logger.info("[??????????????????] ?????? orderNo:" + orderDO.getOrderNo());
                                                } catch (Exception e) {
                                                    logger.error("[??????????????????] ?????? orderNo:" + orderDO.getOrderNo(), e);
                                                    transactionStatus.setRollbackOnly();
                                                }
                                            }
                                        });
                                    }
                                } else {
                                    logger.info("[????????????] ???????????? GroupShopId:" + groupShopDO.getId());
                                    // 2.2.2.2 ?????????????????????????????? (?????????????????????)
                                    for (OrderDO orderDO : lockOrderList) {
                                        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                                            @Override
                                            protected void doInTransactionWithoutResult(TransactionStatus transactionStatus) {
                                                try {
                                                    // ????????????RPC????????????????????????????????????????????????????????????????????????????????????????????????
                                                    OrderDO updateOrderDO = new OrderDO();
                                                    updateOrderDO.setStatus(OrderStatusType.WAIT_STOCK.getCode());
                                                    orderBizService.changeOrderSubStatus(orderDO.getOrderNo(), OrderStatusType.GROUP_SHOP_WAIT.getCode(), updateOrderDO);
                                                    erpClient.takeSalesHeader(orderDO.getOrderNo());
                                                    logger.info("[??????????????????] ?????? orderNo:" + orderDO.getOrderNo());
                                                } catch (Exception e) {
                                                    logger.error("[??????????????????] ?????? orderNo:" + orderDO.getOrderNo(), e);
                                                    transactionStatus.setRollbackOnly();
                                                }
                                            }
                                        });
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("[????????????] ???????????? ?????? id=" + groupShopDO.getId(), e);
                                parentTransactionStatus.setRollbackOnly();
                            }
                        }
                    });
                }
            }
        }
    }


    /**
     * ??????????????????
     *
     * @throws Exception
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void downLevel() throws Exception {
        if (lockComponent.tryLock(LockConst.VIP_EXPIRE_LOCK, 30)) {
            Date now = new Date();
            transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(TransactionStatus status) {
                    List<Long> userIds = null;
                    try {
                        QueryWrapper<UserDO> wrapper = new QueryWrapper<>();
                        wrapper.eq("level", UserLevelType.VIP.getCode());
                        wrapper.le("gmt_vip_expire", now);
                        wrapper.select("id");
                        userIds = userMapper.selectList(wrapper.select("id")).stream().map(UserDO::getId).collect(Collectors.toList());
                        if (!CollectionUtils.isEmpty(userIds)) {
                            UserDO update = new UserDO();
                            update.setLevel(UserLevelType.COMMON.getCode());
                            userMapper.update(update, wrapper);
                        }
                    } catch (Exception e) {
                        status.setRollbackOnly();
                        return;
                    }
                    // ?????????????????????????????????session
                    if (!CollectionUtils.isEmpty(userIds)) {
                        for (Long userId : userIds) {
                            Set<String> keys = userRedisTemplate.keys(Const.USER_REDIS_PREFIX + userId + "-*");
                            if (!CollectionUtils.isEmpty(keys)) {
                                for (String key : keys) {
                                    String userJson = userRedisTemplate.opsForValue().get(key);
                                    UserDTO userDTO = JSONObject.parseObject(userJson, UserDTO.class);
                                    userDTO.setLevel(UserLevelType.COMMON.getCode());
                                    userRedisTemplate.opsForValue().set(key, JSONObject.toJSONString(userDTO));
                                }

                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * ????????????????????????
     *
     * @throws Exception
     */
    @Scheduled(cron = "20 * * * * ?")
    public void checkVipOrderStatus() throws Exception {
        if (lockComponent.tryLock(LockConst.VIP_PAY_TIMEOUT_LOCK, 30)) {
            try {
                QueryWrapper<VipOrderDO> wrapper = new QueryWrapper<>();
                wrapper.eq("status", VipOrderStatusType.WAIT_REFUND.getCode());
                wrapper.le("gmt_pay", new Date(new Date().getTime() - 7 * 24 * 60 * 60 * 1000l));
                List<VipOrderDO> vipOrderDOS = vipOrderMapper.selectList(wrapper);
                if (!CollectionUtils.isEmpty(vipOrderDOS)) {
                    for (VipOrderDO vipOrderDO : vipOrderDOS) {
                        delayedMessageQueue.publishTask(DMQHandlerType.VIP_ORDER_BUY_OVER.getCode(), String.valueOf(vipOrderDO.getId()), 1);
                    }
                }
            } finally {
                lockComponent.release(LockConst.VIP_PAY_TIMEOUT_LOCK);
            }
        }
    }

    /**
     * ???????????????
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    public void renewMachineNo() {
        if (this.machineComponent.isInit()) {
            this.machineComponent.renew();
        }
    }

}
