package com.iotechn.unimall.biz.pay;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dobbinsoft.fw.core.exception.AppServiceException;
import com.dobbinsoft.fw.core.exception.ServiceException;
import com.dobbinsoft.fw.pay.enums.PayChannelType;
import com.dobbinsoft.fw.pay.exception.PayServiceException;
import com.dobbinsoft.fw.pay.handler.MatrixPayCallbackHandler;
import com.dobbinsoft.fw.pay.model.notify.MatrixPayNotifyResponse;
import com.dobbinsoft.fw.pay.model.notify.MatrixPayOrderNotifyResult;
import com.dobbinsoft.fw.support.mq.DelayedMessageQueue;
import com.iotechn.unimall.biz.client.erp.ErpClient;
import com.iotechn.unimall.biz.executor.GlobalExecutor;
import com.iotechn.unimall.biz.service.groupshop.GroupShopBizService;
import com.iotechn.unimall.biz.service.notify.AdminNotifyBizService;
import com.iotechn.unimall.biz.service.order.OrderBizService;
import com.iotechn.unimall.biz.service.product.ProductBizService;
import com.iotechn.unimall.data.domain.OrderDO;
import com.iotechn.unimall.data.domain.OrderSkuDO;
import com.iotechn.unimall.data.dto.order.OrderDTO;
import com.iotechn.unimall.data.enums.DMQHandlerType;
import com.iotechn.unimall.data.enums.OrderStatusType;
import com.iotechn.unimall.data.enums.SpuActivityType;
import com.iotechn.unimall.data.exception.ExceptionDefinition;
import com.iotechn.unimall.data.mapper.OrderMapper;
import com.iotechn.unimall.data.mapper.OrderSkuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OrderPayCallbackHandler implements MatrixPayCallbackHandler {

    private static final Logger logger = LoggerFactory.getLogger(OrderPayCallbackHandler.class);

    @Autowired
    private OrderBizService orderBizService;

    @Autowired
    private OrderSkuMapper orderSkuMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private ErpClient erpClient;

    @Autowired
    private ProductBizService productBizService;

    @Autowired
    private GroupShopBizService groupShopBizService;

    @Autowired
    private DelayedMessageQueue delayedMessageQueue;

    @Autowired
    private AdminNotifyBizService adminNotifyBizService;

    @Override
    public void beforeCheckSign(HttpServletRequest httpServletRequest) {

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Object handle(MatrixPayOrderNotifyResult result, HttpServletRequest request) {
        try {
            logger.info("??????{}???????????????????????????", result.getPayChannel().getMsg());
            logger.info(JSONObject.toJSONString(result));
            /* ???????????????????????????????????????ID */
            // ????????????????????????????????????????????????
            String orderAbstractNo = result.getOutTradeNo();
            boolean isParent = !orderAbstractNo.contains("S");
            String payId = result.getTransactionId();

            List<OrderDO> orderDOList;
            if (isParent) {
                orderDOList = orderMapper.selectList(
                        new QueryWrapper<OrderDO>()
                                .eq("parent_order_no", orderAbstractNo));
            } else {
                orderDOList = orderMapper.selectList(
                        new QueryWrapper<OrderDO>()
                                .eq("order_no", orderAbstractNo));
            }

            if (CollectionUtils.isEmpty(orderDOList)) {
                return MatrixPayNotifyResponse.fail("??????????????? orderNo=" + orderAbstractNo);
            }

            int status = orderDOList.get(0).getStatus().intValue();
            int actualPrice = 0;

            for (OrderDO orderDO : orderDOList) {
                actualPrice += orderDO.getActualPrice();
                if (orderDO.getStatus().intValue() != status) {
                    return MatrixPayNotifyResponse.fail("???????????????????????????");
                }
            }

            if (status != OrderStatusType.UNPAY.getCode()) {
                return MatrixPayNotifyResponse.success("????????????????????????");
            }

            Integer totalFee = result.getTotalFee();

            // ????????????????????????
            if (!totalFee.equals(actualPrice)) {
                return MatrixPayNotifyResponse.fail(orderAbstractNo + " : ????????????????????? totalFee=" + totalFee);
            }

            /**************** ????????????????????? ??????????????? ?????? ???????????????????????????????????? **********************/

            //1. ??????????????????
            Date now = new Date();
            OrderDO updateOrderDO = new OrderDO();
            updateOrderDO.setPayId(payId);
            updateOrderDO.setPayChannel(result.getPayChannel().getCode());
            updateOrderDO.setPayPrice(result.getTotalFee());
            updateOrderDO.setGmtPay(now);
            updateOrderDO.setGmtUpdate(now);
            updateOrderDO.setStatus(OrderStatusType.WAIT_STOCK.getCode());
            updateOrderDO.setAppId(result.getAppid());
            List<OrderSkuDO> orderSkuDOList;

            if (isParent) {
                // ????????????
                updateOrderDO.setSubPay(0);
                List<String> orderNos = orderDOList.stream().map(item -> item.getOrderNo()).collect(Collectors.toList());
                orderSkuDOList = orderSkuMapper.selectList(
                        new QueryWrapper<OrderSkuDO>()
                                .in("order_no", orderNos));
                if (orderSkuDOList.stream().filter(item -> (item.getActivityType() != null && item.getActivityType() == SpuActivityType.GROUP_SHOP.getCode())).count() > 0) {
                    // ????????????????????? ???????????????????????????????????????
                    List<OrderDO> subOrderList = orderBizService.checkOrderExistByParentNo(orderAbstractNo, null);
                    // ???orderSkuList?????????????????????Key???Map
                    Map<String, List<OrderSkuDO>> orderSkuMap = orderSkuDOList.stream().collect(Collectors.groupingBy(OrderSkuDO::getOrderNo));
                    // ?????????????????????skuList
                    for (OrderDO subOrder : subOrderList) {
                        List<OrderSkuDO> subOrderSkuList = orderSkuMap.get(subOrder.getOrderNo());
                        List<OrderSkuDO> groupShopSkuList = subOrderSkuList.stream().filter(item -> (item.getActivityType() != null && item.getActivityType() == SpuActivityType.GROUP_SHOP.getCode())).collect(Collectors.toList());
                        if (groupShopSkuList.size() > 0) {
                            // ?????????????????????
                            OrderDO groupShopUpdateDO = new OrderDO();
                            groupShopUpdateDO.setPayId(payId);
                            groupShopUpdateDO.setPayChannel(PayChannelType.WX.getCode());
                            groupShopUpdateDO.setPayPrice(result.getTotalFee());
                            groupShopUpdateDO.setGmtPay(now);
                            groupShopUpdateDO.setGmtUpdate(now);
                            groupShopUpdateDO.setStatus(OrderStatusType.GROUP_SHOP_WAIT.getCode());
                            groupShopUpdateDO.setSubPay(1);
                            // ??????buyer count
                            for (OrderSkuDO orderSkuDO : groupShopSkuList) {
                                groupShopBizService.incGroupShopNum(orderSkuDO.getActivityId(), orderSkuDO.getNum());
                            }
                            orderBizService.changeOrderSubStatus(subOrder.getOrderNo(), OrderStatusType.UNPAY.getCode(), groupShopUpdateDO);
                        } else {
                            erpClient.takeSalesHeader(subOrder.getOrderNo());
                            orderBizService.changeOrderSubStatus(subOrder.getOrderNo(), OrderStatusType.UNPAY.getCode(), updateOrderDO);
                        }
                    }
                } else {
                    // ????????????????????????????????????????????????????????????????????????
                    // ??????????????? ?????????????????? ??????????????????
                    orderBizService.changeOrderParentStatus(orderAbstractNo, OrderStatusType.UNPAY.getCode(), updateOrderDO, orderDOList.size());
                }
            } else {
                // ????????????
                updateOrderDO.setSubPay(1);
                orderSkuDOList = orderSkuMapper.selectList(
                        new QueryWrapper<OrderSkuDO>()
                                .eq("order_no", orderAbstractNo));
                List<OrderSkuDO> groupShopSkuList = orderSkuDOList.stream().filter(item -> (item.getActivityType() != null && item.getActivityType() == SpuActivityType.GROUP_SHOP.getCode())).collect(Collectors.toList());
                if (groupShopSkuList.size() > 0) {
                    // ?????????????????????
                    OrderDO groupShopUpdateDO = new OrderDO();
                    groupShopUpdateDO.setPayId(payId);
                    groupShopUpdateDO.setPayChannel(PayChannelType.WX.getCode());
                    groupShopUpdateDO.setPayPrice(result.getTotalFee());
                    groupShopUpdateDO.setGmtPay(now);
                    groupShopUpdateDO.setGmtUpdate(now);
                    groupShopUpdateDO.setStatus(OrderStatusType.GROUP_SHOP_WAIT.getCode());
                    groupShopUpdateDO.setSubPay(1);
                    // ??????buyer count
                    for (OrderSkuDO orderSkuDO : groupShopSkuList) {
                        groupShopBizService.incGroupShopNum(orderSkuDO.getActivityId(), orderSkuDO.getNum());
                    }
                    orderBizService.changeOrderSubStatus(orderAbstractNo, OrderStatusType.UNPAY.getCode(), groupShopUpdateDO);
                } else {
                    erpClient.takeSalesHeader(orderDOList.get(0).getOrderNo());
                    orderBizService.changeOrderSubStatus(orderAbstractNo, OrderStatusType.UNPAY.getCode(), updateOrderDO);
                }
            }

            //2. ??????????????????
            // ???????????????????????????Spu????????????Sku?????????
            Map<Long, Integer> salesMap = orderSkuDOList.stream().collect(Collectors.toMap(OrderSkuDO::getSpuId, OrderSkuDO::getNum, (k1, k2) -> k1.intValue() + k2.intValue()));
            productBizService.incSpuSales(salesMap);


            //3. ????????????????????? & ??????????????????????????????????????????
            Map<String, List<OrderSkuDO>> orderSkuMap = orderSkuDOList.stream().collect(Collectors.groupingBy(OrderSkuDO::getOrderNo));
            Map<String, List<OrderDO>> orderMap = orderDOList.stream().collect(Collectors.groupingBy(OrderDO::getOrderNo));
            for (String subOrderNo : orderSkuMap.keySet()) {
                OrderDTO finalOrderDTO = new OrderDTO();
                OrderDO orderDO = orderMap.get(subOrderNo).get(0);
                BeanUtils.copyProperties(orderDO, finalOrderDTO);
                finalOrderDTO.setPayChannel(PayChannelType.WX.getCode());
                finalOrderDTO.setSkuList(orderSkuMap.get(subOrderNo));
                GlobalExecutor.execute(() -> {
                    adminNotifyBizService.newOrder(finalOrderDTO);
                });
                delayedMessageQueue.deleteTask(DMQHandlerType.ORDER_AUTO_CANCEL.getCode(), subOrderNo);
                logger.info("[????????????????????????] orderNo:" + subOrderNo);
            }
            return MatrixPayNotifyResponse.success("????????????");
        } catch (ServiceException e) {
            throw new PayServiceException(e.getMessage());
        }
    }
}
