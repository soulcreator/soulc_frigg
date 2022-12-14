package com.iotechn.unimall.biz.service.product;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dobbinsoft.fw.core.exception.BizServiceException;
import com.dobbinsoft.fw.core.exception.CoreExceptionDefinition;
import com.dobbinsoft.fw.core.exception.ServiceException;
import com.dobbinsoft.fw.support.component.CacheComponent;
import com.dobbinsoft.fw.support.model.Page;
import com.iotechn.unimall.biz.service.category.CategoryBizService;
import com.iotechn.unimall.data.constant.CacheConst;
import com.iotechn.unimall.data.domain.*;
import com.iotechn.unimall.data.dto.product.AdminSpuDTO;
import com.iotechn.unimall.data.dto.product.SkuDTO;
import com.iotechn.unimall.data.dto.product.SpuDTO;
import com.iotechn.unimall.data.enums.BizType;
import com.iotechn.unimall.data.enums.StatusType;
import com.iotechn.unimall.data.exception.ExceptionDefinition;
import com.iotechn.unimall.data.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by rize on 2019/7/12.
 */
@Service
public class ProductBizService {

    @Autowired
    private SpuMapper spuMapper;

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private SpuAttributeMapper spuAttributeMapper;

    @Autowired
    private ImgMapper imgMapper;

    @Autowired
    private SpuSpecificationMapper spuSpecificationMapper;

    @Autowired
    private CategoryBizService categoryBizService;

    @Autowired
    private CacheComponent cacheComponent;

//    @Autowired(required = false)
//    private SearchEngine searchEngine;


    /**
     * SPU ?????????detail??????????????????????????????????????????
     */
    public static final String[] SPU_EXCLUDE_DETAIL_FIELDS;

    private static final Logger logger = LoggerFactory.getLogger(ProductBizService.class);

