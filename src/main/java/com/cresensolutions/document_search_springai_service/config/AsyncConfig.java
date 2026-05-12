package com.cresensolutions.document_search_springai_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration class for asynchronous task execution.
 * Enables async processing and configures a thread pool executor
 * for handling concurrent document search operations.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Configures a thread pool task executor for async operations.
     * Used for processing document queries and AI operations concurrently.
     * Thread pool settings are tuned for moderate load with graceful shutdown.
     *
     * @return configured Executor for async task execution
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Minimum number of threads to keep alive
        executor.setCorePoolSize(5);
        // Maximum number of threads in the pool
        executor.setMaxPoolSize(10);
        // Queue capacity for pending tasks
        executor.setQueueCapacity(100);
        // Thread name prefix for easier debugging
        executor.setThreadNamePrefix("doc-search-");
        // Policy for handling rejected tasks (execute in calling thread)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Wait for tasks to complete on shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // Maximum time to wait for shutdown
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
