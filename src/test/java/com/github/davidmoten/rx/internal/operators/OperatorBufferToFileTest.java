package com.github.davidmoten.rx.internal.operators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.github.davidmoten.rx.Actions;
import com.github.davidmoten.rx.Transformers;
import com.github.davidmoten.rx.buffertofile.DataSerializer;
import com.github.davidmoten.rx.buffertofile.DataSerializers;
import com.github.davidmoten.rx.buffertofile.Options;

import rx.Observable;
import rx.Scheduler;
import rx.Scheduler.Worker;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.observers.TestSubscriber;
import rx.plugins.RxJavaPlugins;
import rx.schedulers.Schedulers;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class OperatorBufferToFileTest {

	@Before
	@After
	public void resetBefore() {
		RxJavaPlugins ps = RxJavaPlugins.getInstance();

		try {
			Method m = ps.getClass().getDeclaredMethod("reset");
			m.setAccessible(true);
			m.invoke(ps);
		} catch (Throwable ex) {
			ex.printStackTrace();
		}
	}

	@Test
	public void handlesEmpty() {
		Scheduler scheduler = createSingleThreadScheduler();
		for (int i = 0; i < loops(); i++) {
			TestSubscriber<String> ts = TestSubscriber.create(0);
			Observable.<String> empty()
					.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), scheduler))
					.subscribe(ts);
			ts.requestMore(1);
			ts.awaitTerminalEvent();
			ts.assertNoErrors();
			ts.assertNoValues();
			ts.assertCompleted();
			waitUntilWorkCompleted(scheduler);
		}
	}

	@Test
	public void handlesEmptyUsingJavaIOSerialization() {
		Scheduler scheduler = createSingleThreadScheduler();
		for (int i = 0; i < loops(); i++) {
			TestSubscriber<String> ts = TestSubscriber.create(0);
			Observable.<String> empty()
					.compose(Transformers.onBackpressureBufferToFile(DataSerializers.<String> javaIO(), scheduler))
					.subscribe(ts);
			ts.requestMore(1);
			ts.awaitTerminalEvent();
			ts.assertNoErrors();
			ts.assertNoValues();
			ts.assertCompleted();
			waitUntilWorkCompleted(scheduler);
		}
	}

	@Test
	public void handlesThreeUsingJavaIOSerialization() {
		Scheduler scheduler = createSingleThreadScheduler();
		for (int i = 0; i < loops(); i++) {
			TestSubscriber<String> ts = TestSubscriber.create();
			Observable.just("a", "bc", "def")
					.compose(Transformers.onBackpressureBufferToFile(DataSerializers.<String> javaIO(), scheduler))
					.subscribe(ts);
			ts.awaitTerminalEvent();
			ts.assertNoErrors();
			ts.assertValues("a", "bc", "def");
			ts.assertCompleted();
			waitUntilWorkCompleted(scheduler);
		}
	}

	@Test
	public void handlesThreeElementsImmediateScheduler() throws InterruptedException {
		checkHandlesThreeElements(Options.defaultInstance());
	}

	private void checkHandlesThreeElements(Options options) {
		List<String> b = Observable.just("abc", "def", "ghi")
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), Schedulers.immediate(),
						options))
				.toList().toBlocking().single();
		assertEquals(Arrays.asList("abc", "def", "ghi"), b);
	}

	private static int loops() {
		return Integer.parseInt(System.getProperty("loops", "1000"));
	}

	@Test
	public void testNullsInStreamHandledByJavaIOSerialization() {
		List<Integer> list = Observable.just(1, 2, (Integer) null, 4)
				.compose(Transformers.<Integer> onBackpressureBufferToFile()).toList().toBlocking().single();
		assertEquals(Arrays.asList(1,2, (Integer) null, 4), list);
	}

	@Test
	public void handlesThreeElementsWithBackpressureAndEnsureCompletionEventArrivesWhenThreeRequested()
			throws InterruptedException {
		Scheduler scheduler = createSingleThreadScheduler();
		for (int i = 0; i < loops(); i++) {
			TestSubscriber<String> ts = TestSubscriber.create(0);
			Observable.just("abc", "def", "ghi")
					//
					.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), scheduler,
							Options.defaultInstance()))
					.subscribe(ts);
			ts.requestMore(2);
			ts.requestMore(1);
			ts.awaitTerminalEvent(1, TimeUnit.SECONDS);
			if (ts.getOnNextEvents().size() != 3) {
				Assert.fail("wrong number of elements on loop " + i + " found " + ts.getOnNextEvents().size());
			}
			ts.assertValues("abc", "def", "ghi");
			ts.assertNoErrors();
			waitUntilWorkCompleted(scheduler);
		}
	}

	@Test
	public void handlesErrorSerialization() throws InterruptedException {
		Scheduler scheduler = createSingleThreadScheduler();
		for (int i = 0; i < loops(); i++) {
			TestSubscriber<String> ts = TestSubscriber.create();
			Observable.<String> error(new IOException("boo"))
					//
					.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), scheduler,
							Options.defaultInstance()))
					.subscribe(ts);
			ts.awaitTerminalEvent(10, TimeUnit.SECONDS);
			ts.assertError(IOException.class);
			waitUntilWorkCompleted(scheduler);
		}
	}

	@Test
	public void handlesErrorWhenDelayErrorIsFalse() throws InterruptedException {
		Scheduler scheduler = createSingleThreadScheduler();
		TestSubscriber<String> ts = TestSubscriber.create(0);
		Observable.just("abc", "def").concatWith(Observable.<String> error(new IOException("boo")))
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), scheduler,
						Options.delayError(false).build()))
				.doOnNext(new Action1<String>() {
					boolean first = true;

					@Override
					public void call(String t) {
						if (first) {
							first = false;
							try {
								TimeUnit.MILLISECONDS.sleep(500);
							} catch (InterruptedException e) {
							}
						}
					}
				}).subscribe(ts);
		ts.requestMore(2);
		ts.awaitTerminalEvent(5000, TimeUnit.SECONDS);
		ts.assertError(IOException.class);
		waitUntilWorkCompleted(scheduler);
	}

	@Test
	public void handlesUnsubscription() throws InterruptedException {
		Scheduler scheduler = createSingleThreadScheduler();
		TestSubscriber<String> ts = TestSubscriber.create(0);
		Observable.just("abc", "def", "ghi")
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), scheduler,
						Options.defaultInstance()))
				.subscribe(ts);
		ts.requestMore(2);
		TimeUnit.MILLISECONDS.sleep(500);
		ts.unsubscribe();
		TimeUnit.MILLISECONDS.sleep(500);
		ts.assertValues("abc", "def");
		waitUntilWorkCompleted(scheduler);
	}

	@Test
	public void handlesUnsubscriptionDuringDrainLoop() throws InterruptedException {
		Scheduler scheduler = createSingleThreadScheduler();
		TestSubscriber<String> ts = TestSubscriber.create(0);
		Observable.just("abc", "def", "ghi")
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.string(), scheduler))
				.doOnNext(new Action1<Object>() {

					@Override
					public void call(Object t) {
						try {
							// pauses drain loop
							Thread.sleep(500);
						} catch (InterruptedException e) {
						}
					}
				}).subscribe(ts);
		ts.requestMore(2);
		TimeUnit.MILLISECONDS.sleep(250);
		ts.unsubscribe();
		TimeUnit.MILLISECONDS.sleep(500);
		ts.assertValues("abc");
		waitUntilWorkCompleted(scheduler);
	}

	@Test
	public void handlesManyLargeMessages() {
		Scheduler scheduler = createSingleThreadScheduler();
		DataSerializer<Integer> serializer = createLargeMessageSerializer();
		int max = 100;
		int last = Observable.range(1, max)
				//
				.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler))
				// log
				// .lift(Logging.<Integer> logger().showMemory().log())
				// delay emissions
				.doOnNext(new Action1<Object>() {
					int count = 0;

					@Override
					public void call(Object t) {
						// delay processing of reads for first three items
						count++;
						if (count < 3) {
							try {
								// System.out.println(t);
								Thread.sleep(1000);
							} catch (InterruptedException e) {
								//
							}
						}
					}
				}).last().toBlocking().single();
		assertEquals(max, last);
		waitUntilWorkCompleted(scheduler);
	}

	@Test
	public void rolloverWorks() throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			DataSerializer<Integer> serializer = DataSerializers.integer();
			int max = 100;
			Scheduler scheduler = createSingleThreadScheduler();
			int last = Observable.range(1, max)
					//
					.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler,
							Options.rolloverEvery(max / 10).build()))
					.last().toBlocking().single();
			assertEquals(max, last);
			// wait for all scheduled work to complete (unsubscription)
			waitUntilWorkCompleted(scheduler, 10, TimeUnit.SECONDS);
		}
	}

	private static void waitUntilWorkCompleted(Scheduler scheduler) {
		waitUntilWorkCompleted(scheduler, 10, TimeUnit.SECONDS);
	}

	private static void waitUntilWorkCompleted(Scheduler scheduler, long duration, TimeUnit unit) {
		final CountDownLatch latch = new CountDownLatch(1);
		Worker worker = scheduler.createWorker();
		worker.schedule(Actions.countDown(latch));
		worker.schedule(Actions.unsubscribe(worker));
		try {
			if (!worker.isUnsubscribed() && !latch.await(duration, unit)) {
				throw new RuntimeException("did not complete");
			}
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	public void handlesMultiSecondLoopOfMidStreamUnsubscribeRollover() throws Throwable {
		int max = 1000;
		Options options = Options.rolloverEvery(max / 10).build();
		checkMultiSecondLoopOfMidStreamUnsubscribeWithOptions(max, options);
	}

	@Test
	public void handlesMultiSecondLoopOfMidStreamUnsubscribeNoRollover() throws Throwable {
		int max = 1000;
		Options options = Options.disableRollover().build();
		checkMultiSecondLoopOfMidStreamUnsubscribeWithOptions(max, options);
	}

	private static void checkMultiSecondLoopOfMidStreamUnsubscribeWithOptions(int max, Options options)
			throws InterruptedException, Throwable {
		int maxSeconds = Integer.parseInt(System.getProperty("max.seconds", "4"));
		// run for ten seconds
		long t = System.currentTimeMillis();
		long count = 0;
		Scheduler scheduler = createNamedSingleThreadScheduler("scheduler1");
		Scheduler scheduler2 = createNamedSingleThreadScheduler("scheduler2");
		while ((System.currentTimeMillis() - t < TimeUnit.SECONDS.toMillis(maxSeconds))) {
			try {
				DataSerializer<Integer> serializer = DataSerializers.integer();
				final CountDownLatch latch = new CountDownLatch(1);
				final AtomicInteger last = new AtomicInteger(-1);
				final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
				final int unsubscribeAfter = max / 2 + 1;
				final Queue<Integer> list = new ConcurrentLinkedQueue<Integer>();
				Subscriber<Integer> subscriber = new Subscriber<Integer>() {
					int count = 0;

					@Override
					public void onCompleted() {
						latch.countDown();
					}

					@Override
					public void onError(Throwable e) {
						error.set(e);
						latch.countDown();
					}

					@SuppressWarnings("unused")
					@Override
					public void onNext(Integer t) {
						count++;
						list.add(t);
						if (count != t) {
							System.out.println(list);
							onError(new RuntimeException("count=" + count + " but t=" + t));
							System.exit(1);
						}
						if (count == unsubscribeAfter) {
							unsubscribe();
							if (false)
								System.out.println(Thread.currentThread().getName() + "|called unsubscribe");
							last.set(count);
							latch.countDown();
						}
					}
				};
				Observable.range(1, max)
						//
						.subscribeOn(scheduler2)
						//
						.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler, options))
						.subscribe(subscriber);

				if (!latch.await(1000, TimeUnit.SECONDS)) {
					System.out.println("cycle=" + count + ", list.size= " + list.size() + "\n" + list);
					if (error.get() != null) {
						throw error.get();
					} else {
						Assert.fail();
					}
				}
				if (error.get() != null)
					Assert.fail(error.get().getMessage());

				if (list.size() < unsubscribeAfter) {
					System.out.println("cycle=" + count);
					List<Integer> expected = new ArrayList<Integer>();
					for (int i = 1; i <= unsubscribeAfter; i++) {
						expected.add(i);
					}
					System.out.println("expected=" + expected);
					System.out.println("actual  =" + list);
				}
				assertTrue(list.size() >= unsubscribeAfter);

				count++;
			} finally {
				waitUntilWorkCompleted(scheduler);
				waitUntilWorkCompleted(scheduler2);
			}
		}
		System.out.println(count + " cycles passed");
	}

	private static Scheduler createNamedSingleThreadScheduler(final String name) {
		return Schedulers.from(Executors.newFixedThreadPool(1, new ThreadFactory() {

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r);
				t.setName(name);
				return t;
			}
		}));
	}

	@Test
	public void checkRateForSmallMessagesNoRollover() {
		checkRateForSmallMessagesWithOptions(Options.disableRollover().build());
	}

	@Test
	public void checkRateForSmallMessagesRollover() {
		checkRateForSmallMessagesWithOptions(Options.rolloverSizeBytes(Long.MAX_VALUE - 1).build());
	}

	private static String df(double d) {
		return new DecimalFormat("0.0").format(d);
	}

	private static void checkRateForSmallMessagesWithOptions(Options options) {
		Scheduler scheduler = createSingleThreadScheduler();
		DataSerializer<Integer> serializer = DataSerializers.integer();

		int max = Integer.parseInt(System.getProperty("max.small", "300000"));
		long t = System.currentTimeMillis();
		int last = Observable.range(1, max)
				//
				.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler, options))
				// log
				// .lift(Logging.<Integer>
				// logger().showCount().every(1000).showMemory().log())
				.last().toBlocking().single();
		t = System.currentTimeMillis() - t;
		assertEquals(max, last);
		System.out.println(
				"rate = " + df((double) max * 4 / (t) / 1000) + "MB/s (4B messages, " + rolloverStatus(options) + ")");
		waitUntilWorkCompleted(scheduler);
	}

	private static String rolloverStatus(Options options) {
		return options.rolloverEnabled() ? "rollover" : "no rollover";
	}

	@Test
	public void checkRateForOneKMessagesNoRollover() {
		checkRateForOneKMessagesWithOptions(Options.disableRollover().build());
	}

	@Test
	public void checkRateForOneKMessagesRollover() {
		checkRateForOneKMessagesWithOptions(Options.rolloverSizeBytes(Long.MAX_VALUE - 1).build());
	}

	private static void checkRateForOneKMessagesWithOptions(Options options) {
		Scheduler scheduler = createSingleThreadScheduler();
		DataSerializer<Integer> serializer = createSerializer1K();

		int max = Integer.parseInt(System.getProperty("max.medium", "3000"));
		long t = System.currentTimeMillis();
		int last = Observable.range(1, max)
				//
				.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler, options))
				// log
				// .lift(Logging.<Integer>
				// logger().showCount().every(1000).showMemory().log())
				.last().toBlocking().single();
		t = System.currentTimeMillis() - t;
		assertEquals(max, last);
		System.out.println("rate = " + df((double) max * MEDIUM_MESSAGE_SIZE / 1000 / (t)) + "MB/s (1K messages, "
				+ rolloverStatus(options) + ")");
		waitUntilWorkCompleted(scheduler);
	}

	private static final int MEDIUM_MESSAGE_SIZE = 2 << 10;

	private static DataSerializer<Integer> createSerializer1K() {
		return new DataSerializer<Integer>() {

			private final byte[] message = new byte[MEDIUM_MESSAGE_SIZE - 4];

			@Override
			public void serialize(DataOutput output, Integer value) throws IOException {
				output.write(message);
				output.writeInt(value);
			}

			@Override
			public Integer deserialize(DataInput input, int availableBytes) throws IOException {
				input.readFully(message);
				return input.readInt();
			}
		};
	}

	@Test
	public void checkRateForOneKMessagesNoReadNoRollover() {
		checkRateForOneKMessagesNoReadWithOptions(Options.disableRollover().build());
	}

	@Test
	public void checkRateForOneKMessagesNoReadRollover() {
		checkRateForOneKMessagesNoReadWithOptions(Options.rolloverSizeBytes(Long.MAX_VALUE - 1).build());
	}

	private static void checkRateForOneKMessagesNoReadWithOptions(Options options) {
		Scheduler scheduler = createSingleThreadScheduler();
		DataSerializer<Integer> serializer = createSerializer1K();

		int max = Integer.parseInt(System.getProperty("max.medium", "3000"));
		long t = System.currentTimeMillis();
		final Lock lock = new ReentrantLock();
		lock.lock();
		int first = Observable.range(1, max)
				//
				.doOnCompleted(new Action0() {
					@Override
					public void call() {
						lock.unlock();
					}
				})
				//
				.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler, options))
				//
				.doOnNext(new Action1<Integer>() {
					@Override
					public void call(Integer n) {
						lock.lock();
					}
				}).first().toBlocking().single();
		t = System.currentTimeMillis() - t;
		assertEquals(1, first);
		System.out.println(
				"rate = " + df((double) max / (t)) + "MB/s (1K messages, " + rolloverStatus(options) + ", write only)");
		waitUntilWorkCompleted(scheduler);
	}

	@Test
	public void testCompletionDeletesAllFilesUsingRolloverOnSize() {
		Scheduler scheduler = createSingleThreadScheduler();
		DataSerializer<Integer> serializer = DataSerializers.integer();

		int max = Integer.parseInt(System.getProperty("max.small", "300000"));
		long t = System.currentTimeMillis();
		final Func0<File> defaultFileFactory = Options.defaultInstance().fileFactory();
		final Queue<File> q = new ConcurrentLinkedQueue<File>();
		Func0<File> fileFactory = new Func0<File>() {
			@Override
			public File call() {
				File file = defaultFileFactory.call();
				q.add(file);
				return file;
			}
		};
		int last = Observable.range(1, max)
				//
				.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler,
						Options.rolloverSizeBytes(10000).fileFactory(fileFactory).build()))
				.last().toBlocking().single();
		t = System.currentTimeMillis() - t;
		assertEquals(max, last);
		waitUntilWorkCompleted(scheduler);
		for (File f : q) {
			assertFalse("file should not exist " + f, f.exists());
		}
	}

	@Test
	public void testForReadMe() {
		Scheduler scheduler = createSingleThreadScheduler();
		DataSerializer<String> serializer = new DataSerializer<String>() {

			@Override
			public void serialize(DataOutput output, String s) throws IOException {
				output.writeUTF(s);
			}

			@Override
			public String deserialize(DataInput input, int availableBytes) throws IOException {
				return input.readUTF();
			}
		};
		List<String> list = Observable.just("a", "b", "c")
				.compose(Transformers.onBackpressureBufferToFile(serializer, scheduler)).toList().toBlocking().single();
		assertEquals(Arrays.asList("a", "b", "c"), list);
		waitUntilWorkCompleted(scheduler);
	}

	private DataSerializer<Integer> createLargeMessageSerializer() {
		DataSerializer<Integer> serializer = new DataSerializer<Integer>() {

			final static int dummyArraySize = 1000000;// 1MB
			final static int chunkSize = 1000;

			@Override
			public void serialize(DataOutput output, Integer n) throws IOException {
				output.writeInt(n);
				// write some filler
				int toWrite = dummyArraySize;
				while (toWrite > 0) {
					if (toWrite >= chunkSize) {
						output.write(new byte[chunkSize]);
						toWrite -= chunkSize;
					} else {
						output.write(new byte[toWrite]);
						toWrite = 0;
					}
				}
			}

			@Override
			public Integer deserialize(DataInput input, int availableBytes) throws IOException {
				int value = input.readInt();
				// read the filler
				int bytesRead = 0;
				while (bytesRead < dummyArraySize) {
					if (dummyArraySize - bytesRead >= chunkSize) {
						input.readFully(new byte[chunkSize]);
						bytesRead += chunkSize;
					} else {
						input.readFully(new byte[dummyArraySize - bytesRead]);
						bytesRead = dummyArraySize;
					}
				}
				return value;
			}
		};
		return serializer;
	}

	@Test
	public void serializesListsUsingJavaIO() {
		Scheduler scheduler = createSingleThreadScheduler();
		List<Integer> list = Observable.just(1, 2, 3, 4).buffer(2).compose(Transformers
				.<List<Integer>> onBackpressureBufferToFile(DataSerializers.<List<Integer>> javaIO(), scheduler)).last()
				.toBlocking().single();
		assertEquals(Arrays.asList(3, 4), list);
		waitUntilWorkCompleted(scheduler);
	}

	private static Scheduler createSingleThreadScheduler() {
		return Schedulers.from(Executors.newSingleThreadExecutor());
	}

	public static void main(String[] args) throws InterruptedException {
		final CountDownLatch latch = new CountDownLatch(1);
		Observable.range(1, Integer.MAX_VALUE)
				//
				.compose(Transformers.onBackpressureBufferToFile(DataSerializers.integer(), Schedulers.computation(),
						Options.rolloverSizeBytes(100000000).build()))
				//
				// .lift(Logging.<Integer>
				// logger().showCount().every(1000000).showMemory().log())
				//
				// .delay(200, TimeUnit.MILLISECONDS, Schedulers.immediate())
				//
				.subscribe(new Subscriber<Integer>() {
					int count = 0;

					@Override
					public void onCompleted() {
						// TODO Auto-generated method stub

					}

					@Override
					public void onError(Throwable e) {
						latch.countDown();
					}

					@Override
					public void onNext(Integer t) {
						count++;
						if (t != count) {
							System.out.println(t + " != " + count);
							latch.countDown();
						}
					}
				});
		latch.await();
	}

}
