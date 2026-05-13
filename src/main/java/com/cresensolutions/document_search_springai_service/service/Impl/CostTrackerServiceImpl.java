package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.service.CostTrackerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class CostTrackerServiceImpl implements CostTrackerService {

    @Value("${doc-search.cost-tracking.csv-path:/tmp/cost_tracking.csv}")
    private String csvPath;

    @Value("${doc-search.cost-tracking.input-cost:0.015}") // cost per 1M tokens
    private double inputCostPerMillion;

    @Value("${doc-search.cost-tracking.output-cost:0.06}") // cost per 1M tokens
    private double outputCostPerMillion;

    private final AtomicReference<Double> currentTotal = new AtomicReference<>(0.0);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @PostConstruct
    public void init() {
        File file = new File(csvPath);
        if (!file.exists()) {
            try {
                Path parent = Paths.get(csvPath).getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (PrintWriter writer = new PrintWriter(new FileWriter(csvPath, false))) {
                    writer.println("date,time,operation_type,tokens_input,tokens_output,tokens_reasoning,cost_input,cost_output,total_cost,cumulative_total");
                }
            } catch (IOException e) {
                log.error("Failed to initialize cost tracking CSV at {}", csvPath, e);
            }
        } else {
            loadCurrentTotal();
        }
    }

    private void loadCurrentTotal() {
        try {
            java.util.List<String> lines = Files.readAllLines(Paths.get(csvPath));
            if (lines.size() > 1) {
                String lastLine = lines.get(lines.size() - 1);
                String[] parts = lastLine.split(",");
                if (parts.length >= 10) {
                    currentTotal.set(Double.parseDouble(parts[9]));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load current total from {}, starting at 0.0", csvPath);
            currentTotal.set(0.0);
        }
    }

    @Override
    public synchronized void logUsage(String operationType, int inputTokens, int outputTokens, int reasoningTokens) {
        double inputCost = (inputTokens / 1_000_000.0) * inputCostPerMillion;
        double outputCost = (outputTokens / 1_000_000.0) * outputCostPerMillion;
        double totalCost = inputCost + outputCost;

        double newTotal = currentTotal.updateAndGet(current -> current + totalCost);
        LocalDateTime now = LocalDateTime.now();

        try (PrintWriter writer = new PrintWriter(new FileWriter(csvPath, true))) {
            writer.printf("%s,%s,%s,%d,%d,%d,%.10f,%.10f,%.10f,%.10f%n",
                    now.format(DATE_FORMAT),
                    now.format(TIME_FORMAT),
                    operationType,
                    inputTokens,
                    outputTokens,
                    reasoningTokens,
                    inputCost,
                    outputCost,
                    totalCost,
                    newTotal);
        } catch (IOException e) {
            log.error("Failed to append cost usage to CSV", e);
        }
    }

    @Override
    public double getCumulativeTotal() {
        return currentTotal.get();
    }
}
