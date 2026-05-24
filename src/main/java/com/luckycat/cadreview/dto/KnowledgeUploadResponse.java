package com.luckycat.cadreview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规范文档上传入库的应答体（{@code POST /api/knowledge/upload}）。
 *
 * <p>{@code KnowledgeService.ingestDocument} 在完成 Tika 解析、分块、写入向量库后返回。
 * 前端可据此向用户展示入库结果——尤其是 chunkCount 与 status，便于判断切片是否符合预期。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeUploadResponse {

    // 后端生成的文档 UUID。后续按文档整体删除（{@code DELETE /api/knowledge/documents/{documentId}}）
    // 必须用本字段作为路径参数；与单个 chunk 的 ID 不同
    private String documentId;

    // 切分后的 chunk 总数；过少（如 1）通常意味着文档解析失败，过多则可能是切片粒度过细
    private int chunkCount;

    // 原始上传文件名，原样保留，方便前端列表展示
    private String fileName;

    // 入库状态文案，例如 "SUCCESS"；当前实现里固定写死成功值，后续如果改为异步入库可承载真实状态
    private String status;
}
