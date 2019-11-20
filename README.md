## Problem description:

Flux operator:

`ParallelFlux::ordered(Comparator<? super T> comparator, int prefetch)`

doesn't work correctly when `parallelism > 1` and `prefetch > reactor.bufferSize.small`.
The elements are randomly dropped by ordered and eventually whole flux hangs and stops processing data.


## Expected Behavior
ordered(comparator,  prefetch) works with custom prefetch values that are greater then reactor.bufferSize.small 

## Actual Behavior
when `paralleism > 1` and `prefetch value > reactor.bufferSize.small` elements are dropped in flux and processing hangs

## Steps to Reproduce

Example program that exposes issue, depending on test system (cpu speed, memory) 
one might tweak parallelism and orderedPrefetch to expose the issue on my test system
can reproduce 100% times with `parallelism == 2` and `orderedPrefetch == reactor.bufferSize.small + 128`
in most test runs flux hangs before processing 100k elements
```java

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
        // parallelism must be > 1
        int parallelism = 2;

        int bufferXS = 32;
        int bufferS = 256;

        // if orderedPrefetch > bufferS then Flux drops elements and eventually get stuck
        int orderedPrefetch = bufferS + 128;

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

```

## Environment

* Reactor version used
  * reactor-core 3.3.0.RELEASE Dysprosium-SR1
* JVM version
  * openjdk version "11.0.4" 2019-07-16
  * OpenJDK Runtime Environment AdoptOpenJDK (build 11.0.4+11)
  * OpenJDK 64-Bit Server VM AdoptOpenJDK (build 11.0.4+11, mixed mode)

* OS and version
  * macOS Mojave 10.14.6 

## Notes
`ParallelFlux::sequential(int prefetch)` doesn't suffer form the same issue i.e. it works as expected with any `parallelism` and `prefetch` values.

Comparing `sequential` and `ordered` implementations it looks like in case of `ordered` the prefetch argument is ignored 
and we always create default buffer size.
`MergeOrderedInnerSubscriber` takes prefetch param but always creates `SpscArrayQueue<>(SMALL_BUFFER_SIZE)`,
https://github.com/reactor/reactor-core/blob/5e9231edb8e3362730ae7c9b7f7dceeb336c5d17/reactor-core/src/main/java/reactor/core/publisher/FluxMergeOrdered.java#L350

Then later on in onNext we call `SpscArrayQueue::offer` so if buffer if undersized we silently drop elements
https://github.com/reactor/reactor-core/blob/5e9231edb8e3362730ae7c9b7f7dceeb336c5d17/reactor-core/src/main/java/reactor/core/publisher/FluxMergeOrdered.java#L362

