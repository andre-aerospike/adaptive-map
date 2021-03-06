package com.aerospike.storage.adaptive;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Value;
import com.aerospike.client.cdt.MapOrder;
import com.aerospike.client.cdt.MapPolicy;
import com.aerospike.client.policy.WritePolicy;

public class PerformanceTest {
	
	public static final String HOST = "127.0.0.1";
	public static final String NAMESPACE = "test";
	public static final String SET = "perfTest";
	public static final String MAP_BIN = "map";
	public static final int THREADS = 60;
	public static final int NUM_KEYS = 1000;
	public static final int TOTAL_KEYS = 1_000_000;
	public static final int BLOCK_SPLIT_SIZE = 100;
	
	private AtomicLong counter = new AtomicLong();
	private AtomicLong errorCounter = new AtomicLong();
	private AtomicLong keyCounter = new AtomicLong();
	private AtomicLongArray lastWriteTime = new AtomicLongArray(THREADS);
	private volatile boolean terminate = false;
	
	public String getKey(long id) {
		String numberKey = "0000000000" + id; 
		return "key" + (numberKey.substring(numberKey.length()-10));
	}

	public ExecutorService executors = Executors.newFixedThreadPool(THREADS);
	
	public class Runner implements Runnable {
		private final AdaptiveMap map;
		private final int id;
		private final Random random;
		
		public Runner(AdaptiveMap map, int id) {
			this.map = map;
			this.id = id;
			this.random = new Random(id);
		}
		
		@Override
		public void run() {
			WritePolicy writePolicy = new WritePolicy();
			writePolicy.maxRetries = 5;
			writePolicy.sleepBetweenRetries = 200;
			writePolicy.totalTimeout = 5000;
			while (!terminate) {
				int key = random.nextInt(NUM_KEYS);
				//int mapKey = random.nextInt();
				long mapKey = keyCounter.incrementAndGet();
				try {
					map.put(writePolicy, "Key-" + key, getKey(mapKey), null, Value.get(counter.get()));
					lastWriteTime.set(id, System.nanoTime());
					counter.incrementAndGet();
				}
				catch (Exception e) {
					errorCounter.incrementAndGet();
					lastWriteTime.set(id, System.nanoTime());
					e.printStackTrace();
				}
			}
		}
	}
	
	/**
	 * Get the health of the threads. If a thread hasn't processed a transaction in more than 1 second, it is flagged 
	 * as a problem thread.
	 * @param now
	 * @return
	 */
	public String getThreadHealth(long now) {
		StringBuffer buffer = new StringBuffer(THREADS);
		for (int i = 0; i < THREADS; i++) {
			if (now - lastWriteTime.get(i) > 1_000_000_000) {
				buffer.append('X');
			}
			else {
				buffer.append('.');
			}
		}
		return buffer.toString();
	}
	
	public void begin() throws Exception {
		IAerospikeClient client = new AerospikeClient(null, HOST, 3000);
		AdaptiveMap map = new AdaptiveMap(client, NAMESPACE, SET, MAP_BIN, new MapPolicy(MapOrder.KEY_ORDERED, 0), false, BLOCK_SPLIT_SIZE);
		
//		client.truncate(null, NAMESPACE, SET, null);
		
		for (int i = 0; i < THREADS; i++) {
			executors.submit(new Runner(map, i));
		}
		executors.shutdown();
		long start = System.nanoTime();
		while (true) {
			Thread.sleep(1000);
			long now = System.nanoTime();
			System.out.printf("%,9dms: Success: %,9d  Failed: %,9d  Thread Health: %s\n", (now-start)/1_000_000, counter.get(), errorCounter.get(), getThreadHealth(now));
			if (counter.get() >= TOTAL_KEYS) {
				terminate = true;
				executors.awaitTermination(1, TimeUnit.DAYS);
				break;
			}
		}
		Thread.sleep(2000);
		System.out.printf("Done: Success: %,9d  Failed: %,9d\n", counter.get(), errorCounter.get());
		client.close();
	}
	
	public static void main(String[] args) throws Exception {
		new PerformanceTest().begin();
	}
}
