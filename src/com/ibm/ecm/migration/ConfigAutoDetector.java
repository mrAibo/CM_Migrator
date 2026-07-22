/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Properties;

/**
 * ConfigAutoDetector v2.2 – Intelligente automatische Konfigurationserkennung
 * 
 * Funktionen:
 * – Systemerkennung (CPU, RAM)
 * – Netzwerk-Benchmark (Quell-/Ziel-CM-Server)
 * – E/A-Benchmark (Festplatte, H2-Datenbank)
 * – Intelligente Profilempfehlung
 * – Überschreibungsunterstützung (automatische Werte können manuell überschrieben werden)
 * 
 * @since v2.2.0
 */
public class ConfigAutoDetector {
    private static final Logger logger = LogManager.getLogger(ConfigAutoDetector.class);
    
    // Benchmark constants
    private static final int BENCHMARK_ITERATIONS = 10;
    private static final int BENCHMARK_DATA_SIZE_MB = 10;
    private static final int H2_BENCHMARK_ROWS = 10000;
    
    // ========================================================================
    // SYSTEM DETECTION
    // ========================================================================
    
    public int detectOptimalThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        int optimal = Math.min(cores * 2, 200);
        logger.info("Detected {} CPU cores, recommended threads: {}", cores, optimal);
        return optimal;
    }
    
    public long detectAvailableMemoryMB() {
        long maxHeap = Runtime.getRuntime().maxMemory();
        long mb = maxHeap / (1024 * 1024);
        logger.info("JVM max heap: {} MB", mb);
        return mb;
    }
    
    public long detectSystemMemoryMB() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                long totalRam = ((com.sun.management.OperatingSystemMXBean) osBean).getTotalPhysicalMemorySize();
                return totalRam / (1024 * 1024);
            }
        } catch (Exception e) {
            logger.debug("Could not detect system RAM: {}", e.getMessage());
        }
        return -1;
    }
    
    // ========================================================================
    // NETWORK BENCHMARK
    // ========================================================================
    
    public static class NetworkBenchmarkResult {
        public final String serverSSID;
        public final double latencyMs;      
        public final double throughputMBps; 
        public final double itemsPerSec;    
        public final boolean success;
        public final String errorMessage;
        
        public NetworkBenchmarkResult(String ssid, double latency, double throughput, double items, boolean success, String error) {
            this.serverSSID = ssid;
            this.latencyMs = latency;
            this.throughputMBps = throughput;
            this.itemsPerSec = items;
            this.success = success;
            this.errorMessage = error;
        }
        
        public static NetworkBenchmarkResult failed(String ssid, String error) {
            return new NetworkBenchmarkResult(ssid, -1, -1, -1, false, error);
        }
        
        @Override
        public String toString() {
            if (!success) return String.format("NetworkBenchmark[%s]: FAILED - %s", serverSSID, errorMessage);
            return String.format("NetworkBenchmark[%s]: latency=%.1fms, throughput=%.1fMB/s, items/s=%.0f",
                    serverSSID, latencyMs, throughputMBps, itemsPerSec);
        }
    }
    
    public NetworkBenchmarkResult benchmarkServer(String ssid, String user, String password) {
        logger.info("Starting network benchmark for SSID: {}", ssid);
        
        try {
            long[] latencies = new long[BENCHMARK_ITERATIONS];
            long startTotal = System.nanoTime();
            
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                long start = System.nanoTime();
                
                // Attempt to create connection (tests network + auth)
                CMConnection conn = new CMConnection(ssid, user, password, CMConnection.Role.SOURCE);
                conn.connect();
                
                // Simple operation to test responsiveness
                Thread.sleep(10); 
                
                conn.disconnect();
                
                latencies[i] = System.nanoTime() - start;
            }
            
            long totalTime = System.nanoTime() - startTotal;
            
            double avgLatencyMs = 0;
            for (long l : latencies) {
                avgLatencyMs += l / 1_000_000.0;
            }
            avgLatencyMs /= BENCHMARK_ITERATIONS;
            
            double connPerSec = BENCHMARK_ITERATIONS / (totalTime / 1_000_000_000.0);
            double estimatedThroughput = connPerSec * 0.1; 
            double estimatedItemsPerSec = 1000.0 / avgLatencyMs * 10; 
            
            NetworkBenchmarkResult result = new NetworkBenchmarkResult(
                    ssid, avgLatencyMs, estimatedThroughput, estimatedItemsPerSec, true, null);
            
            logger.info(result.toString());
            return result;
            
        } catch (Exception e) {
            logger.error("Network benchmark failed for {}: {}", ssid, e.getMessage());
            return NetworkBenchmarkResult.failed(ssid, e.getMessage());
        }
    }
    
    // ========================================================================
    // I/O BENCHMARK
    // ========================================================================
    
    public static class IOBenchmarkResult {
        public final double diskWriteMBps;
        public final double diskReadMBps;
        public final double h2InsertRowsPerSec;
        public final boolean success;
        public final String errorMessage;
        
        public IOBenchmarkResult(double writeSpeed, double readSpeed, double h2Speed, boolean success, String error) {
            this.diskWriteMBps = writeSpeed;
            this.diskReadMBps = readSpeed;
            this.h2InsertRowsPerSec = h2Speed;
            this.success = success;
            this.errorMessage = error;
        }
        
        public static IOBenchmarkResult failed(String error) {
            return new IOBenchmarkResult(-1, -1, -1, false, error);
        }
        
        @Override
        public String toString() {
            if (!success) return String.format("IOBenchmark: FAILED - %s", errorMessage);
            return String.format("IOBenchmark: diskWrite=%.1fMB/s, diskRead=%.1fMB/s, h2Insert=%.0f rows/s",
                    diskWriteMBps, diskReadMBps, h2InsertRowsPerSec);
        }
    }
    
    public IOBenchmarkResult benchmarkLocalIO() {
        logger.info("Starting local I/O benchmark...");
        
        File tempFile = null;
        try {
            // 1. Disk Write Benchmark
            tempFile = File.createTempFile("cm_migrator_bench_", ".tmp");
            byte[] data = new byte[1024 * 1024]; // 1 MB buffer
            
            long writeStart = System.nanoTime();
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                for (int i = 0; i < BENCHMARK_DATA_SIZE_MB; i++) {
                    fos.write(data);
                }
                fos.flush();
                fos.getFD().sync(); 
            }
            long writeTime = System.nanoTime() - writeStart;
            double writeSpeedMBps = BENCHMARK_DATA_SIZE_MB / (writeTime / 1_000_000_000.0);
            
            // 2. Disk Read Benchmark
            long readStart = System.nanoTime();
            try (RandomAccessFile raf = new RandomAccessFile(tempFile, "r")) {
                byte[] readBuffer = new byte[1024 * 1024];
                while (raf.read(readBuffer) != -1) {
                    // Just read
                }
            }
            long readTime = System.nanoTime() - readStart;
            double readSpeedMBps = BENCHMARK_DATA_SIZE_MB / (readTime / 1_000_000_000.0);
            
            // 3. H2 DB Benchmark
            double h2Speed = benchmarkH2Database();
            
            IOBenchmarkResult result = new IOBenchmarkResult(writeSpeedMBps, readSpeedMBps, h2Speed, true, null);
            logger.info(result.toString());
            return result;
            
        } catch (Exception e) {
            logger.error("I/O benchmark failed: {}", e.getMessage());
            return IOBenchmarkResult.failed(e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
    
    private double benchmarkH2Database() {
        String tempDbPath = "./data/benchmark_temp_" + System.currentTimeMillis();
        try {
            String url = "jdbc:h2:" + tempDbPath + ";LOCK_MODE=0";
            
            try (Connection conn = DriverManager.getConnection(url, "sa", "")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE BENCHMARK_TEST (ID INT PRIMARY KEY, DATA VARCHAR(256), HASH VARCHAR(64))");
                }
                
                conn.setAutoCommit(false);
                long start = System.nanoTime();
                
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO BENCHMARK_TEST (ID, DATA, HASH) VALUES (?, ?, ?)")) {
                    for (int i = 0; i < H2_BENCHMARK_ROWS; i++) {
                        ps.setInt(1, i);
                        ps.setString(2, "TestData_" + i + "_" + System.currentTimeMillis());
                        ps.setString(3, "abc123def456" + i);
                        ps.addBatch();
                        
                        if (i % 1000 == 0) {
                            ps.executeBatch();
                        }
                    }
                    ps.executeBatch();
                }
                conn.commit();
                
                long elapsed = System.nanoTime() - start;
                return H2_BENCHMARK_ROWS / (elapsed / 1_000_000_000.0);
            }
            
        } catch (Exception e) {
            logger.warn("H2 benchmark failed: {}", e.getMessage());
            return -1;
        } finally {
            try {
                new File(tempDbPath + ".mv.db").delete();
                new File(tempDbPath + ".trace.db").delete();
            } catch (Exception ignored) {}
        }
    }
    
    // ========================================================================
    // SMART PROFILE RECOMMENDATION
    // ========================================================================
    
    public static class BenchmarkResults {
        public final NetworkBenchmarkResult sourceBench;
        public final NetworkBenchmarkResult destBench;
        public final IOBenchmarkResult ioBench;
        public final int cpuCores;
        public final long memoryMB;
        
        public BenchmarkResults(NetworkBenchmarkResult src, NetworkBenchmarkResult dest, 
                               IOBenchmarkResult io, int cores, long memory) {
            this.sourceBench = src;
            this.destBench = dest;
            this.ioBench = io;
            this.cpuCores = cores;
            this.memoryMB = memory;
        }
        
        public String getBottleneck() {
            if (sourceBench != null && destBench != null && sourceBench.success && destBench.success) {
                if (sourceBench.itemsPerSec < destBench.itemsPerSec * 0.5) {
                    return "SOURCE";
                } else if (destBench.itemsPerSec < sourceBench.itemsPerSec * 0.5) {
                    return "DESTINATION";
                }
            }
            if (ioBench != null && ioBench.success && ioBench.diskWriteMBps < 50) {
                return "DISK_IO";
            }
            return "BALANCED";
        }
    }
    
    public String recommendProfile(BenchmarkResults results) {
        int cores = results.cpuCores;
        long memory = results.memoryMB;
        
        String baseProfile;
        if (memory >= 32000 && cores >= 16) {
            baseProfile = "EXTREM";
        } else if (memory >= 16000 && cores >= 8) {
            baseProfile = "GROSS";
        } else if (memory >= 8000 && cores >= 4) {
            baseProfile = "MITTEL";
        } else {
            baseProfile = "KLEIN";
        }
        
        if (results.sourceBench != null && results.sourceBench.success) {
            if (results.sourceBench.latencyMs > 200) {
                baseProfile = downgradeProfile(baseProfile);
                logger.info("Downgrading profile due to high source latency: {}ms", results.sourceBench.latencyMs);
            }
        }
        
        if (results.ioBench != null && results.ioBench.success) {
            if (results.ioBench.diskWriteMBps < 50) {
                logger.info("Slow disk detected: {}MB/s - will adjust batch settings", results.ioBench.diskWriteMBps);
            }
        }
        
        logger.info("Recommended profile: {} (based on {} cores, {} MB RAM)", baseProfile, cores, memory);
        return baseProfile;
    }
    
    private String downgradeProfile(String profile) {
        switch (profile) {
            case "ULTI": return "EXTREM";
            case "EXTREM": return "GROSS";
            case "GROSS": return "MITTEL";
            case "MITTEL": return "KLEIN";
            default: return "KLEIN";
        }
    }
    
    public Properties generateRecommendedConfig(BenchmarkResults results) {
        Properties props = new Properties();
        
        String profile = recommendProfile(results);
        props.setProperty("PROFILE", profile);
        
        int baseThreads = getProfileThreads(profile);
        int cpuOptimal = results.cpuCores * 2;
        int threads = Math.min(baseThreads, cpuOptimal);
        
        if ("SOURCE".equals(results.getBottleneck())) {
            threads = Math.max(threads / 2, 5); 
        }
        
        props.setProperty("# AUTO-DETECTED VALUES (can be overridden)", "");
        props.setProperty("# THREAD_COUNT", String.valueOf(threads));
        props.setProperty("# BATCH_SIZE", String.valueOf(getProfileBatch(profile)));
        props.setProperty("# QUEUE_SIZE", String.valueOf(getProfileQueue(profile)));
        
        if (results.ioBench != null && results.ioBench.success) {
            if (results.ioBench.diskWriteMBps < 50) {
                int reducedBatch = Math.max(50, getProfileBatch(profile) / 2);
                props.setProperty("# BATCH_SIZE (reduced for slow disk)", String.valueOf(reducedBatch));
            }
            
            if (results.ioBench.h2InsertRowsPerSec > 20000) {
                props.setProperty("# DB_URL_APPEND", ";CACHE_SIZE=131072;LOCK_MODE=0");
            }
        }
        
        props.setProperty("# SOURCE_POOL_SIZE", String.valueOf(threads + 1));
        props.setProperty("# DEST_POOL_SIZE", String.valueOf(threads));
        
        props.setProperty("# DETECTED_BOTTLENECK", results.getBottleneck());
        
        return props;
    }
    
    private int getProfileThreads(String profile) {
        switch (profile) {
            case "ULTI": return 200;
            case "EXTREM": return 100;
            case "GROSS": return 50;
            case "MITTEL": return 20;
            case "KLEIN": default: return 5;
        }
    }
    
    private int getProfileBatch(String profile) {
        switch (profile) {
            case "ULTI": return 2000;
            case "EXTREM": return 1000;
            case "GROSS": return 500;
            case "MITTEL": return 200;
            case "KLEIN": default: return 50;
        }
    }
    
    private int getProfileQueue(String profile) {
        switch (profile) {
            case "ULTI": return 50000;
            case "EXTREM": return 20000;
            case "GROSS": return 10000;
            case "MITTEL": return 5000;
            case "KLEIN": default: return 1000;
        }
    }
    
    public BenchmarkResults runFullBenchmark(String sourceSSID, String destSSID, String user, String password) {
        logger.info("========================================");
        logger.info("Starting Full System Benchmark");
        logger.info("========================================");
        
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long memoryMB = detectAvailableMemoryMB();
        
        logger.info("System: {} cores, {} MB heap", cpuCores, memoryMB);
        
        NetworkBenchmarkResult srcBench = null;
        NetworkBenchmarkResult destBench = null;
        
        if (sourceSSID != null && !sourceSSID.isEmpty()) {
            srcBench = benchmarkServer(sourceSSID, user, password);
        }
        
        if (destSSID != null && !destSSID.isEmpty()) {
            destBench = benchmarkServer(destSSID, user, password);
        }
        
        IOBenchmarkResult ioBench = benchmarkLocalIO();
        
        logger.info("========================================");
        logger.info("Benchmark Complete");
        logger.info("========================================");
        
        return new BenchmarkResults(srcBench, destBench, ioBench, cpuCores, memoryMB);
    }
    
    public BenchmarkResults runQuickBenchmark() {
        logger.info("Running quick local benchmark...");
        
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long memoryMB = detectAvailableMemoryMB();
        IOBenchmarkResult ioBench = benchmarkLocalIO();
        
        return new BenchmarkResults(null, null, ioBench, cpuCores, memoryMB);
    }
}