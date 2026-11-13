package com.example.demo;

/** 提交订单请求体。record 让 #req.orderNo 的 SpEL 走组件名访问。 */
public record OrderRequest(String orderNo, int amount) {
}
