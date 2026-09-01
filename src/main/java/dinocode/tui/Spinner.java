package dinocode.tui;

import java.io.PrintWriter;

/**
 * 首字到达前的等待指示：动画帧 + 中文文案（ch16 视觉优化，全界面去 emoji）。
 * 整行重绘，不参与光标编辑。
 */
final class Spinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final PrintWriter out;
    private Thread thread;
    private volatile boolean running;

    Spinner(PrintWriter out) {
        this.out = out;
    }

    /** 启动动画（可重入：人在回路暂停-恢复会多次 start）。 */
    synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        running = true;
        thread = new Thread(this::animate, "dino-spinner");
        thread.setDaemon(true);
        thread.start();
    }

    /** 仅停止动画（不清行），供暂停-恢复场景（ch06 人在回路）。 */
    void ensureStopped() {
        running = false;
        if (thread != null) {
            try {
                thread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 停止并清掉指示行（幂等）。 */
    void stop() {
        ensureStopped();
        synchronized (out) {
            out.print(Ansi.CLEAR_LINE);
            out.flush();
        }
    }

    private void animate() {
        int frame = 0;
        while (running) {
            synchronized (out) {
                out.print("\r" + Ansi.GREEN + FRAMES[frame % FRAMES.length] + " 小龙思考中…" + Ansi.RESET);
                out.flush();
            }
            frame++;
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
