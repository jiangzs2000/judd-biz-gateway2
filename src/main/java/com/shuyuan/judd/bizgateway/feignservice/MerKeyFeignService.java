package com.shuyuan.judd.bizgateway.feignservice;

import com.shuyuan.judd.bizgateway.constant.ServiceConstants;
import com.shuyuan.judd.client.model.merchant.MerKey;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import spring.shuyuan.judd.base.model.Response;

@FeignClient(value = ServiceConstants.FUNDATION_SERVICE_NAME, path = "/merkey", decode404 = true)
public interface MerKeyFeignService {
    @GetMapping("/getByMerNo")
    Response<MerKey> getByMerNo(@RequestParam(name = "merNo") String merNo);
}
