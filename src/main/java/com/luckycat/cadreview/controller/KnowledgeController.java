package com.luckycat.cadreview.controller;

import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.dto.KnowledgeSearchRequest;
import com.luckycat.cadreview.dto.KnowledgeSearchResult;
import com.luckycat.cadreview.dto.KnowledgeUploadResponse;
import com.luckycat.cadreview.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @PostMapping("/upload")
    public ApiResult<KnowledgeUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false) String category) {
        KnowledgeUploadResponse response = knowledgeService.ingestDocument(file, category);
        return ApiResult.ok(response);
    }

    @PostMapping("/search")
    public ApiResult<List<KnowledgeSearchResult>> search(
            @RequestBody KnowledgeSearchRequest request) {
        List<KnowledgeSearchResult> results;
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            results = knowledgeService.searchWithFilter(
                    request.getQuery(), request.getTopK(), request.getCategory());
        } else {
            results = knowledgeService.searchRelevantClauses(
                    request.getQuery(), request.getTopK());
        }
        return ApiResult.ok(results);
    }

    @DeleteMapping("/documents/{documentId}")
    public ApiResult<Void> deleteDocument(@PathVariable String documentId) {
        knowledgeService.deleteDocument(documentId);
        return ApiResult.ok(null);
    }
}
