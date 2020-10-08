package com.shuyuan.judd.bizgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableEurekaClient
@EnableFeignClients
@SpringBootApplication(exclude= DataSourceAutoConfiguration.class)
public class BizGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(BizGatewayApplication.class, args);
    }

}
