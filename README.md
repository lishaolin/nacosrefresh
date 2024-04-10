现系统内部部分工具类提供静态方法，静态方法若想读取naocs的配置信息需先拿到naocs的配置信息随后赋值给静态变量，此时静态变量就不可热更新了

现提供注解可实时热刷新静态变量

`@NacosStaticRefresh`加在类上，标识该类的静态属性需要热更新

```
@Component
@NacosStaticRefresh
public class Util 
```

`@NacosStaticValue`加载需要更新的字段上，标识该字段需要热更新

```
    @NacosStaticValue("operations.test.test")
    private static String staticTest;
```

`@NacosStaticValue`有一个必填的value字段，该value需与nacos上的配置全路径相同

```
    @Value("${operations.test.test}")
    private String test;

    @NacosStaticValue("operations.test.test")
    private static String staticTest;
```

该注解可能失效的情况：

1. `@NacosStaticRefresh`标识的类并非被Spring托管的bean
2. 该配置未配置在系统对应的dataId的yml文件中 dataId的生成规则： `${prefix}-${spring.profiles.active}.${file-extension}` `prefix` 默认为 spring.application.name 的值，也可以通过配置项 `spring.cloud.nacos.config.prefix`来配置。 `spring.profiles.active` 即为当前环境对应的 profile `file-exetension` 为配置内容的数据格式，可以通过配置项 `spring.cloud.nacos.config.file-extension` 来配置。

