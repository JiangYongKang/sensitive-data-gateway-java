package com.github.highcumontoa.sensitivedatagatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * 放行响应：处理后的数据 + 字段处理标注 + 审计/版本元信息。
 */
public record AccessResponse(
        String auditId,
        String policyVersion,
        Map<String, Object> data,
        List<FieldTransformMarker> fieldMarkers) {
}
