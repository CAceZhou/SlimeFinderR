package io.github.cacezhou.slimefinder;

import java.util.Arrays;

public class BitMatrix {
    private final long[] data;
    public final int rows_count;
    public final int cols_count;
    private final int longPerRow; // 每一行需要多少个 long

    // 逻辑首行在物理数组中的索引 (0 ~ rows_count-1)
    private int headRowIndex = 0;

    private BitMatrix(int rows_count, int cols_count) {
        this.rows_count = rows_count;
        this.cols_count = cols_count;
        this.longPerRow = (cols_count + 63) >>> 6;
        this.data = new long[this.rows_count * this.longPerRow];
    }

    public static BitMatrix create(int rows, int cols) {
        return new BitMatrix(rows, cols);
    }


    /**
     * 将逻辑行号映射为物理行号 (data数组中的存储位置)
     * 利用 headRowIndex 进行环形偏移
     */
    private int getPhysicalRow(int logicalRow) {
        // 简单的环形加法，比 % 运算更快
        int idx = headRowIndex + logicalRow;
        if (idx >= rows_count) idx -= rows_count;
        return idx;
    }

    /**
     * 向下位移 delta 行 (负数则向上)
     * 极速模式：不再复制数组，而是移动头指针并清零失效数据
     */
    public void shiftVertical(int delta) {
        if (delta == 0) return;

        // 如果位移超过整个矩阵高度，全清
        if (Math.abs(delta) >= rows_count) {
            Arrays.fill(data, 0L);
            headRowIndex = 0;
            return;
        }

        if (delta > 0) {
            // 顶部移出，底部新增。
            // Head 指针向下移动。
            // 移动后变成了逻辑上的新底部行的物理行需要清零

            // 清理即将变成新底部的物理内存 (即当前的 Top 行)
            // 此时 headRowIndex 指向逻辑第 0 行，也是物理上马上要失效的行
            for (int i = 0; i < delta; i++) {
                int pRow = getPhysicalRow(i);
                clearPhysicalRow(pRow);
            }

            // 移动指针
            headRowIndex += delta;
            if (headRowIndex >= rows_count) headRowIndex -= rows_count;

        } else {
            // 底部移出，顶部新增。
            // Head 指针向上移动 (减小)。
            // 移动前需要清理掉当前的逻辑底部行

            int absDelta = -delta;
            int logicalBottomStart = rows_count - absDelta;

            // 清理当前的逻辑底部行
            for (int i = 0; i < absDelta; i++) {
                int pRow = getPhysicalRow(logicalBottomStart + i);
                clearPhysicalRow(pRow);
            }

            // 移动指针
            headRowIndex -= absDelta;
            if (headRowIndex < 0) headRowIndex += rows_count;
        }
    }

    // 辅助：将指定物理行清零
    private void clearPhysicalRow(int physicalRow) {
        int startIdx = physicalRow * longPerRow;
        int endIdx = startIdx + longPerRow;
        // Arrays.fill 内部有针对小范围的优化，速度很快
        Arrays.fill(data, startIdx, endIdx, 0L);
    }


    /**
     * 向右位移
     */
    public void shiftHorizontal(int delta) {
        if (delta == 0) return;
        if (Math.abs(delta) >= cols_count) {
            Arrays.fill(data, 0L); // 全清不影响 headRowIndex
            return;
        }

        int wordShift = delta >> 6;
        int bitShift = delta & 63;

        // 直接遍历物理内存，顺序不重要
        if (delta > 0) {
            shiftRightInternal(wordShift, bitShift);
        } else {
            shiftLeftInternal(-wordShift, -bitShift);
        }
        cleanTailPadding();
    }

    // 内部实现保持原样，直接操作 data 数组
    private void shiftRightInternal(int wordShift, int bitShift) {
        int invBitShift = 64 - bitShift;
        for (int r = 0; r < rows_count; r++) {
            int rowStart = r * longPerRow;
            int rowEnd = rowStart + longPerRow;
            for (int i = rowEnd - 1; i >= rowStart; i--) {
                long res = 0;
                int srcIdx = i - wordShift;
                if (srcIdx >= rowStart) res |= (data[srcIdx] << bitShift);
                int lowerIdx = srcIdx - 1;
                if (bitShift > 0 && lowerIdx >= rowStart) res |= (data[lowerIdx] >>> invBitShift);
                data[i] = res;
            }
        }
    }

