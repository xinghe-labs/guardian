package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * demo 应用。没有任何 guardian 相关的显式配置类/组件扫描——
 * 自动装配是否生效，取决于 starter 的 AutoConfiguration.imports，
 * 这正是这个 demo 要验证的事。
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
