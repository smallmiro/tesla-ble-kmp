package dev.mirolab.teslable

import dev.mirolab.teslable.model.KeyFormFactor

/** 이 플랫폼의 기본 키 형태(FR-022): Android `ANDROID_DEVICE`, iOS `IOS_DEVICE`, JVM `CLOUD_KEY`(테스트·실험용). */
public expect fun platformKeyFormFactor(): KeyFormFactor