    private void shiftLeftInternal(int wordShift, int bitShift) {
        int invBitShift = 64 - bitShift;
        for (int r = 0; r < rows_count; r++) {
            int rowStart = r * longPerRow;
            int rowEnd = rowStart + longPerRow;
            for (int i = rowStart; i < rowEnd; i++) {
                long res = 0;
                int srcIdx = i + wordShift;
                if (srcIdx < rowEnd) res |= (data[srcIdx] >>> bitShift);
                int upperIdx = srcIdx + 1;
                if (bitShift > 0 && upperIdx < rowEnd) res |= (data[upperIdx] << invBitShift);
                data[i] = res;
            }
        }
    }


    public void set(int row, int col, boolean val) {
        if (row < 0 || row >= rows_count || col < 0 || col >= cols_count) throw new IndexOutOfBoundsException();

        int pRow = getPhysicalRow(row); // 映射到物理行
        int wordIdx = (pRow * longPerRow) + (col >> 6);
        int bitIdx = col & 63;

        if (val) data[wordIdx] |= (1L << bitIdx);
        else     data[wordIdx] &= ~(1L << bitIdx);
    }

    public boolean get(int row, int col) {
        if (row < 0 || row >= rows_count || col < 0 || col >= cols_count) throw new IndexOutOfBoundsException();

        int pRow = getPhysicalRow(row); // 映射到物理行
        int wordIdx = (pRow * longPerRow) + (col >> 6);

        return ((data[wordIdx] >>> (col & 63)) & 1L) == 1;
    }

    public int countOnes() {
        // 统计总数与行序无关，直接线性扫描物理内存，最快
        cleanTailPadding();
        int count = 0;
        for (long val : data) {
            count += Long.bitCount(val);
        }
        return count;
    }

    private void cleanTailPadding() {
        // 清理 padding 也与行序无关
        int effectiveBits = cols_count & 63;
        if (effectiveBits == 0) return;
        long mask = (1L << effectiveBits) - 1;
        for (int r = 0; r < rows_count; r++) {
            int lastWordIdx = (r * longPerRow) + longPerRow - 1;
            data[lastWordIdx] &= mask;
        }
    }


    /**

     */
    public void intersect(BitMatrix other) {
        checkDim(other);

        if (this.headRowIndex == 0 && other.headRowIndex == 0) {
            // 两个都没动过，或者刚好归零
            intersectLinear(other);
        } else {
            // 环形不对齐，必须按逻辑行遍历
            intersectLogical(other);
        }
    }

    private void intersectLinear(BitMatrix other) {
        int len = this.data.length;
        for (int i = 0; i < len; i++) {
            this.data[i] &= other.data[i];
        }
    }

    private void intersectLogical(BitMatrix other) {
        for (int r = 0; r < rows_count; r++) {
            int pRowA = this.getPhysicalRow(r);
            int pRowB = other.getPhysicalRow(r);

            // 手动操作该行的所有 long
            int startA = pRowA * longPerRow;
            int startB = pRowB * longPerRow;
            for (int k = 0; k < longPerRow; k++) {
                this.data[startA + k] &= other.data[startB + k];
            }
        }
    }

    public void union(BitMatrix other) {
        checkDim(other);
        if (this.headRowIndex == 0 && other.headRowIndex == 0) {
            for (int i = 0; i < data.length; i++) this.data[i] |= other.data[i];
        } else {
            for (int r = 0; r < rows_count; r++) {
                int startA = this.getPhysicalRow(r) * longPerRow;
                int startB = other.getPhysicalRow(r) * longPerRow;
                for (int k = 0; k < longPerRow; k++) this.data[startA + k] |= other.data[startB + k];
            }
        }
    }

