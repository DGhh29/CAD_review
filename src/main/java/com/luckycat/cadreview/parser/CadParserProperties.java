package com.luckycat.cadreview.parser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cad-review.parser")
public class CadParserProperties {
    private String pythonPath = "python";
    private String scriptPath = "D:/workspace/oDev/cad-drawing-parser/parse_cad.py";
    private int timeoutSeconds = 120;
    private int maxEntities = 10000;
    private int maxTexts = 2000;
    private String dwgConverter = "";
    private boolean directDwgEnabled = false;
}
