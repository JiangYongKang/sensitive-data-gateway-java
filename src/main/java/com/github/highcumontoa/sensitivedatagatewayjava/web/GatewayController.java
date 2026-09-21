package com.github.highcumontoa.sensitivedatagatewayjava.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessResponse;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DenyResponse;
import com.github.highcumontoa.sensitivedatagatewayjava.service.DefaultGatewayService;
import com.github.highcumontoa.sensitivedatagatewayjava.service.GatewayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 数据访问入口。读取原始 JSON 以保证审计记录的是真实原始输入。
 */
@RestController
@RequestMapping("/api/v1/gateway")
public class GatewayController {

    private final GatewayService service;
    private final ObjectMapper objectMapper;

    public GatewayController(GatewayService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/access")
    public ResponseEntity<?> access(HttpServletRequest httpRequest,
                                    @RequestBody(required = false) byte[] body) {
        String rawJson = readBody(body);
        AccessRequest request = parse(rawJson);
        try {
            AccessResponse response = service.access(request, rawJson);
            return ResponseEntity.ok(response);
        } catch (DefaultGatewayService.DeniedException denied) {
            DenyResponse bodyResp = new DenyResponse(
                    denied.getAuditId(),
                    denied.getPolicyVersion(),
                    denied.getCode(),
                    denied.getMessage(),
                    denied.isAudited());
            return ResponseEntity.status(denied.getCode().getHttpStatus()).body(bodyResp);
        }
    }

    private String readBody(byte[] body) {
        if (body == null || body.length == 0) {
            throw new GatewayException(DecisionCode.BAD_REQUEST, "请求体为空");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private AccessRequest parse(String rawJson) {
        try {
            AccessRequest request = objectMapper.readValue(rawJson, AccessRequest.class);
            if (request == null) {
                throw new GatewayException(DecisionCode.BAD_REQUEST, "请求体无法解析");
            }
            return request;
        } catch (IOException e) {
            throw new GatewayException(DecisionCode.BAD_REQUEST,
                    "请求不是合法的 JSON，或字段类型不符合契约", e);
        }
    }
}
