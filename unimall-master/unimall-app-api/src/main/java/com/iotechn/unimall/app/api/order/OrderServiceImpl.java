package com.iotechn.unimall.app.api.order;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dobbinsoft.fw.core.exception.AppServiceException;
import com.dobbinsoft.fw.core.exception.ServiceException;
import com.dobbinsoft.fw.core.util.GeneratorUtil;
import com.dobbinsoft.fw.pay.enums.PayChannelType;
import com.dobbinsoft.fw.pay.service.pay.MatrixPayService;
import com.dobbinsoft.fw.support.component.CacheComponent;
import com.dobbinsoft.fw.support.component.LockComponent;
import com.dobbinsoft.fw.support.component.MachineComponent;
import com.dobbinsoft.fw.support.model.Page;
import com.dobbinsoft.fw.support.mq.DelayedMessageQueue;
import com.dobbinsoft.fw.support.service.BaseService;
import com.iotechn.unimall.biz.client.erp.ErpClient;
import com.iotechn.unimall.biz.executor.GlobalExecutor;
import com.iotechn.unimall.biz.service.address.AddressBizService;
import com.iotechn.unimall.biz.service.cart.CartBizService;
import com.iotechn.unimall.biz.service.coupon.CouponBizService;
import com.iotechn.unimall.biz.service.freight.FreightTemplateBizService;
import com.iotechn.unimall.biz.service.groupshop.GroupShopBizService;
import com.iotechn.unimall.biz.service.notify.AdminNotifyBizService;
import com.iotechn.unimall.biz.service.order.OrderBizService;
import com.iotechn.unimall.biz.service.pay.PayBizService;
import com.iotechn.unimall.biz.service.product.ProductBizService;
import com.iotechn.unimall.data.constant.CacheConst;
import com.iotechn.unimall.data.constant.LockConst;
import com.iotechn.unimall.data.domain.AddressDO;
import com.iotechn.unimall.data.domain.OrderDO;
import com.iotechn.unimall.data.domain.OrderSkuDO;
import com.iotechn.unimall.data.domain.SpuDO;
import com.iotechn.unimall.data.dto.AdminDTO;
import com.iotechn.unimall.data.dto.CouponUserDTO;
import com.iotechn.unimall.data.dto.UserDTO;
import com.iotechn.unimall.data.dto.freight.ShipTraceDTO;
import com.iotechn.unimall.data.dto.order.OrderDTO;
import com.iotechn.unimall.data.dto.order.OrderRequestDTO;
import com.iotechn.unimall.data.dto.order.OrderRequestSkuDTO;
import com.iotechn.unimall.data.dto.product.GroupShopDTO;
import com.iotechn.unimall.data.dto.product.GroupShopSkuDTO;
import com.iotechn.unimall.data.dto.product.SkuDTO;
import com.iotechn.unimall.data.enums.*;
import com.iotechn.unimall.data.exception.ExceptionDefinition;
import com.iotechn.unimall.data.mapper.OrderMapper;
import com.iotechn.unimall.data.mapper.OrderSkuMapper;
import com.iotechn.unimall.data.mapper.SkuMapper;
import com.iotechn.unimall.data.model.FreightCalcModel;
import com.iotechn.unimall.data.model.OrderCalcSkuModel;
import com.iotechn.unimall.data.model.SkuStockInfoModel;
import com.iotechn.unimall.data.properties.UnimallOrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by rize on 2019/7/4.
 */
