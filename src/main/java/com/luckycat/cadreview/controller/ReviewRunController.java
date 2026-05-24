package com.luckycat.cadreview.controller;

import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.dto.ReviewReport;
import com.luckycat.cadreview.dto.ReviewRunCreatedResponse;
import com.luckycat.cadreview.dto.ReviewRunSummary;
import com.luckycat.cadreview.service.ReviewRunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 异步 CAD 审图入口。
 */
@RestController
@RequestMapping("/api/cad/review-runs")
@RequiredArgsConstructor
public class ReviewRunController {

    private final ReviewRunService reviewRunService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ReviewRunCreatedResponse> create(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "ruleSet", required = false) String ruleSet) {
        return ApiResult.ok(reviewRunService.create(file, ruleSet));
    }

    @GetMapping("/{runId}")
    public ApiResult<ReviewRunSummary> status(@PathVariable String runId) {
        return reviewRunService.getSummary(runId)
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.error(40404, "Review run not found"));
    }

    @GetMapping("/{runId}/report")
    public ApiResult<ReviewReport> report(@PathVariable String runId) {
        return reviewRunService.getReport(runId)
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.error(40404, "Review report not found or not completed"));
    }
}
