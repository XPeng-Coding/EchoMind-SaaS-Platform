package com.echomind.evaluation;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把耗时的端到端评测从 HTTP 请求生命周期中拆出。
 * 任务及完成报告持久化到本地文件，服务重启后可继续读取已完成报告。
 */
@Service
public class EvaluationJobService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationJobService.class);
    private static final int MAX_STORED_JOBS = 20;

    private final EndToEndEvaluator evaluator;
    private final ObjectMapper objectMapper;
    private final EchoMindProperties properties;
    private final Map<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "echomind-evaluation-worker");
        thread.setDaemon(true);
        return thread;
    });

    public EvaluationJobService(EndToEndEvaluator evaluator, ObjectMapper objectMapper, EchoMindProperties properties) {
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void load() {
        Path path = jobsPath();
        if (Files.exists(path)) {
            try {
                Map<String, Object> stored = objectMapper.readValue(path.toFile(), new TypeReference<>() { });
                Object rawJobs = stored.get("jobs");
                if (rawJobs instanceof List<?> values) {
                    for (Object value : values) {
                        if (value instanceof Map<?, ?> map && map.get("job_id") != null) {
                            Map<String, Object> job = new LinkedHashMap<>();
                            map.forEach((key, item) -> job.put(String.valueOf(key), item));
                            jobs.put(String.valueOf(map.get("job_id")), job);
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to load evaluation jobs: {}", ex.getMessage());
            }
        }

        boolean changed = false;
        for (Map<String, Object> job : jobs.values()) {
            String status = String.valueOf(job.get("status"));
            if ("queued".equals(status) || "running".equals(status)) {
                job.put("status", "failed");
                job.put("phase", "interrupted");
                job.put("error", "评测任务因服务重启而中断，请重新运行。");
                job.put("finished_at", now());
                changed = true;
            }
        }
        trim();
        if (changed) {
            save();
        }
    }

    public synchronized Map<String, Object> submit(EvalRunRequest request) {
        String jobId = UUID.randomUUID().toString();
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("job_id", jobId);
        job.put("status", "queued");
        job.put("phase", "queued");
        job.put("progress", 0);
        job.put("created_at", now());
        job.put("started_at", null);
        job.put("finished_at", null);
        job.put("error", null);
        job.put("report", null);
        jobs.put(jobId, job);
        trim();
        save();
        executor.submit(() -> execute(jobId, request));
        return publicJob(job);
    }

    public Map<String, Object> get(String jobId) {
        Map<String, Object> job = jobs.get(jobId);
        return job == null ? null : publicJob(job);
    }

    public List<Map<String, Object>> list(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_STORED_JOBS));
        return jobs.values().stream()
                .sorted(Comparator.comparing(job -> String.valueOf(job.getOrDefault("created_at", "")), Comparator.reverseOrder()))
                .limit(boundedLimit)
                .map(this::publicJob)
                .toList();
    }

    @PreDestroy
    public synchronized void close() {
        executor.shutdownNow();
        for (Map<String, Object> job : jobs.values()) {
            String status = String.valueOf(job.get("status"));
            if ("queued".equals(status) || "running".equals(status)) {
                job.put("status", "failed");
                job.put("phase", "interrupted");
                job.put("error", "评测任务因服务停止而中断，请重新运行。");
                job.put("finished_at", now());
            }
        }
        save();
    }

    private void execute(String jobId, EvalRunRequest request) {
        Map<String, Object> job = jobs.get(jobId);
        if (job == null) {
            return;
        }
        synchronized (this) {
            job.put("status", "running");
            job.put("phase", "running");
            job.put("progress", 5);
            job.put("started_at", now());
            save();
        }
        try {
            Map<String, Object> report = evaluator.run(request);
            synchronized (this) {
                job.put("status", "succeeded");
                job.put("phase", "completed");
                job.put("progress", 100);
                job.put("report", report);
                job.put("finished_at", now());
                save();
            }
        } catch (Exception ex) {
            log.error("Evaluation job failed: {}", jobId, ex);
            synchronized (this) {
                job.put("status", "failed");
                job.put("phase", "failed");
                job.put("error", "评测执行失败（" + ex.getClass().getSimpleName() + "），请查看服务日志。");
                job.put("finished_at", now());
                save();
            }
        }
    }

    private synchronized void save() {
        try {
            Path path = jobsPath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), Map.of("jobs", new ArrayList<>(jobs.values())));
        } catch (Exception ex) {
            log.warn("Failed to save evaluation jobs: {}", ex.getMessage());
        }
    }

    private void trim() {
        if (jobs.size() <= MAX_STORED_JOBS) {
            return;
        }
        List<Map<String, Object>> keep = jobs.values().stream()
                .sorted(Comparator.comparing(job -> String.valueOf(job.getOrDefault("created_at", "")), Comparator.reverseOrder()))
                .limit(MAX_STORED_JOBS)
                .toList();
        jobs.clear();
        keep.forEach(job -> jobs.put(String.valueOf(job.get("job_id")), job));
    }

    private Map<String, Object> publicJob(Map<String, Object> job) {
        Map<String, Object> response = new LinkedHashMap<>();
        for (String key : List.of("job_id", "status", "phase", "progress", "created_at", "started_at", "finished_at", "error", "report")) {
            response.put(key, job.get(key));
        }
        return response;
    }

    private Path jobsPath() {
        return Path.of(properties.getEval().getJobsPath());
    }

    private String now() {
        return Instant.now().toString();
    }
}