@Service
public class OrderServiceImpl extends BaseService<UserDTO, AdminDTO> implements OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private CouponBizService couponBizService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderSkuMapper orderSkuMapper;

    @Autowired
    private CartBizService cartBizService;

    @Autowired
    private MatrixPayService payService;

    @Autowired
    private PayBizService payBizService;

    @Autowired
    private LockComponent lockComponent;

    @Autowired
    private AddressBizService addressBizService;

    @Autowired
    private OrderBizService orderBizService;

    @Autowired
    private FreightTemplateBizService freightTemplateBizService;

    @Autowired
    private GroupShopBizService groupShopBizService;

    @Autowired
    private AdminNotifyBizService adminNotifyBizService;

    @Autowired
    private CacheComponent cacheComponent;

    @Autowired
    private ProductBizService productBizService;

    @Autowired
    private DelayedMessageQueue delayedMessageQueue;

    @Autowired
    private MachineComponent machineComponent;

    @Value("${com.dobbinsoft.fw.env}")
    private String ENV;

    @Autowired
    private UnimallOrderProperties unimallOrderProperties;

    @Autowired
    private ErpClient erpClient;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public String takeOrder(OrderRequestDTO orderRequest, String channel, Long userId) throws ServiceException {
        if (lockComponent.tryLock(LockConst.TAKE_ORDER_LOCK + userId, 20)) {
            //????????????????????????????????????????????????
            List<OrderRequestSkuDTO> skuList = orderRequest.getSkuList();
            boolean calcStockFlag = false;
            try {
                UserDTO user = sessionUtil.getUser();
                //??????????????????
                Integer userLevel = user.getLevel();
                if (user.getStatus().intValue() != UserStatusType.ACTIVE.getCode()) {
                    throw new AppServiceException(ExceptionDefinition.ORDER_USER_IS_NOT_ACTIVE);
                }
                // ???Sku???????????????????????????????????????????????????????????????
                orderRequest.getSkuList().sort((o1, o2) -> (int) (o1.getSkuId() - o2.getSkuId()));
                //??????????????? START
                if (CollectionUtils.isEmpty(skuList) || orderRequest.getTotalPrice() == null) {
                    throw new AppServiceException(ExceptionDefinition.PARAM_CHECK_FAILED);
                }
                if (orderRequest.getTotalPrice() <= 0) {
                    throw new AppServiceException(ExceptionDefinition.ORDER_PRICE_MUST_GT_ZERO);
                }
                // ????????????????????????????????????????????????????????????????????????
                if (orderRequest.getAddressId() == null) {
                    throw new AppServiceException(ExceptionDefinition.ORDER_ADDRESS_CANNOT_BE_NULL);
                }
                // ??????????????????
                AddressDO addressDO = addressBizService.getAddressById(orderRequest.getAddressId());
                // ???????????????????????????????????????
                List<SkuStockInfoModel> stockErrorSkuList = new LinkedList<>();
                // ?????????????????????????????????????????????
                List<Long> statusErrorSkuList = new LinkedList<>();
                // ????????????????????????????????????????????????SkuDO??????????????????
                List<OrderCalcSkuModel> calcSkuList = new ArrayList<>();
                // ???SkuIds ????????????
                List<Long> skuIds = new ArrayList<>();
                for (OrderRequestSkuDTO orderRequestSkuDTO : skuList) {
                    Long skuId = orderRequestSkuDTO.getSkuId();
                    skuIds.add(skuId);
                    OrderCalcSkuModel orderCalcSpuDTO = cacheComponent.getHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + orderRequestSkuDTO.getSpuId(), OrderCalcSkuModel.class);
                    if (orderCalcSpuDTO == null) {
                        // ?????????DB?????????
                        SpuDO spuFromDB = productBizService.getProductByIdFromDB(orderRequestSkuDTO.getSpuId());
                        if (spuFromDB == null || (spuFromDB.getStatus() == SpuStatusType.STOCK.getCode())) {
                            // ??????????????????????????????
                            statusErrorSkuList.add(skuId);
                            continue;
                        } else {
                            orderCalcSpuDTO = new OrderCalcSkuModel();
                            BeanUtils.copyProperties(spuFromDB, orderCalcSpuDTO);
                        }

                    }
                    long surplus = cacheComponent.decrementHashKey(CacheConst.PRT_SKU_STOCK_BUCKET, "K" + skuId, orderRequestSkuDTO.getNum());
                    if (surplus < 0) {
                        // ???????????????0????????????????????????????????????????????????
                        SkuStockInfoModel skuStockInfo = new SkuStockInfoModel();
                        skuStockInfo.setSkuId(skuId);
                        skuStockInfo.setExpect(orderRequestSkuDTO.getNum());
                        // ????????????????????? + ??????????????? = ?????????????????????
                        skuStockInfo.setSurplus((int) surplus + orderRequestSkuDTO.getNum());
                        stockErrorSkuList.add(skuStockInfo);
                        continue;
                    }
                    // ???SkuId????????????
                    orderCalcSpuDTO.setSkuId(skuId);
                    orderCalcSpuDTO.setNum(orderRequestSkuDTO.getNum());
                    calcSkuList.add(orderCalcSpuDTO);
                }

                calcStockFlag = true;

                // ????????????????????????????????????????????? ???????????????Attach??????
                if (!CollectionUtils.isEmpty(stockErrorSkuList)) {
                    throw new AppServiceException(ExceptionDefinition.ORDER_SKU_STOCK_NOT_ENOUGH).attach(stockErrorSkuList);
                }

                // ???????????????????????????????????????
                if (!CollectionUtils.isEmpty(statusErrorSkuList)) {
                    throw new AppServiceException(ExceptionDefinition.ORDER_SKU_NOT_EXIST).attach(statusErrorSkuList);
                }

                Date now = new Date();
                // ????????????????????????????????????????????????sku????????????
                List<SkuDTO> skuDTOListOfAll = productBizService.getSkuListByIds(skuIds);
                // ???????????????
                CouponUserDTO couponUserDTO = null;
                if (orderRequest.getCouponId() != null) {
                    couponUserDTO = couponBizService.getCouponUserById(orderRequest.getCouponId(), userId);
                    if (couponUserDTO == null || couponUserDTO.getGmtUsed() != null) {
                        throw new AppServiceException(ExceptionDefinition.ORDER_COUPON_NOT_EXIST);
                    }
                }

                List<OrderCalcSkuModel> orderCalcSkuList = calcSkuList;
                // ??????????????????
                orderCalcSkuList.sort((o1, o2) -> (int) (o1.getSkuId() - o2.getSkuId()));
                // ???????????????????????????????????????
                List<SkuDTO> skuDTOList = skuDTOListOfAll;
                skuDTOList.sort((o1, o2) -> (int) (o1.getId() - o2.getId()));
                // ???????????? = ?????????????????? + ?????????????????? (????????????)
                int totalSkuPrice = 0;
                // ??????????????????
                int skuPrice = 0;
                int skuOriginalPrice = 0;
                // ????????????????????????
                Map<Long, Integer> groupShopPriceMap = new HashMap<>();
                Map<Long, Integer> groupShopOriginalPriceMap = new HashMap<>();
                for (int i = 0; i < skuDTOList.size(); i++) {
                    OrderCalcSkuModel orderCalcSkuDTO = orderCalcSkuList.get(i);
                    SkuDTO skuDTO = skuDTOList.get(i);
                    Integer originalPrice = skuDTO.getOriginalPrice();
                    // FIXME ??????????????????????????????skuDTO????????????????????????
                    // FIXME ???????????????????????????????????????????????????????????????????????????
                    if ((skuDTO.getActivityType() != null
                            && skuDTO.getActivityType() == SpuActivityType.GROUP_SHOP.getCode() && skuDTO.getGmtActivityStart() != null
                            && skuDTO.getGmtActivityEnd() != null && skuDTO.getGmtActivityStart().getTime() < now.getTime() && skuDTO.getGmtActivityEnd().getTime() > now.getTime())) {
                        // ????????????????????????????????????????????????????????????
                        GroupShopDTO groupShopActivity = groupShopBizService.getGroupShopById(skuDTO.getActivityId());
                        if (groupShopActivity == null) {
                            throw new AppServiceException(ExceptionDefinition.ORDER_GROUP_SHOP_ACTIVITY_HAS_OVER);
                        }
                        // ???????????????SKU?????????????????????????????????????????????????????????????????????
                        Integer oldPrice = groupShopPriceMap.get(groupShopActivity.getId());
                        Integer oldOriginalPrice = groupShopOriginalPriceMap.get(groupShopActivity.getId());
                        Map<Long, GroupShopSkuDTO> groupShopSkuMap = groupShopActivity.getGroupShopSkuDTOList().stream().collect(Collectors.toMap(GroupShopSkuDTO::getSkuId, v -> v));
                        // ??????????????????
                        totalSkuPrice += groupShopSkuMap.get(skuDTO.getId()).getSkuGroupShopPrice() * orderCalcSkuDTO.getNum();
                        // ??????????????????
                        skuDTO.setPrice(groupShopSkuMap.get(skuDTO.getId()).getSkuGroupShopPrice());
                        skuDTO.setVipPrice(groupShopSkuMap.get(skuDTO.getId()).getSkuGroupShopPrice());
                        if (oldPrice != null) {
                            groupShopPriceMap.put(groupShopActivity.getId(), oldPrice + (groupShopSkuMap.get(skuDTO.getId()).getSkuGroupShopPrice() * orderCalcSkuDTO.getNum()));
                        } else {
                            groupShopPriceMap.put(groupShopActivity.getId(), groupShopSkuMap.get(skuDTO.getId()).getSkuGroupShopPrice() * orderCalcSkuDTO.getNum());
                        }
                        if (oldOriginalPrice != null) {
                            groupShopOriginalPriceMap.put(groupShopActivity.getId(), oldOriginalPrice + (skuDTO.getOriginalPrice() * orderCalcSkuDTO.getNum()));
                        } else {
                            groupShopOriginalPriceMap.put(groupShopActivity.getId(), skuDTO.getOriginalPrice() * orderCalcSkuDTO.getNum());
                        }
                    } else if (userLevel == UserLevelType.VIP.getCode()) {
                        skuOriginalPrice += originalPrice * orderCalcSkuDTO.getNum();
                        skuPrice += skuDTO.getVipPrice() * orderCalcSkuDTO.getNum();
                        totalSkuPrice += skuDTO.getVipPrice() * orderCalcSkuDTO.getNum();
                    } else {
                        skuOriginalPrice += originalPrice * orderCalcSkuDTO.getNum();
                        skuPrice += skuDTO.getPrice() * orderCalcSkuDTO.getNum();
                        totalSkuPrice += skuDTO.getPrice() * orderCalcSkuDTO.getNum();
                    }
                    // ???DB??????????????????????????????????????????
                    orderCalcSkuDTO.setFreightTemplateId(skuDTO.getFreightTemplateId());
                    orderCalcSkuDTO.setWeight(skuDTO.getWeight());
                    orderCalcSkuDTO.setPrice(skuDTO.getPrice());
                    orderCalcSkuDTO.setVipPrice(skuDTO.getVipPrice());
                }
                // ???????????????
                int childIndex = 1;
                // ????????????
                FreightCalcModel freightCalcModel = new FreightCalcModel();
                // ???SKU???????????????????????????????????????
                Map<Long, List<OrderCalcSkuModel>> freightTemplateCalcMap = orderCalcSkuList
                        .stream()
                        .collect(Collectors.groupingBy(OrderCalcSkuModel::getFreightTemplateId));
                // ??????SKU???????????????
                List<FreightCalcModel.FreightAndWeight> faws = new LinkedList<>();
                freightTemplateCalcMap.forEach((k, v) -> {
                    FreightCalcModel.FreightAndWeight faw = new FreightCalcModel.FreightAndWeight();
                    faw.setId(k);
                    int weight = 0;
                    int price = 0;
                    for (OrderCalcSkuModel orderCalcSkuModel : v) {
                        weight += orderCalcSkuModel.getWeight() * orderCalcSkuModel.getNum();
                        price += (userLevel == UserLevelType.VIP.getCode() ? orderCalcSkuModel.getVipPrice() : orderCalcSkuModel.getPrice()) * orderCalcSkuModel.getNum();
                    }
                    faw.setPrice(price);
                    faw.setWeight(weight);
                    faws.add(faw);
                });
                freightCalcModel.setFreightAndWeights(faws);
                Integer freightPrice = freightTemplateBizService.computePostage(freightCalcModel);
                // ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                boolean singleFee = false;
                // ????????????????????????
                Long useCouponOrderId = null;
                // ?????????????????????
                String parentOrderNo = GeneratorUtil.genOrderId(this.machineComponent.getMachineNo() + "", this.ENV);
                if (skuPrice > 0) {
                    // ??????????????????
                    // ???????????????(???????????????????????????????????????)???SkuList????????????
                    List<SkuDTO> commonSkuList = new ArrayList<>();
                    List<OrderCalcSkuModel> commonOrderCalcSkuList = new ArrayList<>();
                    for (int i = 0; i < skuDTOList.size(); i++) {
                        SkuDTO item = skuDTOList.get(i);
                        if (!(item.getActivityType() != null
                                && item.getActivityType() == SpuActivityType.GROUP_SHOP.getCode() && item.getGmtActivityStart() != null
                                && item.getGmtActivityEnd() != null && item.getGmtActivityStart().getTime() < now.getTime() && item.getGmtActivityEnd().getTime() > now.getTime())) {
                            commonSkuList.add(item);
                            commonOrderCalcSkuList.add(orderCalcSkuList.get(i));
                        }
                    }
                    // ????????????
                    OrderDO o = save(skuOriginalPrice, skuPrice, channel, freightPrice,
                            couponUserDTO, orderRequest, parentOrderNo, childIndex,
                            userId, now, addressDO, commonSkuList, commonOrderCalcSkuList, userLevel, null, null);
                    useCouponOrderId = o.getId();
                    // ??????????????????????????????????????????
                    singleFee = true;
                }
                if (groupShopPriceMap.size() > 0) {
                    // ???????????????????????????????????????????????????
                    Set<Long> groupShopIds = groupShopPriceMap.keySet();
                    for (Long groupShopId : groupShopIds) {
                        Integer groupShopSkuOriginalPrice = groupShopOriginalPriceMap.get(groupShopId);
                        Integer groupShopSkuPrice = groupShopPriceMap.get(groupShopId);
                        // ??????????????????????????????????????????SkuDTOList
                        List<SkuDTO> groupShopSkuList = new ArrayList<>();
                        List<OrderCalcSkuModel> groupShopOrderCalcSkuList = new ArrayList<>();
                        for (int i = 0; i < skuDTOList.size(); i++) {
                            SkuDTO item = skuDTOList.get(i);
                            if (item.getActivityType() != null
                                    && item.getActivityType() == SpuActivityType.GROUP_SHOP.getCode() && item.getGmtActivityStart() != null && item.getActivityId().longValue() == groupShopId.longValue()
                                    && item.getGmtActivityEnd() != null && item.getGmtActivityStart().getTime() < now.getTime() && item.getGmtActivityEnd().getTime() > now.getTime()) {
                                groupShopSkuList.add(item);
                                groupShopOrderCalcSkuList.add(calcSkuList.get(i));
                            }
                        }
                        if (singleFee) {
                            // ???????????????????????????????????????
                            childIndex++;
                            save(groupShopSkuOriginalPrice, groupShopSkuPrice, channel, 0,
                                    couponUserDTO, orderRequest, parentOrderNo, childIndex,
                                    userId, now, addressDO, groupShopSkuList, groupShopOrderCalcSkuList, userLevel, SpuActivityType.GROUP_SHOP.getCode(), groupShopId);
                        } else {
                            // ???????????????????????????????????????
                            childIndex++;
                            save(groupShopSkuOriginalPrice, groupShopSkuPrice, channel, freightPrice,
                                    couponUserDTO, orderRequest, parentOrderNo, childIndex,
                                    userId, now, addressDO, groupShopSkuList, groupShopOrderCalcSkuList, userLevel, SpuActivityType.GROUP_SHOP.getCode(), groupShopId);
                            // ??????????????????
                            singleFee = true;
                        }
                    }
                }
                Map<Long, Integer> skuStockMap = skuList.stream().collect(Collectors.toMap(OrderRequestSkuDTO::getSkuId, OrderRequestSkuDTO::getNum));
                // ?????????????????????microFix?????????????????????
                productBizService.decSkuStock(skuStockMap);
                // ??????????????????microFix?????????????????????
                if (couponUserDTO != null) {
                    couponBizService.useCoupon(orderRequest.getCouponId(), useCouponOrderId);
                }
                // ???????????????????????????????????????????????????
                if (orderRequest.getTakeWay().equals("cart")) {
                    cartBizService.deleteBySkuId(skuIds, userId);
                }
                // ??????????????????????????????????????????????????????????????????????????????
                if (orderRequest.getExceptPrice() != null) {
                    int exceptPrice = this.checkPrepay(parentOrderNo, null, userId);
                    if (exceptPrice != orderRequest.getExceptPrice().intValue()) {
                        throw new AppServiceException(ExceptionDefinition.ORDER_PRODUCT_PRICE_HAS_BEEN_CHANGED);
                    }
                }
                // ?????? & ??????????????????????????????????????????
                for (int i = 1; i <= childIndex; i++) {
                    String childOrderNo = parentOrderNo + "S" + ((1000) + i);
                    logger.info("??????????????????:" + childOrderNo);
                    delayedMessageQueue.publishTask(DMQHandlerType.ORDER_AUTO_CANCEL.getCode(), childOrderNo, unimallOrderProperties.getAutoCancelTime().intValue());
                }

                return parentOrderNo;
            } catch (Exception e) {
                if (calcStockFlag) {
                    for (OrderRequestSkuDTO orderRequestSkuDTO : skuList) {
                        cacheComponent.incrementHashKey(CacheConst.PRT_SKU_STOCK_BUCKET, "K" + orderRequestSkuDTO.getSkuId(), orderRequestSkuDTO.getNum());
                    }
                }
                if (e instanceof ServiceException) {
                    // ????????????
                    throw e;
                }
                // ????????????
                logger.error("[????????????] ??????", e);
                throw e;
            } finally {
                lockComponent.release(LockConst.TAKE_ORDER_LOCK + userId);
            }
        }
        throw new AppServiceException(ExceptionDefinition.ORDER_SYSTEM_BUSY);
    }

    @Override
    public Page<OrderDTO> getOrderPage(Integer pageNo, Integer pageSize, Integer status, Long userId) throws ServiceException {
        List<OrderDTO> orderDTOList = orderMapper.selectOrderPage(status, (pageNo - 1) * pageSize, pageSize, userId);
        Long count = orderMapper.countOrder(status, (pageNo - 1) * pageSize, pageSize, userId);
        //??????SKU
        orderDTOList.forEach(item -> {
            item.setSkuList(orderSkuMapper.selectList(new QueryWrapper<OrderSkuDO>().eq("order_id", item.getId())));
        });
        return new Page<>(orderDTOList, pageNo, pageSize, count);
    }

    @Override
    public OrderDTO getOrderDetail(Long orderId, Long userId) throws ServiceException {
        return orderBizService.getOrderDetail(orderId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Object prepay(String parentOrderNo, String orderNo, Integer platform, String payChannel, String ip, Long userId) throws ServiceException {
        int actualPrice = this.checkPrepay(parentOrderNo, orderNo, userId);
        // ???????????????????????????
        return payBizService.commonPrepay(ObjectUtils.isEmpty(parentOrderNo) ? orderNo : parentOrderNo, actualPrice, platform, payChannel, ip);
    }

    private int checkPrepay(String parentOrderNo, String orderNo, Long userId) throws ServiceException {
        // ??????????????? ??? ?????????????????????????????????
        if ((ObjectUtils.isEmpty(parentOrderNo) && ObjectUtils.isEmpty(orderNo)) || (!ObjectUtils.isEmpty(parentOrderNo) && !ObjectUtils.isEmpty(orderNo))) {
            throw new AppServiceException(ExceptionDefinition.ORDER_PARAM_CHECK_FAILED);
        }
        List<OrderDO> orderList;
        if (!ObjectUtils.isEmpty(parentOrderNo))
            orderList = orderBizService.checkOrderExistByParentNo(parentOrderNo, userId);
        else
            orderList = orderBizService.checkOrderExistByNo(orderNo, userId);
        // ??????????????????
        int actualPrice = 0;
        for (OrderDO orderDO : orderList) {
            Integer status = orderDO.getStatus();
            if (status != OrderStatusType.UNPAY.getCode()) {
                throw new AppServiceException(ExceptionDefinition.ORDER_STATUS_NOT_SUPPORT_PAY);
            }
            actualPrice += orderDO.getActualPrice();
        }
        return actualPrice;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Object offlinePrepay(String parentOrderNo, String orderNo, Long userId) throws ServiceException {
        // ??????????????? ??? ?????????????????????????????????
        if ((ObjectUtils.isEmpty(parentOrderNo) && ObjectUtils.isEmpty(orderNo)) || (!ObjectUtils.isEmpty(parentOrderNo) && !ObjectUtils.isEmpty(orderNo))) {
            throw new AppServiceException(ExceptionDefinition.ORDER_PARAM_CHECK_FAILED);
        }
        List<OrderDO> orderList;
        if (!ObjectUtils.isEmpty(parentOrderNo))
            orderList = orderBizService.checkOrderExistByParentNo(parentOrderNo, userId);
        else
            orderList = orderBizService.checkOrderExistByNo(orderNo, userId);
        // ??????????????????
        for (OrderDO orderDO : orderList) {
            Integer status = orderDO.getStatus();
            if (status != OrderStatusType.UNPAY.getCode()) {
                throw new AppServiceException(ExceptionDefinition.ORDER_STATUS_NOT_SUPPORT_PAY);
            }
        }
        Date now = new Date();
        for (OrderDO orderDO : orderList) {
            List<OrderSkuDO> orderSkuDOList = orderSkuMapper.selectList(new QueryWrapper<OrderSkuDO>().eq("order_id", orderDO.getId()));
            List<OrderSkuDO> groupShopSkuList = orderSkuDOList.stream().filter(item -> (item.getActivityType() != null && item.getActivityType() == SpuActivityType.GROUP_SHOP.getCode())).collect(Collectors.toList());
            if (groupShopSkuList.size() > 0) {
                // ??????????????????????????????
                OrderDO groupShopUpdateDO = new OrderDO();
                groupShopUpdateDO.setPayId("OFFLINE");
                groupShopUpdateDO.setPayChannel(PayChannelType.OFFLINE.getCode());
                groupShopUpdateDO.setPayPrice(orderDO.getActualPrice());
                groupShopUpdateDO.setGmtPay(now);
                groupShopUpdateDO.setGmtUpdate(now);
                groupShopUpdateDO.setStatus(OrderStatusType.GROUP_SHOP_WAIT.getCode());
                groupShopUpdateDO.setSubPay(1);
                // ??????buyer count
                for (OrderSkuDO orderSkuDO : groupShopSkuList) {
                    groupShopBizService.incGroupShopNum(orderSkuDO.getActivityId(), orderSkuDO.getNum());
                }
                orderBizService.changeOrderSubStatus(orderDO.getOrderNo(), OrderStatusType.UNPAY.getCode(), groupShopUpdateDO);
            } else {
                // ???????????????
                erpClient.takeSalesHeader(orderDO.getOrderNo());
                OrderDO updateOrderDO = new OrderDO();
                updateOrderDO.setPayChannel(PayChannelType.OFFLINE.getCode());
                updateOrderDO.setStatus(OrderStatusType.WAIT_STOCK.getCode());
                updateOrderDO.setGmtUpdate(new Date());
                boolean succ = orderBizService.changeOrderSubStatus(orderDO.getOrderNo(), OrderStatusType.UNPAY.getCode(), updateOrderDO);
                if (!succ) {
                    throw new AppServiceException(ExceptionDefinition.ORDER_STATUS_CHANGE_FAILED);
                }
            }
            // ??????????????????
            Map<Long, Integer> salesMap = orderSkuDOList.stream().collect(Collectors.toMap(OrderSkuDO::getSpuId, OrderSkuDO::getNum, (k1, k2) -> k1.intValue() + k2.intValue()));
            productBizService.incSpuSales(salesMap);
        }
        // ??????????????????????????????
        delayedMessageQueue.deleteTask(DMQHandlerType.ORDER_AUTO_CANCEL.getCode(), orderNo);
        return "ok";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String refund(String orderNo, String reason, Long userId) throws ServiceException {
        OrderDO orderDO = orderBizService.checkOrderExistByNo(orderNo, userId).get(0);
        if (PayChannelType.OFFLINE.getCode().equals(orderDO.getPayChannel())) {
            throw new AppServiceException(ExceptionDefinition.ORDER_PAY_CHANNEL_NOT_SUPPORT_REFUND);
        }
        if (OrderStatusType.refundable(orderDO.getStatus())) {
            OrderDO updateOrderDO = new OrderDO();
            updateOrderDO.setRefundReason(reason);
            updateOrderDO.setStatus(OrderStatusType.REFUNDING.getCode());
            orderBizService.changeOrderSubStatus(orderNo, orderDO.getStatus(), updateOrderDO);
            GlobalExecutor.execute(() -> {
                OrderDTO orderDTO = new OrderDTO();
                BeanUtils.copyProperties(orderDO, orderDTO);
                List<OrderSkuDO> orderSkuList = orderSkuMapper.selectList(new QueryWrapper<OrderSkuDO>().eq("order_no", orderDO.getOrderNo()));
                orderDTO.setSkuList(orderSkuList);
                adminNotifyBizService.refundOrder(orderDTO);
            });
            return "ok";
        }
        throw new AppServiceException(ExceptionDefinition.ORDER_STATUS_NOT_SUPPORT_REFUND);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String cancel(String orderNo, Long userId) throws ServiceException {
        OrderDO orderDO = orderBizService.checkOrderExistByNo(orderNo, userId).get(0);
        if (orderDO.getStatus() != OrderStatusType.UNPAY.getCode()) {
            throw new AppServiceException(ExceptionDefinition.ORDER_STATUS_NOT_SUPPORT_CANCEL);
        }
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setStatus(OrderStatusType.CANCELED.getCode());
        updateOrderDO.setGmtUpdate(new Date());
        List<OrderSkuDO> orderSkuList = orderSkuMapper.selectList(new QueryWrapper<OrderSkuDO>().eq("order_id", orderDO.getId()));
        orderSkuList.forEach(item -> {
            skuMapper.returnSkuStock(item.getSkuId(), item.getNum());
        });
        orderBizService.changeOrderSubStatus(orderNo, OrderStatusType.UNPAY.getCode(), updateOrderDO);
        delayedMessageQueue.deleteTask(DMQHandlerType.ORDER_AUTO_CANCEL.getCode(), orderNo);
        return "ok";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String confirm(String orderNo, Long userId) throws ServiceException {
        OrderDO orderDO = orderBizService.checkOrderExistByNo(orderNo, userId).get(0);
        if (orderDO.getStatus() != OrderStatusType.WAIT_CONFIRM.getCode()) {
            throw new AppServiceException(ExceptionDefinition.ORDER_STATUS_NOT_SUPPORT_CONFIRM);
        }
        OrderDO updateOrderDO = new OrderDO();
        updateOrderDO.setStatus(OrderStatusType.WAIT_APPRAISE.getCode());
        updateOrderDO.setGmtUpdate(new Date());
        orderBizService.changeOrderSubStatus(orderNo, OrderStatusType.WAIT_CONFIRM.getCode(), updateOrderDO);
        delayedMessageQueue.deleteTask(DMQHandlerType.ORDER_AUTO_CONFIRM.getCode(), orderNo);
        return "ok";
    }

    @Override
    public ShipTraceDTO queryShip(String orderNo, Long userId) throws ServiceException {
        OrderDO orderDO = orderBizService.checkOrderExistByNo(orderNo, userId).get(0);
        if (orderDO.getStatus() < OrderStatusType.WAIT_CONFIRM.getCode()) {
            throw new AppServiceException(ExceptionDefinition.ORDER_HAS_NOT_SHIP);
        }
        if (StringUtils.isEmpty(orderDO.getShipCode()) || StringUtils.isEmpty(orderDO.getShipNo())) {
            throw new AppServiceException(ExceptionDefinition.ORDER_DID_NOT_SET_SHIP);
        }
        ShipTraceDTO shipTraceList = freightTemplateBizService.getShipTraceList(orderDO.getShipNo(), orderDO.getShipCode());
        if (CollectionUtils.isEmpty(shipTraceList.getTraces())) {
            throw new AppServiceException(ExceptionDefinition.ORDER_DO_NOT_EXIST_SHIP_TRACE);
        }
        return shipTraceList;
    }

    @Override
    public Integer previewFreight(OrderRequestDTO orderRequest, Long userId) throws ServiceException {
        List<OrderRequestSkuDTO> skuList = orderRequest.getSkuList();
        AddressDO addressDO = null;
        if (orderRequest.getAddressId() != null) {
            addressDO = addressBizService.getAddressById(orderRequest.getAddressId());
        }
        FreightCalcModel calcModel = new FreightCalcModel();
        if (addressDO == null) {
            // ?????????????????????????????????????????????????????????????????????????????????
            calcModel.setProvince("????????????????????????");
        } else {
            calcModel.setProvince(addressDO.getProvince());
        }
        // ??????????????????????????????????????????????????????????????? ??????????????????Id ?????? ???????????????
        UserDTO user = sessionUtil.getUser();
        // ???SKU????????????????????????
        Map<Long, List<OrderRequestSkuDTO>> calcMap = skuList.stream().collect(Collectors.groupingBy(OrderRequestSkuDTO::getFreightTemplateId));
        List<FreightCalcModel.FreightAndWeight> faws = new LinkedList<>();
        calcMap.forEach((k, v) -> {
            FreightCalcModel.FreightAndWeight faw = new FreightCalcModel.FreightAndWeight();
            faw.setId(k);
            int weight = 0;
            int price = 0;
            for (OrderRequestSkuDTO skuDTO : v) {
                weight += skuDTO.getWeight() * skuDTO.getNum();
                price += (user.getLevel() == UserLevelType.VIP.getCode() ? skuDTO.getVipPrice() : skuDTO.getPrice()) * skuDTO.getNum();
            }
            faw.setWeight(weight);
            faw.setPrice(price);
            faws.add(faw);
        });
        calcModel.setFreightAndWeights(faws);
        int sum = freightTemplateBizService.computePostage(calcModel);
        return sum;
    }


    /**
     * ????????????????????????
     *
     * @param skuOriginalPrice
     * @param skuPrice
     * @param channel
     * @param freightPrice
     * @param couponUserDTO
     * @param orderRequest
     * @param parentOrderNo
     * @param childIndex
     * @param userId
     * @param now
     * @param addressDO
     * @param skuDTOList
     * @param orderCalcSkuList
     * @param userLevel
     * @param activityType
     * @param activityId
     * @return
     * @throws ServiceException
     */
    private OrderDO save(int skuOriginalPrice, int skuPrice, String channel,
                         int freightPrice, CouponUserDTO couponUserDTO,
                         OrderRequestDTO orderRequest, String parentOrderNo, int childIndex,
                         Long userId, Date now, AddressDO addressDO, List<SkuDTO> skuDTOList,
                         List<OrderCalcSkuModel> orderCalcSkuList, Integer userLevel,
                         Integer activityType, Long activityId) throws ServiceException {
        OrderDO orderDO = new OrderDO();
        // ???????????????????????????
        orderDO.setSkuOriginalTotalPrice(skuOriginalPrice);
        orderDO.setSkuTotalPrice(skuPrice);
        // ????????????
        orderDO.setChannel(channel);
        // ??????????????????
        orderDO.setFreightPrice(freightPrice);
        // ???????????????????????????
        int couponPrice = 0;
        if (couponUserDTO != null) {
            couponPrice = couponUserDTO.getDiscount();
            // ??????????????????????????????????????????????????????????????????????????????
            if (couponUserDTO.getMin() > skuPrice) {
                throw new AppServiceException(ExceptionDefinition.ORDER_COUPON_PRICE_NOT_ENOUGH);
            }
        }
        orderDO.setCouponPrice(couponPrice);
        orderDO.setActualPrice(skuPrice + freightPrice - couponPrice);
        orderDO.setMono(orderRequest.getMono());
        orderDO.setParentOrderNo(parentOrderNo);
        orderDO.setOrderNo(parentOrderNo + "S" + (1000 + childIndex));
        orderDO.setUserId(userId);
        orderDO.setStatus(OrderStatusType.UNPAY.getCode());
        orderDO.setGmtUpdate(now);
        orderDO.setGmtCreate(now);
        if (!userId.equals(addressDO.getUserId())) {
            throw new AppServiceException(ExceptionDefinition.ORDER_ADDRESS_NOT_BELONGS_TO_YOU);
        }
        orderDO.setConsignee(addressDO.getConsignee());
        orderDO.setPhone(addressDO.getPhone());
        orderDO.setProvince(addressDO.getProvince());
        orderDO.setCity(addressDO.getCity());
        orderDO.setCounty(addressDO.getCounty());
        orderDO.setAddress(addressDO.getAddress());
        // ????????????????????????
        if (activityType != null && activityType == SpuActivityType.GROUP_SHOP.getCode()) {
            orderDO.setGroupShopId(activityId);
        }
        orderMapper.insert(orderDO);

        for (int i = 0; i < skuDTOList.size(); i++) {
            OrderCalcSkuModel orderCalcSpuDTO = orderCalcSkuList.get(i);
            SkuDTO skuDTO = skuDTOList.get(i);
            Assert.isTrue(orderCalcSpuDTO.getSkuId().longValue() == skuDTO.getId().longValue(), "???????????????");
            OrderSkuDO orderSkuDO = new OrderSkuDO();
            orderSkuDO.setBarCode(skuDTO.getBarCode());
            orderSkuDO.setTitle(skuDTO.getTitle());
            orderSkuDO.setUnit(skuDTO.getUnit());
            orderSkuDO.setSpuTitle(skuDTO.getSpuTitle());
            orderSkuDO.setImg(skuDTO.getImg() == null ? skuDTO.getSpuImg() : skuDTO.getImg());
            orderSkuDO.setNum(orderCalcSpuDTO.getNum());
            orderSkuDO.setOriginalPrice(skuDTO.getOriginalPrice());
            orderSkuDO.setPrice(skuDTO.getPrice());
            if (userLevel == UserLevelType.VIP.getCode()) {
                orderSkuDO.setPrice(skuDTO.getVipPrice());
            } else {
                orderSkuDO.setPrice(skuDTO.getPrice());
            }
            orderSkuDO.setSkuId(skuDTO.getId());
            orderSkuDO.setSpuId(skuDTO.getSpuId());
            orderSkuDO.setOrderNo(orderDO.getOrderNo());
            orderSkuDO.setOrderId(orderDO.getId());
            orderSkuDO.setGmtCreate(now);
            orderSkuDO.setGmtUpdate(now);
            // ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            orderSkuDO.setActivityType(activityType);
            orderSkuDO.setActivityId(activityId);
            orderSkuMapper.insert(orderSkuDO);
        }
        return orderDO;
    }

}
