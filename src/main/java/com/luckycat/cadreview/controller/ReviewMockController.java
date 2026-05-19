package com.luckycat.cadreview.controller;

import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.dto.AuditPackage;
import com.luckycat.cadreview.dto.ReviewMockResponse;
import com.luckycat.cadreview.service.ReviewMockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class ReviewMockController {

    private final ReviewMockService reviewMockService;

    @PostMapping("/review-mock")
    public ApiResult<ReviewMockResponse> reviewMock(@Valid @RequestBody AuditPackage auditPackage) {
        ReviewMockResponse response = reviewMockService.review(auditPackage);
        return ApiResult.ok(response);
    }
}
