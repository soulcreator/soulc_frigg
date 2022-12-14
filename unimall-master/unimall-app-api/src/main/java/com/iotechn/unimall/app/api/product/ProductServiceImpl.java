package com.iotechn.unimall.app.api.product;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dobbinsoft.fw.core.exception.ServiceException;
import com.dobbinsoft.fw.support.component.CacheComponent;
import com.dobbinsoft.fw.support.model.Page;
import com.iotechn.unimall.biz.service.footprint.FootprintBizService;
import com.iotechn.unimall.biz.service.freight.FreightTemplateBizService;
import com.iotechn.unimall.biz.service.groupshop.GroupShopBizService;
import com.iotechn.unimall.biz.service.product.ProductBizService;
import com.iotechn.unimall.data.constant.CacheConst;
import com.iotechn.unimall.data.domain.SkuDO;
import com.iotechn.unimall.data.domain.SpuAttributeDO;
import com.iotechn.unimall.data.domain.SpuDO;
import com.iotechn.unimall.data.domain.SpuSpecificationDO;
import com.iotechn.unimall.data.dto.product.GroupShopDTO;
import com.iotechn.unimall.data.dto.product.SpuDTO;
import com.iotechn.unimall.data.enums.BizType;
import com.iotechn.unimall.data.enums.SpuActivityType;
import com.iotechn.unimall.data.mapper.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;


/**
 * Created by rize on 2019/7/2.
 */
@Service
public class ProductServiceImpl implements ProductService {

    @Autowired
    private ProductBizService productBizService;

    @Autowired
    private CacheComponent cacheComponent;

    @Autowired
    private SpuMapper spuMapper;

    @Autowired
    private ImgMapper imgMapper;

    @Autowired
    private SkuMapper skuMapper;

    @Autowired
    private SpuAttributeMapper spuAttributeMapper;

    @Autowired
    private SpuSpecificationMapper spuSpecificationMapper;

    @Autowired
    private FootprintBizService footprintBizService;

    @Autowired
    private GroupShopBizService groupShopBizService;

    @Autowired
    private FreightTemplateBizService freightTemplateBizService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Override
    public Page<SpuDO> getProductPage(Integer pageNo, Integer pageSize, Long categoryId, String orderBy, Boolean isAsc, String title) throws ServiceException {
        if (categoryId != null && categoryId <= 0) {
            categoryId = null;
        }
        return productBizService.getProductPage(pageNo, pageSize, categoryId, orderBy, isAsc, title);
    }

    @Override
    public SpuDTO getProduct(Long spuId, Long userId) throws ServiceException {
        // 1. ??????????????????
        SpuDTO spuDTO = productBizService.getProductByIdFromCache(spuId);
        // 2.
        // 2.1. ???????????????????????????????????????????????????????????????????????????????????????????????????????????????
        SpuDTO detailSpuInfo = cacheComponent.getHashObj(CacheConst.PRT_SPU_DETAIL_HASH_BUCKET, "P" + spuId, SpuDTO.class);
        if (detailSpuInfo == null) {
            // ???DB???????????????
            detailSpuInfo = new SpuDTO();
            // 1. SkuDOList
            List<SkuDO> skuDOList = skuMapper.selectList(new QueryWrapper<SkuDO>().eq("spu_id", spuDTO.getId()));
            detailSpuInfo.setSkuList(skuDOList);
            // 2. ????????????
            List<String> imgs = imgMapper.getImgs(BizType.GOODS.getCode(), spuId);
            detailSpuInfo.setImgList(imgs);
            // 3. ??????detail
            SpuDO spuLoadDetail = spuMapper.selectOne(new QueryWrapper<SpuDO>().select("detail").eq("id", spuId));
            detailSpuInfo.setDetail(spuLoadDetail.getDetail());
            // 4. ??????????????????
            List<SpuAttributeDO> attributeList = spuAttributeMapper.selectList(new QueryWrapper<SpuAttributeDO>().eq("spu_id", spuId));
            detailSpuInfo.setAttributeList(attributeList);
            // 5. ??????????????????
            List<SpuSpecificationDO> specificationList = spuSpecificationMapper.selectList(new QueryWrapper<SpuSpecificationDO>().eq("spu_id", spuId));
            detailSpuInfo.setSpecificationList(specificationList);
            cacheComponent.putHashObj(CacheConst.PRT_SPU_DETAIL_HASH_BUCKET, "P" + spuId, detailSpuInfo);
        }
        // 2.2. ?????????????????? N ?????????
        BeanUtils.copyProperties(spuDTO, detailSpuInfo, "skuList", "imgList", "detail", "attributeList", "specificationList");
        spuDTO = detailSpuInfo;
        // 3. ????????????Sku?????????
        spuDTO.getSkuList().forEach(item -> {
            String stockStr = cacheComponent.getHashRaw(CacheConst.PRT_SKU_STOCK_BUCKET, "K" + item.getId());
            if (stockStr == null) {
                // ?????????????????????????????????????????????
                transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(TransactionStatus transactionStatus) {
                        // ?????????????????????????????????
                        Integer stockForUpdate = skuMapper.getStockForUpdate(item.getId());
                        cacheComponent.putHashRaw(CacheConst.PRT_SKU_STOCK_BUCKET, "K" + item.getId(), stockForUpdate + "");
                        item.setStock(stockForUpdate);
                    }
                });
            } else {
                Integer stock = Integer.parseInt(stockStr);
                item.setStock(stock);
            }
        });
        // 4. ???????????? MicroFix ??????????????????RPC??????????????????detailSpuInfo???????????????????????????????????????????????????detailSpuInfo?????????
        spuDTO.setFreightTemplate(freightTemplateBizService.getFreightTemplateById(spuDTO.getFreightTemplateId()));
        if (userId != null) {
            // ?????????????????? & ??????????????????LIKE?????????
            footprintBizService.newFootprint(spuId, userId);
            spuDTO.setFavorite(cacheComponent.getHashRaw(CacheConst.PRT_USER_FAVORITE_HASH_BUCKET + spuId, "U" + userId) != null);
        }
        // 5. ??????????????????
        if (spuDTO.getActivityType() != null && spuDTO.getActivityType() != SpuActivityType.NONE.getCode()
                && spuDTO.getGmtActivityEnd() != null && spuDTO.getGmtActivityEnd().getTime() > System.currentTimeMillis()) {
            // ????????????????????????????????????????????????????????????????????????????????????????????????BizService??????????????????????????????????????????????????????
            if (spuDTO.getActivityType() == SpuActivityType.GROUP_SHOP.getCode()) {
                GroupShopDTO groupShopDTO = groupShopBizService.getGroupShopById(spuDTO.getActivityId());
                spuDTO.setActivity(groupShopDTO);
            }
        }
        return spuDTO;
    }
}