    static {
        Field[] fields = SpuDO.class.getDeclaredFields();
        List<String> tempList = new ArrayList<>();
        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            String name = StrUtil.toUnderlineCase(field.getName());
            if (!name.equals("detail")) {
                tempList.add(name);
            }
        }
        tempList.add("id");
        tempList.add("gmt_update");
        tempList.add("gmt_create");
        SPU_EXCLUDE_DETAIL_FIELDS = tempList.toArray(new String[0]);
    }

    /**
     * ???????????? ????????????
     * @param adminSpuDTO
     * @return
     * @throws ServiceException
     */
    public String create(AdminSpuDTO adminSpuDTO) throws ServiceException {
        // 1.????????????
        if (adminSpuDTO.getId() != null) {
            throw new BizServiceException(ExceptionDefinition.PRODUCT_CREATE_HAS_ID);
        }
        if (CollectionUtils.isEmpty(adminSpuDTO.getSkuList())) {
            throw new BizServiceException(ExceptionDefinition.PRODUCT_SKU_LIST_EMPTY);
        }
        // 1.2.??????Sku????????????
        Set<String> barCodes = adminSpuDTO.getSkuList().stream().map(item -> item.getBarCode()).collect(Collectors.toSet());
        if (barCodes.size() != adminSpuDTO.getSkuList().size()) {
            throw new BizServiceException(ExceptionDefinition.PRODUCT_UPLOAD_SKU_BARCODE_REPEAT);
        }
        List<SkuDO> existSkuDO = skuMapper.selectList(new QueryWrapper<SkuDO>().in("bar_code", barCodes));
        if (!CollectionUtils.isEmpty(existSkuDO)) {
            String spuIds = existSkuDO.stream().map(item -> item.getSpuId().toString()).collect(Collectors.joining(","));
            String skuIds = existSkuDO.stream().map(item -> item.getBarCode()).collect(Collectors.joining(","));
            throw new BizServiceException(CoreExceptionDefinition
                    .buildVariableException(ExceptionDefinition.PRODUCT_CREATE_BARCODE_REPEAT, spuIds, skuIds));
        }
        // 2.1.????????????
        Date now = new Date();
        SpuDO spuDO = new SpuDO();
        BeanUtils.copyProperties(adminSpuDTO, spuDO);
        spuDO.setGmtUpdate(now);
        spuDO.setGmtCreate(now);
        spuDO.setSales(0);
        packPrice(adminSpuDTO, spuDO);
        spuMapper.insert(spuDO);
        adminSpuDTO.setId(spuDO.getId());
        // 2.2.??????SKU???
        for (SkuDO skuDO : adminSpuDTO.getSkuList()) {
            skuDO.setSpuId(spuDO.getId());
            skuDO.setGmtUpdate(now);
            skuDO.setGmtCreate(now);
            skuMapper.insert(skuDO);
        }
        // 2.3.??????spuAttr
        insertSpuAttribute(adminSpuDTO, now);
        // 2.4.??????IMG
        insertSpuImg(adminSpuDTO, spuDO.getId(), now);
        // 2.5.??????????????????
        List<SpuSpecificationDO> specificationList = adminSpuDTO.getSpecificationList();
        specificationList.forEach(item -> {
            // ???SpuSpecificationDO ??????????????????
            item.setSpuId(spuDO.getId());
            item.setId(null);
            item.setGmtCreate(now);
            item.setGmtUpdate(now);
        });
        spuSpecificationMapper.insertBatchSomeColumn(specificationList);
        // 3.1. ??????????????????
        this.createSpuCache(spuDO);
        // 3.2. ??????Sku????????????
        for (SkuDO skuDO : adminSpuDTO.getSkuList()) {
            cacheComponent.putHashRaw(CacheConst.PRT_SKU_STOCK_BUCKET, "K" + skuDO.getId(), skuDO.getStock() + "");
        }
        // 4. TODO ?????????????????????
//        if (searchEngine != null) {
//            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
//                @Override
//                public void afterCommit() {
//                    AdminProductServiceImpl.this.transmissionSearchEngine(spuDO.getId());
//                }
//            });
//
//        }
        return "ok";
    }

    /**
     * ??????????????????/VIP??????
     * @param adminSpuDTO
     * @param spuDO
     * @throws BizServiceException
     */
    private void packPrice(AdminSpuDTO adminSpuDTO, SpuDO spuDO) throws BizServiceException {
        SkuDO minPriceSku = null;
        for (SkuDO skuDO : adminSpuDTO.getSkuList()) {
            if (skuDO.getOriginalPrice() < skuDO.getPrice() || skuDO.getPrice() < skuDO.getVipPrice() || skuDO.getOriginalPrice() < skuDO.getVipPrice()) {
                throw new BizServiceException(ExceptionDefinition.PRODUCT_PRICE_CHECKED_FAILED);
            }
            if (minPriceSku == null) {
                minPriceSku = skuDO;
            }
            if (minPriceSku.getPrice() > skuDO.getPrice()) {
                minPriceSku = skuDO;
            }
        }
        spuDO.setOriginalPrice(minPriceSku.getOriginalPrice());
        spuDO.setPrice(minPriceSku.getPrice());
        spuDO.setVipPrice(minPriceSku.getVipPrice());
    }

    /**
     * ???????????? ????????????
     * @param spuDTO
     * @return
     * @throws ServiceException
     */
    public String edit(AdminSpuDTO spuDTO) throws ServiceException {
        // 1. ????????????
        if (spuDTO.getId() == null) {
            throw new BizServiceException(ExceptionDefinition.PARAM_CHECK_FAILED);
        }
        if (CollectionUtils.isEmpty(spuDTO.getSkuList())) {
            throw new BizServiceException(ExceptionDefinition.PRODUCT_SKU_LIST_EMPTY);
        }
        // ?????????????????????????????????????????????????????????????????????????????????
        SpuDO spuFromDB = spuMapper.selectById(spuDTO.getId());
        // 2.1. ????????????
        Date now = new Date();
        SpuDO spuDO = new SpuDO();
        BeanUtils.copyProperties(spuDTO, spuDO);
        packPrice(spuDTO, spuDO);
        spuMapper.updateById(spuDO);
        // 2.2. ??????barCodes
        Set<String> barCodes = new HashSet<>();
        for (SkuDO skuDO : spuDTO.getSkuList()) {
            if (skuDO.getOriginalPrice() < skuDO.getPrice() || skuDO.getPrice() < skuDO.getVipPrice() || skuDO.getOriginalPrice() < skuDO.getVipPrice()) {
                throw new BizServiceException(ExceptionDefinition.PRODUCT_PRICE_CHECKED_FAILED);
            }
            skuDO.setId(null);
            skuDO.setSpuId(spuDO.getId());
            skuDO.setGmtUpdate(now);
            if (skuMapper.update(skuDO,
                    new QueryWrapper<SkuDO>()
                            .eq("bar_code", skuDO.getBarCode())) <= 0) {
                skuDO.setGmtCreate(now);
                skuMapper.insert(skuDO);
            }
            boolean succ = barCodes.add(skuDO.getBarCode());
            if (!succ) {
                throw new BizServiceException(ExceptionDefinition.PRODUCT_UPLOAD_SKU_BARCODE_REPEAT);
            }
        }
        // 2.2.2. ????????????barCode
        skuMapper.delete(new QueryWrapper<SkuDO>().eq("spu_id", spuDO.getId()).notIn("bar_code", barCodes));
        // 2.3. ??????spuAttr
        spuAttributeMapper.delete(new QueryWrapper<SpuAttributeDO>().eq("spu_id", spuDTO.getId()));
        insertSpuAttribute(spuDTO, now);
        imgMapper.delete(new QueryWrapper<ImgDO>().eq("biz_id", spuDO.getId()).eq("biz_type", BizType.GOODS.getCode()));
        // 2.4. ??????IMG
        insertSpuImg(spuDTO, spuDO.getId(), now);
        // TODO 2.5. ??????????????????
        // 3. ????????????
        // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 3.1. ???????????????????????????????????????????????????????????????????????????
                if (spuFromDB.getCategoryId().longValue() != spuDO.getCategoryId().longValue()) {
                    // 3.1.1. ?????????CategoryId?????????????????????????????????????????? ????????????????????????
                    // 3.1.1.1 ????????????????????????
                    List<Long> oldCategoryFamily = categoryBizService.getCategoryFamily(spuFromDB.getCategoryId());
                    for (Long oldCid : oldCategoryFamily) {
                        cacheComponent.delZSet(CacheConst.PRT_CATEGORY_ORDER_ID_ZSET + oldCid, "P" + spuDO.getId());
                        cacheComponent.delZSet(CacheConst.PRT_CATEGORY_ORDER_PRICE_ZSET + oldCid, "P" + spuDO.getId());
                        cacheComponent.delZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + oldCid, "P" + spuDO.getId());
                    }
                    // 3.1.1.2 ????????????????????????
                    if (spuDO.getStatus().intValue() == StatusType.ACTIVE.getCode()) {
                        List<Long> newCategoryFamily = categoryBizService.getCategoryFamily(spuDO.getCategoryId());
                        for (Long newCid : newCategoryFamily) {
                            cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_ID_ZSET + newCid, spuDO.getId(), "P" + spuDO.getId());
                            cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_PRICE_ZSET + newCid, spuDO.getPrice(), "P" + spuDO.getId());
                            cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + newCid, spuFromDB.getSales(), "P" + spuDO.getId());
                        }
                    }
                }
                List<Long> categoryFamily = categoryBizService.getCategoryFamily(spuDO.getCategoryId());
                if (spuFromDB.getPrice().intValue() != spuDO.getPrice().intValue()) {
                    // 3.1.2.?????????Price?????????????????????????????????????????? ??????????????????
                    if (spuDO.getStatus().intValue() == StatusType.ACTIVE.getCode()) {
                        for (Long categoryId : categoryFamily) {
                            cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_PRICE_ZSET + categoryId, spuDO.getPrice(), "P" + spuDO.getId());
                        }
                    }
                }
                // 3.2. ????????????????????????
                SpuDTO basicSpuDTO = new SpuDTO();
                BeanUtils.copyProperties(spuDO, basicSpuDTO, "detail");
                basicSpuDTO.setCategoryIds(categoryFamily);
                cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + spuDO.getId(), basicSpuDTO);
                // 3.3. ????????????????????????
                cacheComponent.delHashKey(CacheConst.PRT_SPU_DETAIL_HASH_BUCKET, "P" + spuDO.getId());
                // 3.4. ??????Sku????????????
                for (SkuDO skuDO : spuDTO.getSkuList()) {
                    cacheComponent.putHashRaw(CacheConst.PRT_SKU_STOCK_BUCKET, "K" + skuDO.getId(), skuDO.getStock() + "");
                }
                // 4 ??????????????????
                ProductBizService.this.transmissionSearchEngine(spuDO.getId());
            }
        });
        return "ok";
    }

    private void insertSpuAttribute(AdminSpuDTO spuDTO, Date now) {
        if (!CollectionUtils.isEmpty(spuDTO.getAttributeList())) {
            for (SpuAttributeDO spuAttributeDO : spuDTO.getAttributeList()) {
                spuAttributeDO.setSpuId(spuDTO.getId());
                spuAttributeDO.setGmtUpdate(now);
                spuAttributeDO.setGmtCreate(now);
                spuAttributeMapper.insert(spuAttributeDO);
            }
        }
    }

    private void insertSpuImg(AdminSpuDTO spuDTO, Long bizId, Date now) {
        List<String> imgList = spuDTO.getImgList();
        List<ImgDO> imgDOList = imgList.stream().map(item -> {
            ImgDO imgDO = new ImgDO();
            imgDO.setBizType(BizType.GOODS.getCode());
            imgDO.setBizId(bizId);
            imgDO.setUrl(item);
            imgDO.setGmtCreate(now);
            imgDO.setGmtUpdate(now);
            return imgDO;
        }).collect(Collectors.toList());
        imgMapper.insertBatchSomeColumn(imgDOList);
    }

    public void transmissionSearchEngine(Long id) {
        // TODO
//        if (searchEngine != null) {
//            GlobalExecutor.execute(() -> {
//                SpuDO spuFromDB = AdminProductServiceImpl.this.productBizService.getProductByIdFromDB(id);
//                searchEngine.dataTransmission(spuFromDB);
//            });
//        }
    }

    public void deleteSearchEngine(Long id) {
        // TODO
//        if (searchEngine != null) {
//            GlobalExecutor.execute(() -> {
//                SpuDO spuDO = new SpuDO();
//                spuDO.setId(id);
//                searchEngine.deleteData(spuDO);
//            });
//        }
    }

    /**
     * ?????????????????????????????????????????????????????????????????????????????????detail(????????????)??????
     *
     * @param pageNo
     * @param pageSize
     * @param categoryId
     * @param orderBy
     * @param isAsc
     * @param title
     * @return
     * @throws ServiceException
     */
    public Page<SpuDO> getProductPage(Integer pageNo, Integer pageSize, Long categoryId, String orderBy, Boolean isAsc, String title) throws ServiceException {
        if (!StringUtils.isEmpty(title)) {
            // TODO ????????????
//            try {
//                if (this.searchEngine != null) {
//                    SearchWrapperModel searchWrapper =
//                            new SearchWrapperModel()
//                                    .div(pageNo, pageSize)
//                                    .like("title", title);
//                    if (categoryId != null && categoryId > 0) {
//                        searchWrapper.eq("category_id", categoryId);
//                    }
//                    if (orderBy != null && isAsc != null) {
//                        if (isAsc)
//                            searchWrapper.orderByAsc(orderBy);
//                        else
//                            searchWrapper.orderByDesc(orderBy);
//                    }
//                    Page<SpuDO> searchRes = searchEngine.search(searchWrapper, SpuDO.class);
//                    return searchRes;
//                }
//            } catch (SearchEngineException e) {
//                logger.error("[??????????????????] ???????????? ??????", e);
//                throw new AppServiceException(ExceptionDefinition.buildVariableException(ExceptionDefinition.SEARCH_ENGINE_INNER_EXCEPTION, e.getMessage()));
//            }
            // ??????DB??????
            return this.getProductPageFromDB(pageNo, pageSize, categoryId, orderBy, isAsc, title);
        }
        // 1. ??????????????????????????????Id
        String zsetBucketKey;
        if ("price".equals(orderBy)) {
            zsetBucketKey = CacheConst.PRT_CATEGORY_ORDER_PRICE_ZSET + categoryId;
        } else if ("id".equals(orderBy)) {
            zsetBucketKey = CacheConst.PRT_CATEGORY_ORDER_ID_ZSET + categoryId;
        } else if ("sales".equals(orderBy)) {
            zsetBucketKey = CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + categoryId;
        } else {
            throw new BizServiceException(ExceptionDefinition.GOODS_ORDER_BY_WAY_ILLEGAL);
        }
        Page<String> page = cacheComponent.getZSetPage(zsetBucketKey, pageNo, pageSize, isAsc);
        if (page.getTotal() == 0) {
            // ??????????????????????????????DB??????
            List<SpuDO> productIdsFromDB = getProductIdsOnSaleFromDB(categoryId);
            // ????????????categoryId???????????????Category(???????????????) ???????????????Id????????????????????????????????????CategoryId???null???????????????????????????
            if (!CollectionUtils.isEmpty(productIdsFromDB)) {
                // ???????????????????????????ZSet???
                Set<ZSetOperations.TypedTuple<String>> set = productIdsFromDB.stream().map(item -> (ZSetOperations.TypedTuple<String>) (new DefaultTypedTuple("P" + item.getId(), item.getSales().doubleValue()))).collect(Collectors.toSet());
                // ????????????
                cacheComponent.putZSetMulti(zsetBucketKey, set);
                // ????????????????????????
                page = cacheComponent.getZSetPage(zsetBucketKey, pageNo, pageSize, isAsc);
            }
        }
        // ???Spu Hash????????????????????????????????????Id????????????Spu
        List<SpuDO> spuList = cacheComponent.getHashMultiAsList(CacheConst.PRT_SPU_HASH_BUCKET, page.getItems(), SpuDO.class);
        boolean hasEmptyObj = false;
        for (int i = 0; i < spuList.size(); i++) {
            SpuDO spuDO = spuList.get(i);
            if (spuDO == null) {
                // ??????????????????
                SpuDO spuDOFromDB = spuMapper.selectOne(new QueryWrapper<SpuDO>().select(SPU_EXCLUDE_DETAIL_FIELDS).eq("id", Long.parseLong(page.getItems().get(i).replace("P", ""))));
                if (spuDOFromDB == null) {
                    // ???????????????????????????
                    hasEmptyObj = true;
                    logger.error("[????????????????????????] key=" + zsetBucketKey + ";item=" + page.getItems().get(i));
                    cacheComponent.delZSet(zsetBucketKey, page.getItems().get(i));
                } else {
                    // ?????? spuList ??????
                    spuList.set(i, spuDOFromDB);
                    // ??????ClassifyIds
                    List<Long> familyCategoryIds = categoryBizService.getCategoryFamily(spuDOFromDB.getCategoryId());
                    SpuDTO spuDTO = new SpuDTO();
                    BeanUtils.copyProperties(spuDOFromDB, spuDTO);
                    spuDTO.setCategoryIds(familyCategoryIds);
                    cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + spuDOFromDB.getId(), spuDTO);
                }
            }
        }
        if (hasEmptyObj) {
            spuList = spuList.stream().filter(item -> item != null).collect(Collectors.toList());
        }
        return page.replace(spuList);
    }

    /**
     * ????????????????????????????????????????????????detail(????????????)??????
     *
     * @param pageNo
     * @param pageSize
     * @param categoryId
     * @param orderBy
     * @param isAsc
     * @param title
     * @return
     */
    public Page<SpuDO> getProductPageFromDB(Integer pageNo, Integer pageSize, Long categoryId, String orderBy, Boolean isAsc, String title) throws ServiceException {
        QueryWrapper<SpuDO> wrapper = new QueryWrapper<SpuDO>();
        wrapper.select(SPU_EXCLUDE_DETAIL_FIELDS);
        if (orderBy != null && isAsc != null) {
            if (isAsc) {
                wrapper.orderByAsc(orderBy);
            } else {
                wrapper.orderByDesc(orderBy);
            }
        }
        if (categoryId != null) {
            wrapper.eq("category_id", categoryId);
        }
        if (!ObjectUtils.isEmpty(title)) {
            wrapper.like("title", title);
        }
        return spuMapper.selectPage(Page.div(pageNo, pageSize, SpuDO.class), wrapper);
    }

    /**
     * ???????????????????????????Id????????? ????????????
     *
     * @param categoryId
     * @return
     */
    private List<SpuDO> getProductIdsOnSaleFromDB(Long categoryId) throws ServiceException {
        if (categoryId != null) {
            List<Long> categoryFamily = categoryBizService.getCategorySelfAndChildren(categoryId);
            return spuMapper.selectList(new QueryWrapper<SpuDO>().select("id", "sales").eq("status", StatusType.ACTIVE.getCode()).in("category_id", categoryFamily));
        } else {
            return spuMapper.selectList(new QueryWrapper<SpuDO>().eq("status", StatusType.ACTIVE.getCode()).select("id", "sales"));
        }
    }

    /**
     * ?????????????????????SKU????????????
     *
     * @param skuIds
     * @return
     */
    public List<SkuDTO> getSkuListByIds(List<Long> skuIds) {
        return skuMapper.getSkuDTOListByIds(skuIds);
    }

    /**
     * ?????????????????????SPU,???????????????detail??????
     *
     * @param id
     * @return
     */
    public SpuDO getProductByIdFromDB(Long id) {
        return spuMapper.selectOne(new QueryWrapper<SpuDO>().select(SPU_EXCLUDE_DETAIL_FIELDS).eq("id", id));
    }

    public SpuDO getProductByIdFromDBForUpdate(Long id) {
        return spuMapper.selectOne(new QueryWrapper<SpuDO>().select(SPU_EXCLUDE_DETAIL_FIELDS).eq("id", id).last(" FOR UPDATE"));
    }

    /**
     * TODO ??????????????????SPU?????????detail??????
     *
     * @param spuId
     * @return
     */
    public SpuDTO getProductByIdFromCache(Long spuId) throws ServiceException {
        SpuDTO spuDTO = null; // cacheComponent.getHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + spuId, SpuDTO.class);
        if (spuDTO == null) {
            SpuDO spuDO = spuMapper.selectOne(new QueryWrapper<SpuDO>().select(ProductBizService.SPU_EXCLUDE_DETAIL_FIELDS).eq("id", spuId));
            if (spuDO != null) {
                spuDTO = new SpuDTO();
                BeanUtils.copyProperties(spuDO, spuDTO);
                List<Long> categoryFamily = categoryBizService.getCategoryFamily(spuDO.getCategoryId());
                spuDTO.setCategoryIds(categoryFamily);
//                cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + spuId, spuDTO);
            } else {
                throw new BizServiceException(ExceptionDefinition.PRODUCT_NOT_EXIST);
            }
        }
        return spuDTO;
    }

    /**
     * ????????????
     *
     * @param skuStockMap
     */
    public void decSkuStock(Map<Long, Integer> skuStockMap) {
        skuStockMap.forEach((k, v) -> skuMapper.decSkuStock(k, v));
    }

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * ??????????????????
     *
     * @param skuSalesMap
     */
    public void incSpuSales(Map<Long, Integer> skuSalesMap) {
        skuSalesMap.forEach((k, v) -> {
            // 1. ???????????????
            spuMapper.incSales(k, v);
            // 2. ????????????
            SpuDTO spuDtoFromCache = cacheComponent.getHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + k, SpuDTO.class);
            int isTheSame = -1;
            Double nullSource = cacheComponent.incZSetSource(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + null, "P" + k, v);
            if (nullSource != null) {
                isTheSame = (int) Math.round(nullSource);
            }
            for (Long categoryId : spuDtoFromCache.getCategoryIds()) {
                Double source = cacheComponent.incZSetSource(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + categoryId, "P" + k, v);
                if (source != null) {
                    int i = (int) Math.round(source);
                    if (i != isTheSame) {
                        // ????????????
                        isTheSame = - 1;
                    }
                }
            }
            if (isTheSame == -1) {
                // ??????????????????????????????????????? ??????????????????????????????????????????
                transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(TransactionStatus transactionStatus) {
                        // ?????????ID????????????????????????
                        SpuDO newSpuDO = ProductBizService.this.getProductByIdFromDBForUpdate(k);
                        List<Long> categoryFamily = categoryBizService.getCategoryFamily(newSpuDO.getCategoryId());
                        SpuDTO newSpuDto = new SpuDTO();
                        BeanUtils.copyProperties(newSpuDO, newSpuDto);
                        newSpuDto.setCategoryIds(categoryFamily);
                        cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + k, newSpuDto);
                        for (Long categoryId : categoryFamily) {
                            cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + categoryId, newSpuDO.getSales(), "P" + newSpuDO.getId());
                        }
                        cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + null, newSpuDO.getSales(), "P" + newSpuDO.getId());
                    }
                });
            } else {
                spuDtoFromCache.setSales(isTheSame);
                cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + k, spuDtoFromCache);
            }
            // TODO 3. ??????????????????
