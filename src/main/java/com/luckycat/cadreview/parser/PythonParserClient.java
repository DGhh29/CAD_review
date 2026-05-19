package com.luckycat.cadreview.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PythonParserClient {

    private final CadParserProperties properties;
    private final ObjectMapper objectMapper;

    public JsonNode parse(Path filePath) {
        List<String> command = buildCommand(filePath);
        log.info("Executing CAD parser: {}", String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();

            boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new CadParseException("CAD parser timeout after " + properties.getTimeoutSeconds() + "s");
            }

            if (process.exitValue() != 0) {
                String errorMsg = new String(stderr).trim();
                throw new CadParseException("CAD parser failed (exit " + process.exitValue() + "): " + errorMsg);
            }

            return objectMapper.readTree(stdout);
        } catch (IOException e) {
            throw new CadParseException("Failed to execute CAD parser process", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CadParseException("CAD parser interrupted", e);
        }
    }

    private List<String> buildCommand(Path filePath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(properties.getPythonPath());
        cmd.add(properties.getScriptPath());
        cmd.add(filePath.toAbsolutePath().toString());
        cmd.add("--pretty");
        cmd.add("--max-entities");
        cmd.add(String.valueOf(properties.getMaxEntities()));
        cmd.add("--max-texts");
        cmd.add(String.valueOf(properties.getMaxTexts()));
        if (properties.getDwgConverter() != null && !properties.getDwgConverter().isBlank()) {
            cmd.add("--dwg-converter");
            cmd.add(properties.getDwgConverter());
        }
        return cmd;
    }
}
