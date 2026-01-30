package io.github.cacezhou.slimefinder;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;


record SearchResult(int x, int z, int count, String matrixView) {}

public class AsyncSlimeFinder {

    private record SimplePoint(int x, int z, int score) {}

    public static List<SearchResult> findTopSlimeClusters(
            long worldSeed,
            int centerChunkX,
            int centerChunkZ,
            int searchRadius,
            int slimeRadius,
            int threadCount,
            int topN
    ) {
        int sideLength = 2 * searchRadius + 1;
        long totalSteps = (long) sideLength * sideLength; // 总步数
        int startXGlobal = centerChunkX - searchRadius;
        int startZGlobal = centerChunkZ - searchRadius;

        // 增加进度累加器
        LongAdder completedSteps = new LongAdder();

        // 启动一个后台监控线程打印进度
        // 在 findTopSlimeClusters 方法内开头
        long startTime = System.currentTimeMillis(); // 记录开始时间
        Thread monitorThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    long current = completedSteps.sum();
                    if (current > 0) {
                        long now = System.currentTimeMillis();
                        long elapsed = now - startTime;

                        // 计算进度
                        double progress = (double) current / totalSteps;
                        // 推算剩余时间 (毫秒)
                        long etaMillis = (long) (elapsed / progress) - elapsed;

                        // 格式化时间显示
                        String etaStr = Main.formatTime(etaMillis);
                        double percentage = progress * 100.0;

                        // 打印进度、百分比、以及 ETA
                        System.out.printf("\r[Progress] %d/%d (%.2f%%) | ETA: %s",
                                current, totalSteps, percentage, etaStr);

                        if (current >= totalSteps) break;
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                // 退出
            }
        });
        monitorThread.setDaemon(true); // 设为守护线程
        monitorThread.start();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<PriorityQueue<SimplePoint>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadStartX = startXGlobal + (i * sideLength / threadCount);
            final int threadEndX = startXGlobal + ((i + 1) * sideLength / threadCount);
            final int threadWidth = threadEndX - threadStartX;

            if (threadWidth <= 0) continue;

            futures.add(executor.submit(() -> {
                Random rand = new Random();
                PriorityQueue<SimplePoint> localHeap = new PriorityQueue<>(Comparator.comparingInt(SimplePoint::score));
                SlimeSlider slider = new SlimeSlider(threadStartX, startZGlobal, sideLength, slimeRadius, worldSeed);

                long currentThreadSteps = (long) threadWidth * sideLength;
                for (long s = 0; s < currentThreadSteps; s++) {
                    int score = slider.slideNext(rand);

                    if (score > 0) {
                        if (localHeap.size() < topN) {
                            localHeap.add(new SimplePoint(slider.getCentre()[0], slider.getCentre()[1], score));
                        } else if (score > localHeap.peek().score) {
                            localHeap.poll();
                            localHeap.add(new SimplePoint(slider.getCentre()[0], slider.getCentre()[1], score));
                        }
                    }

                    // 每完成更新一次进度
                    if (s > 0 && s % sideLength == 0) {
                        completedSteps.add(sideLength);
                    }
                }
                // 补全剩余步数
                completedSteps.add(currentThreadSteps % sideLength);
                return localHeap;
            }));
        }

        PriorityQueue<SimplePoint> globalHeap = new PriorityQueue<>(Comparator.comparingInt(SimplePoint::score));
        for (Future<PriorityQueue<SimplePoint>> future : futures) {
            try {
                PriorityQueue<SimplePoint> localHeap = future.get();
                while (!localHeap.isEmpty()) {
                    SimplePoint p = localHeap.poll();
                    if (globalHeap.size() < topN) globalHeap.add(p);
                    else if (p.score > globalHeap.peek().score) {
                        globalHeap.poll();
                        globalHeap.add(p);
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        executor.shutdown();
        monitorThread.interrupt(); // 停止监控线程
        System.out.println("\n 搜索完成 \n");

        List<SearchResult> finalResults = new ArrayList<>();
        while (!globalHeap.isEmpty()) {
            finalResults.add(convertToFullResult(globalHeap.poll(), worldSeed, slimeRadius));
        }
        Collections.reverse(finalResults);
        return finalResults;
    }

    private static SearchResult convertToFullResult(SimplePoint p, long seed, int r) {
        BitMatrix m = BitMatrix.create(2 * r + 1, 2 * r + 1);
        Random tempRand = new Random();
        for (int row = 0; row < 2 * r + 1; row++) {
            for (int col = 0; col < 2 * r + 1; col++) {
                m.set(row, col, Main.isSlimeChunk(tempRand, seed, p.x - r + col, p.z - r + row));
            }
        }
        return new SearchResult(p.x, p.z, p.score, m.toString());
    }
}