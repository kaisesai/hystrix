/**
 * Copyright 2012 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import com.netflix.hystrix.HystrixCommandMetrics.HealthCounts;
import rx.Subscriber;
import rx.Subscription;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 断路器
 * <p>
 * Circuit-breaker logic that is hooked into {@link HystrixCommand} execution and will stop allowing executions if failures have gone past the defined threshold.
 * <p>
 * The default (and only) implementation  will then allow a single retry after a defined sleepWindow until the execution
 * succeeds at which point it will again close the circuit and allow executions again.
 */
public interface HystrixCircuitBreaker {

  /**
   * 是否允许请求通过
   * <p>
   * Every {@link HystrixCommand} requests asks this if it is allowed to proceed or not.  It is idempotent and does
   * not modify any internal state, and takes into account the half-open logic which allows some requests through
   * after the circuit has been opened
   *
   * @return boolean whether a request should be permitted
   */
  boolean allowRequest();

  /**
   * Whether the circuit is currently open (tripped).
   *
   * @return boolean state of circuit breaker
   */
  boolean isOpen();

  /**
   * Invoked on successful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
   */
  void markSuccess();

  /**
   * Invoked on unsuccessful executions from {@link HystrixCommand} as part of feedback mechanism when in a half-open state.
   */
  void markNonSuccess();

  /**
   * Invoked at start of command execution to attempt an execution.  This is non-idempotent - it may modify internal
   * state.
   */
  boolean attemptExecution();

  /**
   * @ExcludeFromJavadoc
   * @ThreadSafe
   */
  class Factory {

    // 断路器缓存，通过命令 key 来关联
    // String is HystrixCommandKey.name() (we can't use HystrixCommandKey directly as we can't guarantee it implements hashcode/equals correctly)
    private static ConcurrentHashMap<String, HystrixCircuitBreaker> circuitBreakersByCommand
      = new ConcurrentHashMap<String, HystrixCircuitBreaker>();

    /**
     * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey}.
     * <p>
     * This is thread-safe and ensures only 1 {@link HystrixCircuitBreaker} per {@link HystrixCommandKey}.
     *
     * @param key        {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
     * @param group      Pass-thru to {@link HystrixCircuitBreaker}
     * @param properties Pass-thru to {@link HystrixCircuitBreaker}
     * @param metrics    Pass-thru to {@link HystrixCircuitBreaker}
     * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
     */
    public static HystrixCircuitBreaker getInstance(HystrixCommandKey key,
                                                    HystrixCommandGroupKey group,
                                                    HystrixCommandProperties properties,
                                                    HystrixCommandMetrics metrics) {
      // 从缓存中获取断路器
      // this should find it for all but the first time
      HystrixCircuitBreaker previouslyCached = circuitBreakersByCommand.get(key.name());
      if (previouslyCached != null) {
        return previouslyCached;
      }

      // if we get here this is the first time so we need to initialize

      // Create and add to the map ... use putIfAbsent to atomically handle the possible race-condition of
      // 2 threads hitting this point at the same time and let ConcurrentHashMap provide us our thread-safety
      // If 2 threads hit here only one will get added and the other will get a non-null response instead.

      // 这里创建断路器，并且通过命令 key 来关联
      HystrixCircuitBreaker cbForCommand = circuitBreakersByCommand
        .putIfAbsent(key.name(), new HystrixCircuitBreakerImpl(key, group, properties, metrics));
      if (cbForCommand == null) {
        // this means the putIfAbsent step just created a new one so let's retrieve and return it
        return circuitBreakersByCommand.get(key.name());
      } else {
        // this means a race occurred and while attempting to 'put' another one got there before
        // and we instead retrieved it and will now return it
        return cbForCommand;
      }
    }

