package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.service.Impl.CostTrackerServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("CostTrackerServiceImpl Tests")
class CostTrackerServiceImplTest {

    @InjectMocks
    CostTrackerServiceImpl service;

    private Path tempCsvPath;

    @BeforeEach
    void setUp() throws IOException {
        tempCsvPath = Files.createTempFile("cost_tracking_test", ".csv");
        Files.deleteIfExists(tempCsvPath); // Delete it so init() creates it

        ReflectionTestUtils.setField(service, "csvPath", tempCsvPath.toString());
        ReflectionTestUtils.setField(service, "inputCostPerMillion", 0.015);
        ReflectionTestUtils.setField(service, "outputCostPerMillion", 0.06);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(tempCsvPath);
    }

    @Test
    @DisplayName("init: creates CSV file with headers if it does not exist")
    void init_createsCsvFile() {
        service.init();

        assertThat(Files.exists(tempCsvPath)).isTrue();
        List<String> lines = readLines();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("date,time,operation_type,tokens_input,tokens_output,tokens_reasoning,cost_input,cost_output,total_cost,cumulative_total");
        assertThat(service.getCumulativeTotal()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("init: loads current total from existing CSV file")
    void init_loadsCurrentTotal() throws IOException {
        // Create an existing file with a record
        Files.writeString(tempCsvPath, "date,time,operation_type,tokens_input,tokens_output,tokens_reasoning,cost_input,cost_output,total_cost,cumulative_total\n" +
                "2023-01-01,12:00:00,TEST,1000,500,0,0.000015,0.000030,0.000045,1.234567\n");

        service.init();

        assertThat(service.getCumulativeTotal()).isEqualTo(1.234567);
    }

    @Test
    @DisplayName("init: starts at 0.0 if CSV file is corrupted")
    void init_startsAtZeroIfCorrupted() throws IOException {
        Files.writeString(tempCsvPath, "date,time,operation_type,tokens_input,tokens_output,tokens_reasoning,cost_input,cost_output,total_cost\n" +
                "2023-01-01,12:00:00,TEST,1000,500,0,0.000015,0.000030,0.000045\n"); // Missing cumulative_total column

        service.init();

        assertThat(service.getCumulativeTotal()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("logUsage: appends usage to CSV and updates cumulative total")
    void logUsage_appendsAndUpdatesTotal() {
        service.init(); // Create the header

        service.logUsage("LLM_CALL", 1_000_000, 2_000_000, 0);

        // Input: 1 * 0.015 = 0.015
        // Output: 2 * 0.06 = 0.12
        // Total = 0.135
        assertThat(service.getCumulativeTotal()).isEqualTo(0.135);

        List<String> lines = readLines();
        assertThat(lines).hasSize(2);
        String lastLine = lines.get(1);
        
        String[] parts = lastLine.split(",");
        assertThat(parts).hasSize(10);
        assertThat(parts[2]).isEqualTo("LLM_CALL");
        assertThat(parts[3]).isEqualTo("1000000");
        assertThat(parts[4]).isEqualTo("2000000");
        assertThat(parts[5]).isEqualTo("0");
        // Using string formatting to avoid locale issues, or just parse to double
        assertThat(Double.parseDouble(parts[8])).isCloseTo(0.135, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(Double.parseDouble(parts[9])).isCloseTo(0.135, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("logUsage: multiple calls accumulate total")
    void logUsage_multipleCallsAccumulate() {
        service.init();

        service.logUsage("CALL_1", 1_000_000, 0, 0); // Cost: 0.015
        service.logUsage("CALL_2", 0, 1_000_000, 0); // Cost: 0.060

        assertThat(service.getCumulativeTotal()).isCloseTo(0.075, org.assertj.core.data.Offset.offset(0.0001));

        List<String> lines = readLines();
        assertThat(lines).hasSize(3); // Header + 2 rows
    }

    private List<String> readLines() {
        try {
            return Files.readAllLines(tempCsvPath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
