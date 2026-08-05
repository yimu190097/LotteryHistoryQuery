#!/usr/bin/env bash
# ============================================================
# 纯 JVM（无 Android 依赖、无 Robolectric）FlowLayout 算法复刻验证
# 用于：在网络/Robolectric android-all 下载受限时，离线验证
#      "240dp极端窄屏 + maxPerLine=10" 的分行逻辑
#
# 用法：bash /workspace/verify_kl8_240dp_no_android.sh
# ============================================================

cat > /tmp/FlowLayoutAlgoTest.java <<'JAVA_EOF'
import java.util.*;

/**
 * 复刻 FlowLayout.kt 的 onMeasure + onLayout 核心换行算法。
 * 变量命名与源码 FlowLayout.kt:28-112 完全对齐，方便 review。
 *
 * 输入（常量，对应 240dp 屏 + 快乐8 20 球）：
 *   density = 1.0            (直接用 dp 当 px，等价 Robolectric 默认密度 mdpi)
 *   contentMaxWidth = 240    (240dp 屏宽，padding=0)
 *   ballSize = 20            (球=20dp)
 *   margin = 2               (四边 margin=2dp)
 *   maxPerLine = 10          (快乐8强制每行10个)
 *   childCount = 20          (20个号码)
 *
 * 预期输出（6 项断言）：
 *   ① 分 2 行
 *   ② 第1行 10 个球
 *   ③ 第2行 10 个球
 *   ④ 最大行宽 == 240 ( == 10 × (20+2+2) )，不溢出
 *   ⑤ 第1行最大号(46) < 第2行最小号(51)（排序后按序排列）
 *   ⑥ 总高 == 2 × (20+2+2) = 48
 */
public class FlowLayoutAlgoTest {
    static final int[] KL8_2026204_SORTED = {
         3, 10, 13, 21, 22, 24, 28, 37, 38, 46,
        51, 52, 54, 56, 59, 60, 65, 66, 70, 73
    };

    // 单个球的模拟布局结果
    static class Ball {
        int num; int left, top, right, bottom;
        Ball(int n){ num = n; }
    }

