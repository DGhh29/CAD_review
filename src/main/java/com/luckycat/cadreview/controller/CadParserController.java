package com.luckycat.cadreview.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.luckycat.cadreview.common.ApiResult;
import com.luckycat.cadreview.parser.CadParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/cad")
@RequiredArgsConstructor
public class CadParserController {

    private final CadParserService cadParserService;

    @PostMapping("/parse-dxf")
    public ApiResult<JsonNode> parseDxf(@RequestParam("file") MultipartFile file) {
        JsonNode result = cadParserService.parseDxf(file);
        return ApiResult.ok(result);
    }

    @PostMapping("/parse-dwg")
    public ApiResult<JsonNode> parseDwg(@RequestParam("file") MultipartFile file) {
        JsonNode result = cadParserService.parseDwg(file);
        return ApiResult.ok(result);
    }
}
