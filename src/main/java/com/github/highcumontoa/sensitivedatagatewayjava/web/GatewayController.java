package com.github.highcumontoa.sensitivedatagatewayjava.web;

import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditStore;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchAccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchProcessedData;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.ProcessedData;
import com.github.highcumontoa.sensitivedatagatewayjava.service.BatchSensitiveDataGateway;
import com.github.highcumontoa.sensitivedatagatewayjava.service.SensitiveDataGateway;
import com.github.highcumontoa.sensitivedatagatewayjava.web.dto.AccessRequestDto;
import com.github.highcumontoa.sensitivedatagatewayjava.web.dto.BatchAccessRequestDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 本地 REST 入口：单条 /api/v1/data/access 保持兼容；批量 /api/v1/data/batch。
 */
@RestController
@RequestMapping("/api/v1")
public class GatewayController {

    private final SensitiveDataGateway gateway;
    private final BatchSensitiveDataGateway batchGateway;
    private final AuditStore auditStore;

    public GatewayController(SensitiveDataGateway gateway,
                             BatchSensitiveDataGateway batchGateway,
                             AuditStore auditStore) {
        this.gateway = gateway;
        this.batchGateway = batchGateway;
        this.auditStore = auditStore;
    }

    @PostMapping("/data/access")
    public ResponseEntity<ProcessedData> access(@RequestBody AccessRequestDto dto) {
        AccessRequest request = new AccessRequest(
                dto.getCallerId(),
                dto.getPurpose(),
                dto.getRequestedPaths() == null ? List.of() : List.copyOf(dto.getRequestedPaths()),
                dto.getPolicyVersion(),
                dto.getData());
        return ResponseEntity.ok(gateway.access(request));
    }

    @PostMapping("/data/batch")
    public ResponseEntity<BatchProcessedData> batch(@RequestBody BatchAccessRequestDto dto) {
        BatchAccessRequest request = new BatchAccessRequest(
                dto.getCallerId(),
                dto.getPurpose(),
                dto.getRequestedPaths() == null ? List.of() : List.copyOf(dto.getRequestedPaths()),
                dto.getPolicyVersion(),
                dto.getRecords() == null ? List.of() : List.copyOf(dto.getRecords()));
        return ResponseEntity.ok(batchGateway.accessBatch(request));
    }

    @GetMapping("/audit")
    public ResponseEntity<Object> audit() {
        return ResponseEntity.ok(auditStore.findAll());
    }
}
