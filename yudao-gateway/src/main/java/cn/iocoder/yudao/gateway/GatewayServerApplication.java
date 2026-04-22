package cn.iocoder.yudao.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayServerApplication {

    /**
     * 网关启动
     * @param args
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayServerApplication.class, args);
    }

}