    /**
     * Get the {@link HystrixCircuitBreaker} instance for a given {@link HystrixCommandKey} or null if none exists.
     *
     * @param key {@link HystrixCommandKey} of {@link HystrixCommand} instance requesting the {@link HystrixCircuitBreaker}
     * @return {@link HystrixCircuitBreaker} for {@link HystrixCommandKey}
     */
    public static HystrixCircuitBreaker getInstance(HystrixCommandKey key) {
      return circuitBreakersByCommand.get(key.name());
    }

    /**
     * Clears all circuit breakers. If new requests come in instances will be recreated.
     */
    /* package */
    static void reset() {
      circuitBreakersByCommand.clear();
    }
  }

  /**
   * The default production implementation of {@link HystrixCircuitBreaker}.
   *
   * @ExcludeFromJavadoc
   * @ThreadSafe
   */
  /* package */class HystrixCircuitBreakerImpl implements HystrixCircuitBreaker {

    private final HystrixCommandProperties properties;

    private final HystrixCommandMetrics metrics;

    enum Status {
      CLOSED,// 关闭状态
      OPEN, // 开启状态
      HALF_OPEN;// 半开半闭状态
    }

    // 使用原子引用类
    private final AtomicReference<Status> status = new AtomicReference<Status>(Status.CLOSED);

    // 断路器打开是的时间戳
    private final AtomicLong circuitOpened = new AtomicLong(-1);

    private final AtomicReference<Subscription> activeSubscription
      = new AtomicReference<Subscription>(null);

    protected HystrixCircuitBreakerImpl(HystrixCommandKey key, HystrixCommandGroupKey commandGroup,
                                        final HystrixCommandProperties properties,
                                        HystrixCommandMetrics metrics) {
      this.properties = properties;
      this.metrics = metrics;

      //On a timer, this will set the circuit between OPEN/CLOSED as command executions occur
      Subscription s = subscribeToStream();
      activeSubscription.set(s);
    }


    private Subscription subscribeToStream() {
      /*
       * 订阅一个流，用于统计器心跳检测时，获取监听器，添加一个订阅者（观察者），这个歌观察者就是断路器
       * This stream will recalculate the OPEN/CLOSED status on every onNext from the health stream
       */
      return metrics.getHealthCountsStream().observe().subscribe(new Subscriber<HealthCounts>() {
        @Override
        public void onCompleted() {

        }

        @Override
        public void onError(Throwable e) {

        }

        @Override
        public void onNext(HealthCounts hc) {
          // check if we are past the statisticalWindowVolumeThreshold
          if (hc.getTotalRequests() < properties.circuitBreakerRequestVolumeThreshold().get()) {
            // 心跳检查总的请求数量，小于配置属性中的请求流量阈值
            // we are not past the minimum volume threshold for the stat window,
            // so no change to circuit status.
            // if it was CLOSED, it stays CLOSED
            // if it was half-open, we need to wait for a successful command execution
            // if it was open, we need to wait for sleep window to elapse
          } else {
            if (hc.getErrorPercentage() < properties.circuitBreakerErrorThresholdPercentage()
                                                    .get()) {
              // 心跳检查中的请求错误率，小于配中的请求异常占比阈值
              //we are not past the minimum error threshold for the stat window,
              // so no change to circuit status.
              // if it was CLOSED, it stays CLOSED
              // if it was half-open, we need to wait for a successful command execution
              // if it was open, we need to wait for sleep window to elapse
            } else {
              // 其他情况下，将断路器的状态设置为打开状态
              // 注意，这里是的断路器数据时保存在 static 的 concurrentHashMap 中的， 由多个线程共享，所以需要使用原子引用类的 CAS 方法更新状态，
              // 所以可能会出现失败的情况，那么只需要考虑成功的情况即可。

              // our failure rate is too high, we need to set the state to OPEN
              if (status.compareAndSet(Status.CLOSED, Status.OPEN)) {
                // 记录断路器状态变为开启时的时间
                circuitOpened.set(System.currentTimeMillis());
              }
            }
          }
        }
      });
    }

    @Override
    public void markSuccess() {
      // 从半开启状态设置为关闭状态
      if (status.compareAndSet(Status.HALF_OPEN, Status.CLOSED)) {
        //This thread wins the race to close the circuit - it resets the stream to start it over from 0
        metrics.resetStream();
        Subscription previousSubscription = activeSubscription.get();
        if (previousSubscription != null) {
          previousSubscription.unsubscribe();
        }
        // 这里为什么要再次创建一个订阅者呢？在断路器对象被创建时，就已经创建了一个订阅者呀？！
        Subscription newSubscription = subscribeToStream();
        activeSubscription.set(newSubscription);
        circuitOpened.set(-1L);
      }
    }

    @Override
    public void markNonSuccess() {
      // 从半开启状态设置为开启状态
      if (status.compareAndSet(Status.HALF_OPEN, Status.OPEN)) {
        //This thread wins the race to re-open the circuit - it resets the start time for the sleep window
        circuitOpened.set(System.currentTimeMillis());
      }
    }

    @Override
    public boolean isOpen() {
      // 断路器是否强制打开配置
      if (properties.circuitBreakerForceOpen().get()) {
        return true;
      }
      // 断路器是否强制关闭
      if (properties.circuitBreakerForceClosed().get()) {
        return false;
      }
      // 断路器被打开的时间戳，大于 0
      return circuitOpened.get() >= 0;
    }

    @Override
    public boolean allowRequest() {
      // 断路器是否强制打开配置
      if (properties.circuitBreakerForceOpen().get()) {
        return false;
      }
      // 断路器是否强制关闭
      if (properties.circuitBreakerForceClosed().get()) {
        return true;
      }
      // 断路器被打开的时间戳，大于 0
      if (circuitOpened.get() == -1) {
        return true;
      } else {
        // 断路器状态是否为半开启状态
        if (status.get().equals(Status.HALF_OPEN)) {
          return false;
        } else {
          // 是否在睡眠窗口之后
          return isAfterSleepWindow();
        }
      }
    }

    // 是否在睡眠窗口之后
    private boolean isAfterSleepWindow() {
      // 断路器打开是的时间戳
      final long circuitOpenTime = circuitOpened.get();
      // 当前时间戳
      final long currentTime = System.currentTimeMillis();
      // 断路器睡眠窗口时间
      final long sleepWindowTime = properties.circuitBreakerSleepWindowInMilliseconds().get();
      // 当前时间 > 断路器打开是的时间戳 + 断路器睡眠窗口时间
      return currentTime > circuitOpenTime + sleepWindowTime;
    }

    // 尝试执行
    @Override
    public boolean attemptExecution() {
      // 断路器是否强制打开配置
      if (properties.circuitBreakerForceOpen().get()) {
        return false;
      }
      // 断路器是否强制关闭
      if (properties.circuitBreakerForceClosed().get()) {
        return true;
      }
      // 断路器被打开的时间戳，小于 0，允许执行
      if (circuitOpened.get() == -1) {
        return true;
      } else {
        // 断路器窗口时间之后
        if (isAfterSleepWindow()) {
          //only the first request after sleep window should execute
          //if the executing command succeeds, the status will transition to CLOSED
          //if the executing command fails, the status will transition to OPEN
          //if the executing command gets unsubscribed, the status will transition to OPEN
          // 将断路器由开启状态，设置为半开启状态
          if (status.compareAndSet(Status.OPEN, Status.HALF_OPEN)) {
            return true;
          } else {
            return false;
          }
        } else {
          // 断路器窗口时间之内
          return false;
        }
      }
    }
  }

  /**
   * An implementation of the circuit breaker that does nothing.
   *
   * @ExcludeFromJavadoc
   */
  /* package */static class NoOpCircuitBreaker implements HystrixCircuitBreaker {

    @Override
    public boolean allowRequest() {
      return true;
    }

    @Override
    public boolean isOpen() {
      return false;
    }

    @Override
    public void markSuccess() {

    }

    @Override
    public void markNonSuccess() {

    }

    @Override
    public boolean attemptExecution() {
      return true;
    }
  }

}
