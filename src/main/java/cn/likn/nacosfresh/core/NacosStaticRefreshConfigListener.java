package cn.likn.nacosfresh.core;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.likn.nacosfresh.annotation.NacosStaticRefresh;
import cn.likn.nacosfresh.annotation.NacosStaticValue;
import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.exception.NacosException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * nacos 自定义监听
 *
 * @author zch
 */
@Slf4j
@RequiredArgsConstructor
public class NacosStaticRefreshConfigListener {

    @Value("${spring.profiles.active:}")
    private String active;

    @Value("${spring.application.name:}")
    private String name;

    @Value("${spring.cloud.nacos.config.file-extension:}")
    private String fileExtension;

    @Value("${spring.cloud.nacos.config.prefix:}")
    private String prefix;

    @Value("${spring.cloud.nacos.config.group:}")
    private String group;

    // 获取系统中的bean
    private final ApplicationContext applicationContext;

    // 获取naocs的配置
    private final NacosConfigManager nacosConfigManager;

    /**
     * 井号
     */
    public final static String WELL_NO = "#";

    /**
     * 中分割符
     */
    public static final String MIDDLE_LINE = "-";

    /**
     * 点
     */
    public static final String DOT = ".";

    /**
     * 空字符串
     */
    public final static String EMPTY_STRING = "";

    /**
     * 美元符号
     */
    public final static String DOLLAR = "$";


    /**
     * 左大括号
     */
    public final static String LEFT_BRACE = "{";

    /**
     * 右大括号
     */
    public final static String RIGHT_BRACE = "}";

    /**
     * 冒号
     */
    public static final String COLON = ":";

    /**
     * 该map存储以下信息
     * key -->  {@link NacosStaticValue}注解里填写的value，也就是nacos的配置路径， eg.spring.cloud.nacos.config.prefix
     * value --> bean名称和bean中被NacosStaticValue标注的字段名称, eg: larkLog#infoUrl
     */
    private static final Map<String, List<String>> properties2BeanNameList = new ConcurrentHashMap<>();


    /**
     * NACOS监听方法
     */
    public void listener() throws NacosException {
        ConfigService configService = nacosConfigManager.getConfigService();
        if (configService == null) {
            return;
        }
        // 添加nacos配置更新监听
        configService.addListener(getDataId(), group, new AbstractListener() {
            @Override
            public void receiveConfigInfo(String configInfo) {
                try {
                    // 将String的yaml文件解析成map
                    Map<String, Object> configMap = new Yaml().load(new StringReader(configInfo));
                    refresh(configMap);
                } catch (Exception e) {
                    log.error("更新静态变量时发生异常", e);
                }

            }
        });
    }

    /**
     * 刷新的核心方法
     *
     * @param configMap configMap 配置的map
     */
    private void refresh(Map<String, Object> configMap) {
        // 若map为空，则初始化map
        if (properties2BeanNameList.isEmpty()) {
            initProperties2BeanNameMap();
        }
        // 以系统中打过注解的类为基去匹配nacos的配置
        for (Map.Entry<String, List<String>> entry : properties2BeanNameList.entrySet()) {
            // 配置路径，也就是@NacosStaticValue的value值
            String propertiesNameList = entry.getKey();
            // 获取该配置对应的值
            Object nacosValue = getNacosValue(propertiesNameList, configMap);
            if (nacosValue == null) {
                continue;
            }
            // bean名称和对应的字段的name
            List<String> beanNameAndFileNames = entry.getValue();

            if (CollectionUtil.isEmpty(beanNameAndFileNames)) {
                continue;
            }

            // 更新配置对应的变量的值
            for (String beanNameAndFileName : beanNameAndFileNames) {
                List<String> split = StrUtil.split(beanNameAndFileName, WELL_NO);
                String beanName = split.get(0);
                String fileName = split.get(1);
                Object bean = SpringUtil.getBean(beanName);
                if (null == bean) {
                    return;
                }
                Field field = ReflectUtil.getField(bean.getClass(), fileName);
                Object fieldValue = ReflectUtil.getFieldValue(bean, field);

                // 集合类型
                if (field.getType().equals(List.class)) {
                    if (!JSONUtil.parseArray(fieldValue).equals(JSONUtil.parseArray(nacosValue))) {
                        ReflectUtil.setFieldValue(bean, field, nacosValue);
                        log.info("静态变量：{}更新成功, 更新前：{}, 更新后:{}", propertiesNameList, fieldValue, nacosValue);
                    }
                    continue;
                }
                // String类型
                if (field.getType().equals(String.class)) {
                    String value = (String) fieldValue;
                    String nacosValueStr = String.valueOf(nacosValue);
                    if (!StrUtil.equals(value, nacosValueStr)) {
                        StringBuffer sBuffer = new StringBuffer();
                        Pattern pattern = Pattern.compile("\\$\\{([^}]+)}");
                        Matcher matcher = pattern.matcher(nacosValueStr);
                        while (matcher.find()) {
                            String matchStr = matcher.group(1);
                            matcher.appendReplacement(sBuffer,String.valueOf(getNacosValue(matchStr, configMap)));
                        }
                        matcher.appendTail(sBuffer);
                        ReflectUtil.setFieldValue(bean, field, sBuffer.toString());
                        log.info("静态变量：{}更新成功, 更新前：{}, 更新后:{}", propertiesNameList, fieldValue, nacosValue);
                    }
                    continue;
                }
                // Integer,Boolean,Long等
                if (isBaseObject(field)) {
                    if (!Objects.equals(fieldValue, nacosValue)) {
                        ReflectUtil.setFieldValue(bean, field, nacosValue);
                        log.info("静态变量：{}更新成功, 更新前：{}, 更新后:{}", propertiesNameList, fieldValue, nacosValue);
                    }
                    continue;
                }
                // 其余类型
                if (!JSONUtil.parseObj(fieldValue).equals(JSONUtil.parseObj(nacosValue))) {
                    JSONObject object = JSONUtil.parseObj(nacosValue);
                    ReflectUtil.setFieldValue(bean, field, object.toBean(field.getType()));
                    log.info("静态变量：{}更新成功, 更新前：{}, 更新后:{}", propertiesNameList, fieldValue, nacosValue);
                }
            }
        }
    }

