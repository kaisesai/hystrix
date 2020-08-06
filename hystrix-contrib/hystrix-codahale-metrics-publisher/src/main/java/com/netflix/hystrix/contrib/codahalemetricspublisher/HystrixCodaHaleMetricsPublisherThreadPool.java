/**
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.contrib.codahalemetricspublisher;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.netflix.hystrix.HystrixThreadPoolKey;
import com.netflix.hystrix.HystrixThreadPoolMetrics;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisherThreadPool;
import com.netflix.hystrix.util.HystrixRollingNumberEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link HystrixMetricsPublisherThreadPool} using Coda Hale Metrics (https://github.com/codahale/metrics)
 */
public class HystrixCodaHaleMetricsPublisherThreadPool implements HystrixMetricsPublisherThreadPool {
    private final String metricsRootNode;
    private final HystrixThreadPoolKey key;
    private final HystrixThreadPoolMetrics metrics;
    private final HystrixThreadPoolProperties properties;
    private final MetricRegistry metricRegistry;
    private final String metricGroup;
    private final String metricType;

    static final Logger logger = LoggerFactory.getLogger(HystrixCodaHaleMetricsPublisherThreadPool.class);

    public HystrixCodaHaleMetricsPublisherThreadPool(String metricsRootNode, HystrixThreadPoolKey threadPoolKey, HystrixThreadPoolMetrics metrics, HystrixThreadPoolProperties properties, MetricRegistry metricRegistry) {
        this.metricsRootNode = metricsRootNode;
        this.key = threadPoolKey;
        this.metrics = metrics;
        this.properties = properties;
        this.metricRegistry = metricRegistry;
        this.metricGroup = "HystrixThreadPool";
        this.metricType = key.name();
    }

    /**
     * An implementation note.  If there's a version mismatch between hystrix-core and hystrix-codahale-metrics-publisher,
     * the code below may reference a HystrixRollingNumberEvent that does not exist in hystrix-core.  If this happens,
     * a j.l.NoSuchFieldError occurs.  Since this data is not being generated by hystrix-core, it's safe to count it as 0
     * and we should log an error to get users to update their dependency set.
     */
    @Override
    public void initialize() {
        metricRegistry.register(createMetricName("name"), new Gauge<String>() {
            @Override
            public String getValue() {
                return key.name();
            }
        });

        // allow monitor to know exactly at what point in time these stats are for so they can be plotted accurately
        metricRegistry.register(createMetricName("currentTime"), new Gauge<Long>() {
            @Override
            public Long getValue() {
                return System.currentTimeMillis();
            }
        });

        metricRegistry.register(createMetricName("threadActiveCount"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return metrics.getCurrentActiveCount();
            }
        });

        metricRegistry.register(createMetricName("completedTaskCount"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return metrics.getCurrentCompletedTaskCount();
            }
        });

        metricRegistry.register(createMetricName("largestPoolSize"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return metrics.getCurrentLargestPoolSize();
            }
        });

        metricRegistry.register(createMetricName("totalTaskCount"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return metrics.getCurrentTaskCount();
            }
        });

        metricRegistry.register(createMetricName("queueSize"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return metrics.getCurrentQueueSize();
            }
        });

        metricRegistry.register(createMetricName("rollingMaxActiveThreads"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return metrics.getRollingMaxActiveThreads();
            }
        });

        metricRegistry.register(createMetricName("countThreadsExecuted"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return metrics.getCumulativeCountThreadsExecuted();
            }
        });

        metricRegistry.register(createMetricName("rollingCountCommandsRejected"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                try {
                    return metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED);
                } catch (NoSuchFieldError error) {
                    logger.error("While publishing CodaHale metrics, error looking up eventType for : rollingCountCommandsRejected.  Please check that all Hystrix versions are the same!");
                    return 0L;
                }
            }
        });

        metricRegistry.register(createMetricName("rollingCountThreadsExecuted"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return metrics.getRollingCountThreadsExecuted();
            }
        });

        // properties
        metricRegistry.register(createMetricName("propertyValue_corePoolSize"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return properties.coreSize().get();
            }
        });

        metricRegistry.register(createMetricName("propertyValue_maximumSize"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return properties.maximumSize().get();
            }
        });

        // 配置监控信息
        metricRegistry.register(createMetricName("propertyValue_actualMaximumSize"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return properties.actualMaximumSize();
            }
        });

        metricRegistry.register(createMetricName("propertyValue_keepAliveTimeInMinutes"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return properties.keepAliveTimeMinutes().get();
            }
        });

        metricRegistry.register(createMetricName("propertyValue_queueSizeRejectionThreshold"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return properties.queueSizeRejectionThreshold().get();
            }
        });

        metricRegistry.register(createMetricName("propertyValue_maxQueueSize"), new Gauge<Number>() {
            @Override
            public Number getValue() {
                return properties.maxQueueSize().get();
            }
        });
    }

    protected String createMetricName(String name) {
        return MetricRegistry.name(metricsRootNode, metricGroup, metricType, name);
    }
}
