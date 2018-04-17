/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks;

import com.google.common.collect.Maps;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.IoActions;
import org.gradle.util.CollectionUtils;

import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TaskStatistics implements Closeable {
    private final static Logger LOGGER = Logging.getLogger(TaskStatistics.class);
    private final static String TASK_STATISTICS_PROPERTY = "org.gradle.internal.tasks.stats";

    private final AtomicInteger eagerTasks = new AtomicInteger();
    private final AtomicInteger lazyTasks = new AtomicInteger();
    private final AtomicInteger lazyRealizedTasks = new AtomicInteger();
    private final Map<Class, Integer> typeCounts = Maps.newHashMap();
    private final Map<Class, Integer> realizedTypeCounts = Maps.newHashMap();
    private final boolean collectStatistics;
    private final Bucket elapsedConfigurationTime;
    private final Bucket elapsedCreationTime;

    private PrintWriter lazyTaskLog;

    public TaskStatistics() {
        String taskStatistics = System.getProperty(TASK_STATISTICS_PROPERTY);
        if (taskStatistics!=null) {
            collectStatistics = true;
            if (!taskStatistics.isEmpty()) {
                try {
                    lazyTaskLog = new PrintWriter(new FileWriter(taskStatistics));
                } catch (IOException e) {
                    // don't care
                }
            }
        } else {
            collectStatistics = false;
        }
        elapsedConfigurationTime = new Bucket("total configuration time");
        elapsedCreationTime = new Bucket("total creation time");
    }

    public void eagerTask(Class<?> type) {
        if (collectStatistics) {
            eagerTasks.incrementAndGet();
            synchronized (typeCounts) {
                Integer count = typeCounts.get(type);
                if (count == null) {
                    count = 1;
                } else {
                    count = count + 1;
                }
                typeCounts.put(type, count);
            }
        }
    }

    public void lazyTask() {
        if (collectStatistics) {
            lazyTasks.incrementAndGet();
        }
    }

    public void lazyTaskRealized(Class<?> type) {
        if (collectStatistics) {
            lazyRealizedTasks.incrementAndGet();
            synchronized (realizedTypeCounts) {
                Integer count = realizedTypeCounts.get(type);
                if (count == null) {
                    count = 1;
                } else {
                    count = count + 1;
                }
                realizedTypeCounts.put(type, count);
            }
            if (lazyTaskLog != null) {
                new Throwable().printStackTrace(lazyTaskLog);
            }
        }
    }

    public Bucket getElapsedConfigurationTimeBucket() {
        return elapsedConfigurationTime;
    }

    public Bucket getElapsedCreationTimeBucket() {
        return elapsedCreationTime;
    }

    @Override
    public void close() throws IOException {
        if (collectStatistics) {
            LOGGER.lifecycle("E {} L {} LR {}, config {} ms, create {} ms", eagerTasks.getAndSet(0), lazyTasks.getAndSet(0), lazyRealizedTasks.getAndSet(0),
                TimeUnit.NANOSECONDS.toMillis(elapsedConfigurationTime.getMeasurement()),
                TimeUnit.NANOSECONDS.toMillis(elapsedCreationTime.getMeasurement()));
            printTypeCounts("Task types that were eagerly created", typeCounts);
            printTypeCounts("Lazy task types that were realized", realizedTypeCounts);
            IoActions.closeQuietly(lazyTaskLog);
        }
    }

    private void printTypeCounts(String header, Map<Class, Integer> typeCounts) {
        if (!typeCounts.isEmpty()) {
            LOGGER.lifecycle(header);
            List<Map.Entry<Class, Integer>> sorted = CollectionUtils.sort(typeCounts.entrySet(), new Comparator<Map.Entry<Class, Integer>>() {
                @Override
                public int compare(Map.Entry<Class, Integer> a, Map.Entry<Class, Integer> b) {
                    return b.getValue().compareTo(a.getValue());
                }
            });
            for (Map.Entry<Class, Integer> typeCount : sorted) {
                LOGGER.lifecycle(typeCount.getKey() + " " + typeCount.getValue());
            }
        }
    }
}