    // 获取dataID
    private String getDataId() {
        String fPrefix = name;
        if (StrUtil.isNotBlank(prefix)) {
            fPrefix = prefix;
        }
        return StrUtil.concat(false, fPrefix, MIDDLE_LINE, active, DOT, fileExtension);
    }

    // 是否为以下的几种类型2
    public boolean isBaseObject(Field field) {
        return field.getType().equals(Integer.class) || field.getType().equals(Boolean.class) || field.getType().equals(Long.class);
    }

    /**
     * bean初始化
     *
     * @throws NacosException NacosException
     */
    @PostConstruct
    public void init() throws NacosException {
        listener();
    }

    /**
     * 初始化map
     */
    private void initProperties2BeanNameMap() {
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean = applicationContext.getBean(beanName);
            if (bean.getClass().isAnnotationPresent(NacosStaticRefresh.class)) {
                Field[] fields = ReflectUtil.getFields(bean.getClass());
                for (Field field : fields) {
                    if (field.isAnnotationPresent(NacosStaticValue.class)) {
                        NacosStaticValue annotation = field.getAnnotation(NacosStaticValue.class);
                        if (null != annotation) {
                            String value = annotation.value().replace(WELL_NO, EMPTY_STRING).replace(DOLLAR, EMPTY_STRING).replace(LEFT_BRACE, EMPTY_STRING).replace(RIGHT_BRACE, EMPTY_STRING).replace(COLON, EMPTY_STRING);
                            String name = field.getName();
                            if (properties2BeanNameList.get(value) == null) {
                                List<String> beanNameList = new ArrayList<>();
                                beanNameList.add(beanName.concat(WELL_NO).concat(name));
                                properties2BeanNameList.put(value, beanNameList);
                            } else {
                                properties2BeanNameList.get(value).add(beanName.concat(WELL_NO).concat(name));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取nacos配置对应的value
     *
     * @param propertiesNameList 配置
     * @param configMap          配置map
     * @return nacos配置对应的value
     */
    private Object getNacosValue(String propertiesNameList, Map<String, Object> configMap) {
        // 将propertiesNameList通过 . 分开，层层寻找到该配置的值
        List<String> propertiesN = StrUtil.split(propertiesNameList, DOT);
        if (CollectionUtil.isEmpty(propertiesN)) {
            return null;
        }
        Map<String, Object> nestedMap = null;
        Object nacosValue = null;
        for (int i = 0; i < propertiesN.size(); i++) {
            if (i == 0) {
                nestedMap = configMap;
            }
            if (i != propertiesN.size() - 1) {
                Object o = nestedMap.get(propertiesN.get(i));
                if (o instanceof Map) {
                    nestedMap = (Map<String, Object>) o;
                } else {
                    return null;
                }
            } else {
                nacosValue = nestedMap.get(propertiesN.get(i));
            }
        }
        return nacosValue;
    }
}