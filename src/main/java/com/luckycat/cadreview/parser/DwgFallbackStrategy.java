package com.luckycat.cadreview.parser;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Slf4j
@Component
@ConditionalOnProperty(name = "cad-review.parser.direct-dwg-enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class DwgFallbackStrategy implements DwgParseStrategy {

    private final PythonParserClient parserClient;

    @Override
    public JsonNode parse(Path dwgFile) {
        log.info("Using fallback DWG strategy (dwg→dxf conversion via Python)");
        return parserClient.parse(dwgFile);
    }

    @Override
    public String strategyName() {
        return "dwg-fallback-via-python";
    }
}
