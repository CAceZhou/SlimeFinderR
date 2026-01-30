package io.github.cacezhou.slimefinder;

import java.util.Random;

public class SlimeSlider {
    private final BitMatrix slideMatrix;
    private final BitMatrix circleMatrix;

    // 搜索范围的行数（单位：区块）
    private final int searchRows;

    // 挂机加载窗口掩码的半径
    private final int radius;
    // 直径
    private final int maskDim;

    // 当前窗口中心点在世界坐标系中的区块坐标 (x, z)
    private final int[] centre;

    // 窗口左上角在世界坐标系中的区块坐标
    // leftTop 是 slideMatrix 逻辑坐标 (0,0) 对应的地方
    private final int[] leftTop;

    public final long seed;
    private Direction currSlideDirection = Direction.DOWN;

    // 扫描状态记录
    private int stepsTakenInRow = -1; // 当前列已经走了多少步

    public enum Direction {
        // Down (z++) -> shiftVertical(1) -> 数据上移，新行在逻辑底部
        // Up   (z--) -> shiftVertical(-1) -> 数据下移，新行在逻辑顶部
        UP(-1), DOWN(1), RIGHT(1);

        public final int sign;
        Direction(int sign) { this.sign = sign; }

        public Direction getOpposite() {
            return (this == UP) ? DOWN : UP;
        }
    }

    /**
     * 初始化滑行窗口
     * @param startChunkX 起始点的区块x坐标
     * @param startChunkZ 起始点的区块z坐标
     * @param rowCount 每扫描多少行向右移一列
     * @param circleRadius 玩家刷怪范围的半径
     * @param seed 地图种子
     */
    public SlimeSlider(int startChunkX,
                       int startChunkZ,
                       int rowCount,
                       int circleRadius,
                       long seed) {
        this.searchRows = rowCount;
        this.radius = circleRadius;
        this.maskDim = 2 * circleRadius + 1;
        this.seed = seed;

        // 初始中心点
        this.centre = new int[]{startChunkX, startChunkZ};
        // 初始窗口左上角
        this.leftTop = new int[]{startChunkX - radius, startChunkZ - radius};

        this.slideMatrix = BitMatrix.create(maskDim, maskDim);
        this.circleMatrix = BitMatrix.createCircleEven(circleRadius);

        // 预热填充整个 slideMatrix
        Random tempRan = new Random();
        for (int r = 0; r < maskDim; r++) {
            for (int c = 0; c < maskDim; c++) {
                boolean isSlime = Main.isSlimeChunk(tempRan, seed, leftTop[0] + c, leftTop[1] + r);
                slideMatrix.set(r, c, isSlime);
            }
        }
    }

    /**
     * 蛇形滑动到下一个位置并返回密有效区块数
     * @return 当前位置可加载的史莱姆区块数
     */
    public int slideNext(Random random) {
        // 判断是否需要转向（到达列边界）
        if (stepsTakenInRow >= searchRows - 1) {

            // 向右横移一步
            moveWindow(Direction.RIGHT);
            slideMatrix.shiftHorizontal(-Direction.RIGHT.sign); // 窗口向右，数据左移

            // 填充最右侧新出现的一列
            int newCol = maskDim - 1;
            for (int r = 0; r < maskDim; r++) {
                // x 坐标为 leftTop[0] + newCol, z 坐标随行变
                boolean isSlime = Main.isSlimeChunk(random, seed, leftTop[0] + newCol, leftTop[1] + r);
                slideMatrix.set(r, newCol, isSlime);
            }

            // 转向并重置计数
            currSlideDirection = currSlideDirection.getOpposite();
            stepsTakenInRow = 0;

            // 返回当前位置结果
            return slideMatrix.countIntersection(circleMatrix);
        }

        // 正常垂直滑动
        moveWindow(currSlideDirection);
        slideMatrix.shiftVertical(currSlideDirection.sign);

        // 确定新数据填入哪一行
        int fillRow = (currSlideDirection == Direction.DOWN) ? (maskDim - 1) : 0;

        // 填充新行
        for (int c = 0; c < maskDim; c++) {
            // z 坐标为 leftTop[1] + fillRow, x 坐标随列变
            boolean isSlime = Main.isSlimeChunk(random, seed, leftTop[0] + c, leftTop[1] + fillRow);
            slideMatrix.set(fillRow, c, isSlime);
        }

        stepsTakenInRow++;
        return slideMatrix.countIntersection(circleMatrix);
    }

    private void moveWindow(Direction dir) {
        if (dir == Direction.RIGHT) {
            centre[0] += 1;
            leftTop[0] += 1;
        } else {
            // DOWN 是 +1, UP 是 -1
            centre[1] += dir.sign;
            leftTop[1] += dir.sign;
        }
    }

    public int[] getCentre() { return centre.clone(); }

    public BitMatrix getSlideMatrix() {
        BitMatrix matrixToReturn = BitMatrix.create(maskDim, maskDim);
        slideMatrix.extractSubMatrix(0, 0, matrixToReturn);
        return matrixToReturn;
    }
}