    public void xor(BitMatrix other) {
        checkDim(other);
        if (this.headRowIndex == 0 && other.headRowIndex == 0) {
            for (int i = 0; i < data.length; i++) this.data[i] ^= other.data[i];
        } else {
            for (int r = 0; r < rows_count; r++) {
                int startA = this.getPhysicalRow(r) * longPerRow;
                int startB = other.getPhysicalRow(r) * longPerRow;
                for (int k = 0; k < longPerRow; k++) this.data[startA + k] ^= other.data[startB + k];
            }
        }
    }

    public void invert() {
        // 取反与顺序无关
        for (int i = 0; i < data.length; i++) data[i] = ~data[i];
        cleanTailPadding();
    }

    private void checkDim(BitMatrix other) {
        if (this.rows_count != other.rows_count || this.cols_count != other.cols_count) {
            throw new IllegalArgumentException("Matrix dimensions must match.");
        }
    }

    /**
     * 计算 (startRow, startCol) 处的重叠数。
     */
    public int countIntersection(BitMatrix other) {
        assert this.rows_count == other.rows_count;
        assert this.cols_count == other.cols_count;
        return countIntersectionAt(0, 0, other);
    }

    /**
     * 计算 (startRow, startCol) 处的重叠数。
     */
    public int countIntersectionAt(int startRow, int startCol, BitMatrix other) {
        // 边界裁剪
        int checkRows = Math.min(other.rows_count, this.rows_count - startRow);
        int checkCols = Math.min(other.cols_count, this.cols_count - startCol);
        if (checkRows <= 0 || checkCols <= 0) return 0;

        int srcWordOffset = startCol >> 6;
        int srcBitShift   = startCol & 63;
        int invBitShift   = 64 - srcBitShift;
        int wordsToCheck  = (checkCols + 63) >>> 6;

        int totalHits = 0;
        long[] mapData = this.data;
        long[] spriteData = other.data; // 假设 mask 通常很小且是新建的，head=0，直接读 linear 即可

        for (int r = 0; r < checkRows; r++) {
            // 获取当前逻辑行对应的物理行索引
            int currentPhysicalRow = getPhysicalRow(startRow + r);
            int mapRowStart = currentPhysicalRow * this.longPerRow;

            // 假设是从 0 开始的 (head=0)
            // TODO 如果 mask 也是滚动的，这里得改 mask.getPhysicalRow(r)
            // 通常 mask 是 createCircle 出来的，是纯净的。
            int spriteRowStart = r * other.longPerRow;

            if (srcBitShift == 0) {
                for (int i = 0; i < wordsToCheck; i++) {
                    totalHits += Long.bitCount(mapData[mapRowStart + srcWordOffset + i] & spriteData[spriteRowStart + i]);
                }
            } else {
                for (int i = 0; i < wordsToCheck; i++) {
                    int currentMapIdx = mapRowStart + srcWordOffset + i;
                    long mapVal = mapData[currentMapIdx] >>> srcBitShift;

                    // 修正后的越界检查：只有当不是该行最后一个 word 时才读下一个
                    if (srcWordOffset + i + 1 < this.longPerRow) {
                        mapVal |= (mapData[currentMapIdx + 1] << invBitShift);
                    }

                    totalHits += Long.bitCount(mapVal & spriteData[spriteRowStart + i]);
                }
            }
        }
        return totalHits;
    }

    public void extractSubMatrix(int srcRow, int srcCol, BitMatrix dest) {
        extractSubMatrix(srcRow, srcCol, dest.rows_count, dest.cols_count, dest);
    }

