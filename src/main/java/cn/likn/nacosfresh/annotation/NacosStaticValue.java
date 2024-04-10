package cn.likn.nacosfresh.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Documented
public @interface NacosStaticValue {

    String value();

}
