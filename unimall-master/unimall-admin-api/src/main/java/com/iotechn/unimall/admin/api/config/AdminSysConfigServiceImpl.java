package com.iotechn.unimall.admin.api.config;

import cn.hutool.core.codec.Base64;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dobbinsoft.fw.core.exception.ServiceException;
import com.dobbinsoft.fw.support.component.dynamic.DynamicConfigComponent;
import com.iotechn.unimall.data.domain.DynamicConfigDO;
import com.iotechn.unimall.data.mapper.DynamicConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Description:
 * User: rize
 * Date: 2020/8/5
 * Time: 16:49
 */
@Service("adminSysConfigService")
public class AdminSysConfigServiceImpl implements AdminSysConfigService {

    @Autowired(required = false)
    private DynamicConfigComponent dynamicConfigComponent;

    @Autowired(required = false)
    private DynamicConfigMapper dynamicConfigMapper;

    @Override
    public List<DynamicConfigDO> getData(Long adminId) throws ServiceException {
        return dynamicConfigMapper.selectList(new QueryWrapper<>());
    }

    @Override
    public String save(String configsStr, String prefix, Long adminId) throws ServiceException {
        List<DynamicConfigDO> configs = JSONObject.parseArray(configsStr, DynamicConfigDO.class);
        for (DynamicConfigDO dynamicConfigDO : configs) {
            dynamicConfigComponent.write(prefix + dynamicConfigDO.getConfigKey(), dynamicConfigDO.getConfigValue());
        }
        return "ok";
    }

}
