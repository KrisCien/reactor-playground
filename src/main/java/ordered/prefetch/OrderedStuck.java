package ordered.prefetch;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderedStuck {
    private static void setReactorBufferSizes(int bufferXS, int bufferS) {
        System.setProperty("reactor.bufferSize.x", String.valueOf(bufferXS));
        System.setProperty("reactor.bufferSize.small", String.valueOf(bufferS));
        if (Queues.SMALL_BUFFER_SIZE != bufferS) {
            throw new RuntimeException("Failed to set reactor.bufferSize.small");
        }
        if (Queues.XS_BUFFER_SIZE != bufferXS) {
            throw new RuntimeException("Failed to set reactor.bufferSize.x");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        int parallelism = 2;  // parallelism must be > 1 to expose issue
        int bufferXS = 32;   // reactor.bufferSize.x
        int bufferS = 256;   // reactor.bufferSize.small
        int orderedPrefetch = bufferS + 128;  // if orderedPrefetch > bufferS then Flux drops elements and eventually hangs

        setReactorBufferSizes(bufferXS, bufferS);
        final AtomicInteger prev = new AtomicInteger(0);
        final CountDownLatch done = new CountDownLatch(1);
        final long start = System.nanoTime();

        Flux.range(0, 4_000_000)
                .subscribeOn(Schedulers.newSingle("init", true))
                .parallel(parallelism)
                .runOn(Schedulers.newParallel("process", parallelism, true))
                .map(i -> i)
                .ordered(Comparator.comparing(i -> i), orderedPrefetch)
                .subscribe(
                        i -> print(prev.getAndSet(i), i),
                        e -> done.countDown(),
                        () -> done.countDown()
                );

        done.await();
        System.out.printf("elapsed: %d\n", (System.nanoTime() - start) / 1_000_000);
    }

    private static void print(int previous, int current) {
        if (previous + 1 != current) {
            System.out.printf("elements dropped: prev: %d, next: %d, lost: %d\n", previous, current, current - previous);
        }
        if (current % 100000 == 0) {
            System.out.println("processed: " + current);
        }
    }
}
