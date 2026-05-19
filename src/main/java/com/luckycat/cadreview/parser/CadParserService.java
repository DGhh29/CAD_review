package com.luckycat.cadreview.parser;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class CadParserService {

    private final PythonParserClient pythonParserClient;
    private final DwgParseStrategy dwgParseStrategy;

    public JsonNode parseDxf(MultipartFile file) {
        validateExtension(file, ".dxf");
        Path tempFile = saveToTemp(file, ".dxf");
        try {
            return pythonParserClient.parse(tempFile);
        } finally {
            deleteSilently(tempFile);
        }
    }

    public JsonNode parseDwg(MultipartFile file) {
        validateExtension(file, ".dwg");
        Path tempFile = saveToTemp(file, ".dwg");
        try {
            log.info("Parsing DWG with strategy: {}", dwgParseStrategy.strategyName());
            return dwgParseStrategy.parse(tempFile);
        } finally {
            deleteSilently(tempFile);
        }
    }

    private void validateExtension(MultipartFile file, String expected) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(expected)) {
            throw new IllegalArgumentException("Expected " + expected + " file, got: " + originalName);
        }
    }

    private Path saveToTemp(MultipartFile file, String suffix) {
        try {
            Path temp = Files.createTempFile("cad-upload-", suffix);
            file.transferTo(temp.toFile());
            return temp;
        } catch (IOException e) {
            throw new CadParseException("Failed to save uploaded file", e);
        }
    }

    private void deleteSilently(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp file: {}", path, e);
        }
    }
}
