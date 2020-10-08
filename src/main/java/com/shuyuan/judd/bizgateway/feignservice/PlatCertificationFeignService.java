package com.shuyuan.judd.bizgateway.feignservice;

import com.shuyuan.judd.bizgateway.constant.ServiceConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import spring.shuyuan.judd.base.model.Response;

@FeignClient(value = ServiceConstants.PLATCERTIFICATION_SERVICE_NAME, path = "/signature", decode404 = true)
public interface PlatCertificationFeignService {
    @PostMapping("/sign")
    Response<String> sign(@RequestParam(name="content") String content);
    @PostMapping("/check")
    Response<String> check(@RequestParam(name="content") String content, @RequestParam(name="signature") String signature);
}
