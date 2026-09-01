package dinocode.tui;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spinner 可重入性回归测试（ch06 人在回路：ensureStopped → start 会二次启动线程）。
 */
class SpinnerTest {

    @Test
    void startIsRestartable() throws Exception {
        StringWriter buffer = new StringWriter();
        Spinner spinner = new Spinner(new PrintWriter(buffer, true));

        spinner.start();          // 第一次启动
        Thread.sleep(120);        // 让动画跑几帧
        spinner.ensureStopped();  // ch06 人在回路暂停

        // 第二次 start：修复前抛 IllegalThreadStateException（线程不可重启）
        assertDoesNotThrow(spinner::start);
        Thread.sleep(120);
        spinner.stop();
    }

    @Test
    void stopIsIdempotent() {
        Spinner spinner = new Spinner(new PrintWriter(new StringWriter(), true));
        spinner.start();
        spinner.stop();
        assertDoesNotThrow(spinner::stop); // 幂等
    }

    @Test
    void startAfterFullStopWorks() throws Exception {
        Spinner spinner = new Spinner(new PrintWriter(new StringWriter(), true));
        spinner.start();
        spinner.stop();
        Thread.sleep(50);
        assertDoesNotThrow(spinner::start); // stop 后再 start 也应可用
        spinner.stop();
        assertTrue(true);
    }
}
