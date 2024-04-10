package cn.likn.nacosfresh.config;

import cn.likn.nacosfresh.core.NacosStaticRefreshConfigListener;
import com.alibaba.cloud.nacos.NacosConfigManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

public class NacosConfigListenerAutoConfiguration {

    @Bean("nacosStaticRefreshConfigListener")
    public NacosStaticRefreshConfigListener nacosStaticRefreshConfigListener(ApplicationContext context,
                                                                             NacosConfigManager configManager) {
        return new NacosStaticRefreshConfigListener(context, configManager);
    }

}
