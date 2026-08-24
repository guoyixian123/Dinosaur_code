package dino.tui;

import java.io.PrintWriter;

/**
 * 首字到达前的等待指示：动画帧 + 🦖（checklist §H）。
 * 整行重绘，不参与光标编辑，因此可安全使用 emoji。
 */
final class Spinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private final PrintWriter out;
    private final Thread thread;
    private volatile boolean running = true;

    Spinner(PrintWriter out) {
        this.out = out;
        this.thread = new Thread(this::animate, "dino-spinner");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /** 停止并清掉指示行（幂等）。 */
    void stop() {
        running = false;
        try {
            thread.join(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (out) {
            out.print(Ansi.CLEAR_LINE);
            out.flush();
        }
    }

    private void animate() {
        int frame = 0;
        while (running) {
            synchronized (out) {
                out.print("\r" + Ansi.GREEN + FRAMES[frame % FRAMES.length] + " 🦖 thinking…" + Ansi.RESET);
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
