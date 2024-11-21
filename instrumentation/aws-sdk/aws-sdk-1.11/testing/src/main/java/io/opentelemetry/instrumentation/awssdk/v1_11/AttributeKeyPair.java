package io.opentelemetry.instrumentation.awssdk.v1_11;

import groovyjarjarantlr4.v4.runtime.misc.Nullable;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import java.util.List;

public class AttributeKeyPair<T> {

  private final AttributeKey<T> key;
  private final T value;

  AttributeKeyPair(AttributeKey<T> key, T value) {
    this.key = key;
    this.value = value;
  }
