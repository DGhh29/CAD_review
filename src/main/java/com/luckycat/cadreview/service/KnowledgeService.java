package com.luckycat.cadreview.service;

import com.luckycat.cadreview.dto.KnowledgeSearchResult;
import com.luckycat.cadreview.dto.KnowledgeUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final VectorStore vectorStore;

    public KnowledgeUploadResponse ingestDocument(MultipartFile file, String category) {
        String documentId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename();
        log.info("Ingesting document: {} (id={})", fileName, documentId);

        InputStreamResource resource;
        try {
            resource = new InputStreamResource(file.getInputStream());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> docs = reader.read();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("documentId", documentId);
        metadata.put("fileName", fileName);
        metadata.put("category", category != null ? category : "general");
        metadata.put("uploadTime", LocalDateTime.now().toString());
        docs.forEach(d -> d.getMetadata().putAll(metadata));

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(300)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(docs);

        vectorStore.add(chunks);
        log.info("Ingested {} chunks for document: {}", chunks.size(), fileName);

        return KnowledgeUploadResponse.builder()
                .documentId(documentId)
                .chunkCount(chunks.size())
                .fileName(fileName)
                .status("SUCCESS")
                .build();
    }

    public List<KnowledgeSearchResult> searchRelevantClauses(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();

        List<Document> results = vectorStore.similaritySearch(request);

        return results.stream()
                .map(doc -> KnowledgeSearchResult.builder()
                        .id(doc.getId())
                        .content(doc.getText())
                        .score(doc.getMetadata().containsKey("distance")
                                ? 1.0 - ((Number) doc.getMetadata().get("distance")).doubleValue()
                                : 0.0)
                        .metadata(doc.getMetadata())
                        .build())
                .collect(Collectors.toList());
    }

    public List<KnowledgeSearchResult> searchWithFilter(String query, int topK, String category) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK);

        if (category != null && !category.isBlank()) {
            FilterExpressionBuilder fb = new FilterExpressionBuilder();
            builder.filterExpression(fb.eq("category", category).build());
        }

        List<Document> results = vectorStore.similaritySearch(builder.build());

        return results.stream()
                .map(doc -> KnowledgeSearchResult.builder()
                        .id(doc.getId())
                        .content(doc.getText())
                        .score(doc.getMetadata().containsKey("distance")
                                ? 1.0 - ((Number) doc.getMetadata().get("distance")).doubleValue()
                                : 0.0)
                        .metadata(doc.getMetadata())
                        .build())
                .collect(Collectors.toList());
    }

    public void deleteDocument(String documentId) {
        Filter.Expression filterExpression = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("documentId"),
                new Filter.Value(documentId)
        );
        vectorStore.delete(filterExpression);
        log.info("Deleted all chunks for documentId={}", documentId);
    }
}
