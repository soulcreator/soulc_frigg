//package com.iotechn.unimall.admin.api.tools;
//
//import com.baomidou.mybatisplus.annotation.TableName;
//import com.iotechn.unimall.data.domain.GeneratorTableColumnDO;
//import com.iotechn.unimall.data.dto.CodeReverseGenerateDTO;
//import com.iotechn.unimall.data.dto.CodeReverseGenerateTableDTO;
//import com.iotechn.unimall.data.mapper.GeneratorMapper;
//import freemarker.cache.FileTemplateLoader;
//import freemarker.template.Configuration;
//import freemarker.template.Template;
//import freemarker.template.TemplateException;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.io.File;
//import java.io.FileWriter;
//import java.io.IOException;
//import java.util.Date;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//import java.util.stream.Collectors;
//
///**
// * Created with IntelliJ IDEA.
// * Description:
// * User: rize
// * Date: 2020/3/11
// * Time: 11:03
// */
//@Service("generatorService")
//public class GeneratorServiceImpl implements GeneratorService {
//
//    @Autowired
//    private GeneratorMapper generatorMapper;
//
//    private static final Logger logger = LoggerFactory.getLogger(GeneratorServiceImpl.class);
//
//    @Override
//    public List<CodeReverseGenerateTableDTO> listTable(Long adminId) throws ServiceException {
//        List<String> strings = generatorMapper.listTables();
//        List<CodeReverseGenerateTableDTO> tableDTOList = strings.stream().map(item -> {
//            CodeReverseGenerateTableDTO tableDTO = new CodeReverseGenerateTableDTO();
//            tableDTO.setExist(checkMapperExist(item));
//            tableDTO.setName(item);
//            return tableDTO;
//        }).collect(Collectors.toList());
//        return tableDTOList;
//    }
//
//    private boolean checkMapperExist(String tableName) {
//        Set<Class<?>> set = ClassScannerUtil.getClasses("com.iotechn.unimall.data.domain");
//        for (Class clazz : set) {
//            TableName annotation = (TableName) clazz.getDeclaredAnnotation(TableName.class);
//            if (annotation != null) {
//                String value = annotation.value();
//                if (value != null) {
//                    if (tableName.equals(value)) {
//                        return true;
//                    }
//                }
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public List<CodeReverseGenerateDTO.ColumnDefinition> loadColumns(String tableName, Long adminId) throws ServiceException {
//        List<GeneratorTableColumnDO> generatorTableColumnDOS = generatorMapper.listColumns(tableName);
//        List<CodeReverseGenerateDTO.ColumnDefinition> columnDefinitions = generatorTableColumnDOS.stream().map(item -> {
//            CodeReverseGenerateDTO.ColumnDefinition definition = new CodeReverseGenerateDTO.ColumnDefinition();
//            definition.setName(item.getField());
//            definition.setClazz(mappingJavaClass(item.getType()));
//            definition.setAlias(lineToHump(item.getField()));
//            definition.setChinese(definition.getAlias());
//            // ?????????NULL = NO??????????????????????????????NotNull???????????????True
//            definition.setNotnull(item.getNull().equals("NO"));
//            definition.setMoney(false);
//            definition.setInsertColumn(false);
//            definition.setQuery(false);
//            definition.setLikeQuery(false);
//            definition.setShowInList(false);
//            definition.setNotnull(false);
//            return definition;
//        }).collect(Collectors.toList());
//        return columnDefinitions;
//    }
//
//    private String mappingJavaClass(String type) {
//        if (type.startsWith("varchar")) {
//            return String.class.getName();
//        } else if (type.startsWith("int")) {
//            return Integer.class.getName();
//        } else if (type.startsWith("bigint")) {
//            return Long.class.getName();
//        } else if (type.startsWith("date")) {
//            return Date.class.getName();
//        } else {
//            // ??????????????????????????????
//            return "";
//        }
//    }
//
//    private String lineToHump(String str) {
//        Pattern linePattern = Pattern.compile("_(\\w)");
//        str = str.toLowerCase();
//        Matcher matcher = linePattern.matcher(str);
//        StringBuffer sb = new StringBuffer();
//        while (matcher.find()) {
//            matcher.appendReplacement(sb, matcher.group(1).toUpperCase());
//        }
//        matcher.appendTail(sb);
//        return sb.toString();
//    }
//
//    @Override
//    public String generate(CodeReverseGenerateDTO generateDTO, Long adminId) throws ServiceException {
//        String workDirStr = System.getProperty("user.dir");
//        File workDir = new File(workDirStr);
//        if (workDir.exists()) {
//            try {
//                // ??????????????????????????????????????????????????????????????????????????????????????????????????????
//                String templateBaseDir = workDirStr + "/unimall-admin-api/src/main/resources";
//                FileTemplateLoader fileTemplateLoader = new FileTemplateLoader(new File(templateBaseDir));
//                Configuration configuration = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
//                configuration.setTemplateLoader(fileTemplateLoader);
//                String title = generateDTO.getTitle();
//                String[] split = title.split("\\.");
//                String packageName = split[0];
//                String serviceName = split[1];
//                String doName = lineToHump(generateDTO.getTableName().replace("unimall_", ""));
//                generateDTO.setDoLowCaseName(doName);
//                doName = doName.replaceFirst(doName.substring(0,1), doName.substring(0,1).toUpperCase());
//                generateDTO.setDoName(doName);
//                generateDTO.setPackageName(packageName);
//                generateDTO.setServiceName(serviceName);
//                generateDTO.setServiceLowCaseName(serviceName.toLowerCase());
//                Set<String> importClassNames = new HashSet<>();
//                for (CodeReverseGenerateDTO.ColumnDefinition columnDefinition : generateDTO.getColumnDefinitionList()) {
//                    if (!columnDefinition.getClazz().startsWith("java.lang")) {
//                        importClassNames.add(columnDefinition.getClazz());
//                    }
//                    String substring = columnDefinition.getClazz().substring(columnDefinition.getClazz().lastIndexOf(".") + 1);
//                    columnDefinition.setClazzName(substring);
//                }
//                generateDTO.setImportClasses(importClassNames);
//                // step1. ??????DO??????
//                Template doTemplate = configuration.getTemplate("do.ftl", "utf-8");
//                writeFile(workDirStr + "/unimall-data/src/main/java/com/iotechn/unimall/data/domain/" + doName + "DO.java", doTemplate, generateDTO);
//                // step2. ??????Mapper
//                Template mapperTemplate = configuration.getTemplate("mapper.ftl", "utf-8");
//                writeFile(workDirStr + "/unimall-data/src/main/java/com/iotechn/unimall/data/mapper/" + doName + "Mapper.java", mapperTemplate, generateDTO);
//                // step3. ??????Service??????
//                Template serviceTemplate = configuration.getTemplate("service.ftl", "utf-8");
//                String apiDirStr = workDirStr + "/unimall-admin-api/src/main/java/com/iotechn/unimall/admin/api/" + packageName;
//                File apiDir = new File(apiDirStr);
//                if (!apiDir.exists()) {
//                    apiDir.mkdir();
//                }
//                writeFile(workDirStr + "/unimall-admin-api/src/main/java/com/iotechn/unimall/admin/api/" + packageName + "/Admin" + serviceName + "Service.java", serviceTemplate, generateDTO);
//                // step4. ??????Service??????
//                Template serviceImplTemplate = configuration.getTemplate("serviceImpl.ftl", "utf-8");
//                writeFile(workDirStr + "/unimall-admin-api/src/main/java/com/iotechn/unimall/admin/api/" + packageName + "/Admin" + serviceName + "ServiceImpl.java", serviceImplTemplate, generateDTO);
//                // step5. ??????api/*.js??????
//                Template apijsTemplate = configuration.getTemplate("apijs.ftl", "utf-8");
//                writeFile(workDirStr + "/unimall-admin/src/api/" + generateDTO.getPageName() + ".js", apijsTemplate, generateDTO);
//                // step6. ????????????
//                Template pageTemplate = configuration.getTemplate("page.ftl", "utf-8");
//                writeFile(workDirStr + "/unimall-admin/src/views/" + generateDTO.getFolder() + "/" + generateDTO.getPageName() + ".vue", pageTemplate, generateDTO);
//                return "ok";
//            } catch (IOException e) {
//                logger.error("[????????????] IO??????", e);
//                throw new AdminServiceException(ExceptionDefinition.ADMIN_GENERATOR_IO_EXCEPTION);
//            } catch (TemplateException e) {
//                logger.error("[????????????] ??????", e);
//                throw new AdminServiceException(ExceptionDefinition.ADMIN_GENERATOR_TEMPLATE_EXCEPTION);
//            }
//
//        } else {
//            throw new AdminServiceException(ExceptionDefinition.ADMIN_GENERATOR_WORK_DIR_NOT_EXIST);
//        }
//    }
//
//    /**
//     * @param fileName ???????????????
//     * @param template
//     * @param model
//     * @throws IOException
//     */
//    private void writeFile(String fileName, Template template, CodeReverseGenerateDTO model) throws IOException, ServiceException, TemplateException {
//        File file = new File(fileName);
//        if (!file.exists()) {
//            file.createNewFile();
//        } else {
//            throw new AdminServiceException(ExceptionDefinition.ADMIN_GENERATOR_FILE_ALREADY_EXIST);
//        }
//        FileWriter fileWriter = new FileWriter(fileName);
//        template.process(model, fileWriter);
//        fileWriter.flush();
//        fileWriter.close();
//    }
//
//}