    /**
     * 只会覆盖dest需要被覆盖的位置，不会清零其他部分
     * @param srcRow 源行
     * @param srcCol 源列
     * @param h 裁切出的高
     * @param w 裁切出的宽
     * @param dest 储存到这里
     */
    public void extractSubMatrix(int srcRow, int srcCol, int h, int w, BitMatrix dest) {
        int safeH = Math.min(h, Math.min(this.rows_count - srcRow, dest.rows_count));
        int safeW = Math.min(w, Math.min(this.cols_count - srcCol, dest.cols_count));
        if (safeH <= 0 || safeW <= 0) return; // 避免异常，直接返回

        int srcWordOffset = srcCol >> 6;
        int srcBitShift   = srcCol & 63;
        int invBitShift   = 64 - srcBitShift;
        int wordsToCopy   = (safeW + 63) >>> 6;
        int lastWordBits  = safeW & 63;
        long lastWordMask = (lastWordBits == 0) ? -1L : (1L << lastWordBits) - 1;

        for (int r = 0; r < safeH; r++) {
            // 逻辑行转物理行
            int currentPhysicalRow = getPhysicalRow(srcRow + r);
            int srcRowStart = currentPhysicalRow * this.longPerRow;

            // dest 默认 head=0，直接计算。如果 dest 也要支持滚动，需要 dest.getPhysicalRow(r)
            int destRowStart = r * dest.longPerRow;

            if (srcBitShift == 0) {
                System.arraycopy(this.data, srcRowStart + srcWordOffset, dest.data, destRowStart, wordsToCopy);
            } else {
                for (int i = 0; i < wordsToCopy; i++) {
                    int currentSrcIdx = srcRowStart + srcWordOffset + i;
                    long val = this.data[currentSrcIdx] >>> srcBitShift;
                    if (srcWordOffset + i + 1 < this.longPerRow) {
                        val |= (this.data[currentSrcIdx + 1] << invBitShift);
                    }
                    dest.data[destRowStart + i] = val;
                }
            }
            // 清理 dest 行尾脏数据
            dest.data[destRowStart + wordsToCopy - 1] &= lastWordMask;
        }
    }

    public void print() {
        System.out.print(this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(rows_count * (cols_count * 2 + 1));
        for (int r = 0; r < rows_count; r++) {
            for (int c = 0; c < cols_count; c++) {
                sb.append(get(r, c) ? "1 " : ". ");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static BitMatrix createCircle(int r) {
        // createCircle 代码逻辑完全基于 set(r, c)，会自动适配，无需修改
        // 直接复制上面的 createCircle 实现即可
        int dim = 2 * r + 1;
        BitMatrix matrix = BitMatrix.create(dim, dim);
        long rSq = (long) r * r;
        int center = r;
        for (int y = 0; y < dim; y++) {
            long dy = y - center;
            long dySq = dy * dy;
            if (dySq > rSq) continue;
            int dx = (int) Math.sqrt(rSq - dySq);
            int xStart = center - dx;
            int xEnd = center + dx;
            for (int x = xStart; x <= xEnd; x++) {
                matrix.set(y, x, true);
            }
        }
        return matrix;
    }

    /**
     * 创建一个偶数直径的圆形矩阵
     * 圆心位于 2r x 2r 矩阵中心点的“顶点”上（即坐标 r-0.5, r-0.5）
     *
     * @param r 逻辑半径。生成的矩阵边长为 2r
     * @return 包含圆形的矩阵，尺寸为 (2r) x (2r)
     */
    public static BitMatrix createCircleEven(int r) {
        int dim = 2 * r;
        BitMatrix matrix = BitMatrix.create(dim, dim);

        // 圆心偏移量：对于偶数直径，圆心在像素之间的交点上
        double center = r - 0.5;
        double rSq = (double) r * r;

        for (int y = 0; y < dim; y++) {
            // 计算当前行到几何中心的垂直距离
            double dy = (double) y - center;
            double dySq = dy * dy;

            if (dySq > rSq) continue;

            // 根据勾股定理计算水平宽度 dx = sqrt(r^2 - dy^2)
            double dx = Math.sqrt(rSq - dySq);

            // 计算当前行的起始和终止列索引
            // 使用 ceil 和 floor 确保离散点落在圆内
            int xStart = (int) Math.ceil(center - dx);
            int xEnd = (int) Math.floor(center + dx);

            // 边界保护并填充
            int actualXStart = Math.max(0, xStart);
            int actualXEnd = Math.min(dim - 1, xEnd);

            for (int x = actualXStart; x <= actualXEnd; x++) {
                matrix.set(y, x, true);
            }
        }
        return matrix;
    }
}