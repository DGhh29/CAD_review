package com.luckycat.cadreview.parser;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;

public interface DwgParseStrategy {
    JsonNode parse(Path dwgFile);
    String strategyName();
}
