package com.shuyuan.judd.bizgateway.filter;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.shuyuan.judd.bizgateway.constant.ServiceConstants;
import com.shuyuan.judd.bizgateway.feignservice.MerKeyFeignService;
import com.shuyuan.judd.client.constants.BizProtocalFieldConstants;
import com.shuyuan.judd.client.model.merchant.MerKey;
import io.netty.buffer.UnpooledByteBufAllocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spring.shuyuan.judd.base.Bean.SpringUtil;
import spring.shuyuan.judd.base.cache.CacheService;
import spring.shuyuan.judd.base.model.Response;
import spring.shuyuan.judd.base.utils.RSASignatureUtil;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class CheckRequestSignatureFilter implements GlobalFilter, Ordered {

    @Autowired
    private MerKeyFeignService merKeyFeignService;
    @Autowired
    private CacheService cacheService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.debug("************************CheckRequestSignatureFilter*********************");
        exchange.getAttributes().put("startTime", System.currentTimeMillis());
        if (exchange.getRequest().getMethod().equals(HttpMethod.POST)) {
            //重新构造request，参考ModifyRequestBodyGatewayFilterFactory
            ServerRequest serverRequest = ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders());
            MediaType mediaType = exchange.getRequest().getHeaders().getContentType();
            AtomicBoolean passed = new AtomicBoolean(false);
            //重点
            Mono<String> modifiedBody = serverRequest.bodyToMono(String.class).flatMap(body -> {
                /*因为约定了终端传参的格式，所以只考虑json的情况，如果是表单传参，请自行发挥
                if (MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) {
                    JSONObject jsonObject = JSONObject.parseObject(body);
                    String paramStr = jsonObject.getString("param");
                    String newBody;
                    try{
                        newBody = verifySignature(paramStr);
                    }catch (Exception e){
                        return processError(e.getMessage());
                    }
                    return Mono.just(newBody);
                }
                */
                if (MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) {
                    JSONObject jsonObject = JSONObject.parseObject(body);
                    String signature = exchange.getRequest().getHeaders().getFirst(ServiceConstants.SIGNATURE);
                    String merNo = jsonObject.getString(BizProtocalFieldConstants.MER_NO);

                    try {
                        if(verifySignature(merNo, body, signature).getCode().equals(Response.Code.SUCCESS.getCode())){
                            passed.set(true);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();

                    }
                }
                return Mono.just(body);
            });
            if(!passed.get()){
                return processError("验签失败", exchange);
            }
            BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, String.class);
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(exchange.getRequest().getHeaders());
            //猜测这个就是之前报400错误的元凶，之前修改了body但是没有重新写content length
            headers.remove("Content-Length");
            CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);
            return bodyInserter.insert(outputMessage, new BodyInserterContext()).then(Mono.defer(() -> {
                ServerHttpRequest decorator = this.decorate(exchange, headers, outputMessage);
                return returnMono(chain, exchange.mutate().request(decorator).build());
            }));
        } else {
            //GET 验签
            MultiValueMap<String, String> map = exchange.getRequest().getQueryParams();
            if (!CollectionUtils.isEmpty(map)) {
                String merNo = map.getFirst(BizProtocalFieldConstants.MER_NO);
                String signature = exchange.getRequest().getHeaders().getFirst(ServiceConstants.SIGNATURE);
                if(StringUtils.isEmpty(signature)){
                    return processError("验签缺失", exchange);
                }
                Object[] arr = map.keySet().toArray();
                Arrays.sort(arr);
                StringBuffer sb = new StringBuffer();
                for(Object key : arr){
                    sb.append(key).append("=").append(map.get(key)).append("&");
                }
                sb.substring(0,sb.length()-1); //去掉末尾的 &
                try{
                    if(!verifySignature(merNo, sb.toString(), signature).getCode().equals(Response.Code.SUCCESS.getCode())){
                        return processError("验签失败", exchange);
                    }
                }catch (Exception e){
                    return processError(e.getMessage(), exchange);
                }
            }
            return returnMono(chain, exchange);
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private Mono<Void> returnMono(GatewayFilterChain chain,ServerWebExchange exchange){
        return chain.filter(exchange).then(Mono.fromRunnable(()->{
            Long startTime = exchange.getAttribute("startTime");
            if (startTime != null){
                long executeTime = (System.currentTimeMillis() - startTime);
                log.info("耗时：{}ms" , executeTime);
                log.info("状态码：{}" , Objects.requireNonNull(exchange.getResponse().getStatusCode()).value());
            }
        }));
    }

    private Response<String> verifySignature(String merNo, String body, String signature) throws Exception{

        String pubKey = cacheService.getString(ServiceConstants.MER_KEY_PREFIX+merNo);
        if(StringUtils.isEmpty(pubKey)){
            if(merKeyFeignService == null){
                merKeyFeignService = SpringUtil.getBean(MerKeyFeignService.class);
            }
            MerKey mk = merKeyFeignService.getByMerNo(merNo).getData();
            if(mk == null){
                log.info("商户{}未上传公钥", merNo);
                return Response.createNativeFail("商户未上传公钥");
            }
            pubKey = mk.getPublickey();
        }
        if(RSASignatureUtil.doCheck(body,signature,pubKey)){
            log.debug("验签通过{}", body);
            return Response.createSuccess("验签通过");
        }else{
            log.debug("验签失败{}", body);
            return Response.createNativeFail("验签失败");
        }
    }

    private Mono<Void> processError(String message, ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        // header set
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response
                .writeWith(Mono.fromSupplier(() -> {
                    DataBufferFactory bufferFactory = response.bufferFactory();
                    try {
                        return bufferFactory.wrap(JSONObject.toJSONString(Response.createNativeFail(message)).getBytes("utf8"));
                    } catch (UnsupportedEncodingException e) {
                        log.warn("Error writing response", e);
                        return bufferFactory.wrap(new byte[0]);
                    }
                }));
    }

    ServerHttpRequestDecorator decorate(ServerWebExchange exchange, HttpHeaders headers, CachedBodyOutputMessage outputMessage) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            public HttpHeaders getHeaders() {
                long contentLength = headers.getContentLength();
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.putAll(super.getHeaders());
                if (contentLength > 0L) {
                    httpHeaders.setContentLength(contentLength);
                } else {
                    httpHeaders.set("Transfer-Encoding", "chunked");
                }
                return httpHeaders;
            }
            public Flux<DataBuffer> getBody() {
                return outputMessage.getBody();
            }
        };
    }
}