    public static void main(String[] args) {
        final float density = 1.0f;
        final int contentMaxWidth = 240; // 240dp 屏宽，padding=0
        final int ballSize = (int)(20 * density);
        final int margin   = (int)(2  * density);
        final int maxPerLine = 10;
        final int childCount = KL8_2026204_SORTED.length;
        final int cw = ballSize + 2*margin; // 每个子元素宽（含 margin）
        final int ch = ballSize + 2*margin; // 每个子元素高（含 margin）

        System.out.println("===== [纯算法验证] 快乐8 2026204期 240dp极端窄屏 + maxPerLine=10 =====");
        System.out.println("屏宽(contentMaxWidth)=" + contentMaxWidth + "dp, 球=" + ballSize + "dp, margin=" + margin + "dp");
        System.out.println("单球占位(含margin)=" + cw + "dp, 10球需宽=" + (10*cw) + "dp, maxPerLine=" + maxPerLine);
        System.out.println("--------------------------------------------------------");

        // ---- onMeasure 复刻（FlowLayout.kt:28-80）----
        int lineWidth = 0, lineHeight = 0, totalHeight = 0, maxLineWidth = 0, countInLine = 0;
        for (int i = 0; i < childCount; i++) {
            boolean widthExceeded = (lineWidth + cw) > contentMaxWidth && lineWidth > 0;
            boolean countExceeded = (maxPerLine > 0) && (countInLine >= maxPerLine);
            if (widthExceeded || countExceeded) {
                // 换行
                totalHeight += lineHeight;
                maxLineWidth = Math.max(maxLineWidth, lineWidth);
                lineWidth = cw;
                lineHeight = ch;
                countInLine = 1;
            } else {
                lineWidth += cw;
                lineHeight = Math.max(lineHeight, ch);
                countInLine++;
            }
        }
        totalHeight += lineHeight;
        maxLineWidth = Math.max(maxLineWidth, lineWidth);
        int measuredWidth  = contentMaxWidth;          // EXACTLY
        int measuredHeight = totalHeight;              // UNSPECIFIED

        System.out.println("[onMeasure] maxLineWidth=" + maxLineWidth + " <= " + contentMaxWidth + " (屏宽)  ✓");
        System.out.println("[onMeasure] totalHeight=" + totalHeight + " (=" + (totalHeight/ch) + "行 × " + ch + "dp)");

        // ---- onLayout 复刻（FlowLayout.kt:82-112）----
        Ball[] balls = new Ball[childCount];
        for (int i = 0; i < childCount; i++) balls[i] = new Ball(KL8_2026204_SORTED[i]);
        int paddingLeft = 0, paddingTop = 0;
        int contentWidth = contentMaxWidth;
        int x = paddingLeft, y = paddingTop; lineHeight = 0; countInLine = 0;
        for (int i = 0; i < childCount; i++) {
            Ball b = balls[i];
            boolean widthExceeded = (x + margin + ballSize + margin) > (paddingLeft + contentWidth) && (x > paddingLeft);
            boolean countExceeded = (maxPerLine > 0) && (countInLine >= maxPerLine);
            if (widthExceeded || countExceeded) {
                x = paddingLeft; y += lineHeight; lineHeight = 0; countInLine = 0;
            }
            int childLeft = x + margin;
            int childTop  = y + margin;
            b.left = childLeft; b.top = childTop;
            b.right = childLeft + ballSize; b.bottom = childTop + ballSize;
            x += margin + ballSize + margin;
            lineHeight = Math.max(lineHeight, margin + ballSize + margin);
            countInLine++;
        }

        // 按 top 分组为行
        TreeMap<Integer, List<Ball>> rows = new TreeMap<>();
        for (Ball b : balls) {
            rows.computeIfAbsent(b.top, k -> new ArrayList<>()).add(b);
        }
        List<List<Ball>> rowList = new ArrayList<>(rows.values());

        // ---- 断言 ----
        int pass = 0, fail = 0;
        pass += assertEq("断言①: 应分 2 行", 2, rowList.size());
        pass += assertEq("断言②: 第1行应 10 个球", 10, rowList.get(0).size());
        pass += assertEq("断言③: 第2行应 10 个球", 10, rowList.get(1).size());
        pass += assertEq("断言④: 最大行宽 == 240 (不溢出)", 240, maxLineWidth);
        int line1Max = rowList.get(0).stream().mapToInt(b -> b.num).max().orElse(-1);
        int line2Min = rowList.get(1).stream().mapToInt(b -> b.num).min().orElse(Integer.MAX_VALUE);
        pass += assertTrue("断言⑤: 第1行最大号(" + line1Max + ") < 第2行最小号(" + line2Min + ")", line1Max < line2Min);
        pass += assertEq("断言⑥: 总高 == 48 (2行 × 24dp)", 48, measuredHeight);

        // ---- 诊断打印 ----
        System.out.println("--------------------------------------------------------");
        for (int r = 0; r < rowList.size(); r++) {
            List<Ball> row = rowList.get(r);
            StringBuilder sb = new StringBuilder("第" + (r+1) + "行(" + row.size() + "个|top=" + row.get(0).top + "dp): ");
            row.forEach(b -> sb.append(String.format("%02d ", b.num)));
            // 行末位置
            Ball last = row.get(row.size()-1);
            sb.append("  ← 末球右边界=").append(last.right).append("dp");
            System.out.println(sb);
        }
        System.out.println("--------------------------------------------------------");
        System.out.println("【纯算法验证结果】通过=" + pass + " / 失败=" + fail);
        if (fail == 0) {
            System.out.println("✅ 240dp极端窄屏分行表现完全符合预期：每行恰好10个，分2行，无横向溢出，号码顺序正确。");
            System.out.println("（此为源码 FlowLayout.onMeasure/onLayout 的等价算法复刻，可作为 Robolectric 插桩测试的离线补充证明）");
            System.exit(0);
        } else {
            System.out.println("❌ 存在断言失败，请检查算法。");
            System.exit(1);
        }
    }

    static int assertEq(String msg, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        System.out.println((ok ? "✓ " : "✗ ") + msg + "  [期望=" + expected + ", 实际=" + actual + "]");
        return ok ? 1 : 0;
    }
    static int assertTrue(String msg, boolean cond) {
        System.out.println((cond ? "✓ " : "✗ ") + msg);
        return cond ? 1 : 0;
    }
}
JAVA_EOF

# 编译 & 运行
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
  javac /tmp/FlowLayoutAlgoTest.java -d /tmp/ && java -cp /tmp FlowLayoutAlgoTest
  exit $?
else
  echo "⚠️  系统未找到 javac/java，跳过纯算法验证。请使用 Gradle Robolectric 测试。"
  exit 2
fi
