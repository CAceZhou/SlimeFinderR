package io.github.cacezhou.slimefinder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class Main {

    public static void main(String[] args) throws Exception {
        String configPath = "config";
        ConfigLoader.SearchParams p;
        if (args.length == 1) {
            configPath = args[0];
        } else if (args.length != 0) {
            System.err.println("Usage: java -jar <jarName>.jar <configPath>");
            return;
        }
        try {
            System.out.println("正在从 " + configPath + " 读取配置...");
            ConfigLoader loader = new ConfigLoader(configPath);
            p = loader.getSearchParams();
            System.out.println("配置加载成功，种子: " + p.seed());
            System.out.println("准备在范围 " + p.searchRadius() + " 内进行多线程搜索...");
        } catch (IOException e) {
            System.err.println("错误：无法读取配置文件 " + configPath);
            System.out.println("将生成默认配置文件...");
            extractResource("/config", configPath);
            System.out.println("生成完毕！请修改参数后再运行！");
            return;
        } catch (NumberFormatException e) {
            System.err.println("错误：配置文件中的数值格式不正确，请检查是否包含非数字字符。");
            return;
        }

        System.out.println("开始搜索...");
        long t0 = System.currentTimeMillis();

        // 搜索中心(0,0)，搜索半径 10000 (即 20001x20001 范围)，史莱姆半径 8，线程 8，取前 10 名
        List<SearchResult> topList = AsyncSlimeFinder.findTopSlimeClusters(
                p.seed(), p.centerX(), p.centerZ(), p.searchRadius(), p.slimeRadius(), p.threads(), p.topN()
        );

        long t1 = System.currentTimeMillis();
        System.out.println("搜索完成，耗时: " + (t1 - t0) / 1000D + "s");

        for (int i = 0; i < topList.size(); i++) {
            SearchResult res = topList.get(i);
            System.out.printf("TOP %d: 区块坐标 [%d, %d] | 坐标：[%d, %d] | 史莱姆区块数: %d\n",
                    i + 1, res.x(), res.z(), res.x()*16, res.z()*16, res.count());
             System.out.println(res.matrixView()); // 如果需要打印矩阵视图则取消注释
        }
    }

    public static void main1(String[] args) {
        int sideLength = 32767;
        int threadCount = 8;
        SlimeSlider[] sliders = new SlimeSlider[threadCount];
        int XLengthPerSlider = (int) Math.ceil((double) sideLength / threadCount);;  // 每个线程负责的列数（默认的向上取整）
        int startPoint = 0;
        for (int i = 0; i < sliders.length; i++) {
            sliders[i] = new SlimeSlider(startPoint, 0, sideLength, 8, 8594768700734077283L);
            startPoint += XLengthPerSlider;
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (SlimeSlider slider : sliders) {
                executor.submit(() -> {
                    Random rand = new Random();
                    int maxC = 0;
                    int[] centre = new int[2];
                    long times = 0;
                    BitMatrix matrix = BitMatrix.create(1, 1);
                    for (int x = 0; x < XLengthPerSlider * sideLength; x++) {
                        int c = slider.slideNext(rand);
                        times++;
                        if (times > 0.01 * XLengthPerSlider * sideLength) {
                            System.out.println(XLengthPerSlider * sideLength - x);
                            times = 0;
                        }
                        if (c >= maxC) {
                            maxC = c;
                            centre = slider.getCentre();
                            matrix = slider.getSlideMatrix();
                        } else if (c < 0) {
                            break;
                        }
                    }
                    System.out.println("centre = " + centre[0] + "," + centre[1] + " count: " + maxC);
                    System.out.println(matrix.toString());
                });
            }
        }

    }

    public static void test() {

        // 1. 生成半径为 5 的圆形矩阵
        BitMatrix circle = BitMatrix.createCircle(5);
        System.out.printf("原始圆形 ( %d个1 ) \n", circle.countOnes());
        circle.print();

        // 2. 模拟高频操作：向右移 3 格，向下移 2 格
        // 由于采用了行对齐存储，这些操作是极速的
        circle.shiftHorizontal(3);
        circle.shiftVertical(0);

        System.out.printf("\n位移后的圆形 ( %d个1 ) \n", circle.countOnes());
        circle.print();

        BitMatrix rectangle = BitMatrix.create(67, 67);
        System.out.println("\n一个正方形");
        Random random = new Random();
        for (int i = 0; i < 4*67*67; i++) {
            rectangle.set(random.nextInt(0, 67), random.nextInt(0,67), true);
        }
        rectangle.print();

        BitMatrix rectangle2 = BitMatrix.create(11, 11);
        rectangle.extractSubMatrix(56, 56, rectangle2);
        System.out.println("\n抽取出一块");
        System.out.println(rectangle2);



        System.out.println("\n");
        circle.intersect(rectangle2);
        circle.print();
        System.out.printf("\n,共%d个1",  circle.countOnes());

        int c = circle.countIntersection(rectangle2);
        System.out.printf("\n内存内计算结果：共%d个1", c);
    }


    /**
     * This method is copied directly from the source code of Minecraft.
     * It determines which chunks are slime chunks.
     *
     * @param seed
     * @param chunkX - chunk x coordinate
     * @param chunkZ - chunk z coordinate
     * @return true if (chunkX, chunkZ) is a slime chunk, false otherwise
     */
    public static boolean isSlimeChunk(Random r, long seed, int chunkX, int chunkZ) {
        r.setSeed(seed + (long) (chunkX * chunkX * 4987142) + (long) (chunkX * 5947611) + (long) (chunkZ * chunkZ) * 4392871L + (long) (chunkZ * 389711) ^ 987234911L);
        return r.nextInt(10) == 0;
    }

    public static String formatTime(long millis) {
        if (millis <= 0) return "00:00:00";
        long seconds = millis / 1000;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    public static void extractResource(String resourcePath, String destinationPath) throws Exception {
        // 1. 获取 JAR 内部文件的输入流 (注意：路径以 / 开头)
        try (InputStream is = Main.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new Exception("未找到资源文件: " + resourcePath);
            }

            // 2. 确定目标路径
            Path target = Paths.get(destinationPath);

            // 3. 如果目录不存在则创建
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            // 4. 执行拷贝 (REPLACE_EXISTING 表示如果文件已存在则覆盖)
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("文件已成功解压到: " + target.toAbsolutePath());
        }
    }


}