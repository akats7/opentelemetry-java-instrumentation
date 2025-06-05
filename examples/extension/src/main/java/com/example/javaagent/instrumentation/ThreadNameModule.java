/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.javaagent.runtimemetrics.java8;

import static java.util.Objects.requireNonNull;

import com.google.auto.service.AutoService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.instrumentation.api.internal.EmbeddedInstrumentationProperties;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.semconv.JvmAttributes;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** An {@link AgentListener} that enables runtime metrics during agent startup. */
@AutoService(AgentListener.class)
public class ThreadNameModule implements AgentListener {

  @Nullable private static final MethodHandle THREAD_INFO_IS_DAEMON;

  static {
    MethodHandle isDaemon;
    try {
      isDaemon =
          MethodHandles.publicLookup()
              .findVirtual(ThreadInfo.class, "isDaemon", MethodType.methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      isDaemon = null;
    }
    THREAD_INFO_IS_DAEMON = isDaemon;
  }

  private static final String INSTRUMENTATION_NAME = "io.opentelemetry.runtime-telemetry-java8";

  private static final AttributeKey<String> THREAD_NAME = AttributeKey.stringKey("jvm.thread.name");
  private static final AttributeKey<String> CPU_MODE = AttributeKey.stringKey("cpu.mode");
  private static final String USER_MODE = "user";
  private static final String SYSTEM_MODE = "system";

  @Nullable
  private static final String INSTRUMENTATION_VERSION =
      EmbeddedInstrumentationProperties.findVersion(INSTRUMENTATION_NAME);

  @Override
  public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {

    OpenTelemetry globalOpenTelemetry = GlobalOpenTelemetry.get();
    MeterBuilder meterBuilder = globalOpenTelemetry.meterBuilder(INSTRUMENTATION_NAME);
    if (INSTRUMENTATION_VERSION != null) {
      meterBuilder.setInstrumentationVersion(INSTRUMENTATION_VERSION);
    }
    Meter meter = meterBuilder.build();

    System.out.println("Hello in AutoService");

    List<AutoCloseable> observables = new ArrayList<>();

    ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();

    observables.add(
        meter
            .upDownCounterBuilder("jvm.thread.count")
            .setDescription("Number of executing platform threads.")
            .setUnit("{thread}")
            .buildWithCallback(java9AndNewerCallback(threadMxBean)));

    observables.add(
        meter
            .upDownCounterBuilder("jvm.thread.cpu_time")
            .setDescription("the amount of CPU time that the specified thread has used")
            .setUnit("{ns}")
            .buildWithCallback(threadCpuTimeCallback(threadMxBean)));
  }

  private static Consumer<ObservableLongMeasurement> threadCpuTimeCallback(
      ThreadMXBean threadBean) {
    return measurement -> {
      Map<Attributes, Long> times = new HashMap<>();

      for (ThreadInfo threadInfo : threadBean.getThreadInfo(threadBean.getAllThreadIds(), 0)) {
        long threadId = threadInfo.getThreadId();
        String threadName = escapeThreadName(threadInfo.getThreadName());
        long totalCpuTime = threadBean.getThreadCpuTime(threadId);
        long userCpuTime = threadBean.getThreadUserTime(threadId);
        long systemCpuTime = totalCpuTime - userCpuTime;
        Attributes threadAttributesUser =
            threadAttributesWithMode(threadInfo, threadName, USER_MODE);
        Attributes threadAttributesSystem =
            threadAttributesWithMode(threadInfo, threadName, SYSTEM_MODE);
        if (totalCpuTime > -1 && userCpuTime > -1) {
          times.compute(
              threadAttributesUser,
              (attributes, value) -> value == null ? userCpuTime : userCpuTime + value);

          times.compute(
              threadAttributesSystem,
              (attributes, value) -> value == null ? systemCpuTime : systemCpuTime + value);
        }
      }
      times.forEach((threadAttributes, time) -> measurement.record(time, threadAttributes));
    };
  }

  private static Consumer<ObservableLongMeasurement> java9AndNewerCallback(
      ThreadMXBean threadBean) {
    return measurement -> {
      Map<Attributes, Long> counts = new HashMap<>();
      long[] threadIds = threadBean.getAllThreadIds();
      for (ThreadInfo threadInfo : threadBean.getThreadInfo(threadIds)) {
        if (threadInfo == null) {
          continue;
        }
        String threadName = escapeThreadName(threadInfo.getThreadName());
        Attributes threadAttributes = threadAttributesWithState(threadInfo, threadName);
        counts.compute(threadAttributes, (k, value) -> value == null ? 1 : value + 1);
      }
      counts.forEach((threadAttributes, count) -> measurement.record(count, threadAttributes));
    };
  }

  private static Attributes threadAttributesWithMode(
      ThreadInfo threadInfo, String threadName, String cpuMode) {
    boolean isDaemon;
    try {
      isDaemon = (boolean) requireNonNull(THREAD_INFO_IS_DAEMON).invoke(threadInfo);
    } catch (Throwable e) {
      throw new IllegalStateException("Unexpected error happened during ThreadInfo#isDaemon()", e);
    }
    return Attributes.of(
        JvmAttributes.JVM_THREAD_DAEMON, isDaemon, THREAD_NAME, threadName, CPU_MODE, cpuMode);
  }

  private static Attributes threadAttributesWithState(ThreadInfo threadInfo, String threadName) {
    boolean isDaemon;
    try {
      isDaemon = (boolean) requireNonNull(THREAD_INFO_IS_DAEMON).invoke(threadInfo);
    } catch (Throwable e) {
      throw new IllegalStateException("Unexpected error happened during ThreadInfo#isDaemon()", e);
    }
    String threadState = threadInfo.getThreadState().name().toLowerCase(Locale.ROOT);
    return Attributes.of(
        JvmAttributes.JVM_THREAD_DAEMON,
        isDaemon,
        JvmAttributes.JVM_THREAD_STATE,
        threadState,
        THREAD_NAME,
        threadName);
  }

  private static String escapeThreadName(String threadName) {
    if (threadName == null) {
      return null;
    }
    return threadName.replaceAll("\\d+", "#");
  }
}
