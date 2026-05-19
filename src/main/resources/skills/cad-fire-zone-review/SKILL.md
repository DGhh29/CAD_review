---
name: cad-fire-zone-review
description: 审核建筑平面图防火分区面积是否超限
---

# 防火分区面积审核

当被要求审核防火分区面积时，按以下步骤执行：

1. 读取 extractedParameters 中的 area 和 limit 字段
2. 比较 area 是否超过 limit
3. 如果超过，verdict 为 PENDING_REVIEW，riskLevel 为 HIGH
4. 如果未超过，verdict 为 PASS，riskLevel 为 LOW
5. 输出结构化 JSON Finding
