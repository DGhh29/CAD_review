package com.luckycat.cadreview.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 与外部 CAD 解析 HTTP 服务通信的客户端实现。
 *
 * <p>外部解析服务（通常是独立的 Python 进程，默认监听 8000 端口）
 * 接收一个 {@code multipart/form-data} 上传的 .dxf/.dwg 文件，
 * 返回一份描述图纸结构的 IR JSON。
 *
 * <p>所属上游是 {@link CadParserService}；典型失败模式：
 * <ul>
 *   <li>解析服务未启动 / 端口不通——抛 {@link CadParseException}（包了 {@code RestClientException}）</li>
 *   <li>读取临时文件 IO 失败——抛 {@link CadParseException}（包了 {@code IOException}）</li>
 *   <li>返回的 JSON 与 {@code Map<String,Object>} 不匹配——同样抛 {@link CadParseException}</li>
 *   <li>解析超时——受 {@link CadParserProperties#getTimeoutSeconds()} 控制，按 read timeout 触发</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpParserClient {

    private final CadParserProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 把本地路径上的 CAD 文件发给外部解析服务，并把响应反序列化为 IR Map。
     *
     * <p>URL 拼装规则：{@code {httpUrl}/parse?max_entities={n}&max_texts={n}}。
     * 当配置值小于等于 0 时，Python 端按“不限制”处理，返回全量明细。
     *
     * @param filePath 已经落到本机临时目录的 CAD 文件
     * @return IR JSON 反序列化后的 Map
     * @throws CadParseException IO 失败 / HTTP 调用失败 / JSON 反序列化失败
     */
    public Map<String, Object> parse(Path filePath) {
        String url = properties.getHttpUrl() + "/parse"
                + "?max_entities=" + properties.getMaxEntities()
                + "&max_texts=" + properties.getMaxTexts();
        log.info("Calling CAD parser HTTP service: {} (file={})", url, filePath.getFileName());

        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String fileName = filePath.getFileName().toString();

            // 重写 getFilename 是为了让 multipart 边界里带上正确的 filename，
            // 否则 Python 端可能拒收无文件名的字节流。
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };

            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", resource);

            RestClient restClient = RestClient.builder()
                    .requestFactory(buildRequestFactory())
                    .build();

            String responseBody = restClient.post()
                    .uri(url)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            return objectMapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new CadParseException("Failed to read or parse CAD file response", e);
        } catch (RestClientException e) {
            throw new CadParseException("CAD parser HTTP call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 构造请求工厂：connect timeout 固定 5 秒（只用于建链），
     * read timeout 取自配置——大图纸 LLM 端通常需要更长时间响应。
     */
    private ClientHttpRequestFactory buildRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(properties.getTimeoutSeconds() * 1000);
        return factory;
    }
}
