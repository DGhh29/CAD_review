package com.luckycat.cadreview.controller;

import com.luckycat.cadreview.agent.AgentOrchestrator;
import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.dto.ReviewReport;
import com.luckycat.cadreview.dto.ReviewTask;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final AgentOrchestrator agentOrchestrator;

    @PostMapping(value = "/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<ReviewReport> submit(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "ruleSet", required = false) String ruleSet) {
        return ApiResult.ok(agentOrchestrator.executeReview(file, ruleSet));
    }

    @PostMapping(value = "/dispatch-only", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<List<ReviewTask>> dispatchOnly(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "ruleSet", required = false) String ruleSet) {
        return ApiResult.ok(agentOrchestrator.dispatchOnly(file, ruleSet));
    }
}
