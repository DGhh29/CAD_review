package com.luckycat.cadreview.metrics.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luckycat.cadreview.metrics.CadMetricRequest;
import com.luckycat.cadreview.metrics.CadMetricResult;
import com.luckycat.cadreview.metrics.CadMetricTarget;
import com.luckycat.cadreview.metrics.MeasuredCandidate;
import com.luckycat.cadreview.metrics.MetricOperation;
import com.luckycat.cadreview.metrics.MetricStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ConnectivityMetricStrategy extends AbstractCadMetricStrategy {

    @Override
    public MetricOperation operation() {
        return MetricOperation.CHECK_CONNECTIVITY;
    }

    @Override
    public CadMetricResult calculate(CadMetricRequest request, CadMetricExecutionContext context) {
        CadMetricTarget target = target(request);
        List<Segment> segments = new ArrayList<>();
        List<MeasuredCandidate> evidence = new ArrayList<>();
        for (JsonNode entity : iterable(context.drawingIr().path("entities"))) {
            if (!matches(entity, target)) {
                continue;
            }
            String type = normalize(entity.path("type").asText(""));
            if ("line".equals(type)) {
                List<Double> start = pointFrom(entity.path("start"));
                List<Double> end = pointFrom(entity.path("end"));
                if (start.size() == 2 && end.size() == 2) {
                    segments.add(new Segment(start, end));
                    evidence.add(new MeasuredCandidate(1.0d, entity, List.of()));
                }
            } else if ("lwpolyline".equals(type) || "polyline".equals(type)) {
                List<double[]> points = points(entity);
                for (int i = 1; i < points.size(); i++) {
                    segments.add(new Segment(toList(points.get(i - 1)), toList(points.get(i))));
                }
                if (entity.path("closed").asBoolean(false) && points.size() > 2) {
                    segments.add(new Segment(toList(points.get(points.size() - 1)), toList(points.get(0))));
                }
                if (!points.isEmpty()) {
                    evidence.add(new MeasuredCandidate(1.0d, entity, List.of()));
                }
            }
        }
        if (segments.isEmpty()) {
            return notFound(request, context, "未找到可检查连通性的线段或多段线");
        }
        Connectivity connectivity = connectivity(segments, target.getTolerance() == null ? 1.0d : target.getTolerance());
        ObjectNode details = baseDetails(context, segments.size());
        details.put("connected", connectivity.connected());
        details.put("componentCount", connectivity.componentCount());
        details.put("endpointCount", connectivity.endpointCount());
        return result(
                request,
                MetricStatus.FOUND,
                connectivity.connected() ? 1.0d : 0.0d,
                evidenceRefs(evidence, evidenceLimit(target)),
                List.of("measuredValue=1 表示连通，0 表示不连通"),
                details);
    }

    private Connectivity connectivity(List<Segment> segments, double tolerance) {
        List<List<Double>> endpoints = new ArrayList<>();
        for (Segment segment : segments) {
            endpoints.add(segment.start());
            endpoints.add(segment.end());
        }
        UnionFind unionFind = new UnionFind(endpoints.size());
        for (int i = 0; i < segments.size(); i++) {
            unionFind.union(i * 2, i * 2 + 1);
        }
        for (int i = 0; i < endpoints.size(); i++) {
            for (int j = i + 1; j < endpoints.size(); j++) {
                if (pointDistance(endpoints.get(i), endpoints.get(j)) <= tolerance) {
                    unionFind.union(i, j);
                }
            }
        }
        Set<Integer> roots = new HashSet<>();
        for (int i = 0; i < endpoints.size(); i++) {
            roots.add(unionFind.find(i));
        }
        return new Connectivity(roots.size() <= 1, roots.size(), endpoints.size());
    }

    private List<Double> pointFrom(JsonNode node) {
        if (node.isArray() && node.size() >= 2) {
            return List.of(node.get(0).asDouble(), node.get(1).asDouble());
        }
        return List.of();
    }

    private List<Double> toList(double[] point) {
        return List.of(point[0], point[1]);
    }

    private record Segment(List<Double> start, List<Double> end) {
    }

    private record Connectivity(boolean connected, int componentCount, int endpointCount) {
    }

    private static class UnionFind {
        private final int[] parent;

        UnionFind(int size) {
            this.parent = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        int find(int value) {
            if (parent[value] != value) {
                parent[value] = find(parent[value]);
            }
            return parent[value];
        }

        void union(int a, int b) {
            int rootA = find(a);
            int rootB = find(b);
            if (rootA != rootB) {
                parent[rootB] = rootA;
            }
        }
    }
}
