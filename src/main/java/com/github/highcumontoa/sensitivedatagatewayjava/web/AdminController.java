package com.github.highcumontoa.sensitivedatagatewayjava.web;

import com.github.highcumontoa.sensitivedatagatewayjava.audit.AuditService;
import com.github.highcumontoa.sensitivedatagatewayjava.config.PolicyLoader;
import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicySnapshot;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地管理接口（无外部依赖，供验证与并发测试）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final PolicyStore store;
    private final AuditService auditService;
    private final PolicyLoader loader;

    public AdminController(PolicyStore store, AuditService auditService, PolicyLoader loader) {
        this.store = store;
        this.auditService = auditService;
        this.loader = loader;
    }

    /** 查看当前在线策略快照（版本清单 + 最新版本）。 */
    @GetMapping("/policies")
    public Map<String, Object> policies() {
        PolicySnapshot snapshot = store.snapshot();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("latestVersion", snapshot.latestVersion());
        view.put("versions", snapshot.versions().keySet().stream().sorted().toList());
        return view;
    }

    /** 发布单个新版本并原子生效。 */
    @PostMapping("/policies/publish")
    public Map<String, Object> publish(@RequestBody PolicyVersion version) {
        if (version == null) {
            throw new GatewayException(DecisionCode.BAD_REQUEST, "策略版本内容为空");
        }
        store.publish(version);
        return Map.of("latestVersion", store.snapshot().latestVersion(),
                "versions", store.snapshot().versions().keySet().stream().sorted().toList());
    }

    /** 从磁盘原子重载全部策略（失败保留现状）。 */
    @PostMapping("/policies/reload")
    public Map<String, Object> reload() {
        String latest = loader.reload(store);
        return Map.of("latestVersion", latest,
                "versions", store.snapshot().versions().keySet().stream().sorted().toList());
    }

    /** 读取全部审计记录（本地复现/核对用）。 */
    @GetMapping("/audits")
    public List<?> audits() {
        return auditService.findAll();
    }
}