//            final SpuDTO finalSpuDto = spuDtoFromCache;
//            GlobalExecutor.execute(() -> {
//                if (searchEngine != null) {
//                    SpuDO newSpuDO = new SpuDO();
//                    BeanUtils.copyProperties(finalSpuDto, newSpuDO);
//                    searchEngine.dataTransmission(newSpuDO);
//                }
//            });
        });
    }

    /**
     * ??????????????????
     *
     * @param barCode
     * @param stock
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public boolean adjustSkuStock(String barCode, Integer stock) {
        SkuDO skuFromDB = skuMapper.selectOne(new QueryWrapper<SkuDO>().select("id", "stock").eq("bar_code", barCode));
        if (skuFromDB == null || skuFromDB.getStock().intValue() == stock.intValue()) {
            return false;
        }
        SkuDO skuDO = new SkuDO();
        skuDO.setId(skuFromDB.getId());
        skuDO.setStock(stock);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheComponent.putHashRaw(CacheConst.PRT_SKU_STOCK_BUCKET, "K" + skuDO.getId(), skuDO.getStock() + "");
            }
        });
        return skuMapper.updateById(skuDO) > 0;
    }

    /**
     * 1.??????????????????ZSET
     * 2.??????????????????Hash???
     * 3.?????????????????????Hash??????????????????????????????
     *
     * @param spuDO
     */
    public void createSpuCache(SpuDO spuDO) {
        // 1. ??????????????????
        List<Long> categoryFamily = categoryBizService.getCategoryFamily(spuDO.getCategoryId());
        if (spuDO.getStatus().intValue() == StatusType.ACTIVE.getCode()) {
            for (Long categoryId : categoryFamily) {
                cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_ID_ZSET + categoryId, spuDO.getId(), "P" + spuDO.getId());
                cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_PRICE_ZSET + categoryId, spuDO.getPrice(), "P" + spuDO.getId());
                cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + categoryId, 0, "P" + spuDO.getId());
            }
            cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_ID_ZSET + null, spuDO.getId(), "P" + spuDO.getId());
            cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_PRICE_ZSET + null, spuDO.getPrice(), "P" + spuDO.getId());
            cacheComponent.putZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + null, 0, "P" + spuDO.getId());
        }
        // 2. ??????Hash??????
        spuDO.setDetail(null);
        SpuDTO newSpuDTO = new SpuDTO();
        BeanUtils.copyProperties(spuDO, newSpuDTO);
        newSpuDTO.setCategoryIds(categoryFamily);
        cacheComponent.putHashObj(CacheConst.PRT_SPU_HASH_BUCKET, "P" + spuDO.getId(), newSpuDTO);
    }

    /**
     * 1.??????????????????ZSET
     * 2.??????????????????Hash???
     * 3.????????????????????????Hash???
     *
     * @param spuDO
     */
    public void deleteSpuCache(SpuDO spuDO) {
        // 1. ??????????????????ZSET
        List<Long> categoryFamily = categoryBizService.getCategoryFamily(spuDO.getCategoryId());
        for (Long categoryId : categoryFamily) {
            cacheComponent.delZSet(CacheConst.PRT_CATEGORY_ORDER_ID_ZSET + categoryId, "P" + spuDO.getId());
            cacheComponent.delZSet(CacheConst.PRT_CATEGORY_ORDER_PRICE_ZSET + categoryId, "P" + spuDO.getId());
            cacheComponent.delZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + categoryId, "P" + spuDO.getId());
        }
        cacheComponent.delZSet(CacheConst.PRT_CATEGORY_ORDER_ID_ZSET + null, "P" + spuDO.getId());
        cacheComponent.delZSet(CacheConst.PRT_CATEGORY_ORDER_PRICE_ZSET + null, "P" + spuDO.getId());
        cacheComponent.delZSet(CacheConst.PRT_CATEGORY_ORDER_SALES_ZSET + null, "P" + spuDO.getId());
        // 2. ??????????????????Hash???
        cacheComponent.delHashKey(CacheConst.PRT_SPU_HASH_BUCKET, "P" + spuDO.getId());
        // 3. ????????????????????????Hash???
        cacheComponent.delHashKey(CacheConst.PRT_SPU_DETAIL_HASH_BUCKET, "P" + spuDO.getId());
    }

}
